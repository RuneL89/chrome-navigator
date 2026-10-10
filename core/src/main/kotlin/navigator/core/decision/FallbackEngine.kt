package navigator.core.decision

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

/**
 * Why the fallback engine was consulted for one decision (vision 02 §7).
 */
enum class EscalationReason {
    /** `choose`: the primary's confidence band was not HIGH (<= 0.70 — [band] maps 0.70
     *  itself to UNCERTAIN, so 0.70 escalates: "consult when NOT HIGH"). */
    PRIMARY_NOT_HIGH,

    /** `noul`: the primary's pTrue landed in the uncertain band [0.30, 0.70] — the
     *  "don't know" band (vision 02 §2). */
    PRIMARY_UNCERTAIN,

    /** The primary failed hard (load/inference exception) — graceful degradation, never
     *  a dead task (vision 02 §7). */
    PRIMARY_FAILURE,
}

/**
 * The full record of one composed decision (vision 02 §7, logged per doc 05 v1.2.0):
 * what the primary answered (null on a hard failure), what the fallback answered
 * (null when not consulted), which answer was executed, and why. Surfaced for logging
 * — the caller (loop/router) still sees one [DecisionEngine] and only [executed].
 */
data class DecisionRecord(
    val questionName: String,
    val executed: TypedResult,
    /** The primary's own answer, or null when the primary failed hard. */
    val primary: TypedResult?,
    /** The fallback's answer when escalated, else null. */
    val fallback: TypedResult?,
    val escalated: Boolean,
    val reason: EscalationReason? = null,
    /** Class+message of the primary's failure when [reason] is [EscalationReason.PRIMARY_FAILURE]. */
    val primaryFailure: String? = null,
)

/** The typed face of one composed decision: [record] plus the executed answer, typed. */
data class Decided<T : TypedResult>(
    val executed: T,
    val record: DecisionRecord,
)

/**
 * What the log path reads off a composed engine (the capability probe the loop makes on
 * its [DecisionEngine]; every other engine returns null from the caller's `as?`):
 * the record of the decision that JUST completed. The caller is strictly sequential
 * (the loop's DECIDE step), so one slot suffices — mirroring [UsageFeed].
 */
interface FallbackReporting {
    fun lastRecord(): DecisionRecord?
}

/**
 * The fallback composition (vision 02 §7, amended 2026-10-06, owner-directed): a primary
 * and a fallback [DecisionEngine] composed into ONE engine — ART-2 intact, both are
 * reached only through the interface, and nothing outside this class consults providers.
 *
 * Escalation policy (vision 02 §7 — INTERNAL, expressed only through [band], never on
 * exact values):
 * - `choose`: consult the fallback when the primary's confidence band is NOT HIGH
 *   (band(p) != HIGH — so p <= 0.70 escalates; 0.70 itself is UNCERTAIN per [Bands]);
 * - `noul`: consult when the primary's pTrue lands in the uncertain band [0.30, 0.70];
 * - primary hard failure (exception): ALWAYS escalate — graceful degradation, never a
 *   dead task;
 * - `score` follows the primary (unused in the v1 loop; never escalates).
 *
 * Composition law:
 * - at most ONE fallback consult per decision ([Decided] is built once; the fallback is
 *   never re-asked to shop for a better answer);
 * - the fallback's answer is FINAL for that step — it is what [choose]/[judge] return;
 * - a fallback EXCEPTION is NOT swallowed: it propagates loudly. The composition may
 *   degrade one engine's failure into the other's answer, but a dead fallback path must
 *   surface, never fail silent (the escalation contract exists to be visible in the log);
 * - coroutine cancellation is never an escalation: it propagates untouched.
 *
 * Both answers are surfaced for logging via [FallbackReporting.lastRecord] (doc 05
 * v1.2.0 `escalated` + `fallback` row fields), and the aggregate count/rate via [stats]
 * (the run reports' escalation evidence).
 */
class FallbackEngine(
    private val primary: DecisionEngine,
    private val fallback: DecisionEngine,
) : DecisionEngine, FallbackReporting {

    private val decisions = AtomicLong(0)
    private val escalations = AtomicLong(0)

    @Volatile
    private var last: DecisionRecord? = null

    /** Consults-at-most-once counters (the run reports' escalation rate evidence). */
    data class Stats(val decisions: Long, val escalations: Long) {
        /** Escalation rate over this engine's lifetime (0.0 over zero decisions). */
        val rate: Double get() = if (decisions == 0L) 0.0 else escalations.toDouble() / decisions
    }

    fun stats(): Stats = Stats(decisions.get(), escalations.get())

    override fun lastRecord(): DecisionRecord? = last

    // -- choose ---------------------------------------------------------------------

    override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult =
        chooseDecided(state, question).executed

    suspend fun chooseDecided(state: String, question: ChoiceQuestion): Decided<ChoiceResult> {
        val (answer, failure) = attempt { primary.choose(state, question) }
        if (answer != null && band(answer.confidence) == Band.HIGH) {
            return settled(question.name, primaryAnswer = answer, executed = answer)
        }
        // Not HIGH (or hard failure): ONE fallback consult, its answer final.
        val reason = if (answer == null) EscalationReason.PRIMARY_FAILURE else EscalationReason.PRIMARY_NOT_HIGH
        val fallbackAnswer = fallback.choose(state, question) // a failure here is LOUD — never swallowed
        return settled(question.name, answer, fallbackAnswer, reason, failure)
    }

    // -- noul -----------------------------------------------------------------------

    override suspend fun judge(state: String, question: NoulQuestion): NoulResult =
        judgeDecided(state, question).executed

    suspend fun judgeDecided(state: String, question: NoulQuestion): Decided<NoulResult> {
        val (answer, failure) = attempt { primary.judge(state, question) }
        if (answer != null && band(answer.pTrue) != Band.UNCERTAIN) {
            return settled(question.name, primaryAnswer = answer, executed = answer)
        }
        val reason = if (answer == null) EscalationReason.PRIMARY_FAILURE else EscalationReason.PRIMARY_UNCERTAIN
        val fallbackAnswer = fallback.judge(state, question)
        return settled(question.name, answer, fallbackAnswer, reason, failure)
    }

    // -- score (follows the primary; never escalates in v1, vision 02 §7) -------------

    override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
        scoreDecided(state, question).executed

    suspend fun scoreDecided(state: String, question: ScoreQuestion): Decided<ScoreResult> {
        val answer = primary.score(state, question)
        return settled(question.name, primaryAnswer = answer, executed = answer)

    }

    /**
     * The cascade's stage-2 batch follows the primary wholesale (vision 02 §7: "`score`
     * follows the primary"): the primary's batched answers are executed, never escalated.
     * A primary hard failure propagates loudly here (a dead stage-2 must surface — the
     * loop's no-good-target path is evidence handling, not silent degradation).
     */
    override suspend fun scoreBatch(
        state: String,
        questions: Map<String, ScoreQuestion>,
    ): Map<String, ScoreResult> = primary.scoreBatch(state, questions)

    // -- internals ---------------------------------------------------------------------

    /**
     * Runs one primary attempt: cancellation propagates untouched (never an escalation);
     * any other exception is a PRIMARY_FAILURE, recorded — not thrown.
     */
    private suspend fun <T : TypedResult> attempt(block: suspend () -> T): Pair<T?, String?> =
        try {
            block() to null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null to (t::class.simpleName ?: "Throwable") + ": " + t.message
        }

    /** Records + returns a settled (non-escalated) decision. */
    private fun <T : TypedResult> settled(
        questionName: String,
        primaryAnswer: T,
        executed: T,
    ): Decided<T> = publish(
        DecisionRecord(
            questionName = questionName,
            executed = executed,
            primary = primaryAnswer,
            fallback = null,
            escalated = false,
        ),
    )

    /** Records + returns an escalated decision: the fallback's answer, executed + final. */
    private fun <T : TypedResult> settled(
        questionName: String,
        primaryAnswer: T?,
        fallbackAnswer: T,
        reason: EscalationReason,
        primaryFailure: String?,
    ): Decided<T> = publish(
        DecisionRecord(
            questionName = questionName,
            executed = fallbackAnswer,
            primary = primaryAnswer,
            fallback = fallbackAnswer,
            escalated = true,
            reason = reason,
            primaryFailure = primaryFailure,
        ),
    )

    private fun <T : TypedResult> publish(record: DecisionRecord): Decided<T> {
        @Suppress("UNCHECKED_CAST")
        val decided = Decided(record.executed as T, record)
        decisions.incrementAndGet()
        if (record.escalated) escalations.incrementAndGet()
        last = record
        return decided
    }
}
