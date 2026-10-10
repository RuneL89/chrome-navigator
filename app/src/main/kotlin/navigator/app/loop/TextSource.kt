package navigator.app.loop

/**
 * Where a TYPE action's text comes from (vision 04 §2 step 5): the writer-LLM phrasing
 * for ordinary text (locked decision #4 — the deterministic [phraseStub] until Phase
 * 10) or a gate-supplied credential for secrets (doc 06 §2a: the owner typed it on the
 * gate card's masked input).
 *
 * Secret law (doc 06 §2a + doc 05 §2): the [GateSuppliedSecret] value is injected
 * DIRECTLY into the target field by code and lives in memory only for that call — it
 * is never phrased by any cloud role, never sent to any engine raw (a sanctioned
 * echo masks as [REDACTED] in every state-text section), and never written to any
 * log, row, or report: those render [REDACTED].
 */
sealed interface TextSource {
    /** Ordinary text: the deterministic Phase-10 stub phrasing (the pre-08F behavior). */
    data class StubPhrase(val text: String) : TextSource

    /**
     * The writer-phrased field content (Phase 10B, doc 04 §2 v1.6.0 — locked decision #4
     * realized): ONE line ≤ [navigator.app.gen.Prompts.PHRASE_MAX_CHARS] chars, produced
     * by `GenClient.phrase(subtask, maskedFieldLabel)` and validated by the client's
     * content-only law. A phrase FAILURE never reaches here — it routes the fill-field
     * step to recovery (never raw-goal typing).
     */
    data class LlmPhrase(val text: String) : TextSource

    /**
     * The owner-typed card secret (doc 06 §2a). Constructed ONLY from a credential
     * card's [GateResolution.ApproveWithSecret] approval — there is no other lawful
     * source, and a credential-typing action with any other source fails closed.
     */
    data class GateSuppliedSecret(val value: String) : TextSource

    companion object {
        /** doc 05 §2: the redaction marker for credential-sourced text everywhere. */
        const val REDACTED = "[credential-redacted]"
    }
}
