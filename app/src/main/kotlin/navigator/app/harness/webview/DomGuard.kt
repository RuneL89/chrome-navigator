package navigator.app.harness.webview

import navigator.app.harness.webview.dom.DomAction
import navigator.app.harness.webview.dom.DomElement
import navigator.app.harness.webview.dom.DomSnapshot
import navigator.app.harness.webview.dom.ElementResolver
import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateSource
import navigator.core.gate.ActionKind
import navigator.core.gate.GateAction
import navigator.core.gate.HClass
import navigator.core.gate.PageContext
import navigator.core.gate.RiskGate

/**
 * V2 S1 — the pre-dispatch guard (FR-33, AC-S1.10): EVERY accepted proposal re-resolves
 * its element against a FRESH snapshot and passes the carried [RiskGate] before a
 * single byte dispatches. A refusal means ZERO dispatch — no best-effort alternative.
 *
 * Guard checks, in order:
 *  1. SEMANTIC RE-QUERY — the proposed element must still exist under its identity
 *     ([ElementResolver]); a snapshot id alone never dispatches (FR-12).
 *  2. USABILITY — the element must be visible (non-zero area) and not disabled; the
 *     page the proposal targets must still be the page the lane is on (ownership: the
 *     fresh snapshot's URL must equal the proposal's snapshot URL — a navigation under
 *     the proposal invalidates it).
 *  3. ACTION POLICY (FR-16) — the fill-only law: submit-classified actions are refused
 *     structurally. The lane vocabulary carries no SUBMIT, and a submit-looking control
 *     (submit/reset buttons, "place order"/"send" buttons on forms) is refused by name.
 *  4. RISK GATE (FR-40 carried) — the H1–H9 classifier over a DOM-derived [GateAction];
 *     ANY classification refuses in the fast lane (strictest rule wins: payment walls,
 *     purchases, logins and credentials belong to the human lane; S2/S5 wire the cards
 *     and the switch). Ambiguous fails closed — a null-vs-class doubt is a class.
 */
object DomGuard {

    /** Submit-shaped controls the fill-only law refuses by name (the owner taps these). */
    private val SUBMIT_WORDS = listOf(
        "submit", "place order", "place your order", "complete order", "confirm order",
        "complete purchase", "send message", "send email", "post comment", "sign in", "log in", "login",
        "lähetä", "tilaa", "kirjaudu",
    )

    sealed interface GuardVerdict {
        /** Dispatch may proceed on the re-resolved element. */
        data class Pass(val resolved: DomElement) : GuardVerdict

        data class Refuse(val reason: String, val riskClass: HClass? = null) : GuardVerdict
    }

    fun guard(
        proposal: DomProposalClient.Proposal,
        proposalSnapshot: DomSnapshot,
        freshSnapshot: DomSnapshot,
        goal: String = "",
    ): GuardVerdict {
        // Element-less actions (the search: the lane builds the engine URL itself) have no
        // element to re-query — but the QUERY must derive from the GOAL (the untrusted page
        // must never steer it — the live-run lesson), and the risk gate still applies.
        if (proposal.action == DomAction.SEARCH) {
            val query = proposal.value.orEmpty()
            if (!queryDerivesFromGoal(query, goal)) {
                return GuardVerdict.Refuse(
                    "the search query \"${query.take(60)}\" does not derive from the goal's subject — " +
                        "the page's own topic must never steer the query; re-derive it from the GOAL",
                )
            }
            val classified = RiskGate.classify(
                toGateAction(proposal, proposal.element),
                PageContext(url = freshSnapshot.page.url),
            )
            if (classified != null) {
                return GuardVerdict.Refuse(
                    "the risk gate classified ${classified.name} on the search — the fast lane never acts on it",
                    classified,
                )
            }
            return GuardVerdict.Pass(proposal.element)
        }

        // 1. semantic re-query
        val resolved = ElementResolver.resolve(proposal.element, freshSnapshot)
            ?: return GuardVerdict.Refuse(
                "the proposed element '${proposal.element.label.take(60)}' no longer matches its identity " +
                    "on the fresh snapshot — the DOM moved under the proposal",
            )

        // 2. page ownership: the proposal was made on one URL; the lane must still be there
        val proposedUrl = ElementResolver.normalizeLabel(proposalSnapshot.page.url)
        val currentUrl = ElementResolver.normalizeLabel(freshSnapshot.page.url)
        if (proposedUrl != currentUrl && proposal.action != DomAction.SCROLL_TO) {
            return GuardVerdict.Refuse(
                "the page navigated under the proposal (${shortUrl(proposalSnapshot.page.url)} → " +
                    "${shortUrl(freshSnapshot.page.url)}) — the proposal is stale",
            )
        }

        // 3. action policy — the fill-only law (FR-16)
        if (proposal.action == DomAction.CLICK) {
            val label = ElementResolver.normalizeLabel(proposal.element.label)
            val type = proposal.element.type.lowercase()
            if (type == "submit" || type == "reset") {
                return GuardVerdict.Refuse(
                    "the fill-only law: a submit/reset control ('$label') is the OWNER's tap — the lane never submits",
                )
            }
            if (SUBMIT_WORDS.any { label == it || label.startsWith("$it ") }) {
                return GuardVerdict.Refuse(
                    "the fill-only law: '$label' reads as a submit/send control — the owner's tap is the only submit",
                )
            }
        }

        // 4. the carried risk gate over a DOM-derived action
        val gateAction = toGateAction(proposal, resolved)
        val context = PageContext(url = freshSnapshot.page.url)
        val classified = RiskGate.classify(gateAction, context)
        if (classified != null) {
            return GuardVerdict.Refuse(
                "the risk gate classified ${classified.name} — the fast lane never acts on it " +
                    "(the human lane handles payment, purchase, login and credential work)",
                classified,
            )
        }
        return GuardVerdict.Pass(resolved)
    }

    /** DOM element + lane action → the classifier's shapes (a11y-era vocabulary). */
    internal fun toGateAction(proposal: DomProposalClient.Proposal, resolved: DomElement): GateAction {
        val candidate = Candidate(
            id = "dom-${resolved.id}",
            label = resolved.label.ifBlank { resolved.tag },
            kinds = listOf(resolved.role),
            cx = resolved.bounds.centerX,
            cy = resolved.bounds.centerY,
            bucket = when {
                resolved.role == "link" -> Bucket.NAV_LINKS
                resolved.tag in listOf("input", "textarea", "select") -> Bucket.FORM_FIELDS
                else -> Bucket.BUTTONS
            },
            source = CandidateSource.DOM,
            sourceOrder = resolved.id,
            synthetic = false,
        )
        val kind = when (proposal.action) {
            DomAction.CLICK -> ActionKind.TAP
            DomAction.TEXT -> ActionKind.TYPE
            DomAction.SELECT, DomAction.CHECK, DomAction.UNCHECK -> ActionKind.TAP
            DomAction.SCROLL_TO -> ActionKind.SCROLL
            DomAction.SEARCH -> ActionKind.TAP
        }
        return GateAction(target = candidate, kind = kind, textToType = proposal.value)
    }

    private fun shortUrl(url: String): String =
        url.substringAfter("://").let { if (it.length > 60) it.take(60) + "…" else it }

    /**
     * The FR-46 signal: does this action ASK for credentials or payment? A text action
     * on a password field or a card/credential-shaped field, or a click on a
     * payment-shaped control. The DOMAIN check is [PhishingGuard]'s (the loop wires it).
     */
    internal fun isCredentialOrPaymentAsk(element: DomElement, action: DomAction): Boolean {
        val label = ElementResolver.normalizeLabel(element.label)
        val credentialWords = listOf("password", "passphrase", "card number", "cvv", "cvc", "security code", "salasana", "kortinnumero")
        val paymentWords = listOf("pay now", "place order", "complete purchase", "maksa", "tilaa")
        return when (action) {
            DomAction.TEXT ->
                element.type.equals("password", ignoreCase = true) || credentialWords.any { label.contains(it) }
            DomAction.CLICK ->
                element.type.equals("submit", ignoreCase = true) || paymentWords.any { label.contains(it) }
            else -> false
        }
    }

    /**
     * The query-derivation law (the live-run lesson): a search query must share real
     * content with the GOAL — the page's own topic (an untrusted source) must never steer
     * the query. Directive verbs are stripped from both sides, so a goal's "open" does
     * not make a page-steered "The Open Championship" query look derived.
     */
    internal fun queryDerivesFromGoal(query: String, goal: String): Boolean {
        val queryTokens = contentTokens(query)
        val goalTokens = contentTokens(goal).toSet()
        if (queryTokens.isEmpty()) return false
        val shared = queryTokens.count { it in goalTokens }
        return shared >= 2 || (queryTokens.size == 1 && queryTokens.first() in goalTokens)
    }

    private val QUERY_STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are",
        "was", "were", "open", "search", "find", "report", "tell", "me", "about", "show",
        "article", "page", "site", "website", "please", "year", "years",
    )

    private fun contentTokens(text: String): List<String> =
        text.lowercase()
            .split(Regex("""[^a-z0-9äöüßå]+"""))
            .filter { it.length >= 3 && it !in QUERY_STOPWORDS }
}
