package navigator.app.stub

import navigator.app.gen.GenUsage
import navigator.app.gen.PhraseResult
import navigator.app.loop.PhraseSource
import navigator.app.loop.phraseStub

/**
 * The $0 deterministic phrase source for STUB runs (Phase 10B, the DEBUG-ONLY stub
 * override precedent: "a scripted $0 run has no writer"): the Phase-10 stub phrasing
 * ([phraseStub] — the goal-derived deterministic text, capped at
 * [navigator.app.loop.StepExecutor.MAX_PHRASE_LENGTH]) as a [PhraseSource]. The LIVE /
 * laya modes use the GenClient adapter instead (locked decision #4); a STUB cascade run
 * stays $0 and reachable — its fill-field steps type the SAME deterministic text the
 * pre-cascade loop typed.
 */
object StubPhraseSource : PhraseSource {

    /** The stub phrasing's provenance mark (the run report/step log show it). */
    const val PROMPT_VERSION = "stub-phrase"

    override suspend fun phrase(subtask: String, fieldLabel: String): PhraseResult? {
        val text = phraseStub(subtask, fieldLabel).ifBlank { fieldLabel }
        return PhraseResult(
            text = text,
            usage = GenUsage(inputTokens = 0, outputTokens = 0, costMicroUsd = 0),
            promptVersion = PROMPT_VERSION,
        )
    }
}
