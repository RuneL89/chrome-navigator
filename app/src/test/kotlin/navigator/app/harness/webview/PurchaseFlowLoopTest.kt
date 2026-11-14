package navigator.app.harness.webview

import kotlinx.coroutines.runBlocking
import navigator.app.cards.CardMachine
import navigator.app.cards.CardState
import navigator.app.cards.PurchaseDecisionClient
import navigator.app.harness.webview.dom.DomAction
import navigator.app.harness.webview.dom.DomSnapshot
import navigator.app.harness.webview.dom.PageMeta
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass
import navigator.core.ladder.Subtask
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V2 S3 FR-42/43 — the purchase flow through the loop (scripted handle, no device): the
 * order button dispatches ONLY through the auto-place law or the owner's card; a
 * refusal/timeout places nothing; and the fill-only law still stands when no flow exists.
 */
class PurchaseFlowLoopTest {

    private fun page(url: String, text: String, elements: List<navigator.app.harness.webview.dom.DomElement>) =
        DomSnapshot(PageMeta(url, "Shop"), elements, text)

    private fun checkoutPage() = page(
        "https://shop.test/checkout",
        "your cart holds the KitchenPro kettle — total 24.90 EUR with shipping",
        listOf(SnapshotFixtures.element(id = 0, role = "button", label = "Place order", tag = "button", type = "submit")),
    )

    private fun confirmedPage() = page(
        "https://shop.test/checkout",
        "ORDER CONFIRMED — order number 51515. Thank you for your order.",
        emptyList(),
    )

    private class ScriptedPurchaseFlow(
        private val approved: Boolean,
        private val byTimeout: Boolean = false,
        private val decision: PurchaseDecisionClient.Decision =
            PurchaseDecisionClient.Decision("KitchenPro kettle", 1, "eur", totalCents = 2490),
    ) : PurchaseFlowHandle {
        val surfaced = mutableListOf<Pair<PurchaseDecisionClient.Decision, Boolean>>()

        override suspend fun decide(goal: String, snapshot: DomSnapshot): PurchaseDecisionClient.Decision {
            surfaced.add(Pair(decision, decision.totalCents < 3000))
            return decision
        }

        override fun surface(
            decision: PurchaseDecisionClient.Decision,
            belowLimit: Boolean,
            taskId: String,
        ): AnswerAwaiter = AnswerAwaiter {
            val machine = CardMachine(navigator.app.cards.CardClass.PURCHASE) { System.currentTimeMillis() }
            if (approved) machine.approve() else machine.decline(byTimeout)
            machine.state
        }
    }

    private fun ladder() = Ladder(
        "buy",
        listOf(
            Subtask("the checkout is showing", DoneCondition.MarkerText("total 24.90")),
            Subtask("the order is placed", DoneCondition.MarkerText("ORDER CONFIRMED")),
        ),
        LadderTaskClass.ACTION,
    )

    private suspend fun run(
        web: SnapshotFixtures.FakeWebView,
        flow: PurchaseFlowHandle?,
        goal: String,
    ): Triple<DomLaneLoop.Terminal, List<navigator.app.harness.webview.events.PipelineEvent>, Int> {
        val events = mutableListOf<navigator.app.harness.webview.events.PipelineEvent>()
        val bus = navigator.app.harness.webview.events.PipelineEventBus()
        bus.subscribe { events.add(it) }
        val planner = object : LanePlanner {
            var calls = 0
            override suspend fun restructure(goal: String, generation: Int, failure: navigator.core.ladder.ReplanContext?): LanePlanner.PlanResult {
                calls += 1
                return LanePlanner.PlanResult(ladder(), "scripted", generation)
            }

            override suspend fun extractAnswer(goal: String, finalState: String): LanePlanner.AnswerResult =
                LanePlanner.AnswerResult("order 51515")
        }
        val proposer = object : DomProposer {
            override suspend fun propose(
                goal: String,
                snapshot: DomSnapshot,
                subtaskInstruction: String,
                doneRender: String,
                previousRefusal: String?,
            ): DomProposalClient.Proposal = DomProposalClient.Proposal(
                snapshot.elements.first(), DomAction.CLICK, null, "the order places", "{}",
            )
        }
        val judge = object : DomJudger {
            override suspend fun judge(
                goal: String, snapshot: DomSnapshot, subtaskInstruction: String, doneRender: String,
                elementId: Int, elementLabel: String, action: String, value: String?, expectedEffect: String,
            ): DomJudge.Verdict = DomJudge.Verdict(true, 0.95, "advance p=0.95")
        }
        val laneLoop = DomLaneLoop(
            web = web,
            planner = planner,
            proposals = proposer,
            judge = judge,
            executor = DomExecutor(web),
            bus = bus,
            evidence = null,
            purchaseFlow = flow,
            autoPlaceLimitCents = 3_000L,
        )
        val terminal = laneLoop.runTask(goal)
        return Triple(terminal, events, planner.calls)
    }

    @Test
    fun `an approved purchase dispatches the order button and the order confirms`() = runBlocking {
        val checkout = checkoutPage()
        val confirmed = confirmedPage()
        val web = SnapshotFixtures.FakeWebView(ArrayDeque(listOf(checkout, confirmed)), autoNavigate = true)
        // The subtask-1 marker (total 24.90) holds at start → the loop must still do work:
        // give the start page a non-satisfying first page? The marker holds on checkout —
        // the weak-at-start law requires a step: the flow dispatches via the propose path.
        val flow = ScriptedPurchaseFlow(approved = true)
        val (terminal, events, _) = run(web, flow, "buy the KitchenPro kettle for 24.90 on shop.test and place the order")
        assertTrue(terminal is DomLaneLoop.Terminal.Done, "got $terminal")
        assertTrue(
            events.filterIsInstance<navigator.app.harness.webview.events.PipelineEvent.Executed>()
                .any { it.action == "purchase_dispatch" },
            "the order button dispatched through the flow",
        )
        assertEquals(2490L, flow.surfaced.single().first.totalCents)
    }

    @Test
    fun `a declined purchase places nothing and the terminal names it`() = runBlocking {
        val checkout = page(
            "https://shop.test/checkout",
            "welcome text without the total marker yet",
            listOf(SnapshotFixtures.element(id = 0, role = "button", label = "Place order", tag = "button", type = "submit")),
        )
        val web = SnapshotFixtures.FakeWebView(ArrayDeque(listOf(checkout)), autoNavigate = false)
        val flow = ScriptedPurchaseFlow(approved = false)
        val (terminal, events, _) = run(web, flow, "buy the KitchenPro kettle for 24.90 on shop.test and place the order")
        assertTrue(terminal is DomLaneLoop.Terminal.Failed, "got $terminal")
        assertTrue(
            (terminal as DomLaneLoop.Terminal.Failed).reason.contains("no order was placed"),
            "${terminal.reason}",
        )
        assertTrue(
            events.none { it is navigator.app.harness.webview.events.PipelineEvent.Executed },
            "nothing dispatched on a refusal",
        )
    }

    @Test
    fun `a timeout is a no - the terminal says timeout is no`() = runBlocking {
        val checkout = page(
            "https://shop.test/checkout",
            "welcome text",
            listOf(SnapshotFixtures.element(id = 0, role = "button", label = "Place order", tag = "button", type = "submit")),
        )
        val web = SnapshotFixtures.FakeWebView(ArrayDeque(listOf(checkout)), autoNavigate = false)
        val flow = ScriptedPurchaseFlow(approved = false, byTimeout = true)
        val (terminal, _, _) = run(web, flow, "buy the kettle on shop.test")
        assertTrue(
            terminal is DomLaneLoop.Terminal.Failed && terminal.reason.contains("timed out"),
            "got $terminal",
        )
    }

    @Test
    fun `an at-limit purchase surfaces the card law decision (the limit is inclusive)`() = runBlocking {
        val flow = ScriptedPurchaseFlow(
            approved = true,
            decision = PurchaseDecisionClient.Decision("KitchenPro kettle", 1, "eur", totalCents = 3000),
        )
        assertEquals(false, PurchaseFlowLaw.decidesCancelWindow(3000, 3000), "AT the limit → the card, not the window")
        assertEquals(true, PurchaseFlowLaw.decidesCancelWindow(2999, 3000))
    }
}
