package navigator.core.decision

/**
 * The decision cascade's stage-1 intent vocabulary (vision 02 §3 v1.3.0 — LAW; Phase
 * 10B): the FIXED 9 categories every per-step navigation decision is expressed as, in
 * this exact table order. The option set is identical on every call and every task — a
 * stable, learnable prior (the Phase-10 UAT proved per-page dynamic lists with hollow
 * criteria produce hedged, low-confidence answers).
 *
 * ALL texts here — the criteria descriptions, [INTENT_INSTRUCTIONS], and the stage-2
 * rubric in [TargetRubric] — are FIXTURE LAW (vision 07 §2 v1.3.0): they are pinned
 * byte-exact by the Tier A tests and the transcript fixtures, and any wording change
 * re-runs Tier A by retire-and-add (never an in-place edit).
 *
 * The `finish` pseudo-action is retired from decision slates (vision 02 §3): completion
 * is the [VERIFY_DONE] intent + the code-triggered IS-DONE checks (vision 04 §3).
 */
object Intents {

    /** The intent-vocabulary schema version — rides the RLCD manifest as the choose-question-schema version (vision 05 §4 v1.6.0). */
    const val INTENTS_VERSION = "intents-v1"

    /** The wire name of the stage-1 intent choice question. */
    const val INTENT_QUESTION_NAME = "intent"

    /**
     * The stage-1 instructions (fixture law, vision 02 §3 v1.3.0): the judgment phrased
     * for a knowledgeable person answering in a second, never the answer format.
     */
    const val INTENT_INSTRUCTIONS =
        "You are navigating a web browser toward the active subtask. Choose the ONE next move " +
            "from the categories. Judge from the subtask, its done-condition, and the page description."

    const val OPEN_SITE = "open-site"
    const val FOLLOW_LINK = "follow-link"
    const val ACTIVATE_CONTROL = "activate-control"
    const val FILL_FIELD = "fill-field"
    const val SUBMIT = "submit"
    const val GO_BACK = "go-back"
    const val SCROLL = "scroll"
    const val DISMISS_NAG = "dismiss-nag"
    const val VERIFY_DONE = "verify-done"

    /**
     * The 9 intent option criteria descriptions (fixture law) — the fixed table order of
     * vision 02 §3 v1.3.0. Also the round-trip vocabulary: probability maps and gold
     * distributions are keyed by these ids.
     */
    val ALL: List<Option> = listOf(
        Option(
            id = OPEN_SITE,
            label = OPEN_SITE,
            description = "The subtask needs a different site or page loaded by its address; code opens the URL directly.",
        ),
        Option(
            id = FOLLOW_LINK,
            label = FOLLOW_LINK,
            description = "A link or search result on this page plausibly leads toward the subtask's target.",
        ),
        Option(
            id = ACTIVATE_CONTROL,
            label = ACTIVATE_CONTROL,
            description = "A button, toggle, or other control on this page advances the subtask when pressed.",
        ),
        Option(
            id = FILL_FIELD,
            label = FILL_FIELD,
            description = "A form or search field on this page needs text typed to advance; the content will be phrased for you.",
        ),
        Option(
            id = SUBMIT,
            label = SUBMIT,
            description = "A search or form submission should fire now to advance.",
        ),
        Option(
            id = GO_BACK,
            label = GO_BACK,
            description = "This page is a dead end or wrong turn; going back is the best move.",
        ),
        Option(
            id = SCROLL,
            label = SCROLL,
            description = "The subtask's target is likely below the visible area.",
        ),
        Option(
            id = DISMISS_NAG,
            label = DISMISS_NAG,
            description = "A popup, cookie, permission, or upgrade dialog blocks the page; dismiss it.",
        ),
        Option(
            id = VERIFY_DONE,
            label = VERIFY_DONE,
            description = "The subtask's done-condition may now hold on this page; run the completion check.",
        ),
    )

    /** The intent ids in the fixed table order (the wire name space of stage 1). */
    val IDS: List<String> = ALL.map { it.id }

    /**
     * The stage-1 question over the fixed vocabulary — the SAME question on every call
     * and every task (the stable prior law). One instance is enough; the typed question
     * is immutable.
     */
    val QUESTION: ChoiceQuestion = ChoiceQuestion(
        name = INTENT_QUESTION_NAME,
        options = ALL,
        instructions = INTENT_INSTRUCTIONS,
    )

    /** Whether [id] is a vocabulary member. */
    fun isValid(id: String): Boolean = id in IDS.toSet()

    /**
     * Whether the intent needs a stage-2 target (vision 02 §3: "only when the intent
     * needs one"). `follow-link` / `activate-control` / `fill-field` / `dismiss-nag`
     * draw a bucket slate per doc 02 §3; `submit` rides the phase doc §2.6 routing
     * table's buttons-slate stage-2 (the SUBMIT_TRAP target selection). `open-site` is
     * the code path (the SiteEntry VIEW intent — no model target); `go-back` / `scroll`
     * are bare gestures; `verify-done` is the completion check.
     */
    fun needsTarget(id: String): Boolean = when (id) {
        FOLLOW_LINK, ACTIVATE_CONTROL, FILL_FIELD, DISMISS_NAG, SUBMIT -> true
        OPEN_SITE, GO_BACK, SCROLL, VERIFY_DONE -> false
        else -> throw IllegalArgumentException("unknown intent id '$id' — the vocabulary is fixed (vision 02 §3)")
    }
}
