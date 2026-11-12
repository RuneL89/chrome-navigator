package navigator.app.harness.vision

/**
 * The vision engine's spend/step/time ledger. Every ceiling is EXACTLY 100x the product
 * value it mirrors (owner directive,
 * 2026-10-19: ALL budgets to 100x product values — "models are dirt cheap — runs may
 * span days"; supersedes the 2026-10-17 10x directive):
 *
 * - steps/time/spend mirror the Standard [navigator.app.loop.BudgetProfile]
 *   (40/60 steps, 5/10 min, \$0.20/\$0.25 -> 4000/6000, 500/1000 min, \$20.00/\$25.00);
 * - the writer sub-ledger mirrors [navigator.app.loop.WriterBudget]
 *   (\$0.05/\$0.10 -> \$5.00/\$10.00).
 *
 * Every model call (Haiku and Jev alike) records its provider-reported usage into the
 * ONE task ledger; writer-surface calls also record into the writer sub-ledger. Soft
 * ceilings warn (once per dimension, the TaskBudget habit); hard ceilings stop the run
 * LOUDLY. With the pinned conservative writer prices (~8x actual Haiku pricing) nothing
 * ever throttles in practice — that is the point; the numbers exist so a runaway loop
 * still has a wall. Integer micro-dollars throughout (the ART-5 habit).
 */
class ExpBudget(private val nowMs: () -> Long = System::currentTimeMillis) {

    private val startMs: Long = nowMs()
    private var stepsSoftWarned = false
    private var spendSoftWarned = false
    private var writerSoftWarned = false
    private var timeSoftWarned = false

    var spendMicroUsd: Long = 0
        private set
    var writerSpendMicroUsd: Long = 0
        private set

    /** Records one completed call's cost into the task ledger (and the writer ledger when it is one). */
    fun recordSpend(microUsd: Long, writerSurface: Boolean) {
        require(microUsd >= 0) { "spend must be >= 0: $microUsd" }
        spendMicroUsd += microUsd
        if (writerSurface) writerSpendMicroUsd += microUsd
    }

    /**
     * The step-budget verdict at [stepsUsed]. PHASE_18 CN-11 (doc 04 §9.7): the step,
     * spend, and elapsed-time SOFT thresholds are evaluated INDEPENDENTLY and each warns
     * exactly ONCE (the pre-18.C quirk — the steps branch set the spend/time flags without
     * testing them, so spend or time past their soft ceilings alone never warned — is
     * fixed; the Stage 18.A characterization suite was consciously re-pinned). The HARD
     * checks are unchanged: each fires at one past its documented ceiling (`>` the
     * boundary), independently of the other dimensions.
     */
    fun stepCheck(stepsUsed: Int): Check {
        if (stepsUsed > STEPS_HARD) return Check.Hard("steps $stepsUsed > hard $STEPS_HARD")
        if (spendMicroUsd > SPEND_HARD_MICRO_USD) {
            return Check.Hard("spend ${spendMicroUsd}µ\$ > hard $SPEND_HARD_MICRO_USD")
        }
        val elapsed = nowMs() - startMs
        if (elapsed > TIME_HARD_MS) return Check.Hard("elapsed ${elapsed}ms > hard $TIME_HARD_MS")
        if (stepsUsed > STEPS_SOFT && !stepsSoftWarned) {
            stepsSoftWarned = true
            return Check.Soft("steps $stepsUsed > soft $STEPS_SOFT — continuing")
        }
        if (spendMicroUsd > SPEND_SOFT_MICRO_USD && !spendSoftWarned) {
            spendSoftWarned = true
            return Check.Soft("spend ${spendMicroUsd}µ\$ > soft $SPEND_SOFT_MICRO_USD — continuing")
        }
        if (elapsed > TIME_SOFT_MS && !timeSoftWarned) {
            timeSoftWarned = true
            return Check.Soft("elapsed ${elapsed}ms > soft $TIME_SOFT_MS — continuing")
        }
        return Check.Ok
    }

    /** The writer sub-ledger's hard-cap probe (a planned writer call is skipped when false). */
    fun hasWriterHardRoom(): Boolean = writerSpendMicroUsd <= WRITER_HARD_MICRO_USD

    /** The writer sub-ledger's post-record verdict. */
    fun writerCheck(): Check = when {
        writerSpendMicroUsd > WRITER_HARD_MICRO_USD -> Check.Hard(
            "writer spend ${writerSpendMicroUsd}µ\$ > hard $WRITER_HARD_MICRO_USD",
        )
        writerSpendMicroUsd > WRITER_SOFT_MICRO_USD && !writerSoftWarned -> {
            writerSoftWarned = true
            Check.Soft("writer spend ${writerSpendMicroUsd}µ\$ > soft $WRITER_SOFT_MICRO_USD — continuing")
        }
        else -> Check.Ok
    }

    fun elapsedMs(): Long = nowMs() - startMs

    sealed interface Check {
        data object Ok : Check
        data class Soft(val detail: String) : Check
        data class Hard(val detail: String) : Check
    }

    companion object {
        /** 100x the Standard profile's 40/60 steps (owner directive 2026-10-19). */
        const val STEPS_SOFT = 4_000
        const val STEPS_HARD = 6_000

        /** 100x the Standard profile's 5/10 minutes. */
        const val TIME_SOFT_MS: Long = 500 * 60_000L
        const val TIME_HARD_MS: Long = 1_000 * 60_000L

        /** 100x the Standard profile's $0.20/$0.25. */
        const val SPEND_SOFT_MICRO_USD = 20_000_000L
        const val SPEND_HARD_MICRO_USD = 25_000_000L

        /** 100x WriterBudget's $0.05/$0.10. */
        const val WRITER_SOFT_MICRO_USD = 5_000_000L
        const val WRITER_HARD_MICRO_USD = 10_000_000L
    }
}
