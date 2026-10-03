package navigator.core.gate

import navigator.core.enumerate.Bucket

/**
 * The deterministic high-stakes classifier (vision 06 §1 — law, ART-1: code owns the
 * gate, no model judgment in the gate path). Pure heuristics over the action's target
 * node, the action shape, the typed payload's shape, and the page/form context.
 *
 * **Fail-to-confirm:** any matched high-stakes signal resolves to a class — never null.
 * Ambiguity resolves to the stricter matching class ([HClass.strictness]) because the
 * gate's per-task trust grant follows the resolved class: the narrower class is the
 * conservative choice (approving it never auto-executes a broader class later). The
 * catch-all [HClass.H8_CREDENTIAL_TYPING] is resolved residually — it claims an action
 * only when no more specific class does; the phase doc pins the canonical example
 * (ambiguous checkout-like form -> H1).
 *
 * All other actions (navigation, search, scroll, non-credential text entry,
 * non-purchase clicks) classify null and execute without pausing (vision 06 §1).
 */
object RiskGate {

    /**
     * Classifies one action against its page context. Returns the high-stakes class to
     * gate on, or null when the action is ordinary and may execute without pausing.
     */
    fun classify(action: GateAction, context: PageContext): HClass? {
        // Vision 06 §1: navigation and scroll execute without pausing — the synthetic
        // actions are never gated regardless of any page words around them.
        if (action.kind == ActionKind.SCROLL ||
            action.kind == ActionKind.BACK ||
            action.kind == ActionKind.FINISH
        ) {
            return null
        }

        val target = targetText(action)
        val targetField = context.formFields.firstOrNull { it.id == action.target.id }
        val fieldText = targetField?.let { "${it.id.lowercase()} ${it.label.lowercase()} ${it.value.lowercase()}" }.orEmpty()

        val sitePath = PageContext.siteAndPath(context.url)
        val hasCardField = context.formFields.any { it.isCardLike }
        val hasSecretField = context.formFields.any { it.isPassword }
        val hasIdentifierField = context.formFields.any { IDENTIFIER_FIELD_WORDS.containsMatchIn(it.fieldWords()) }
        val pair = hasSecretField && hasIdentifierField
        val changeCues = PASSWORD_CHANGE_CUES.containsMatchIn(sitePath) ||
            context.formFields.any { PASSWORD_CHANGE_CUES.containsMatchIn(it.fieldWords()) }
        val loginWording = LOGIN_CONTROL_WORDS.containsMatchIn(target) || LOGIN_SITE_WORDS.containsMatchIn(sitePath)
        val lifecycleWording = SIGNUP_CONTROL_WORDS.containsMatchIn(target)

        // Tapping into an editable field is the first half of typing into it: treat
        // focus as entry (typing would follow on the next step).
        val entersTarget = action.kind == ActionKind.TYPE ||
            (action.kind == ActionKind.TAP && targetField != null)
        val targetIsSecret = targetField?.isPassword == true || FieldDescriptor.PASSWORD_WORDS.containsMatchIn(target)

        val matched = mutableSetOf<HClass>()

        // -- H1: payment / checkout form entry-or-submission ----------------------------
        // (a) typing into a card-like field, or typing a card-number-shaped value into
        //     ANY field (content-shape exfil guard — a 16-digit number typed into a
        //     comment box is still payment-data movement);
        // (b) submitting a form whose context carries card-like fields;
        // (c) submitting a form whose fields carry checkout/payment keywords (the card
        //     fields may load late — checkout keywords in form context count per vision).
        val typedCardShape = action.textToType?.let { FieldDescriptor.CARD_VALUE_SHAPE.matches(it.trim()) } == true
        if (action.kind == ActionKind.TYPE && (targetField?.isCardLike == true || typedCardShape)) matched += HClass.H1_PAYMENT
        if (action.kind == ActionKind.SUBMIT &&
            (hasCardField || context.formFields.any { CHECKOUT_FORM_WORDS.containsMatchIn(it.fieldWords()) })
        ) {
            matched += HClass.H1_PAYMENT
        }

        // -- H2: purchase confirmation ---------------------------------------------------
        // Buy/purchase/checkout/place-order controls. A purchase-context URL (host/path
        // only — query text is untrusted, ART-4) corroborates any control; elsewhere the
        // verb must come from an actual button/field control, not a NAV_LINKS candidate —
        // a search-results link titled "Buy apple pie online" is not a purchase step.
        if (action.kind == ActionKind.TAP || action.kind == ActionKind.SUBMIT) {
            val purchaseVerb = PURCHASE_CONTROL_WORDS.containsMatchIn(target)
            val purchaseContext = PURCHASE_SITE_WORDS.containsMatchIn(sitePath)
            if (purchaseVerb && (purchaseContext || action.target.bucket != Bucket.NAV_LINKS)) {
                matched += HClass.H2_PURCHASE_CONFIRM
            }
        }

        // -- H3: password create/change — entry or submission ----------------------------
        // Entry is gated under H3 when the context says create/change (new/confirm/
        // current/reset password cues); a bare secret field with no cues routes to H8
        // (the vision's wrong-site/injected-capture class), a login-shaped secret to H4.
        // Submission of any secret-bearing form is gated here UNLESS the form reads as
        // login (pair + login wording) or account lifecycle (signup/delete wording) —
        // those are H4/H5's canonical cases; everything else (cues, lone secret field,
        // ambiguous wording) fails to H3 — never fail-open.
        if (entersTarget && targetIsSecret && changeCues) matched += HClass.H3_PASSWORD_CHANGE
        if (action.kind == ActionKind.SUBMIT && hasSecretField &&
            (changeCues || !(pair && loginWording) && !lifecycleWording)
        ) {
            matched += HClass.H3_PASSWORD_CHANGE
        }

        // -- H4: login form — credential entry or submission ------------------------------
        // identifier+secret PAIR typing (either member) is credential entry on any site;
        // submitting over a pair is credential submission (distinct from search: search
        // forms carry no secret field). Pages with a login widget can over-gate an
        // unrelated submit — the safe direction (fail-to-confirm).
        if (entersTarget && pair && targetField != null &&
            (IDENTIFIER_FIELD_WORDS.containsMatchIn(fieldText) || targetField.isPassword)
        ) {
            matched += HClass.H4_LOGIN
        }
        if (action.kind == ActionKind.SUBMIT && pair) matched += HClass.H4_LOGIN

        // -- H5: account creation / deletion ---------------------------------------------
        // signup keywords; delete/close/cancel-account confirmations (any control kind —
        // destructive links are common). "Register" on a newsletter also fails here:
        // account-creation wording is never an ordinary click.
        if (action.kind == ActionKind.TAP || action.kind == ActionKind.SUBMIT) {
            if (lifecycleWording) matched += HClass.H5_ACCOUNT
        }

        // -- H6: file upload ---------------------------------------------------------------
        // file-input activation or upload intent (tap or submit of upload controls).
        if ((action.kind == ActionKind.TAP || action.kind == ActionKind.SUBMIT) &&
            UPLOAD_CONTROL_WORDS.containsMatchIn(target)
        ) {
            matched += HClass.H6_UPLOAD
        }

        // -- H7: download initiation --------------------------------------------------------
        // download-triggering links/actions (any bucket — downloads are usually links,
        // but a SUBMIT labelled Download initiates one too: vision 06 "links/actions").
        if (
            (action.kind == ActionKind.TAP || action.kind == ActionKind.SUBMIT) &&
            DOWNLOAD_CONTROL_WORDS.containsMatchIn(target)
        ) {
            matched += HClass.H7_DOWNLOAD
        }

        // -- H8: credential typing into any field (residual catch-all) ----------------------
        // A secret field entered with NO create/change cues — the vision's
        // wrong-site/injected credential-capture case. Login-shaped entry resolved to H4
        // above (pair present); this is the no-context remainder. Purely entry-shaped:
        // submissions are covered by H3/H4.
        if (entersTarget && targetIsSecret && !changeCues) matched += HClass.H8_CREDENTIAL_TYPING

        // -- fail-to-confirm resolution -------------------------------------------------------
        val specific = matched.filter { it != HClass.H8_CREDENTIAL_TYPING }
        return when {
            specific.isNotEmpty() -> specific.maxBy { it.strictness }
            matched.isNotEmpty() -> HClass.H8_CREDENTIAL_TYPING
            else -> null
        }
    }

    // -- heuristics tables (each entry scope-documented at its use site; the field-lexical
    //    tables shared with FieldDescriptor derivation live on FieldDescriptor) ----------

    /** Buy/purchase/checkout/place-order control wording. */
    internal val PURCHASE_CONTROL_WORDS = Regex(
        "buy|purchase|place[ -]?order|order[ -]?now|complete[ -]?order|confirm[ -]?order|" +
            "checkout|check-out|pay[ -]?now|proceed[ -]?to[ -]?(checkout|payment)|" +
            "continue[ -]?to[ -]?(checkout|payment)",
    )

    /** Purchase-context host/path words (query stripped before matching — ART-4). */
    internal val PURCHASE_SITE_WORDS = Regex("shop|store|checkout|cart|payment|billing|/buy|/order|/product|/p/|deals?")

    /** Checkout/payment keywords that mark a FORM as checkout-like (vision 06 §1 H1). */
    internal val CHECKOUT_FORM_WORDS = Regex("checkout|payment|billing|cardholder|place[ -]?order|pay[ -]?now")

    /** Password create/change cues: new/confirm/current/old/reset/change/set/update… */
    internal val PASSWORD_CHANGE_CUES = Regex(
        "new[ -]?password|confirm[ -]?password|current[ -]?password|old[ -]?password|" +
            "create[ -]?password|choose[ -]?password|retype[ -]?password|re-enter[ -]?password|" +
            "change[ -]?password|reset[ -]?password|password[ -]?reset|update[ -]?password|set[ -]?a?[ -]?password",
    )

    /** Login control wording (sign in / log in). */
    internal val LOGIN_CONTROL_WORDS = Regex("log[ -]?in|login|sign[ -]?in|signin")

    /** Login-context host/path wording. */
    internal val LOGIN_SITE_WORDS = Regex("login|signin|sign-in|log-in|/auth|accounts?\\.")

    /** Account create/delete wording (H5). */
    internal val SIGNUP_CONTROL_WORDS = Regex(
        "sign[ -]?up|create[ -]?(an?[ -]?)?(new[ -]?)?account|register|join[ -]?now|" +
            "delete[ -]?(my[ -]?)?account|close[ -]?(my[ -]?)?account|cancel[ -]?(my[ -]?)?account|" +
            "remove[ -]?(my[ -]?)?account|deactivate[ -]?(my[ -]?)?account|permanently[ -]?delete",
    )

    /** Identifier-field wording (username/email/phone/account…). */
    internal val IDENTIFIER_FIELD_WORDS = Regex(
        "user[ -]?name|\\blogin\\b|log[ -]?in|e-?mail|account|phone|mobile[ -]?(number|no)|member[ -]?id",
    )

    /** Upload control wording (H6). */
    internal val UPLOAD_CONTROL_WORDS =
        Regex("upload|attach|choose[ -]?file|browse[ -]?files?|select[ -]?file|import[ -]?file")

    /** Download control wording (H7) — deliberately narrow ("save" alone would catch Save-draft). */
    internal val DOWNLOAD_CONTROL_WORDS =
        Regex("download|save[ -]?(this|the)[ -]?(file|page|pdf|image)|get[ -]?the[ -]?app|install[ -]?now")

    private fun targetText(action: GateAction): String =
        "${action.target.label} ${action.target.id}".lowercase()

    private fun FieldDescriptor.fieldWords(): String = "${id.lowercase()} ${label.lowercase()} ${value.lowercase()}"
}
