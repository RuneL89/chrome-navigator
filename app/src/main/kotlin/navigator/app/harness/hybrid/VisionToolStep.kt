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
fun interface VisionToolStep {

    /**
     * Runs [ladder]'s [index] subtask until its outcome is evidenced, the tool cannot
     * progress (bounded), or the owner cancels.
     */
    suspend fun runSubtask(
        goal: String,
        ladder: Ladder,
        index: Int,
        isCancelled: () -> Boolean,
    ): SubtaskOutcome
}

/** What the vision tool reports back to the host loop. */
sealed interface SubtaskOutcome {

    /**
     * The subtask's outcome is evidenced (code condition + battery where authored).
     * [noul] is the recorded judgment opinion (the tool's own Jev done-noul, or 0.0 when
     * only code spoke) — the host's exit record names it honestly.
     */
    data class Done(val steps: Int, val noul: Double = 0.0) : SubtaskOutcome

    /** The tool could not evidence the outcome within its bounds — the host decides. */
    data class Failed(val cause: String, val steps: Int) : SubtaskOutcome

    /** The owner stopped the run during the vision subtask. */
    data object Cancelled : SubtaskOutcome
}
