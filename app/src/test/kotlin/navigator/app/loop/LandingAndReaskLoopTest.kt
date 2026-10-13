package navigator.app.loop

import kotlinx.coroutines.runBlocking
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.Subtask
import navigator.core.log.EngineIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The landing-host reconciliation and the bounded stage-1 re-ask (Phase 10C, vision 04 §1/§2
 * v1.7.0 — owner-ratified after the 2026-10-11 battery): the BBC redirect deadlock
 * (bbc.co.uk → bbc.com) amends instead of spinning, and an unusable slate re-asks stage-1
 * ONCE instead of failing the subtask.
 */
class LandingAndReaskLoopTest {

    /**
     * The scripted engine: intent answers in order (default verify-done), noul in order
     * (default 0.9). A recovery question (the stuck L2 set) is answered give-up by default
     * — the honest end of a run whose engine insists on done claims the code refuses
     * (before the 2026-10-13 meter fix these fixtures spun on withheld claims instead and
     * never reached the recovery machinery).
     */
    private class ScriptedEngine2(
        private val intentPicks: MutableList<String>,
        private val noulValues: MutableList<Double> = mutableListOf(),
        private val recoveryPicks: MutableList<String> = mutableListOf(),
    ) : DecisionEngine {
        val asked = CopyOnWriteList()
        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
            asked.add(question.name)
            val pick = when (question.name) {
                "triage" -> "lookup"
                "recovery" -> if (recoveryPicks.isEmpty()) "give-up" else recoveryPicks.removeAt(0)
                else -> if (intentPicks.isEmpty()) "verify-done" else intentPicks.removeAt(0)
            }
            return ChoiceResult(pick, mapOf(pick to 0.95), 0.95)
        }

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult =
            NoulResult(if (noulValues.isEmpty()) 0.9 else noulValues.removeAt(0))

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            ScoreResult(question.levels.size.toDouble(), question.levels.indices.associateWith { 1.0 })
    }

    /** A tiny thread-safe list (the JVM tests run single-threaded; the type keeps parity with the codebase). */
    private class CopyOnWriteList {
        private val items = mutableListOf<String>()
        fun add(v: String) = synchronized(items) { items.add(v) }
        fun snapshot(): List<String> = synchronized(items) { items.toList() }
    }

    private class OneLadderSource(private val ladder: Ladder) : LadderSource {
        override suspend fun restructure(
            goal: String,
            generation: Int,
            failure: navigator.core.ladder.ReplanContext?,
        ): RestructuredLadder? = RestructuredLadder(ladder, "test/gen$generation", 0)

        override suspend fun repairCondition(
            subtaskInstruction: String,
            current: DoneCondition,
            pageUrl: String,
            pageTitle: String,
        ): RepairedCondition? = null
    }

    private fun page(url: String, vararg texts: String): StepObservation = obs(
        url,
        *texts.map { N(it, clickable = false) }.toTypedArray(),
        N("A second body paragraph carrying more prose.", clickable = false),
        N("Open the fixture link", clickable = true),
        N("One more readable line so the tree clears the usable floor.", clickable = false),
    )

    private fun run(
        ladder: Ladder,
        observations: List<StepObservation>,
        goal: String,
        intentPicks: List<String>,
        launcher: BrowserLauncher? = null,
    ): Triple<RunResult, CollectingLogSink, ScriptedEngine2> {
        val sink = CollectingLogSink()
        val engine = ScriptedEngine2(intentPicks.toMutableList())
        val loop = NavigationLoop(
            engine = engine,
            observer = FakeObserver(observations),
            executor = FakeExecutor(Journal()),
            gatePresenter = AutoDenyGatePresenter,
            logSink = sink,
            config = NavigationLoop.Config(settleMs = 0),
            ladderSource = OneLadderSource(ladder),
            escalationCardPresenter = StopEscalationPresenter,
            browserLauncher = launcher,
        )
        val result = runBlocking {
            loop.run(goal, "landing", EngineIdentity("stub", "scripted-harness-v1"))
        }
        return Triple(result, sink, engine)
    }

    @Test
    fun `a urlHost condition reconciles to the landing host after a cross-family redirect`() {
        // The battery's BBC deadlock: the writer says bbc.co.uk, the site serves bbc.com.
        val ladder = Ladder(
            "Read the BBC news.",
            listOf(Subtask("the bbc news site is loaded", DoneCondition.UrlHost("bbc.co.uk"))),
        )
        val launched = mutableListOf<String>()
        val (result, _, _) = run(
            ladder = ladder,
            observations = listOf(page("https://www.bbc.com/news", "BBC News", "Headlines")),
            goal = "open https://bbc.co.uk and read the news",
            intentPicks = emptyList(),
            launcher = BrowserLauncher { launched.add(it) },
        )

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, result.stepLog.toString())
        assertEquals(
            listOf("https://bbc.co.uk"),
            launched,
            "the site entry resolved the explicit URL from the goal",
        )
        assertTrue(
            result.stepLog.any { it.contains("reconciles to the landing host") },
            "the amendment is logged: " + result.stepLog,
        )
        val exit = assertNotNull(result.exit)
        assertEquals("STRONG", exit.guardLevel, "the amended urlHost is URL identity")
        // the report's subtask renders the AMENDED condition
        val report = assertNotNull(result.ladder)
        assertTrue(
            report.subtasks.single().done.contains("bbc.com"),
            "the ladder's condition now names the landing host: " + report.subtasks.single().done,
        )
    }

    @Test
    fun `a different site family leaves the condition standing`() {
        val ladder = Ladder(
            "Read the shop.",
            listOf(Subtask("the shop is loaded", DoneCondition.UrlHost("shop.example"))),
        )
        val (result, _, _) = run(
            ladder = ladder,
            observations = listOf(page("https://other.test/x", "Other site", "Prose")),
            goal = "open shop.example",
            intentPicks = emptyList(),
            launcher = BrowserLauncher { },
        )

        assertTrue(
            result.stepLog.any { it.contains("not the same site family") },
            "the divergence is named and the condition stands: " + result.stepLog,
        )
        val report = assertNotNull(result.ladder)
        assertTrue(
            report.subtasks.single().done.contains("shop.example"),
            "no amendment across families",
        )
    }

    @Test
    fun `an unusable slate triggers ONE bounded stage-1 re-ask and its answer is pinned`() {
        // fill-field is chosen but the page has NO form fields; after the grace the loop
        // re-asks stage-1 ONCE with the fact; the re-ask's follow-link executes next step.
        val ladder = Ladder(
            "Read the article.",
            listOf(Subtask("the article is displayed", DoneCondition.UrlContains("m0.test/article"))),
        )
        val (result, sink, engine) = run(
            ladder = ladder,
            observations = listOf(page("http://m0.test/home", "Home page", "Prose")),
            goal = "read the article",
            // 3 grace asks + the 4th (the re-ask) -> follow-link
            intentPicks = listOf("fill-field", "fill-field", "fill-field", "fill-field", "follow-link"),
        )

        assertTrue(
            result.stepLog.any { it.contains("stage-1 re-ask") },
            "the bounded re-ask fired: " + result.stepLog,
        )
        assertTrue(
            result.stepLog.any { it.contains("the stage-1 re-ask's answer, pinned for this step") },
            "the re-ask's answer was pinned: " + result.stepLog,
        )
        assertTrue(
            engine.asked.snapshot().count { it == "intent" } >= 5,
            "the re-ask is an extra stage-1 ask beyond the page's DECIDEs: " + engine.asked.snapshot(),
        )
    }

    @Test
    fun `the re-ask repeating the dead-end intent falls through to the ordinary fallback`() {
        val ladder = Ladder(
            "Read the article.",
            listOf(Subtask("the article is displayed", DoneCondition.UrlContains("m0.test/article"))),
        )
        val (result, _, _) = run(
            ladder = ladder,
            observations = listOf(page("http://m0.test/home", "Home page", "Prose")),
            goal = "read the article",
            // the re-ask answers fill-field AGAIN -> the fallback applies
            intentPicks = listOf("fill-field", "fill-field", "fill-field", "fill-field", "fill-field"),
        )

        assertTrue(
            result.stepLog.any { it.contains("the re-ask repeated 'fill-field'") },
            "the repeat is named: " + result.stepLog,
        )
        assertTrue(
            result.stepLog.any { it.contains("FAILED") },
            "the ordinary fallback followed (the subtask failure line): " + result.stepLog,
        )
    }

    /** The fake extractor: counts calls, returns a canned answer. */
    private class FakeExtractor : AnswerExtractor {
        var calls = 0
            private set

        override suspend fun extract(goal: String, finalState: String): ExtractedAnswer {
            calls += 1
            return ExtractedAnswer("The headline is X.", "extract-v1", 2_000L, 100, 50)
        }
    }

    @Test
    fun `a report subtask completes only after the extraction runs - the code-owned record is the evidence`() {
        // The test-4 shape, now with a completion shape that EXISTS: "report the headline"
        // is `answerExtracted`, satisfied by the loop's extraction record (guard STRONG).
        val ladder = Ladder(
            "Report the headline.",
            listOf(Subtask("report the top news headline", DoneCondition.AnswerExtracted)),
        )
        val extractor = FakeExtractor()
        val sink = CollectingLogSink()
        val engine = ScriptedEngine2(mutableListOf())
        val loop = NavigationLoop(
            engine = engine,
            observer = FakeObserver(listOf(page("http://m0.test/news", "Headline here", "Prose"))),
            executor = FakeExecutor(Journal()),
            gatePresenter = AutoDenyGatePresenter,
            logSink = sink,
            config = NavigationLoop.Config(settleMs = 0),
            ladderSource = OneLadderSource(ladder),
            escalationCardPresenter = StopEscalationPresenter,
            answerExtractor = extractor,
        )
        val result = runBlocking {
            loop.run("report the top headline", "report", EngineIdentity("stub", "scripted-harness-v1"))
        }

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, result.stepLog.toString())
        assertEquals(
            1,
            extractor.calls,
            "ONE extraction per run (vision 04 §6): the report subtask's answer stands — the " +
                "goal-exit call is skipped, never re-phrasing (and never overwriting) it",
        )
        assertTrue(
            result.stepLog.any { it.contains("report: the answer for subtask 1/1 was produced") },
            "the production is logged: " + result.stepLog,
        )
        assertTrue(
            result.stepLog.any { it.contains("extract: skipped - the report subtask already produced the answer") },
            "the skip is loud: " + result.stepLog,
        )
        assertEquals("The headline is X.", assertNotNull(result.answer).text)
        assertEquals(
            "STRONG",
            assertNotNull(result.exit).guardLevel,
            "the answerExtracted condition's evidence is code-owned (STRONG), never page content",
        )
        assertEquals(
            2_000L,
            result.writerSpendMicroUsd,
            "the single extraction meters into the writer budget (1 x 2_000 micro-USD)",
        )
    }

    /** Captures the final-state strings the loop hands the extractor (the input pin). */
    private class StateCapturingExtractor : AnswerExtractor {
        val states = mutableListOf<String>()

        override suspend fun extract(goal: String, finalState: String): ExtractedAnswer {
            states.add(finalState)
            return ExtractedAnswer("The headline is X.", "extract-v1", 2_000L, 100, 50)
        }
    }

    @Test
    fun `a zero-action achieved run answers over the step's own observation - never a blank state`() {
        // The 2026-10-13 emulator battery's Test-4 shape: a URL identity already true on
        // the observed page verifies via the code-triggered check with ZERO executed
        // actions, so no post-step observation ever exists. The terminal extraction must
        // read the page the step saw — before the fix it sent "title: \nurl: \n" and the
        // writer answered "the browser state I received is blank", overwriting the good
        // answer produced at the report subtask.
        val ladder = Ladder(
            "Read the BBC headline.",
            listOf(Subtask("the bbc homepage is open", DoneCondition.UrlHost("bbc.com"))),
        )
        val extractor = StateCapturingExtractor()
        val sink = CollectingLogSink()
        val bbcPage = obs(
            "https://www.bbc.com/news",
            N("BBC News", clickable = false),
            N("Headlines", clickable = false),
            N("A second body paragraph carrying more prose.", clickable = false),
            N("Open the fixture link", clickable = true),
            N("One more readable line so the tree clears the usable floor.", clickable = false),
            title = "BBC News - Home",
        )
        val loop = NavigationLoop(
            engine = ScriptedEngine2(mutableListOf()),
            observer = FakeObserver(listOf(bbcPage)),
            executor = FakeExecutor(Journal()),
            gatePresenter = AutoDenyGatePresenter,
            logSink = sink,
            config = NavigationLoop.Config(settleMs = 0),
            ladderSource = OneLadderSource(ladder),
            escalationCardPresenter = StopEscalationPresenter,
            answerExtractor = extractor,
        )
        val result = runBlocking {
            loop.run(
                "open bbc.com and tell me the top news headline",
                "zero-action",
                EngineIdentity("stub", "scripted-harness-v1"),
            )
        }

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, result.stepLog.toString())
        assertEquals(1, extractor.states.size, "one extraction: ${result.stepLog}")
        val state = extractor.states.single()
        assertTrue(
            state.contains("url: https://www.bbc.com/news"),
            "the step's OWN observation is the final state (lastObserved), not a blank one: $state",
        )
        assertTrue(state.contains("title: BBC News - Home"), "the title rides too: $state")
        assertEquals("The headline is X.", assertNotNull(result.answer).text)
    }


    @Test
    fun `the landing host rides the next DECISION row (doc 05)`() {
        // The amendment step itself is consumed by the code-triggered check (its ExitCheckRow,
        // no decision row) — so the field is observable on the NEXT decision row, which is
        // exactly the law's "rides the step's row" shape.
        val ladder = Ladder(
            "Read the BBC news.",
            listOf(
                Subtask("the bbc site is loaded", DoneCondition.UrlHost("bbc.co.uk")),
                Subtask("the article is displayed", DoneCondition.MarkerText("Nowhere On This Page")),
            ),
        )
        val (_, sink, _) = run(
            ladder = ladder,
            observations = listOf(
                page("https://www.bbc.com/news", "BBC News", "Headlines"),
                page("https://www.bbc.com/news", "BBC News", "Headlines"),
            ),
            goal = "open https://bbc.co.uk and read the news",
            intentPicks = listOf("follow-link"),
            launcher = BrowserLauncher { },
        )
        assertTrue(
            sink.rows.any { it.landingHost == "bbc.com" },
            "the decision row carries the landing host: " + sink.rows.map { it.landingHost },
        )
    }

    @Test
    fun `a urlContains arrival the plan text names fires the site entry (post-UAT repair 2)`() {
        // The 2026-10-13 battery's Test-2 shape: the engine chose open-site decisively on
        // "Open the BBC News section page at bbc.com/news" while the derivation refused
        // (it accepted urlHost conditions only) and every step died on "no URL resolved".
        // v1.8.2: a urlContains identity whose segment the PLAN TEXT names site-enters.
        val ladder = Ladder(
            "Open the BBC News section.",
            listOf(
                Subtask("Open the BBC homepage at bbc.com", DoneCondition.UrlHost("bbc.com")),
                Subtask(
                    "Open the BBC News section page at bbc.com/news",
                    DoneCondition.UrlContains("bbc.com/news"),
                ),
            ),
        )
        val launched = mutableListOf<String>()
        val (result, _, _) = run(
            ladder = ladder,
            observations = listOf(
                page("https://www.bbc.com/", "BBC Home", "Headlines"),
                page("https://www.bbc.com/news", "BBC News", "World headlines"),
            ),
            goal = "open bbc.com and open the News section",
            intentPicks = emptyList(),
            launcher = BrowserLauncher { launched.add(it) },
        )
        assertEquals(
            listOf("https://bbc.com", "https://bbc.com/news"),
            launched,
            "both arrivals code-navigate - the second is the urlContains the plan text names",
        )
        assertTrue(
            result.stepLog.any {
                it.contains("site-entry: VIEW intent for https://bbc.com/news (DONE_CONDITION_DOMAIN)")
            },
            "the derivation source is logged: " + result.stepLog,
        )
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, result.stepLog.toString())
    }

    @Test
    fun `the submit intent fires the platform IME affordance when no submit control exists`() = runBlocking {
        // The battery's Google run: the query was typed, the page showed NO submit control
        // (a mobile search page's submit affordance is the keyboard's Search key), and the
        // typing repeated until the stuck kill. Post-UAT repair 2 (doc 04 §2 v1.8.2):
        // `submit` fires the platform IME action on the field this loop filled.
        val searchPage = obs(
            "http://m0.test/search",
            N("Search the catalogue", clickable = true, editable = true),
            N("A paragraph of page prose for the tree floor.", clickable = false),
            N("Browse the index", clickable = true),
            N("Another readable line so the tree clears the usable floor.", clickable = false),
            N("A third body line carrying enough prose for the observation floor.", clickable = false),
            N("One more readable line so the tree clears the usable floor.", clickable = false),
            pageNodes = 6,
        )
        val fixture = LoopFixture(
            phraseSource = navigator.app.stub.StubPhraseSource,
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("fill-field", target = "Search the catalogue"),
                *intentStep("submit"),
            ),
            observations = listOf(searchPage),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 2)),
        )
        val result = fixture.loop.run(
            "search the catalogue for the query",
            "repair-2",
            EngineIdentity("stub", "scripted-harness-v1"),
            fixture.clock,
        )
        assertTrue(
            fixture.executor.calls.contains("submit"),
            "the platform IME submit fired: ${fixture.executor.calls} | steps: ${result.stepLog}",
        )
        val submitRow = assertNotNull(
            fixture.sink.rows.firstOrNull {
                it.executedAction.kind == navigator.core.log.LoggedActionKind.SUBMIT
            },
            "the submit row exists: ${fixture.sink.rows.map { it.executedAction }}",
        )
        assertEquals(
            navigator.core.log.ActionDisposition.EXECUTED,
            submitRow.executedAction.disposition,
        )
        assertTrue(
            result.stepLog.any { it.contains("the platform submit affordance fired") },
            "the firing is loud: " + result.stepLog,
        )
    }

    @Test
    fun `a condition-only urlContains never site-enters - the false-completion guard`() {
        // "the address contains 'google.com/search'" is the writer's proxy for "results are
        // showing". Navigating to the bare endpoint would satisfy the letter without the
        // query — a false completion. The plan text must name the destination.
        assertNull(
            navigator.app.loop.SiteEntry.plan(
                "open google.com and search for mushroom recipes",
                "Show the search results page for mushroom recipes",
                DoneCondition.UrlContains("google.com/search"),
                "https://www.google.com/",
            ),
            "a condition-only URL never site-enters",
        )
        assertNull(
            navigator.app.loop.SiteEntry.plan(
                "open the shop",
                "read the news section",
                DoneCondition.UrlContains("bbc.com/news"),
            ),
            "the naming guard: the human plan must name the destination",
        )
        assertNull(
            navigator.app.loop.SiteEntry.plan(
                "read the page",
                "watch the replay, 2 hrs ago",
                DoneCondition.UrlContains("2 hrs ago"),
            ),
            "a phrase is never a URL",
        )
    }

}
