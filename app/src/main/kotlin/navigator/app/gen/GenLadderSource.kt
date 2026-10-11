package navigator.app.gen

import navigator.app.loop.AnswerExtractor
import navigator.app.loop.ExtractedAnswer
import navigator.app.loop.LadderSource
import navigator.app.loop.RestructuredLadder

/**
 * The LIVE ladder source (Phase 10): [LadderSource] + [AnswerExtractor] backed by the
 * [GenClient] over the pinned generative config. One wiring for the harness's live
 * ladder mode AND the product run path (the same pattern as the recorded Jev engine).
 *
 * Laws:
 * - the refusal mapping: a [GenRefusedException] (invalid ladder after the bounded
 *   content retry) maps to `null` — the loop's CLEAN PRE-NAVIGATION REFUSAL; every
 *   other failure surfaces loudly (a transport outage fails the run — never a silent
 *   fall-through to un-laddered navigation on a ladder-run request);
 * - usage metering rides [GenClient.onUsage] into the writer-cap consumer the caller
 *   supplies (the loop's [navigator.app.loop.WriterBudget] meters the returned
 *   costMicroUsd; this hook exists for the harness's own logging);
 * - NO SECRET EVER ENTERS A WRITER PROMPT (doc 06 §2a): the loop masks sanctioned
 *   secrets out of the goal/final-state before calling; this class adds no inputs.
 */
class GenLadderSource(
    private val client: GenClient,
    /** Observes every successful writer call's usage (harness logging; optional). */
    private val onUsage: (GenUsage) -> Unit = {},
) : LadderSource, AnswerExtractor {

    override suspend fun restructure(goal: String, generation: Int): RestructuredLadder? =
        try {
            val result = client.restructure(goal, generation)
            onUsage(result.usage)
            RestructuredLadder(
                ladder = result.ladder,
                version = result.ladder.versionTag(result.promptVersion, generation),
                costMicroUsd = result.usage.costMicroUsd,
            )
        } catch (e: GenRefusedException) {
            null // the clean pre-navigation refusal (invalid ladder, nothing navigates)
        }

    override suspend fun repairCondition(
        subtaskInstruction: String,
        current: navigator.core.ladder.DoneCondition,
        pageUrl: String,
        pageTitle: String,
    ): navigator.app.loop.RepairedCondition? =
        try {
            val result = client.repairCondition(
                subtaskInstruction = subtaskInstruction,
                conditionRendered = navigator.core.ladder.DoneConditionChecker.render(current),
                pageUrl = pageUrl,
                pageTitle = pageTitle,
            ) ?: return null
            onUsage(result.usage)
            navigator.app.loop.RepairedCondition(
                done = result.done,
                promptVersion = result.promptVersion,
                costMicroUsd = result.usage.costMicroUsd,
            )
        } catch (e: GenRefusedException) {
            null // no repair available — the loop derives or refuses loudly
        }

    override suspend fun extract(goal: String, finalState: String): ExtractedAnswer? =
        try {
            val result = client.extractAnswer(goal, finalState)
            onUsage(result.usage)
            ExtractedAnswer(
                text = result.answer,
                promptVersion = result.promptVersion,
                costMicroUsd = result.usage.costMicroUsd,
                inputTokens = result.usage.inputTokens,
                outputTokens = result.usage.outputTokens,
            )
        } catch (e: GenRefusedException) {
            null // unreachable for extraction, kept for symmetry with the refusal law
        }
}
