package navigator.app.harness.webview

import navigator.app.harness.webview.dom.DomAction
import navigator.app.harness.webview.dom.DomSnapshot
import navigator.app.harness.webview.dom.PageMeta
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass
import navigator.core.ladder.Subtask
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V2 S2 FR-30 (AC-S2.4) — the prefix revalidation law on DOM evidence: URL conditions
 * are re-checked ALWAYS; DOM content conditions are scoped to their own page (a later
 * page's content cannot invalidate an earlier page's verified marker); the rollback
 * goes to the EARLIEST invalid prefix. And the milestone law's fast-lane shape
 * (AC-S2.6's corollary): the risk gate refuses every H-class pre-dispatch, so NO
 * irreversible milestone can ever be crossed by a rollback — none can exist.
 */
class PrefixAndRiskLawsTest {

    private fun snapshot(url: String, text: String = "page") = DomSnapshot(PageMeta(url, "T"), emptyList(), text)

    private fun ladder(vararg subtasks: Subtask) = Ladder("l", subtasks.toList(), LadderTaskClass.READ)

    // -- URL conditions are re-checked ALWAYS -------------------------------------------------

    @Test
    fun `a verified urlHost condition that no longer holds rolls back to it`() {
        val lane = DomLaneLoop(
            web = NoWebView(), planner = NoPlanner(), proposals = NoProposer(), judge = NoJudge(),
            executor = DomExecutor(NoWebView()), bus = navigator.app.harness.webview.events.PipelineEventBus(),
        )
        val l = ladder(
            Subtask("the shop is open", DoneCondition.UrlHost("shop.test")),
            Subtask("the deal is reported", DoneCondition.AnswerExtracted),
        )
        val invalid = lane.earliestInvalidPrefix(
            l, verified = mapOf(0 to "https://shop.test/"), currentUrl = "https://other.test/x",
        )
        assertEquals(0, invalid, "the prefix's URL condition broke under navigation — the earliest invalid is 0")
    }

    @Test
    fun `a verified urlContains condition that no longer holds rolls back to it`() {
        val lane = DomLaneLoop(
            web = NoWebView(), planner = NoPlanner(), proposals = NoProposer(), judge = NoJudge(),
            executor = DomExecutor(NoWebView()), bus = navigator.app.harness.webview.events.PipelineEventBus(),
        )
        val l = ladder(
            Subtask("the article is showing", DoneCondition.UrlContains("shop.test/wiki/Tove_Jansson")),
            Subtask("the deal is reported", DoneCondition.AnswerExtracted),
        )
        val invalid = lane.earliestInvalidPrefix(
            l, verified = mapOf(0 to "https://shop.test/wiki/Tove_Jansson"), currentUrl = "https://shop.test/other",
        )
        assertEquals(0, invalid)
    }

    @Test
    fun `an intact url prefix passes`() {
        val lane = DomLaneLoop(
            web = NoWebView(), planner = NoPlanner(), proposals = NoProposer(), judge = NoJudge(),
            executor = DomExecutor(NoWebView()), bus = navigator.app.harness.webview.events.PipelineEventBus(),
        )
        val l = ladder(
            Subtask("the shop is open", DoneCondition.UrlHost("shop.test")),
            Subtask("the article is showing", DoneCondition.UrlContains("shop.test/wiki/Tove_Jansson")),
            Subtask("the deal is reported", DoneCondition.AnswerExtracted),
        )
        assertNull(
            lane.earliestInvalidPrefix(
                l, verified = mapOf(0 to "https://shop.test/", 1 to "https://shop.test/wiki/Tove_Jansson"),
                currentUrl = "https://shop.test/wiki/Tove_Jansson",
            ),
        )
    }

    // -- DOM content conditions are page-scoped ------------------------------------------------

    @Test
    fun `a verified MARKER condition is NOT re-checked on a later page (content conditions are scoped)`() {
        val lane = DomLaneLoop(
            web = NoWebView(), planner = NoPlanner(), proposals = NoProposer(), judge = NoJudge(),
            executor = DomExecutor(NoWebView()), bus = navigator.app.harness.webview.events.PipelineEventBus(),
        )
        val l = ladder(
            Subtask("the catalog page shows the deal banner", DoneCondition.MarkerText("deal of the day")),
            Subtask("the deal is reported", DoneCondition.AnswerExtracted),
        )
        // The lane navigated away to the product page whose text lacks the banner — the
        // DOM condition does NOT invalidate (it was proven on its own page).
        assertNull(
            lane.earliestInvalidPrefix(
                l, verified = mapOf(0 to "https://shop.test/catalog"), currentUrl = "https://shop.test/product",
            ),
            "a marker condition verified on its own page is not re-judged elsewhere",
        )
    }

    @Test
    fun `the EARLIEST invalid prefix wins (the rollback never skips an earlier break)`() {
        val lane = DomLaneLoop(
            web = NoWebView(), planner = NoPlanner(), proposals = NoProposer(), judge = NoJudge(),
            executor = DomExecutor(NoWebView()), bus = navigator.app.harness.webview.events.PipelineEventBus(),
        )
        val l = ladder(
            Subtask("the shop is open", DoneCondition.UrlHost("shop.test")),
            Subtask("the category page is showing", DoneCondition.UrlContains("shop.test/category")),
            Subtask("the deal is reported", DoneCondition.AnswerExtracted),
        )
        val invalid = lane.earliestInvalidPrefix(
            l, verified = mapOf(0 to "https://shop.test/", 1 to "https://shop.test/category"),
            currentUrl = "https://other.test/",
        )
        assertEquals(0, invalid, "the rollback goes to the earliest invalid, never a later one")
    }

    // -- the milestone law's fast-lane shape (AC-S2.6's corollary) ------------------------------

    @Test
    fun `the rollback never crosses an irreversible milestone because the fast lane cannot create one`() {
        // The invariant: every irreversible-class action is refused PRE-dispatch by the
        // carried risk gate (fill-only law + H-classes), so a verified step can never
        // carry an irreversible effect — the rollback's milestone guard is vacuously
        // safe, and this test pins the mechanism that makes it so.
        val paymentButton = SnapshotFixtures.element(id = 0, role = "button", label = "Pay now", tag = "button", type = "submit")
        val page = DomSnapshot(PageMeta("https://shop.test/checkout", "Checkout"), listOf(paymentButton), "checkout")
        val proposal = DomProposalClient.Proposal(paymentButton, DomAction.CLICK, null, "the payment runs", "{}")
        val verdict = DomGuard.guard(proposal, page, page)
        assertTrue(
            verdict is DomGuard.GuardVerdict.Refuse && verdict.reason.contains("fill-only"),
            "a payment control never dispatches: $verdict",
        )
    }
}

/** Stand-ins for the loop's collaborators the prefix tests never touch. */
private class NoWebView : LaneWebView {
    override val view: android.view.View get() = error("unused")
    override suspend fun load(url: String): String = url
    override suspend fun currentUrl(): String = ""
    override suspend fun snapshot(): DomSnapshot = DomSnapshot(PageMeta("about:blank", ""), emptyList(), "")
    override suspend fun evaluate(js: String): String = "{}"
    override suspend fun dispatchTrustedTouch(cssX: Int, cssY: Int) {}
    override suspend fun goBack() {}
    override fun destroy() {}
}

private class NoPlanner : LanePlanner {
    override suspend fun restructure(goal: String, generation: Int, failure: navigator.core.ladder.ReplanContext?): LanePlanner.PlanResult =
        error("unused")
    override suspend fun extractAnswer(goal: String, finalState: String): LanePlanner.AnswerResult = error("unused")
}

private class NoProposer : DomProposer {
    override suspend fun propose(
        goal: String, snapshot: DomSnapshot, subtaskInstruction: String, doneRender: String, previousRefusal: String?,
    ): DomProposalClient.Proposal = error("unused")
}

private class NoJudge : DomJudger {
    override suspend fun judge(
        goal: String, snapshot: DomSnapshot, subtaskInstruction: String, doneRender: String,
        elementId: Int, elementLabel: String, action: String, value: String?, expectedEffect: String,
    ): DomJudge.Verdict = error("unused")
}


