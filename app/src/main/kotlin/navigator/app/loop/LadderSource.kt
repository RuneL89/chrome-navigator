package navigator.app.loop

import navigator.core.ladder.Ladder

/**
 * The task-ladder seam (Phase 10, vision 04 §1 v1.4.0): the loop consults it for EVERY
 * goal when wired — the writer-LLM restructure stage that turns the free-text goal into
 * a validated task ladder. Laws:
 * - the default ([None]) supplies NO ladder: every existing path (all JVM tests, the
 *   harness's scripted stub goals, the debug stub override) is byte-identical — triage
 *   and cold-start run exactly as before (the phase doc's non-ladder paths);
 * - the ladder is CONTEXT + VERIFICATION TARGET, never an executor (ART-2): Jev still
 *   decides every step; the pointer advances only on a verified IS-DONE (doc 04 §3);
 * - a `null` return is the CLEAN PRE-NAVIGATION REFUSAL: the writer could not produce a
 *   valid ladder within its bounded retry budget, and nothing navigates;
 * - no secret ever enters a writer prompt (doc 06 §2a): implementations receive the
 *   owner's goal text only; the loop masks sanctioned secrets out of any mid-task
 *   context before a re-plan call.
 */
interface LadderSource {

    /**
     * Restructures [goal] into a task ladder — the [generation]th ladder generation for
     * this task (1 = the initial restructure; 2..3 = bounded re-plans). Null = the clean
     * pre-navigation refusal (invalid output after the bounded retry, or a writer
     * failure); the loop refuses the task before any navigation.
     */
    suspend fun restructure(goal: String, generation: Int): RestructuredLadder?

    /**
     * The bounded condition REPAIR (Phase 10C, vision 04 §1 v1.7.0): ONE writer call
     * re-issuing a subtask's done-condition so it is NOT already satisfied on the page the
     * subtask starts from — the writer is told the current page and asked for a condition
     * the subtask's own outcome produces (preferring a `urlContains`/`urlHost` URL identity
     * of the ARRIVED page). Null = no repair available (unwired, refused output, transport
     * failure, or out of writer budget) — the caller then derives a code-side URL identity
     * or refuses the ladder loudly, never accepting a self-satisfying condition.
     */
    suspend fun repairCondition(
        subtaskInstruction: String,
        current: navigator.core.ladder.DoneCondition,
        pageUrl: String,
        pageTitle: String,
    ): RepairedCondition?

    /** The deterministic default: no ladder — the byte-identical legacy path. */
    object None : LadderSource {
        override suspend fun restructure(goal: String, generation: Int): RestructuredLadder? = null

        override suspend fun repairCondition(
            subtaskInstruction: String,
            current: navigator.core.ladder.DoneCondition,
            pageUrl: String,
            pageTitle: String,
        ): RepairedCondition? = null
    }
}

/** One repaired done-condition plus the provenance and metered cost of the repair call. */
data class RepairedCondition(
    val done: navigator.core.ladder.DoneCondition,
    /** The repair prompt version (doc 05 provenance discipline). */
    val promptVersion: String,
    /** Integer micro-dollar cost of the repair call (the SEPARATE writer budget, ART-5). */
    val costMicroUsd: Long,
)

/** One restructured ladder plus the provenance and metered cost of the call that drafted it. */
data class RestructuredLadder(
    val ladder: Ladder,
    /** `<prompt-version>/gen<n>` — rides every DecisionLog row (`plan.version`, doc 05 §2). */
    val version: String,
    /** The writer call's integer micro-dollar cost (the SEPARATE writer budget, ART-5). */
    val costMicroUsd: Long,
)

/**
 * The answer-extraction seam (Phase 10, phase doc §2.1; the Phase-11 result screen's
 * input): ONE writer call over the goal and the code-MASKED final state (doc 06 §2a —
 * the loop masks sanctioned secrets out before the call), phrasing the answer.
 * "Report data, not actions" (doc 06 §4). Null default = no extraction: every existing
 * path stays byte-identical until a run wires the writer.
 */
interface AnswerExtractor {
    suspend fun extract(goal: String, finalState: String): ExtractedAnswer?

    /** The fail-safe default (extraction unwired). */
    object None : AnswerExtractor {
        override suspend fun extract(goal: String, finalState: String): ExtractedAnswer? = null
    }
}

/** One extracted answer plus its provenance, metered writer cost, and token counts. */
data class ExtractedAnswer(
    val text: String,
    /** The extraction prompt version (doc 05 provenance discipline). */
    val promptVersion: String,
    /** Integer micro-dollar cost of the extraction call (ART-5). */
    val costMicroUsd: Long,
    /** Provider-reported input tokens of the extraction call. */
    val inputTokens: Long = 0,
    /** Provider-reported output tokens of the extraction call. */
    val outputTokens: Long = 0,
)

/** The run result's ladder block (Phase 10): the executed ladder + its final pointer state. */
data class LadderRunReport(
    /** `<prompt-version>/gen<n>` of the ACTIVE (last) ladder generation. */
    val version: String,
    /** The ladder generation that was live at the terminal (1 = no re-plan). */
    val generation: Int,
    /** The active ladder's one-sentence summary (display only). */
    val summary: String,
    /** read | action | multi — the validated ladder's task class. */
    val taskClass: String,
    /** One entry per subtask of the ACTIVE ladder, in ladder order. */
    val subtasks: List<LadderSubtaskReport>,
) {
    /** One subtask's terminal state in the run report. */
    data class LadderSubtaskReport(
        val instruction: String,
        /** The done-condition's whitelisted shape, rendered one-line. */
        val done: String,
        /** Verified IS-DONE (pointer passed it, or it is the final subtask of an achieved goal). */
        val completed: Boolean,
        /** True when this was the ACTIVE subtask at the terminal. */
        val active: Boolean,
    )
}
