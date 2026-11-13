package navigator.app.harness.hybrid

import navigator.core.ladder.Ladder

/**
 * PHASE_17 Stage 1 — the TREE-FIRST HYBRID's vision tool: ONE subtask at a time.
 *
 * The host loop ([navigator.app.loop.NavigationLoop]) owns the ladder, the pointer, the
 * accept-advance, the re-plan and the terminal. The tool owns the VISION STEP for the
 * ACTIVE subtask — propose, ground (Jev Box), gate, act, verify with the writer-authored
 * battery — and reports WHETHER THE SUBTASK'S OUTCOME IS NOW EVIDENCED. It never plans a
 * ladder, never advances a pointer, never re-plans: those are the host's laws, and a
 * vision refusal returns to the host as [SubtaskOutcome.Failed] with its cause.
 *
 * The escalation INTO vision is code-owned (the three signals: no unique candidate/
 * ground, a thin/empty tree, no screen change). The model never requests the modality.
 * When the subtask is done the host resets the modality to TREE and continues the next
 * subtask on the tree (the target loop's law).
 */
interface VisionToolStep {

    /**
     * Runs [ladder]'s [index] subtask until its outcome is evidenced, the tool cannot
     * progress (bounded), or the owner cancels. The battery-less form (every existing
     * implementer): equivalent to an empty [checks] list.
     */
    suspend fun runSubtask(
        goal: String,
        ladder: Ladder,
        index: Int,
        isCancelled: () -> Boolean,
    ): SubtaskOutcome

    /**
     * The battery-carrying form. PHASE_18 Stage 18.E fix-loop — [checks] is the
     * writer-authored verify battery for this subtask (the SAME per-subtask battery the
     * pure vision loop runs). The host carries it from the ladder source's
     * [RestructuredLadder.batteries]; without it the tool's battery block is dormant and
     * a thin-tree content condition whose outcome is only VISION-visible cannot complete
     * (live-proven 2026-11-13: the webview shell's thin a11y tree, "the page contains
     * 'Site A'" visible in the image but not in the text). The default delegates to the
     * battery-less form — implementers that verify with a battery override this.
     */
    suspend fun runSubtask(
        goal: String,
        ladder: Ladder,
        index: Int,
        checks: List<navigator.app.harness.vision.VisionPrompts.VerifyCheck>,
        isCancelled: () -> Boolean,
    ): SubtaskOutcome = runSubtask(goal, ladder, index, isCancelled)
}

/** What the vision tool reports back to the host loop. */
sealed interface SubtaskOutcome {

    /**
     * The subtask's outcome is evidenced (code condition + battery where authored).
     * [noul] is the recorded judgment opinion (the tool's own Jev done-noul, or 0.0 when
     * only code spoke) — the host's exit record names it honestly.
     *
     * PHASE_18 Stage 18.E fix-loop — [repairedCondition] carries the runtime condition
     * repair's ACCEPTED condition (the battery saw the subtask's outcome while the
     * writer's done-condition shape did not match; the pure loop's VEXP7 law). The host
     * applies it to the pointer BEFORE the verified advance, so the kept proof and the
     * final verifier read the condition that actually held (doc 04 §9.5). Null = the
     * written condition itself held.
     */
    data class Done(
        val steps: Int,
        val noul: Double = 0.0,
        val repairedCondition: navigator.core.ladder.DoneCondition? = null,
    ) : SubtaskOutcome

    /** The tool could not evidence the outcome within its bounds — the host decides. */
    data class Failed(val cause: String, val steps: Int) : SubtaskOutcome

    /** The owner stopped the run during the vision subtask. */
    data object Cancelled : SubtaskOutcome
}
