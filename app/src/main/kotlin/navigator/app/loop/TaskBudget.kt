package navigator.app.loop

import kotlinx.coroutines.delay
import navigator.core.log.TriageClass

/**
 * Wall-clock source (vision 04 §5: wall-clock budget). Abstracted so the loop and its
 * budgets run in plain JVM unit tests with a fake clock (Gate 6.1 is Robolectric-free).
 */
interface Clock {
    fun nowMs(): Long

    data object SYSTEM : Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }
}

/**
 * Cloud-spend recorder (vision 04 §5: spend is "computed from recorded token counts × the
 * pinned config's prices"). All arithmetic is integer micro-dollars in code (ART-5) —
 * no provider billing API, no floats. The LIVE engine wrapper records the wire call's
 * PROVIDER-REPORTED token counts after every call; the STUB harness engine records
 * nothing, so stub runs cost exactly $0.
 */
interface SpendRecorder {
    fun record(inputTokens: Int, outputTokens: Int)
}

/**
 * The pinned price table (ART-5: numbers in code). Micro-USD per 1K tokens for the
 * pinned primary config, applied to the PROVIDER-REPORTED token counts of each wire
 * call (the response envelope's required `usage` object). Changing a price is a
 * reviewed amendment, never a runtime value.
 */
object SpendPrices {
    const val INPUT_MICRO_USD_PER_1K: Long = 3_000L // $3 / 1M input tokens
    const val OUTPUT_MICRO_USD_PER_1K: Long = 15_000L // $15 / 1M output tokens
}

/** Accumulates spend in integer micro-dollars from recorded token counts (ART-5). */
class SpendMeter : SpendRecorder {
    private var microUsd = 0L

    val spentMicroUsd: Long get() = microUsd

    /** Completed engine (Jev) wire calls — the run record's `jev_calls` (PHASE_17 Stage 0.6). */
    var calls: Int = 0
        private set

    override fun record(inputTokens: Int, outputTokens: Int) {
        require(inputTokens >= 0 && outputTokens >= 0) { "token counts must be >= 0" }
        microUsd += inputTokens * SpendPrices.INPUT_MICRO_USD_PER_1K / 1_000
        microUsd += outputTokens * SpendPrices.OUTPUT_MICRO_USD_PER_1K / 1_000
        calls += 1
    }
}

/**
 * One triage class's budget limits (vision 04 §5 — the per-class BudgetProfile table).
 * All numbers are pinned in code as integer micro-dollars / whole steps / whole
 * milliseconds (ART-5); a change is a reviewed amendment, never a runtime value.
 */
data class BudgetProfile(
    /** The profile's name as it rides the run report (`standard` | `action` | `multi`). */
    val name: String,
    val stepsSoft: Int,
    val stepsHard: Int,
    val timeSoftMs: Long,
    val timeHardMs: Long,
    val spendSoftMicroUsd: Long,
    val spendHardMicroUsd: Long,
)

/**
 * The per-class budget table (vision 04 §5 — law):
 *
 * | Profile | Classes                          | Steps soft/hard | Wall clock soft/hard | Cloud spend soft/hard |
 * |---------|----------------------------------|-----------------|----------------------|-----------------------|
 * | Standard| lookup · research-multi-step · form-fill | 40 / 60 | 5 / 10 min           | $0.20 / $0.25         |
 * | Action  | action                           | 60 / 100        | 10 / 15 min          | $0.20 / $0.25         |
 * | Multi   | action-multi                     | 150 / 300       | 15 / 30 min          | $0.50 / $0.75         |
 *
 * Selection is by triage class; the Standard profile IS the original Phase-6 default
 * (the three original classes' numbers are byte-identical to the pre-08D constants).
 * Shadow calls remain free and unbudgeted (they never reach the meter).
 */
object BudgetProfiles {

    val STANDARD = BudgetProfile(
        name = "standard",
        stepsSoft = TaskBudget.STEPS_SOFT,
        stepsHard = TaskBudget.STEPS_HARD,
        timeSoftMs = TaskBudget.TIME_SOFT_MS,
        timeHardMs = TaskBudget.TIME_HARD_MS,
        spendSoftMicroUsd = TaskBudget.SPEND_SOFT_MICRO_USD,
        spendHardMicroUsd = TaskBudget.SPEND_HARD_MICRO_USD,
    )

    val ACTION = BudgetProfile(
        name = "action",
        stepsSoft = 60,
        stepsHard = 100,
        timeSoftMs = 10L * 60_000L,
        timeHardMs = 15L * 60_000L,
        spendSoftMicroUsd = 200_000L, // $0.20
        spendHardMicroUsd = 250_000L, // $0.25
    )

    val MULTI = BudgetProfile(
        name = "multi",
        stepsSoft = 150,
        stepsHard = 300,
        timeSoftMs = 15L * 60_000L,
        timeHardMs = 30L * 60_000L,
        spendSoftMicroUsd = 500_000L, // $0.50
        spendHardMicroUsd = 750_000L, // $0.75
    )

    /** Profile selection by triage class (vision 04 §5's Classes column). */
    fun forClass(triageClass: TriageClass): BudgetProfile = when (triageClass) {
        TriageClass.LOOKUP, TriageClass.RESEARCH_MULTI_STEP, TriageClass.FORM_FILL -> STANDARD
        TriageClass.ACTION -> ACTION
        TriageClass.ACTION_MULTI -> MULTI
    }
}

/**
 * Per-task budget (vision 04 §5 — law), now in per-class [BudgetProfile] strengths.
 * Soft thresholds warn (the step carries on, the warning is logged); hard thresholds
 * stop the task cleanly with partial results, everything logged.
 *
 * The no-argument defaults are the Standard profile (lookup/research/form-fill — the
 * original Phase-6 numbers); the loop applies the triage-selected profile after triage
 * ([BudgetProfiles.forClass]). Tests may construct tighter budgets.
 */
class TaskBudget(
    private val clock: Clock = Clock.SYSTEM,
    private val spendMeter: SpendMeter = SpendMeter(),
    private val stepsSoft: Int = STEPS_SOFT,
    private val stepsHard: Int = STEPS_HARD,
    private val timeSoftMs: Long = TIME_SOFT_MS,
    private val timeHardMs: Long = TIME_HARD_MS,
    private val spendSoftMicroUsd: Long = SPEND_SOFT_MICRO_USD,
    private val spendHardMicroUsd: Long = SPEND_HARD_MICRO_USD,
) {
    /** The moment the task started (budget clock zero). */
    private val startMs = clock.nowMs()

    val elapsedMs: Long get() = clock.nowMs() - startMs

    enum class Dimension(val label: String) {
        STEPS("steps"),
        TIME("wall clock"),
        SPEND("cloud spend"),
    }

    /** One budget check: hard stops outrank soft warnings; hard dimensions are checked
     *  in the deterministic order steps -> time -> spend. */
    sealed class Check {
        data object Ok : Check()
        data class Soft(val warnings: List<Dimension>) : Check()
        data class Hard(val dimension: Dimension, val detail: String) : Check()
    }

    /**
     * Checks the budget after [stepsUsed] steps. Soft thresholds are one-shot per
     * dimension (the warning fires once, never spams every remaining step).
     */
    fun check(stepsUsed: Int): Check {
        if (stepsUsed >= stepsHard) {
            return Check.Hard(Dimension.STEPS, "$stepsUsed steps >= hard limit $stepsHard")
        }
        val elapsed = elapsedMs
        if (elapsed >= timeHardMs) {
            return Check.Hard(Dimension.TIME, "${elapsed}ms >= hard limit ${timeHardMs}ms")
        }
        val spent = spendMeter.spentMicroUsd
        if (spent >= spendHardMicroUsd) {
            return Check.Hard(
                Dimension.SPEND,
                "$spent micro-USD >= hard limit $spendHardMicroUsd micro-USD",
            )
        }
        val warnings = buildList {
            if (stepsUsed >= stepsSoft) add(Dimension.STEPS)
            if (elapsed >= timeSoftMs) add(Dimension.TIME)
            if (spent >= spendSoftMicroUsd) add(Dimension.SPEND)
        }
        val fresh = warnings - warned
        return if (fresh.isEmpty()) Check.Ok else Check.Soft(fresh.also { warned += it })
    }

    private val warned = mutableSetOf<Dimension>()

    companion object {
        /** Vision 04 §5 (initial values; tunable by amendment). */
        const val STEPS_SOFT = 40
        const val STEPS_HARD = 60
        const val TIME_SOFT_MS = 5L * 60_000L
        const val TIME_HARD_MS = 10L * 60_000L
        const val SPEND_SOFT_MICRO_USD = 200_000L // $0.20
        const val SPEND_HARD_MICRO_USD = 250_000L // $0.25
    }
}
