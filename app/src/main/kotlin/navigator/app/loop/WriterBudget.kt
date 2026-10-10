package navigator.app.loop

/**
 * The SEPARATE per-task writer budget (Phase 10, vision 04 §5 v1.4.0): restructure and
 * re-plan calls + answer extraction meter into THIS cap, beside the decision-spend
 * budget the [TaskBudget]/[SpendMeter] own — expensive re-planning never starves
 * navigation, and neither cap can be exceeded silently (ART-5/ART-9).
 *
 * All arithmetic is integer micro-dollars (ART-5) from the writer calls'
 * provider-reported token counts; a change to a cap is a reviewed amendment, never a
 * runtime value. Pure JVM.
 */
class WriterBudget(
    private val softMicroUsd: Long = SOFT_MICRO_USD,
    private val hardMicroUsd: Long = HARD_MICRO_USD,
) {
    init {
        require(softMicroUsd >= 0 && hardMicroUsd >= softMicroUsd) {
            "WriterBudget caps out of order: soft=$softMicroUsd hard=$hardMicroUsd (ART-5)"
        }
    }

    private var microUsd = 0L
    private var softWarned = false

    /** Total writer spend so far this task (integer micro-dollars). */
    val spentMicroUsd: Long get() = microUsd

    enum class Check {
        /** The call's cost fit under both caps. */
        OK,

        /** The soft cap was crossed by this call — a warning; writing continues. */
        SOFT,

        /**
         * The HARD cap is reached: no further writer call may be metered. Re-planning
         * stops (the failure path proceeds without it); navigation is untouched.
         */
        HARD,
    }

    /**
     * Records one writer call's [costMicroUsd] (integer). The verdict reflects the TOTAL
     * after the call: the hard cap, once reached, stays reached.
     */
    fun record(costMicroUsd: Long): Check {
        require(costMicroUsd >= 0) { "writer cost must be >= 0 micro-USD: $costMicroUsd" }
        microUsd += costMicroUsd
        return when {
            microUsd >= hardMicroUsd -> Check.HARD
            microUsd >= softMicroUsd -> {
                softWarned = true
                Check.SOFT
            }

            else -> Check.OK
        }
    }

    /** Whether another writer call (a re-plan) is lawful: the hard cap is not reached. */
    fun hasHardRoom(): Boolean = microUsd < hardMicroUsd

    /** Whether the soft cap fired at least once (the run report's softWarnings input). */
    val softCapWarned: Boolean get() = softWarned

    companion object {
        /** Vision 04 §5 v1.4.0 (initial values, ART-5; tunable by amendment). */
        const val SOFT_MICRO_USD = 50_000L // $0.05
        const val HARD_MICRO_USD = 100_000L // $0.10
    }
}
