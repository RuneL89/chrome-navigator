package navigator.app.harness.webview

import kotlinx.coroutines.runBlocking
import navigator.app.harness.webview.dom.DomAction
import navigator.app.harness.webview.dom.DomElement
import navigator.app.harness.webview.dom.DomSnapshot
import navigator.app.harness.webview.dom.PageMeta
import navigator.app.harness.webview.events.PipelineEvent
import navigator.app.harness.webview.events.PipelineEventBus
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass
import navigator.core.ladder.ReplanContext
import navigator.core.ladder.Subtask
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V2 S1 (AC-S1.8, AC-S1.10's loop half) — the decision loop over scripted models and a
 * scripted WebView: the honest walk (propose -> judge -> guard -> execute -> verify), the
 * loud refusals, the no-progress re-plan, and the honest terminals. Scripted transports
 * only - no live calls (the secrets law).
 */
class DomLaneLoopTest {

    private companion object {
        fun page(url: String, text: String, elements: List<DomElement>, title: String = "Shop") =
            DomSnapshot(PageMeta(url, title), elements, text)
    }



    private class ScriptedPlanner(
        var ladders: ArrayDeque<Ladder>,
        var answer: String = "1966",
    ) : LanePlanner {
        var restructureCalls = 0
        var extractCalls = 0

        override suspend fun restructure(goal: String, generation: Int, failure: ReplanContext?): LanePlanner.PlanResult {
            restructureCalls += 1
            val ladder = ladders.removeFirstOrNull() ?: error("no scripted ladder for generation $generation")
            return LanePlanner.PlanResult(ladder, "scripted-v1", generation)
        }

        override suspend fun extractAnswer(goal: String, finalState: String): LanePlanner.AnswerResult {
            extractCalls += 1
            return LanePlanner.AnswerResult(answer)
        }
    }

    private class ScriptedProposer(
        private val bySnapshot: (DomSnapshot) -> DomProposalClient.Proposal,
    ) : DomProposer {
        val proposals = mutableListOf<DomProposalClient.Proposal>()
        override suspend fun propose(
            goal: String,
            snapshot: DomSnapshot,
            subtaskInstruction: String,
            doneRender: String,
            previousRefusal: String?,
        ): DomProposalClient.Proposal {
            val p = bySnapshot(snapshot)
            proposals.add(p)
            return p
        }
    }

    private class ScriptedJudge(private val accepted: Boolean = true) : DomJudger {
        val calls = mutableListOf<String>()

        override suspend fun judge(
            goal: String,
            snapshot: DomSnapshot,
            subtaskInstruction: String,
            doneRender: String,
            elementId: Int,
            elementLabel: String,
            action: String,
            value: String?,
            expectedEffect: String,
        ): DomJudge.Verdict {
            calls.add("$action[$elementId]")
            return DomJudge.Verdict(accepted, if (accepted) 0.95 else 0.2, if (accepted) "advance p=0.95" else "refused: none")
        }
    }

    /** Runs the loop and returns the terminal plus the event stream it emitted. */
    private suspend fun run(
        web: SnapshotFixtures.FakeWebView,
        planner: ScriptedPlanner,
        proposer: DomProposer,
        judge: DomJudger,
        goal: String,
        config: DomLaneLoop.Config = DomLaneLoop.Config(),
    ): Pair<DomLaneLoop.Terminal, List<PipelineEvent>> {
        val events = mutableListOf<PipelineEvent>()
        val bus = PipelineEventBus()
        bus.subscribe { events.add(it) }
        val laneLoop = DomLaneLoop(
            web = web,
            planner = planner,
            proposals = proposer,
            judge = judge,
            executor = DomExecutor(web),
            bus = bus,
            evidence = null,
            config = config,
        )
        return Pair(laneLoop.runTask(goal), events)
    }

    // -- the happy path -------------------------------------------------------------------------

    @Test
    fun `a read task walks propose - judge - execute - verify and produces the answer`() = runBlocking {
        val start = page(
            "https://shop.test/",
            "welcome to the shop",
            listOf(SnapshotFixtures.element(id = 0, role = "link", label = "Catalog", tag = "a")),
        )
        val catalog = page("https://shop.test/catalog", "welcome to the catalog page — today's deal is the kettle", emptyList())
        val web = SnapshotFixtures.FakeWebView(ArrayDeque(listOf(start, catalog)), autoNavigate = true)
        val planner = ScriptedPlanner(
            ladders = ArrayDeque(
                listOf(
                    Ladder(
                        "read the deal",
                        listOf(
                            Subtask("the catalog page is showing", DoneCondition.MarkerText("catalog")),
                            Subtask("report the deal of the day", DoneCondition.AnswerExtracted),
                        ),
                        LadderTaskClass.READ,
                    ),
                ),
            ),
            answer = "the kettle for 19 euro",
        )
        val proposer = ScriptedProposer { snapshot ->
            val el = snapshot.elements.first()
            DomProposalClient.Proposal(el, DomAction.CLICK, null, "the catalog page shows", "{}")
        }
        val judge = ScriptedJudge(accepted = true)

        val (terminal, events) = run(web, planner, proposer, judge, "open shop.test and report the deal of the day")

        assertTrue(terminal is DomLaneLoop.Terminal.Done, "got $terminal")
        assertEquals("the kettle for 19 euro", (terminal as DomLaneLoop.Terminal.Done).answer)
        assertEquals(
            listOf("Proposed", "Judged", "Executed", "Verified"),
            events.map { it::class.simpleName },
            "the event stream rode the REAL walk (AC-S1.8)",
        )
        val verified = events.filterIsInstance<PipelineEvent.Verified>().single()
        assertTrue(verified.confirmed, "the click verified: the URL navigated")
        assertEquals(1, planner.extractCalls, "the answer extraction ran exactly once")
    }

    @Test
    fun `the answer subtask's extraction IS the step - the condition then verifies`() = runBlocking {
        val deals = page(
            "https://shop.test/deals",
            "deal of the day: the kettle for 19 euro",
            listOf(SnapshotFixtures.element(id = 0, role = "button", label = "Open deals", tag = "button")),
        )
        val web = SnapshotFixtures.FakeWebView(ArrayDeque(listOf(deals)))
        val planner = ScriptedPlanner(
            ladders = ArrayDeque(
                listOf(
                    Ladder(
                        "read the deal",
                        listOf(Subtask("report the deal of the day", DoneCondition.AnswerExtracted)),
                        LadderTaskClass.READ,
                    ),
                ),
            ),
            answer = "the kettle for 19 euro",
        )
        val proposer = ScriptedProposer { error("no proposal should be needed — the extraction IS the step") }
        val judge = ScriptedJudge()

        val (terminal, events) = run(web, planner, proposer, judge, "report the deal of the day")

        assertTrue(terminal is DomLaneLoop.Terminal.Done && terminal.answer == "the kettle for 19 euro", "got $terminal")
        assertEquals(1, planner.extractCalls)
        assertTrue(events.isEmpty(), "the extraction step emits no propose/judge/execute events")
    }

    // -- the loud refusals ------------------------------------------------------------------------

    @Test
    fun `a judge refusal dispatches nothing and the no-progress budget re-plans then fails honestly`() = runBlocking {
        val page = page(
            "https://shop.test/catalog",
            "welcome to the catalog page",
            listOf(SnapshotFixtures.element(id = 0, role = "button", label = "Open deals", tag = "button")),
        )
        val web = SnapshotFixtures.FakeWebView(ArrayDeque(listOf(page)))
        val oneStep = {
            Ladder(
                "one step",
                listOf(Subtask("the deal is reported", DoneCondition.MarkerText("deal of the day"))),
                LadderTaskClass.READ,
            )
        }
        val planner = ScriptedPlanner(ladders = ArrayDeque(listOf(oneStep(), oneStep())))
        val proposer = ScriptedProposer { snapshot ->
            DomProposalClient.Proposal(snapshot.elements.first(), DomAction.CLICK, null, "effect", "{}")
        }
        val judge = ScriptedJudge(accepted = false)

        val (terminal, events) = run(
            web, planner, proposer, judge, "report the deal",
            config = DomLaneLoop.Config(maxReplans = 1),
        )

        assertTrue(terminal is DomLaneLoop.Terminal.Failed, "got $terminal")
        assertTrue(
            (terminal as DomLaneLoop.Terminal.Failed).reason.contains("no verified progress"),
            "the honest refusal names the budget: ${terminal.reason}",
        )
        assertTrue(web.clickLog.isEmpty(), "a refused proposal dispatches NOTHING")
        assertEquals(0, events.filterIsInstance<PipelineEvent.Executed>().size)
        val judged = events.filterIsInstance<PipelineEvent.Judged>()
        assertTrue(judged.isNotEmpty() && judged.all { !it.accepted }, "every judgment was a refusal")
        assertEquals(2, planner.restructureCalls, "the initial plan + ONE re-plan, then the budget stops the loop")
    }

    @Test
    fun `a submit-shaped proposal is refused by the guard with zero dispatch (the fill-only law)`() = runBlocking {
        val submit = SnapshotFixtures.element(id = 0, role = "button", label = "Place order", tag = "button", type = "submit")
        val page = page("https://shop.test/checkout", "checkout", listOf(submit))
        val web = SnapshotFixtures.FakeWebView(ArrayDeque(listOf(page)))
        val oneStep = {
            Ladder(
                "one step",
                listOf(Subtask("the order is placed", DoneCondition.MarkerText("order confirmed"))),
                LadderTaskClass.ACTION,
            )
        }
        val planner = ScriptedPlanner(ladders = ArrayDeque(listOf(oneStep(), oneStep())))
        val proposer = ScriptedProposer { snapshot ->
            DomProposalClient.Proposal(snapshot.elements.first(), DomAction.CLICK, null, "the order places", "{}")
        }
        val judge = ScriptedJudge(accepted = true)

        val (terminal, events) = run(
            web, planner, proposer, judge, "place the order",
            config = DomLaneLoop.Config(maxReplans = 1),
        )

        assertTrue(terminal is DomLaneLoop.Terminal.Failed, "got $terminal")
        assertTrue(web.clickLog.isEmpty(), "zero dispatch follows a fill-only refusal")
        val refused = events.filterIsInstance<PipelineEvent.Refused>()
        assertTrue(refused.isNotEmpty())
        assertTrue(refused.first().reason.contains("fill-only"))
    }

    // -- the honest ceilings ------------------------------------------------------------------------

    @Test
    fun `a captcha page is a switch offer - no retry, no dispatch`() = runBlocking {
        val challenge = page(
            "https://shop.test/x",
            "please verify you are human to continue",
            emptyList(),
            "Checking",
        )
        val web = SnapshotFixtures.FakeWebView(ArrayDeque(listOf(challenge)))
        val planner = ScriptedPlanner(
            ladders = ArrayDeque(
                listOf(
                    Ladder("s", listOf(Subtask("the page shows", DoneCondition.MarkerText("welcome"))), LadderTaskClass.READ),
                ),
            ),
        )
        val proposer = ScriptedProposer { error("nothing proposes on a challenge") }
        val judge = ScriptedJudge()

        val (terminal, _) = run(web, planner, proposer, judge, "read the page")

        assertTrue(terminal is DomLaneLoop.Terminal.SwitchOffer, "got $terminal")
        assertTrue((terminal as DomLaneLoop.Terminal.SwitchOffer).reason.contains("human lane"))
        assertEquals(1, planner.restructureCalls, "the plan installed, the page then stopped the lane")
    }

    @Test
    fun `a consent wall's essentials-only choice is clicked as lane maintenance (no step spent)`() = runBlocking {
        val wall = page(
            "https://www.google.test/search?q=kettle",
            "Before you continue — cookies help us deliver our services",
            listOf(
                SnapshotFixtures.element(id = 0, role = "button", label = "Accept all", tag = "button", y = 10),
                SnapshotFixtures.element(id = 1, role = "button", label = "Reject all", tag = "button", y = 60),
            ),
            "Before you continue",
        )
        val catalog = page("https://www.google.test/catalog", "the catalog page content", emptyList())
        val web = SnapshotFixtures.FakeWebView(ArrayDeque(listOf(wall, catalog)), autoNavigate = true)
        val planner = ScriptedPlanner(
            ladders = ArrayDeque(
                listOf(
                    Ladder(
                        "read",
                        listOf(Subtask("the catalog page is showing", DoneCondition.MarkerText("catalog"))),
                        LadderTaskClass.READ,
                    ),
                ),
            ),
        )
        val proposer = ScriptedProposer { error("no proposal needed — the maintenance click crosses the wall") }
        val judge = ScriptedJudge()

        val (terminal, events) = run(web, planner, proposer, judge, "read the catalog")

        assertTrue(terminal is DomLaneLoop.Terminal.Done, "got $terminal — the wall was crossed as maintenance")
        assertTrue(
            events.none { it is PipelineEvent.Proposed && it.elementLabel == "Reject all" },
            "the consent move is maintenance, not a proposed step",
        )
        assertEquals(1, web.clickLog.size, "exactly the consent click ran")
    }

    @Test
    fun `a dispatched click with no verified effect feeds the no-progress budget and fails honestly`() = runBlocking {
        val page = page(
            "https://shop.test/catalog",
            "welcome to the catalog page",
            listOf(SnapshotFixtures.element(id = 0, role = "button", label = "Open deals", tag = "button")),
        )
        // The click NEVER navigates — the same page every snapshot.
        val web = SnapshotFixtures.FakeWebView(ArrayDeque(listOf(page)), autoNavigate = false)
        val oneStep = {
            Ladder(
                "one step",
                listOf(Subtask("the deal is reported", DoneCondition.MarkerText("deal of the day"))),
                LadderTaskClass.READ,
            )
        }
        val planner = ScriptedPlanner(ladders = ArrayDeque(listOf(oneStep(), oneStep())))
        val proposer = ScriptedProposer { snapshot ->
            DomProposalClient.Proposal(snapshot.elements.first(), DomAction.CLICK, null, "the deals page shows", "{}")
        }
        val judge = ScriptedJudge(accepted = true)

        val (terminal, events) = run(
            web, planner, proposer, judge, "read the deal",
            config = DomLaneLoop.Config(maxReplans = 1),
        )

        assertTrue(terminal is DomLaneLoop.Terminal.Failed, "got $terminal")
        val verified = events.filterIsInstance<PipelineEvent.Verified>()
        assertTrue(verified.isNotEmpty() && verified.any { !it.confirmed }, "the verified-refused face rode the stream")
        assertTrue(web.clickLog.isNotEmpty(), "the dispatch ran (the postcondition refused to call it progress)")
    }

    // -- the navigation laws ----------------------------------------------------------------------

    @Test
    fun `the code-owned site entry loads the destination the condition names (the writer never invents urls)`() = runBlocking {
        val blank = page("about:blank", "", emptyList(), "")
        val article = page("https://fi.wikipedia.org/wiki/Tove_Jansson", "the article body text", emptyList(), "Tove Jansson")
        val web = SnapshotFixtures.FakeWebView(ArrayDeque(listOf(blank, article)), autoNavigate = true)
        val planner = ScriptedPlanner(
            ladders = ArrayDeque(
                listOf(
                    Ladder(
                        "read",
                        listOf(
                            Subtask(
                                "the Finnish Wikipedia article is showing",
                                DoneCondition.UrlContains("fi.wikipedia.org/wiki/Tove_Jansson"),
                            ),
                        ),
                        LadderTaskClass.READ,
                    ),
                ),
            ),
        )
        val proposer = ScriptedProposer { error("no proposal needed — the entry load reaches the page") }
        val judge = ScriptedJudge()

        val (terminal, events) = run(web, planner, proposer, judge, "open the Finnish Wikipedia article on Tove Jansson")

        // TEMP DEBUG: surface the terminal with the load log
        assertTrue(
            terminal is DomLaneLoop.Terminal.Done,
            "got $terminal (loadLog=${web.loadLog} laddersLeft=${planner.ladders.size} restructures=${planner.restructureCalls})",
        )
        assertEquals(listOf("https://fi.wikipedia.org/wiki/Tove_Jansson"), web.loadLog, "the entry built the URL from the condition")
        assertTrue(events.isEmpty(), "no proposal events — the entry is lane maintenance")
    }

    @Test
    fun `the search action runs the writer's QUERY on the engine - the URL is code-built`() = runBlocking {
        val blank = page("about:blank", "", emptyList(), "")
        val results = page(
            "https://www.google.com/search?q=tove+jansson+medal",
            "search results for the medal",
            listOf(SnapshotFixtures.element(id = 0, role = "link", label = "A result", tag = "a")),
        )
        val web = SnapshotFixtures.FakeWebView(ArrayDeque(listOf(blank, results)), autoNavigate = true)
        val planner = ScriptedPlanner(
            ladders = ArrayDeque(
                listOf(
                    Ladder(
                        "search",
                        listOf(
                            Subtask(
                                "search google for the medal year",
                                DoneCondition.MarkerText("search results for the medal"),
                            ),
                        ),
                        LadderTaskClass.READ,
                    ),
                ),
            ),
        )
        val proposer = ScriptedProposer { snapshot ->
            DomProposalClient.Proposal(
                snapshot.elements.firstOrNull() ?: SnapshotFixtures.element(id = 0),
                DomAction.SEARCH,
                "tove jansson medal",
                "the results page shows",
                "{}",
            )
        }
        val judge = ScriptedJudge(accepted = true)

        val (terminal, events) = run(web, planner, proposer, judge, "Find the year Tove Jansson received the Hans Christian Andersen Medal")

        assertTrue(
            terminal is DomLaneLoop.Terminal.Done,
            "got $terminal (loadLog=${web.loadLog} events=${events.map { it::class.simpleName }}})",
        )
        assertTrue(
            web.loadLog.single().startsWith("https://www.google.com/search?q=") &&
                web.loadLog.single().contains("tove+jansson+medal"),
            "the engine URL was CODE-built from the query: ${web.loadLog}",
        )
        val verified = events.filterIsInstance<PipelineEvent.Verified>().single()
        assertTrue(verified.confirmed, "the search verified on the results page")
    }
}
