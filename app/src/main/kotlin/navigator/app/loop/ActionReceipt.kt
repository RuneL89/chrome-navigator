package navigator.app.loop

/**
 * PHASE_18 Stage 18.C CN-04 — the COMPLETED action receipt (doc 04 §9.3): what the loop
 * knows about one dispatched action AFTER its effect was checked on a fresh post-action
 * observation. The same-action / no-progress accounting consumes receipts — verb + the
 * target's semantic identity + the declared expectation + the dispatch result + the
 * verified postcondition + the page hash before/after — never the pre-observation
 * dispatch result alone, and never an observation-local node id.
 *
 * SECRETS: a receipt never carries typed values (the expectation's declared evidence
 * text is page description data; the actual typed value — a card secret included — is
 * consumed in-memory by the checker and never stored here; doc 06 §2a).
 */
data class ActionReceipt(
    /** The effective verb that acted (lowercase wire name: tap/type/submit/scroll_down/scroll_up/back). */
    val verb: String,
    /** The target's semantic identity — class + label; NO observation-local node id. */
    val targetClass: String?,
    val targetLabel: String?,
    /** The expectation the proposal declared (null = none declared). */
    val expectation: ExpectedEffect?,
    /** What dispatch itself did (fact 1 — never evidence of fact 3). */
    val dispatch: DispatchResult,
    /** The postcondition verdict on the fresh post-action observation (fact 3). */
    val verified: Verdict,
    val pageHashBefore: Int,
    val pageHashAfter: Int,
) {

    /** The dispatch-only result (doc 04 §9.2's closed vocabulary, receipt-scoped). */
    enum class DispatchResult { EXECUTED, REJECTED, NOT_DISPATCHED }

    /** The postcondition verdict a receipt carries. */
    sealed interface Verdict {
        data class Verified(val postcondition: ActionPostcondition) : Verdict
        data object UnknownEffect : Verdict
        data class WrongContext(val detail: String) : Verdict
    }

    /** Whether the effect was verified (the ONLY progress evidence). */
    val progresses: Boolean
        get() = verified is Verdict.Verified

    /**
     * The mechanical same-action key: verb + semantic target identity — deliberately NOT
     * the node id (ids are not durable across fresh reads, doc 04 §9.1) and deliberately
     * NOT the postcondition (mechanical repetition detection stays a separate signal from
     * semantic progress).
     */
    val semanticKey: String
        get() = "$verb:${targetClass?.takeIf { it.isNotBlank() } ?: "-"}:${targetLabel?.takeIf { it.isNotBlank() } ?: "-"}"
}
