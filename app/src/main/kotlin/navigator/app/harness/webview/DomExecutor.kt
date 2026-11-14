package navigator.app.harness.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import navigator.app.harness.webview.dom.DomAction
import navigator.app.harness.webview.dom.DomActions
import navigator.app.harness.webview.dom.DomElement
import navigator.app.harness.webview.dom.DomSnapshot

/**
 * V2 S1 — the guarded executor (FR-34) and its read-back verification (FR-35): ONE
 * evaluateJavascript per action; the dispatch report is NEVER a success claim — the
 * read-back values ride in the same result and the postcondition verdict is computed
 * from them (plus the fresh snapshot for click effects).
 */
class DomExecutor(private val web: LaneWebView) {

    /** The last scroll position this executor produced (the scroll honesty check). */
    private var lastScrollY: Int? = null

    data class Report(
        val dispatched: Boolean,
        /** The read-back verdict (FR-35): only a verified effect counts as progress. */
        val verified: Boolean,
        val evidence: String,
        val hasDownHandler: Boolean = false,
        val mismatch: Boolean = false,
    )

    suspend fun execute(resolved: DomElement, proposal: DomProposalClient.Proposal): Report = when (proposal.action) {
        DomAction.CLICK -> click(resolved, proposal)
        DomAction.TEXT -> text(resolved, proposal)
        DomAction.SELECT -> select(resolved, proposal)
        DomAction.CHECK -> check(resolved, proposal, requested = true)
        DomAction.UNCHECK -> check(resolved, proposal, requested = false)
        DomAction.SCROLL_TO -> scroll(resolved, proposal)
        DomAction.SEARCH -> search(proposal)
    }

    /** The FR-13 escalation's second arm: the trusted touch dispatch at the element center. */
    suspend fun trustedTouch(resolved: DomElement): Report {
        web.dispatchTrustedTouch(resolved.bounds.centerX, resolved.bounds.centerY)
        return Report(
            dispatched = true,
            verified = false,
            evidence = "trusted touch dispatched at (${resolved.bounds.centerX},${resolved.bounds.centerY}) — " +
                "the postcondition owns the verdict",
            hasDownHandler = true,
        )
    }

    private suspend fun click(resolved: DomElement, proposal: DomProposalClient.Proposal): Report {
        val js = DomActions.clickJs(resolved.id, resolved.role, resolved.tag, resolved.type, resolved.label)
        val result = parse(web.evaluate(js)) ?: return Report(false, false, "click script returned no JSON")
        if (isMismatch(result)) return Report(false, false, "identity mismatch — the DOM moved under the proposal", mismatch = true)
        val clicked = bool(result, "clicked") ?: false
        val down = bool(result, "hasDownHandler") ?: false
        return Report(
            dispatched = clicked,
            verified = false, // the click's verdict comes from the POSTCONDITION, not the dispatch
            evidence = if (clicked) "element.click() dispatched" else "click reported not dispatched (${str(result, "error")})",
            hasDownHandler = down,
        )
    }

    private suspend fun text(resolved: DomElement, proposal: DomProposalClient.Proposal): Report {
        val value = proposal.value ?: return Report(false, false, "text action without a value")
        val js = DomActions.textJs(resolved.id, resolved.role, resolved.tag, resolved.type, resolved.label, value)
        val result = parse(web.evaluate(js)) ?: return Report(false, false, "text script returned no JSON")
        if (isMismatch(result)) return Report(false, false, "identity mismatch — the DOM moved under the proposal", mismatch = true)
        val readBack = str(result, "value")
        val verified = DomActions.verifyText(value, readBack)
        return Report(
            dispatched = true,
            verified = verified,
            evidence = if (verified) "field read-back equals the requested text" else "read-back '${readBack?.take(40)}' != requested",
        )
    }

    private suspend fun select(resolved: DomElement, proposal: DomProposalClient.Proposal): Report {
        val value = proposal.value ?: return Report(false, false, "select action without an option text")
        val js = DomActions.selectJs(resolved.id, resolved.role, resolved.tag, resolved.type, resolved.label, value)
        val result = parse(web.evaluate(js)) ?: return Report(false, false, "select script returned no JSON")
        if (isMismatch(result)) return Report(false, false, "identity mismatch — the DOM moved under the proposal", mismatch = true)
        val readBack = str(result, "selected")
        val verified = DomActions.verifySelect(value, readBack)
        return Report(
            dispatched = true,
            verified = verified,
            evidence = if (verified) "selected option read-back equals the request" else "selected '${readBack?.take(40)}' != requested",
        )
    }

    private suspend fun check(resolved: DomElement, proposal: DomProposalClient.Proposal, requested: Boolean): Report {
        val js = DomActions.checkJs(resolved.id, resolved.role, resolved.tag, resolved.type, resolved.label, requested)
        val result = parse(web.evaluate(js)) ?: return Report(false, false, "check script returned no JSON")
        if (isMismatch(result)) return Report(false, false, "identity mismatch — the DOM moved under the proposal", mismatch = true)
        val readBack = bool(result, "checked")
        val verified = DomActions.verifyCheck(requested, readBack)
        return Report(
            dispatched = true,
            verified = verified,
            evidence = if (verified) "checked read-back equals the request" else "checked read-back $readBack != requested",
        )
    }

    private suspend fun scroll(resolved: DomElement, proposal: DomProposalClient.Proposal): Report {
        val js = DomActions.scrollJs(resolved.id, resolved.role, resolved.tag, resolved.type, resolved.label)
        val result = parse(web.evaluate(js)) ?: return Report(false, false, "scroll script returned no JSON")
        if (isMismatch(result)) return Report(false, false, "identity mismatch — the DOM moved under the proposal", mismatch = true)
        val scrollY = intOrNull(result, "scrollY")
        // FR-17's law, enforced: the viewport must actually MOVE — scrolling to where the
        // page already is is NOT progress (the S3 fixture run's treadmill lesson).
        val moved = scrollY != null && scrollY != lastScrollY
        lastScrollY = scrollY ?: lastScrollY
        return Report(
            dispatched = true,
            verified = moved,
            evidence = if (moved) "the viewport moved to scrollY=$scrollY" else "no viewport change (scrollY=$scrollY) — not progress",
        )
    }

    /**
     * The search action (FR-31's navigation half): the writer supplies ONLY the query;
     * the lane builds the engine URL itself and loads it under the lane's load policy.
     * The verdict comes from the postcondition (the results page), never from the load.
     */
    private suspend fun search(proposal: DomProposalClient.Proposal): Report {
        val query = proposal.value ?: return Report(false, false, "search action without a query")
        val url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")
        web.load(url)
        return Report(dispatched = true, verified = false, evidence = "search loaded: ${url.take(80)}")
    }

    // -- result JSON helpers ----------------------------------------------------------------

    private fun parse(raw: String): kotlinx.serialization.json.JsonObject? = try {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) null else Json.parseToJsonElement(trimmed).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun isMismatch(obj: kotlinx.serialization.json.JsonObject): Boolean =
        (obj["mismatch"] as? JsonPrimitive)?.booleanOrNull == true

    private fun bool(obj: kotlinx.serialization.json.JsonObject, key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.booleanOrNull

    private fun str(obj: kotlinx.serialization.json.JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun intOrNull(obj: kotlinx.serialization.json.JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.jsonPrimitive?.content?.toIntOrNull()
}

/** The click POSTCONDITION (FR-35): evidence only — a fresh snapshot decides. */
data class ClickPostcondition(
    val urlBefore: String,
    val textBefore: String,
    val elementStateBefore: String,
) {
    fun verified(fresh: DomSnapshot, resolved: DomElement): Boolean {
        val after = fresh.elements.firstOrNull { it.id == resolved.id }
        val stateAfter = after?.valueState ?: ""
        return DomActions.verifyClickEffect(
            urlBefore, fresh.page.url, textBefore, fresh.text, elementStateBefore, stateAfter,
        )
    }
}
