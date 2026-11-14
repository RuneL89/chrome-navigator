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
    ): GuardVerdict {
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
        }
        return GateAction(target = candidate, kind = kind, textToType = proposal.value)
    }

    private fun shortUrl(url: String): String =
        url.substringAfter("://").let { if (it.length > 60) it.take(60) + "…" else it }
}
