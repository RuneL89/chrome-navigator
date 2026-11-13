package navigator.app.loop

import navigator.core.ladder.DoneConditionChecker
import navigator.core.ladder.Subtask

/**
 * PHASE_18 Stage 18.D CN-17 (doc 04 §9.9, ratified law) — the FAILURE-APPROPRIATE
 * RECOVERY LADDER as a PURE decision type. The law it implements:
 *
 * "Recovery is a failure-appropriate ladder — fresh observation → bounded stability
 * wait → fresh target grounding → one mechanism retry → bounded scroll-to-find or
 * browser-context restoration → perception-tool switch (§8) → prefix rollback (9.5) →
 * informed re-plan → clarify / hand off / stop honestly — and not every failure walks
 * every stage (a wrong window goes straight to foreground recovery; an invalid host
 * prefix goes straight to rollback). Scroll-to-find keeps the list and target across
 * scrolls and stops at the end or cap ... Action and landing bans persist across
 * recovery and re-plans; a changed verb alone is not a new strategy."
 *
 * Composition, not duplication: every stage names machinery that ALREADY exists — the
 * loop's fresh per-step observation ([FRESH_OBSERVATION]), the stuck probe's settle +
 * re-read ([BOUNDED_STABILITY_WAIT]), the grounding ladder's fresh re-ground
 * ([FRESH_TARGET_GROUNDING]), the fill mechanisms ([MECHANISM_RETRY] — the vision
 * proposer's forced action change / the a11y engine's [TypeMechanism]), the scroll-find
 * block's list+target retention and cap ([SCROLL_TO_FIND]), the CN-12
 * [BrowserRecoveryController] restore/escape ([BROWSER_CONTEXT_RESTORE]), the §8
 * perception-tool boundary ([PERCEPTION_TOOL_SWITCH] — ARMED here; Stage 18.E wires
 * it), the prefix audit ([PREFIX_ROLLBACK]), the informed re-plan gate
 * ([INFORMED_REPLAN]) and the CN-15 terminals ([CLARIFY_HANDOFF_OR_STOP]). The
 * controller DECIDES the next lawful stage; the loop EXECUTES it through its existing
 * seams — the controller never dispatches, observes, or plans.
 *
 * Pure JVM; no Android types; no model calls (ART-2/ART-4).
 */
object RecoveryController {

    /** The typed failure causes the ladder is failure-appropriate FOR (doc 04 §9.9). */
    enum class Cause {
        /** The grounded target no longer matches the fresh read (a stale target). */
        STALE_TARGET,
        /** The screen did not change across the stuck window (the static-screen probe). */
        STATIC_SCREEN,
        /** The browser is not the front window (the R4 APP_ROUTING / wrong-window class). */
        WRONG_WINDOW,
        /** A completed prefix condition no longer holds (the CN-06 audit's finding). */
        INVALID_PREFIX,
        /** The grounding channel returned none / low-confidence / failed (CN-09). */
        GROUNDING_MISS,
        /** The capture is degraded or unavailable (CN-10). */
        CAPTURE_UNAVAILABLE,
        /** The no-effect / wrong-context ledger exhausted (CN-11). */
        REPEATED_NO_EFFECT,
    }

    /** The ladder's stages, in §9.9's order (the enum order IS the law). */
    enum class Stage {
        FRESH_OBSERVATION,
        BOUNDED_STABILITY_WAIT,
        FRESH_TARGET_GROUNDING,
        MECHANISM_RETRY,
        SCROLL_TO_FIND,
        BROWSER_CONTEXT_RESTORE,
        PERCEPTION_TOOL_SWITCH,
        PREFIX_ROLLBACK,
        INFORMED_REPLAN,
        CLARIFY_HANDOFF_OR_STOP,
    }

    /**
     * ONE failure episode's state: what this caller has already taken and what it is
     * CAPABLE of taking. The caller's facts are truth — the controller never assumes a
     * capability it was not told about (a launcher-less build cannot restore; a
     * pointer-0 run cannot roll back; the §8 perception switch is armed but not wired
     * until Stage 18.E, so callers pass `perceptionSwitchAvailable = false`).
     */
    data class Episode(
        /** The loop re-observes every step — true once the fresh read is in hand. */
        val freshObservationTaken: Boolean = false,
        /** The bounded stability wait (the probe's settle + re-read) was taken. */
        val stabilityWaitTaken: Boolean = false,
        /** A fresh target grounding was taken for this episode. */
        val freshGroundingTaken: Boolean = false,
        /** The one mechanism retry was taken (or this engine has no retry left). */
        val mechanismRetryTaken: Boolean = false,
        /** Scroll-to-find accounting: scrolls used this episode vs the code cap. */
        val scrollsUsed: Int = 0,
        val scrollCap: Int = 8,
        /** The list/viewport is exhausted (list end reached) — scrolling cannot help. */
        val listExhausted: Boolean = false,
        /** Browser-context restoration (the CN-12 controller) is wired and budgeted. */
        val browserRestoreAvailable: Boolean = false,
        /** A restoration was already taken this episode. */
        val restoreTaken: Boolean = false,
        /**
         * The §8 perception-tool switch (TREE_FIRST's escalation boundary). ARMED here
         * per the CN-17 law; the wiring is Stage 18.E's — callers pass false until then.
         */
        val perceptionSwitchAvailable: Boolean = false,
        /** A prefix rollback is possible (a completed prefix exists to re-check). */
        val rollbackAvailable: Boolean = false,
        /** Informed re-plan capacity remaining (0 = the re-plan budgets are spent). */
        val replansRemaining: Int = 0,
    )

    /**
     * The NEXT LAWFUL stage for [cause] given the episode's taken stages and truthful
     * capabilities. The short-circuits ARE the §9.9 law ("not every failure walks every
     * stage"): a wrong window goes straight to foreground recovery; an invalid prefix
     * goes straight to rollback; a capture outage arms the perception switch (the
     * image-dependent work is disabled, never fabricated). Every other cause walks the
     * ladder in order, skipping stages already taken or unavailable, and ends at
     * [Stage.CLARIFY_HANDOFF_OR_STOP] when the ladder is exhausted — never an infinite
     * loop (every returned stage is either executable or terminal).
     */
    fun nextStage(cause: Cause, e: Episode): Stage = when (cause) {
        // -- the §9.9 short-circuits -------------------------------------------------
        Cause.INVALID_PREFIX ->
            if (e.rollbackAvailable) Stage.PREFIX_ROLLBACK else Stage.INFORMED_REPLAN
        Cause.WRONG_WINDOW ->
            when {
                e.browserRestoreAvailable && !e.restoreTaken -> Stage.BROWSER_CONTEXT_RESTORE
                e.browserRestoreAvailable -> Stage.FRESH_OBSERVATION
                // No restoration capability: the owner handoff, never a page touch in
                // a foreign window (the OFF-PAGE no-recovery law).
                else -> Stage.CLARIFY_HANDOFF_OR_STOP
            }
        Cause.CAPTURE_UNAVAILABLE ->
            if (e.perceptionSwitchAvailable) Stage.PERCEPTION_TOOL_SWITCH
            else Stage.CLARIFY_HANDOFF_OR_STOP

        // -- the in-band ladder (§9.9's order, taken/unavailable stages skipped) ------
        else -> firstExecutable(
            Stage.FRESH_OBSERVATION to (!e.freshObservationTaken),
            Stage.BOUNDED_STABILITY_WAIT to (!e.stabilityWaitTaken),
            Stage.FRESH_TARGET_GROUNDING to (!e.freshGroundingTaken),
            Stage.MECHANISM_RETRY to (!e.mechanismRetryTaken),
            Stage.SCROLL_TO_FIND to (!e.listExhausted && e.scrollsUsed < e.scrollCap),
            Stage.BROWSER_CONTEXT_RESTORE to (e.browserRestoreAvailable && !e.restoreTaken),
            Stage.PERCEPTION_TOOL_SWITCH to e.perceptionSwitchAvailable,
            Stage.PREFIX_ROLLBACK to e.rollbackAvailable,
            Stage.INFORMED_REPLAN to (e.replansRemaining > 0),
            Stage.CLARIFY_HANDOFF_OR_STOP to true,
        )
    }

    /** The first (stage, available) pair whose stage is available. */
    private fun firstExecutable(vararg stages: Pair<Stage, Boolean>): Stage =
        stages.firstOrNull { it.second }?.first ?: Stage.CLARIFY_HANDOFF_OR_STOP

    // -- the strategy law ---------------------------------------------------------------

    /**
     * Whether a re-planned subtask is the SAME STRATEGY as the failing one with only
     * the verb re-worded (doc 04 §9.9: "a changed verb alone is not a new strategy").
     * The rendered done-condition must match AND the instruction's token run — after
     * each side drops its LEADING VERB TOKEN — must match in order: "click the blue
     * Add to cart button" -> "press the blue Add to cart button" is the same strategy;
     * a genuinely changed target/mechanism/browser-context/approach (any non-verb token
     * different, a different condition) is NOT — the caller may lawfully re-plan.
     *
     * Deliberately deterministic and explainable: tokens are whitespace runs
     * lowercased; the condition comparison is the checker's rendered form (the same
     * form the byte-equality guard uses).
     */
    fun isWordingOnlyChange(previous: Subtask, next: Subtask): Boolean {
        if (DoneConditionChecker.render(previous.done) != DoneConditionChecker.render(next.done)) {
            return false
        }
        val prevTokens = tokens(previous.instruction)
        val nextTokens = tokens(next.instruction)
        if (prevTokens.size != nextTokens.size) return false
        return prevTokens.drop(1) == nextTokens.drop(1)
    }

    private fun tokens(text: String): List<String> =
        text.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    // -- the context-transition vocabulary (CN-16's evidence field) ----------------------

    /**
     * The context-transition category an evidence record carries beside the current
     * host (doc 05 §8): [SAME_PAGE] (the navigation identity held), [NAVIGATION] (the
     * identity changed), [TAB_RESTORATION] (the step follows a browser-context
     * restoration — the CN-12 re-open), [UNKNOWN] (no readable navigation identity on
     * either side — the conservative category, never a fabricated transition).
     */
    enum class ContextTransition { SAME_PAGE, NAVIGATION, TAB_RESTORATION, UNKNOWN }

    /**
     * The pure transition decision over the navigation keys the loop already tracks
     * ([VisionSnapshotter.navigationKey]-shaped host keys; "" = unreadable). The
     * restoration flag is the caller's own fact (the restore it just executed).
     */
    fun contextTransition(
        previousNavKey: String,
        currentNavKey: String,
        afterRestoration: Boolean,
    ): ContextTransition = when {
        afterRestoration -> ContextTransition.TAB_RESTORATION
        previousNavKey.isBlank() || currentNavKey.isBlank() -> ContextTransition.UNKNOWN
        previousNavKey == currentNavKey -> ContextTransition.SAME_PAGE
        else -> ContextTransition.NAVIGATION
    }
}
