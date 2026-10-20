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
 * **H9 — persistent-state mutation (vision 06 §1 + §1a, Phase 8E):** the open-ended
 * consequential-action class is detected by a broad NET, not a list: the illustrative
 * verb/context dictionary ([MUTATION_CONTROL_WORDS] — a floor, never the definition),
 * structural mutation signals (state-word flips [STATE_FLIP_WORDS], confirm-dialog
 * semantics [CONFIRM_DIALOG_WORDS]), and fail-to-confirm. As with H2, link-bucket
 * candidates need mutation-context corroboration in the host/path ([MUTATION_SITE_WORDS],
 * query stripped — ART-4): page text can steer at most *toward* a gated action. Actions
 * the net cannot confidently resolve are NOT silent: [mutationNet] reports them
 * SUSPICIOUS and the loop runs the ADDITIVE-ONLY engine check ([AdditiveEngineCheck],
 * vision 06 §3) — a non-LOW answer adds H9; nothing can ever remove a code-triggered
 * class or clear a suspicious action without the engine's confident LOW.
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
        // TARGET-SCOPED credential names (2026-10-08 pre-UAT2 RC2): ENTRY-arm credential
        // classification is satisfied only by the ACTION'S OWN TARGET field's NAME
        // (id + label) — never by its VALUE. A page merely HAVING a password/identifier
        // field must not credential-classify typing into other fields; the target's
        // echoed content (live Chrome fields echo their typed value as a11y text) is
        // content, not a field name. (SUBMIT arms keep the form-level `pair` semantics
        // below — submission is form-level by law.)
        val targetFieldName = targetField?.let { "${it.id.lowercase()} ${it.label.lowercase()}" }.orEmpty()
        // Browser-chrome targets (the omnibox/URL-bar, RC2) are never credential
        // targets: their label IS the page URL text, so no wording on them may read as
        // a secret signal. They are also absent from [context.formFields]
        // ([FieldDescriptor.fromNodes]) — this guard closes the wording fallback too.
        val targetIsBrowserChrome = action.target.browserChrome

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
        val targetIsSecret = !targetIsBrowserChrome &&
            (targetField?.isPassword == true || FieldDescriptor.PASSWORD_WORDS.containsMatchIn(target))

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
        // unrelated submit — the safe direction (fail-to-confirm). ENTRY is
        // target-scoped (RC2): the action's own target must be a PAIR MEMBER — the
        // secret field itself, or a field whose NAME (id/label, never echoed value)
        // reads as the identifier. A browser-chrome target (omnibox) is never a member.
        if (entersTarget && pair && targetField != null &&
            (IDENTIFIER_FIELD_WORDS.containsMatchIn(targetFieldName) || targetField.isPassword)
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
        // submissions are covered by H3/H4. Requires the ACTION'S OWN TARGET to be the
        // secret field (target-scoped, RC2) — a page merely HAVING a secret field never
        // credential-classifies typing into other fields, and a browser-chrome target
        // (the omnibox) is never a credential target.
        if (entersTarget && targetIsSecret && !changeCues) matched += HClass.H8_CREDENTIAL_TYPING

        // -- H9: persistent-state mutation (general class, vision 06 §1 + §1a, Phase 8E) ----
        // The CONFIDENT half of the broad net: the illustrative dictionary floor,
        // state-word flips (mutation-worded toggles), and confirm-dialog semantics — on
        // an actual control (tap/submit). A link-bucket candidate needs mutation-context
        // corroboration in the host/path (query stripped, ART-4): an article titled
        // "How to delete your account" is navigation, not a mutation step — but an
        // uncorroborated mutation-worded link is NOT silently cleared, it is SUSPICIOUS
        // ([mutationNet] -> the loop's additive engine check). Novel verbs not in the
        // dictionary are caught by the same structure when confirm/flip wording rides
        // them, and by the engine check otherwise — never by dictionary growth alone.
        if (action.kind == ActionKind.TAP || action.kind == ActionKind.SUBMIT) {
            val linkBucket = action.target.bucket == Bucket.NAV_LINKS
            val corroborated = MUTATION_SITE_WORDS.containsMatchIn(sitePath)
            if (!linkBucket || corroborated) {
                val mutationWording = MUTATION_CONTROL_WORDS.containsMatchIn(target) ||
                    STATE_FLIP_WORDS.containsMatchIn(target) ||
                    CONFIRM_DIALOG_WORDS.containsMatchIn(target)
                if (mutationWording) matched += HClass.H9_PERSISTENT_MUTATION
            }
        }

        // -- fail-to-confirm resolution -------------------------------------------------------
        val specific = matched.filter { it != HClass.H8_CREDENTIAL_TYPING }
        return when {
            specific.isNotEmpty() -> specific.maxBy { it.strictness }
            matched.isNotEmpty() -> HClass.H8_CREDENTIAL_TYPING
            else -> null
        }
    }

    /**
     * The mutation-net verdict behind the ADDITIVE-ONLY engine check (vision 06 §1a + §3):
     * what the code net itself made of the action's mutation risk.
     *
     * - [MutationNet.GATED] — the code net confidently classified the action
     *   ([classify] != null). The engine is NEVER consulted (its judgment could only
     *   ever add — there is nothing to add) and can never speak against the class.
     * - [MutationNet.SUSPICIOUS] — mutation-ish but unconfirmed: fail-to-confirm
     *   territory with no confident H-class. The loop asks the primary engine the §3
     *   question; a non-LOW answer ADDS H9. A confident LOW clears it — the only exit
     *   that does not execute a gate, and the engine's alone to give.
     * - [MutationNet.ORDINARY] — no mutation signal at all: confidently ordinary
     *   (navigation, search, scroll, plain text entry). Never consulted, never gated —
     *   deterministic negatives stay ungated regardless of what any engine would say.
     */
    fun mutationNet(action: GateAction, context: PageContext): MutationNet {
        if (classify(action, context) != null) return MutationNet.GATED
        if (action.kind != ActionKind.TAP && action.kind != ActionKind.SUBMIT) return MutationNet.ORDINARY

        val target = targetText(action)
        val linkBucket = action.target.bucket == Bucket.NAV_LINKS
        if (linkBucket) {
            // Link-bucket candidates: mutation WORDING alone (uncorroborated — the
            // corroborated case already classified GATED above) is suspicious, not
            // silent: an engine consult separates the article from the real control.
            val worded = MUTATION_CONTROL_WORDS.containsMatchIn(target) ||
                STATE_FLIP_WORDS.containsMatchIn(target) ||
                CONFIRM_DIALOG_WORDS.containsMatchIn(target)
            return if (worded) MutationNet.SUSPICIOUS else MutationNet.ORDINARY
        }
        if (MUTATION_WEAK_WORDS.containsMatchIn(target)) return MutationNet.SUSPICIOUS
        // Novel-verb structure: a first-person data object ("my data/feed/history…")
        // under an unknown verb — the dictionary cannot list every verb, so the shape
        // routes it to the engine instead of letting it pass unexamined.
        if (POSSESSIVE_DATA_SHAPE.containsMatchIn(target)) return MutationNet.SUSPICIOUS
        // Control activation on an authenticated/mutation-flavored surface (host/path
        // only, query stripped — ART-4): the "submissions on authenticated surfaces"
        // structural signal of vision 06 §1a, held at the engine-consult tier. The
        // classic "Save"/"Continue" on a settings page — or on a mutation-bait page —
        // could be harmless and could be the mutation: the engine decides.
        if (AUTH_SURFACE_WORDS.containsMatchIn(PageContext.siteAndPath(context.url))) {
            return MutationNet.SUSPICIOUS
        }
        return MutationNet.ORDINARY
    }

    /** The mutation-net verdict (vision 06 §1a + §3 — see [mutationNet]). */
    enum class MutationNet {
        /** Confidently classified ([classify] != null) — the engine is never consulted. */
        GATED,

        /** Mutation-ish, unconfirmed — fail-to-confirm territory: additive engine check. */
        SUSPICIOUS,

        /** No mutation signal — confidently ordinary; never consulted, never gated. */
        ORDINARY,
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

    /**
     * Illustrative mutation dictionary (H9, vision 06 §1a): the vision's listed verbs
     * (subscribe/unsubscribe, follow/unfriend, cancel, remove, delete, post, send,
     * book, confirm-booking…) plus their close destructive family. A maintained FLOOR
     * that is illustrative, never the definition — novel verbs must still classify via
     * the net's structure ([STATE_FLIP_WORDS], [CONFIRM_DIALOG_WORDS],
     * [mutationNet]) and the additive engine check ([AdditiveEngineCheck]). The
     * dictionary grows via additive classifier-matrix rows; it is test data, not
     * product law. Word-boundary discipline: `\bpost\b`/`\bsend\b`/`\bbook\b` must not
     * fire inside posting/booking/sender — a `booking` hostname is not a booking
     * action.
     */
    internal val MUTATION_CONTROL_WORDS = Regex(
        "subscribe|unsubscribe|follow|unfollow|unfriend|\\bcancel|\\bremove|\\bdelete|" +
            "\\bpost\\b|\\bsend\\b|\\bbook\\b|confirm[ -]?booking|\\brenew|\\bredeem|" +
            "\\bwithdraw|\\bdeactivate|\\brevoke|erase|wipe|purge|destroy|expunge|obliterate|scrub",
    )

    /**
     * State-word flips (H9 structural signal, vision 06 §1a): controls worded as
     * flipping a persisted setting or relationship. Kept toward persistent flips —
     * player-style pause/hide are deliberately absent (ephemeral controls would
     * over-gate; the uncertain cases belong to [mutationNet] + the engine).
     */
    internal val STATE_FLIP_WORDS = Regex(
        "opt[ -]?in|opt[ -]?out|turn[ -]?(on|off)|switch[ -]?(on|off)|enable|disable|" +
            "activat(e|ing)|deactivat|un[ -]?link|disconnect|\\bblock\\b|unblock|" +
            "\\bmute\\b|unmute|publish|unpublish",
    )

    /**
     * Confirm-dialog semantics (H9 structural signal, vision 06 §1a): the wording that
     * marks a destructive-action confirmation — the sentences a mutation dialog carries.
     */
    internal val CONFIRM_DIALOG_WORDS = Regex(
        "are[ -]?you[ -]?sure|cannot[ -]?be[ -]?undone|can'?t[ -]?be[ -]?undone|no[ -]?undo|" +
            "irreversible|permanently|this[ -]?(will|cannot)[ -]?(delete|remove|cancel|erase|affect)|" +
            "confirm[ -]?(deletion|removal|cancellation|erasure)|" +
            "yes[ -]?,?[ -]?(delete|remove|cancel|continue|proceed|i[ -]?understand)",
    )

    /**
     * Weak mutation wording ([mutationNet] SUSPICIOUS tier — the engine's territory,
     * never a confident class): mutation-adjacent verbs that are harmless as often as
     * not (a "Save" can be a draft, an "Update" a read-model refresh). Consult, don't
     * gate and don't clear.
     */
    internal val MUTATION_WEAK_WORDS = Regex(
        "\\bsave\\b|\\bupdate\\b|\\bapply\\b|\\bmanage\\b|\\bsync\\b|\\bimport\\b|\\bmerge\\b|" +
            "\\bconnect\\b|\\barchive\\b|\\brestore\\b|\\btransfer\\b",
    )

    /**
     * Novel-verb structure ([mutationNet] SUSPICIOUS tier): an unknown verb acting on a
     * first-person data object ("Frobnicate my feed") — the shape, not the verb, routes
     * it to the additive engine check.
     */
    internal val POSSESSIVE_DATA_SHAPE = Regex(
        "\\bmy[ -]?(data|account|profile|history|content|photos?|files?|messages|posts?|" +
            "subscriptions?|bookings?|records?|feed|lists?|reviews?|payment)",
    )

    /**
     * Mutation-context host/path words (H9 corroboration for link-bucket candidates,
     * query stripped before matching — ART-4): the same pattern as H2's purchase
     * corroboration. A mutation-worded LINK on such a path is confident H9; the same
     * wording elsewhere is SUSPICIOUS ([mutationNet]), never silently ordinary.
     */
    internal val MUTATION_SITE_WORDS = Regex(
        "account|settings|subscriptions?|profile|preferences|membership|billing|admin|" +
            "security|danger|deactivate|unsubscribe",
    )

    /**
     * Authenticated/mutation-flavored host/path words ([mutationNet] SUSPICIOUS tier,
     * query stripped before matching — ART-4): the "submissions on authenticated
     * surfaces" structural signal of vision 06 §1a, held at the engine-consult tier.
     */
    internal val AUTH_SURFACE_WORDS = Regex(
        "account|settings|profile|preferences|subscriptions?|/billing|membership|admin|" +
            "security|privacy|danger|deactivate",
    )

    private fun targetText(action: GateAction): String =
        "${action.target.label} ${action.target.id}".lowercase()

    private fun FieldDescriptor.fieldWords(): String = "${id.lowercase()} ${label.lowercase()} ${value.lowercase()}"
}
