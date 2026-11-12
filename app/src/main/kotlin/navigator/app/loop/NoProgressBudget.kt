package navigator.app.loop

/**
 * PHASE_18 Stage 18.C CN-11 — the SEMANTIC no-progress ledger (doc 04 §9.7): independent
 * counters for the failure shapes the global step/time/spend ceilings bound far too
 * loosely — consecutive unknown/no-effect actions, consecutive wrong-context actions,
 * grounding failures, capture failures, model failures, planning attempts per planning
 * event, and ordinary + battery re-plans per subtask and per run.
 *
 * Laws:
 * - ONLY a VERIFIED semantic effect resets the no-effect/wrong-context counters (a
 *   dispatch Boolean, a URL change or a screen-hash change alone never does).
 * - Grounding/capture/model counters reset on their OWN channel's success, never on an
 *   unrelated event; model and capture failures are never hidden inside generic re-plan
 *   counts.
 * - The re-plan BUDGET wiring stays the existing budgets (`maxReplans` /
 *   battery-per-subtask); this ledger is the additive semantic layer over them —
 *   [check] exposes the verdicts the loop feeds into its existing failure/re-plan gate.
 * - The capture counter is a BRIDGE, not a duplicate: Stage 18.B CN-10's enforcement
 *   counter in the vision loop stays the bound; the loop feeds the same events here so
 *   the semantic ledger sees them.
 *
 * Thresholds ride the loop's [Config] (ART-5: numbers in code, tests pin them); the
 * defaults follow the handoff calibration (three semantically unproductive actions, two
 * wrong-context actions, single-digit failure/re-planning bounds — deliberately SMALL:
 * an unproductive subtask must hit its bound long before any 6,000-step ceiling).
 */
class NoProgressBudget(
    private val limits: Limits = Limits(),
) {

    /** The calibrated bounds (the loop's Config constructs these; JVM tests pin them). */
    data class Limits(
        val noEffectActions: Int = 3,
        val wrongContextActions: Int = 2,
        val groundingFailures: Int = 3,
        val modelFailures: Int = 3,
        /** Planning attempts per planning event (the writer's bounded retry, ledgered). */
        val planningAttemptsPerEvent: Int = 3,
        /** The per-subtask ordinary re-plan reminder bound (enforcement stays `maxReplans`). */
        val replansPerSubtask: Int = 3,
    )

    // -- the no-effect / wrong-context runs (VERIFIED-effect resets only) -----------------

    private var noEffectRun = 0
    private var wrongContextRun = 0

    // -- the channel failure runs (own-channel resets) --------------------------------------

    private var groundingRun = 0
    private var modelRun = 0
    private var captureRun = 0

    // -- the planning + re-plan ledgers -------------------------------------------------------

    private var planningAttempts = 0
    private var ordinaryReplansSubtask = 0
    private var batteryReplansSubtask = 0
    private var ordinaryReplansRun = 0
    private var batteryReplansRun = 0

    // -- the no-effect / wrong-context ledger -------------------------------------------------

    /** One acted action produced no verifiable effect (an unknown effect NEVER resets this). */
    fun recordNoEffect() {
        noEffectRun += 1
    }

    /** One acted action navigated to the wrong context (WRONG_CONTEXT — a failure, not progress). */
    fun recordWrongContext() {
        wrongContextRun += 1
    }

    /** A VERIFIED semantic effect — the ONLY reset for both no-progress runs. */
    fun resetOnVerifiedEffect() {
        noEffectRun = 0
        wrongContextRun = 0
    }

    // -- the channel failure ledgers ------------------------------------------------------------

    fun recordGroundingFailure() {
        groundingRun += 1
    }

    fun resetGroundingFailures() {
        groundingRun = 0
    }

    fun recordModelFailure() {
        modelRun += 1
    }

    fun resetModelFailures() {
        modelRun = 0
    }

    /** The CN-10 bridge: the vision loop feeds its capture counter's events (no duplicate state). */
    fun recordCaptureFailure() {
        captureRun += 1
    }

    fun resetCaptureFailures() {
        captureRun = 0
    }

    // -- planning + re-plans ----------------------------------------------------------------------

    /** One planning (plan/re-plan/repair) attempt inside the current planning event. */
    fun recordPlanningAttempt() {
        planningAttempts += 1
    }

    /** The planning event completed (a plan installed) — the per-event counter restarts. */
    fun planningEventDone() {
        planningAttempts = 0
    }

    fun recordOrdinaryReplan() {
        ordinaryReplansSubtask += 1
        ordinaryReplansRun += 1
    }

    fun recordBatteryReplan() {
        batteryReplansSubtask += 1
        batteryReplansRun += 1
    }

    /** A subtask advance (or a re-plan install): the per-subtask scopes restart. */
    fun resetSubtaskScope() {
        noEffectRun = 0
        wrongContextRun = 0
        ordinaryReplansSubtask = 0
        batteryReplansSubtask = 0
    }

    /** A new run: everything restarts (no ledger crosses runs). */
    fun resetRun() {
        resetSubtaskScope()
        groundingRun = 0
        modelRun = 0
        captureRun = 0
        planningAttempts = 0
        ordinaryReplansRun = 0
        batteryReplansRun = 0
    }

    // -- reads (the loop's evidence + the verdicts) ----------------------------------------------

    val noEffectCount: Int get() = noEffectRun
    val wrongContextCount: Int get() = wrongContextRun
    val groundingFailureCount: Int get() = groundingRun
    val modelFailureCount: Int get() = modelRun
    val captureFailureCount: Int get() = captureRun
    val planningAttemptCount: Int get() = planningAttempts
    val ordinaryReplansPerSubtask: Int get() = ordinaryReplansSubtask
    val batteryReplansPerSubtask: Int get() = batteryReplansSubtask
    val ordinaryReplansPerRun: Int get() = ordinaryReplansRun
    val batteryReplansPerRun: Int get() = batteryReplansRun

    sealed interface Verdict {
        /** Every ledger inside its bound. */
        data object Ok : Verdict

        /** A no-progress ledger is exhausted — the loop feeds its failure/re-plan gate. */
        data class NoProgress(val kind: Kind, val count: Int, val limit: Int) : Verdict
    }

    /** Which ledger tripped (the evidence line names it). */
    enum class Kind { NO_EFFECT_ACTIONS, WRONG_CONTEXT_ACTIONS, GROUNDING_FAILURES, MODEL_FAILURES }

    /**
     * The verdict over the independent ledgers (checked in the loop's step head, feeding
     * the existing failure/re-plan gate). The capture ledger is deliberately absent: its
     * bound is CN-10's enforcement counter, which ends the run itself past the limit.
     */
    fun check(): Verdict = when {
        wrongContextRun >= limits.wrongContextActions ->
            Verdict.NoProgress(Kind.WRONG_CONTEXT_ACTIONS, wrongContextRun, limits.wrongContextActions)
        noEffectRun >= limits.noEffectActions ->
            Verdict.NoProgress(Kind.NO_EFFECT_ACTIONS, noEffectRun, limits.noEffectActions)
        groundingRun >= limits.groundingFailures ->
            Verdict.NoProgress(Kind.GROUNDING_FAILURES, groundingRun, limits.groundingFailures)
        modelRun >= limits.modelFailures ->
            Verdict.NoProgress(Kind.MODEL_FAILURES, modelRun, limits.modelFailures)
        else -> Verdict.Ok
    }
}
