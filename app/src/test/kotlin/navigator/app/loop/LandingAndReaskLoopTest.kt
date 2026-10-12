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
import kotlin.test.assertTrue

/**
 * The landing-host reconciliation and the bounded stage-1 re-ask (Phase 10C, vision 04 §1/§2
 * v1.7.0 — owner-ratified after the 2026-10-11 battery): the BBC redirect deadlock
 * (bbc.co.uk → bbc.com) amends instead of spinning, and an unusable slate re-asks stage-1
 * ONCE instead of failing the subtask.
 */
class LandingAndReaskLoopTest {

    /** The scripted engine: intent answers in order (default verify-done), noul in order (default 0.9). */
    private class ScriptedEngine2(
        private val intentPicks: MutableList<String>,
        private val noulValues: MutableList<Double> = mutableListOf(),
    ) : DecisionEngine {
        val asked = CopyOnWriteList()
        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
            asked.add(question.name)
            val pick = when (question.name) {
                "triage" -> "lookup"
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
        override suspend fun restructure(goal: String, generation: Int): RestructuredLadder? =
            RestructuredLadder(ladder, "test/gen$generation", 0)

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
            2,
            extractor.calls,
            "the report subtask's own extraction + the goal-exit re-phrasing (the existing law)",
        )
        assertTrue(
            result.stepLog.any { it.contains("report: the answer for subtask 1/1 was produced") },
            "the production is logged: " + result.stepLog,
        )
        assertEquals("The headline is X.", assertNotNull(result.answer).text)
        assertEquals(
            "STRONG",
            assertNotNull(result.exit).guardLevel,
            "the answerExtracted condition's evidence is code-owned (STRONG), never page content",
        )
        assertEquals(
            4_000L,
            result.writerSpendMicroUsd,
            "both extraction calls meter into the writer budget (2 x 2_000 micro-USD)",
        )
    }

}
