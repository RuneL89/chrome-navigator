package navigator.app.loop

import navigator.app.gen.AssistResult
import navigator.app.gen.PhraseResult

/**
 * The cascade's writer seams (Phase 10B, phase doc §2.6 / vision 04 §2 v1.6.0): what the
 * loop needs from the generative writer, behind the same seam discipline as
 * [LadderSource] — the loop takes them as nullable constructor parameters (default null
 * = the handshake has no assist and falls straight through; a fill-field phrase failure
 * routes to recovery, never raw-goal typing). `GenClient` adapts onto both
 * ([navigator.app.gen.GenClient.assist] / [.phrase]) with its own validation law; the
 * usage metering rides the loop's per-task [WriterBudget] exactly at the
 * restructure/re-plan/extraction sites (SOFT/HARD warnings + the hard-room gate).
 */
fun interface AssistSource {
    /**
     * ONE uncertainty-handshake assist call. Returns null on any refused output or the
     * honesty path ([navigator.app.gen.Prompts.ASSIST_SKIP_MARKER]) — the
     * skip-to-fallback signal. The caller masks sanctioned secrets out of
     * [pageMaterial] before this call (doc 06 §2a).
     */
    suspend fun assist(subtask: String, doneCondition: String, pageMaterial: String): AssistResult?
}

fun interface PhraseSource {
    /**
     * ONE writer-phrased typed-text call. Returns null on any refused output or the
     * honesty path ([navigator.app.gen.Prompts.PHRASE_SKIP_MARKER]) — the caller routes
     * the fill-field step to recovery (never raw-goal typing). [fieldLabel] rides the
     * observation's redaction fold upstream (doc 06 §2a).
     */
    suspend fun phrase(subtask: String, fieldLabel: String): PhraseResult?
}
