package navigator.app.harness.vision

/**
 * The EXPERIMENT's spend/step/time ledger (branch-only try-out — no product law lives
 * here). Every ceiling is EXACTLY 10x the product value it mirrors (owner directive,
 * 2026-10-17: "10x budget for everything"):
 *
 * - steps/time/spend mirror the Standard [navigator.app.loop.BudgetProfile]
 *   (40/60 steps, 5/10 min, \$0.20/\$0.25 -> 400/600, 50/100 min, \$2.00/\$2.50);
 * - the writer sub-ledger mirrors [navigator.app.loop.WriterBudget]
 *   (\$0.05/\$0.10 -> \$0.50/\$1.00).
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

    /** The step-budget verdict at [stepsUsed]. */
    fun stepCheck(stepsUsed: Int): Check {
        if (stepsUsed > STEPS_HARD) return Check.Hard("steps $stepsUsed > hard $STEPS_HARD")
        if (spendMicroUsd > SPEND_HARD_MICRO_USD) {
            return Check.Hard("spend ${spendMicroUsd}µ\$ > hard $SPEND_HARD_MICRO_USD")
        }
        val elapsed = nowMs() - startMs
        if (elapsed > TIME_HARD_MS) return Check.Hard("elapsed ${elapsed}ms > hard $TIME_HARD_MS")
        if (stepsUsed > STEPS_SOFT && !spendSoftWarned && !timeSoftWarned) {
            spendSoftWarned = true
            timeSoftWarned = true
            return Check.Soft("steps $stepsUsed > soft $STEPS_SOFT (or time past soft) — continuing")
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
        /** 10x the Standard profile's 40/60 steps. */
        const val STEPS_SOFT = 400
        const val STEPS_HARD = 600

        /** 10x the Standard profile's 5/10 minutes. */
        const val TIME_SOFT_MS: Long = 50 * 60_000L
        const val TIME_HARD_MS: Long = 100 * 60_000L

        /** 10x the Standard profile's $0.20/$0.25. */
        const val SPEND_SOFT_MICRO_USD = 2_000_000L
        const val SPEND_HARD_MICRO_USD = 2_500_000L

        /** 10x WriterBudget's $0.05/$0.10. */
        const val WRITER_SOFT_MICRO_USD = 500_000L
        const val WRITER_HARD_MICRO_USD = 1_000_000L
    }
}
