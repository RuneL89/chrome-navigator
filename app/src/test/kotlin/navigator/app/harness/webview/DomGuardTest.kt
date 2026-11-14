package navigator.app.harness.webview

import navigator.app.harness.webview.dom.DomAction
import navigator.app.harness.webview.dom.DomElement
import navigator.app.harness.webview.dom.PageMeta
import navigator.app.harness.webview.dom.DomSnapshot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V2 S1 FR-33/FR-16/FR-40 (AC-S1.10's guard half) — the pre-dispatch guard: semantic
 * re-query, page ownership, the fill-only law, and the carried risk gate. A refusal
 * dispatches NOTHING.
 */
class DomGuardTest {

    private fun proposal(element: DomElement, action: DomAction = DomAction.CLICK, value: String? = null) =
        DomProposalClient.Proposal(
            element = element,
            action = action,
            value = value,
            expectedEffect = "the expected effect",
            rawContent = "{}",
        )

    private fun page(elements: List<DomElement>, url: String = "https://shop.test/p", text: String = "page") =
        DomSnapshot(PageMeta(url, "Shop"), elements, text)

    @Test
    fun `a live element on the same page passes`() {
        val target = SnapshotFixtures.element(id = 0, role = "button", label = "Add to cart", tag = "button", x = 10, y = 10)
        val before = page(listOf(target))
        val fresh = page(listOf(SnapshotFixtures.element(id = 4, role = "button", label = "Add to cart", tag = "button", x = 10, y = 12)))
        val verdict = DomGuard.guard(proposal(target), before, fresh)
        assertTrue(verdict is DomGuard.GuardVerdict.Pass)
        assertEquals(4, (verdict as DomGuard.GuardVerdict.Pass).resolved.id, "the guard dispatches on the FRESH element")
    }

    @Test
    fun `a vanished element refuses with zero dispatch`() {
        val target = SnapshotFixtures.element(id = 0, role = "button", label = "Add to cart", tag = "button")
        val before = page(listOf(target))
        val fresh = page(listOf(SnapshotFixtures.element(id = 1, role = "button", label = "Buy now", tag = "button")))
        val verdict = DomGuard.guard(proposal(target), before, fresh)
        assertTrue(verdict is DomGuard.GuardVerdict.Refuse && verdict.reason.contains("no longer matches"))
    }

    @Test
    fun `a page that navigated under the proposal refuses the stale proposal`() {
        val target = SnapshotFixtures.element(id = 0, role = "button", label = "Add to cart", tag = "button")
        val before = page(listOf(target), url = "https://shop.test/product")
        val fresh = page(listOf(target), url = "https://shop.test/cart")
        val verdict = DomGuard.guard(proposal(target), before, fresh)
        assertTrue(verdict is DomGuard.GuardVerdict.Refuse && verdict.reason.contains("navigated under the proposal"))
    }

    // -- the fill-only law (FR-16) -------------------------------------------------------------

    @Test
    fun `submit-shaped controls are refused - the owner's tap is the only submit`() {
        val target = SnapshotFixtures.element(id = 0, role = "button", label = "Place order", tag = "button", type = "submit")
        val page = page(listOf(target))
        val verdict = DomGuard.guard(proposal(target), page, page)
        assertTrue(verdict is DomGuard.GuardVerdict.Refuse && verdict.reason.contains("fill-only"))
    }

    @Test
    fun `submit-by-name is refused even without the type attribute`() {
        val target = SnapshotFixtures.element(id = 0, role = "button", label = "Sign in", tag = "button")
        val page = page(listOf(target))
        val verdict = DomGuard.guard(proposal(target), page, page)
        assertTrue(verdict is DomGuard.GuardVerdict.Refuse && verdict.reason.contains("fill-only"))
    }

    @Test
    fun `a plain add-to-cart button is NOT submit-shaped`() {
        val target = SnapshotFixtures.element(id = 0, role = "button", label = "Add to cart", tag = "button")
        val page = page(listOf(target))
        assertTrue(DomGuard.guard(proposal(target), page, page) is DomGuard.GuardVerdict.Pass)
    }

    // -- the carried risk gate (FR-40) -----------------------------------------------------------

    @Test
    fun `credential-shaped fields refuse through the risk gate - the human lane owns them`() {
        val password = SnapshotFixtures.element(
            id = 0, role = "textbox", label = "Password", tag = "input", type = "password", y = 0,
        )
        val page = page(listOf(password), url = "https://accounts.test/login")
        val verdict = DomGuard.guard(proposal(password, DomAction.TEXT, value = "secret-value"), page, page)
        assertTrue(verdict is DomGuard.GuardVerdict.Refuse)
        assertTrue(
            (verdict as DomGuard.GuardVerdict.Refuse).riskClass != null,
            "the H-class rides the refusal for the switch surface",
        )
    }

    @Test
    fun `the guard's DOM-to-gate mapping keeps the vocabulary honest`() {
        val link = SnapshotFixtures.element(id = 0, role = "link", label = "Home", tag = "a")
        val gateAction = DomGuard.toGateAction(
            proposal(link), link,
        )
        assertEquals(navigator.core.enumerate.Bucket.NAV_LINKS, gateAction.target.bucket)
        assertEquals(navigator.core.gate.ActionKind.TAP, gateAction.kind)
        val field = SnapshotFixtures.element(id = 1, role = "textbox", label = "Email", tag = "input")
        val typeAction = DomGuard.toGateAction(
            proposal(field, DomAction.TEXT, value = "a@b.c"), field,
        )
        assertEquals(navigator.core.enumerate.Bucket.FORM_FIELDS, typeAction.target.bucket)
        assertEquals(navigator.core.gate.ActionKind.TYPE, typeAction.kind)
        assertEquals("a@b.c", typeAction.textToType)
    }

    // -- the query-derivation law (the live-run lesson) ---

    @Test
    fun `a page-steered search query is refused - the goal is the only source`() {
        val target = SnapshotFixtures.element(id = 0, role = "button", label = "Search", tag = "button")
        val pageSnapshot = page(listOf(target))
        val proposal = DomProposalClient.Proposal(
            target, navigator.app.harness.webview.dom.DomAction.SEARCH, "The Open Championship golf tournament",
            "results show", "{}",
        )
        val verdict = DomGuard.guard(
            proposal, pageSnapshot, pageSnapshot,
            goal = "Open the Finnish Wikipedia article on Tove Jansson and report the year of her Hans Christian Andersen Medal",
        )
        assertTrue(verdict is DomGuard.GuardVerdict.Refuse && verdict.reason.contains("does not derive from the goal"))
    }

    @Test
    fun `a goal-derived search query passes the derivation law`() {
        val target = SnapshotFixtures.element(id = 0, role = "button", label = "Search", tag = "button")
        val pageSnapshot = page(listOf(target))
        val proposal = DomProposalClient.Proposal(
            target, navigator.app.harness.webview.dom.DomAction.SEARCH, "Tove Jansson Finnish Wikipedia",
            "results show", "{}",
        )
        val verdict = DomGuard.guard(
            proposal, pageSnapshot, pageSnapshot,
            goal = "Open the Finnish Wikipedia article on Tove Jansson and report the year of her Hans Christian Andersen Medal",
        )
        assertTrue(verdict is DomGuard.GuardVerdict.Pass, "got $verdict")
    }
}
