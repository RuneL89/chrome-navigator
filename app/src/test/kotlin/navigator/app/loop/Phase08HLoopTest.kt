package navigator.app.loop

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.log.EngineIdentity
import navigator.core.log.TriageClass
import navigator.core.state.StateTextBuilder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gate 8H.2 (loop half): the v2 state the DECIDE step builds carries the new sections —
 * the content excerpt inside the untrusted block (vision 03 §1), the PLAN lines
 * re-authored from the 08G [PlanSource] proposal, and the tracker's TASK PROGRESS lines
 * on action-multi tasks — and omits both sections on the default (no plan, no
 * multi-target) path, whose shape stays the pre-08H layout.
 */
class Phase08HLoopTest {

    /** Scripts triage + a queue of action picks + a confirming finish; records states. */
    private class RecordingEngine(
        triageChoice: String,
        picks: List<String>,
    ) : DecisionEngine {
        val states = mutableListOf<Pair<String, String>>() // question name -> state text

        private val triage = ChoiceResult(
            chosenId = triageChoice,
            probabilities = listOf("lookup", "research-multi-step", "form-fill", "action", "action-multi", "impossible")
                .associateWith { if (it == triageChoice) 0.9 else 0.02 },
            confidence = 0.9,
        )

        private val picksQueue = ArrayDeque(picks)

        private fun flat(chosen: String, ids: List<String>) = ChoiceResult(
            chosenId = chosen,
            probabilities = ids.associateWith { if (it == chosen) 0.9 else 0.02 },
            confidence = 0.9,
        )

        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
            states += question.name to state
            return when (question.name) {
                Triage.QUESTION_NAME -> triage
                else -> flat(picksQueue.removeFirst(), question.options.map { it.id })
            }
        }

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult {
            states += question.name to state
            return NoulResult(0.95)
        }

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            throw UnsupportedOperationException("score is not exercised by the loop")
    }

    /** A content-rich page: prose nodes (the excerpt) + two clickable target rows. */
    private val page = obs(
        "http://m0.test/listings",
        N("Today's list of saved items"),
        N("Each item below was saved from your reading list; open them one by one."),
        N("First saved item", clickable = true),
        N("Second saved item", clickable = true),
        N("Items stay available offline for thirty days after you open them."),
    )

    /** The page AFTER an item is opened (different content — the lookup guard's evidence). */
    private val pageB = obs(
        "http://m0.test/items/first",
        N("First saved item - full text"),
        N("The article opens with a short history of the reading-list feature."),
        N("Back to the list", clickable = true),
        N("Mark as read", clickable = true),
        N("Saved 3 days ago from the morning digest."),
    )

    private fun runLoop(
        triageChoice: String,
        planSource: PlanSource = PlanSource.None,
        picks: List<String> = listOf("n2", "n3", "finish"),
        observations: List<StepObservation> = listOf(page, pageB),
    ): RecordingEngine {
        val engine = RecordingEngine(triageChoice, picks)
        val loop = NavigationLoop(
            engine = engine,
            observer = FakeObserver(observations),
            executor = FakeExecutor(Journal()),
            gatePresenter = AutoDenyGatePresenter,
            logSink = CollectingLogSink(),
            config = NavigationLoop.Config(settleMs = 0),
            planSource = planSource,
        )
        val result = runBlocking {
            loop.run(goal = "open every saved item in the reading list", goalId = "g", engineIdentity = EngineIdentity("stub", "stub"))
        }
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, "the scenario must drive to a clean exit")
        return engine
    }

    @Test
    fun `the content excerpt rides the untrusted block of the decide state`() {
        // the live observer fills the excerpt (ContentExtractor over the same walk);
        // the fake observation carries it the same way the loop consumes it.
        val withExcerpt = page.copy(excerpt = navigator.core.state.ContentExtractor.extract(page.nodes))
        val engine = runLoop(
            "lookup",
            picks = listOf("n2", "finish"),
            observations = listOf(withExcerpt, pageB),
        )
        val (_, state) = engine.states.first { it.first == "action" }
        val content = state.split("\n").dropWhile { it != StateTextBuilder.UNTRUSTED_HEADER }.drop(1)
        assertEquals(
            listOf("Today's list of saved items", "Each item below was saved from your reading list; open them one by one."),
            content.take(2),
            "the observation's non-candidate prose precedes the bucket lines",
        )
        assertTrue(content.drop(2).any { it.endsWith(":") }, "bucket lines follow the excerpt")
    }

    @Test
    fun `a proposed plan renders as bounded app-authored PLAN lines`() {
        val engine = runLoop(
            "action-multi",
            planSource = ProposedPlanPlanSource(
                ProposedPlan("plan-prompt-v1", listOf("open the list page", "resolve every listed target")),
            ),
            observations = listOf(page),
        )
        val (_, state) = engine.states.first { it.first == "action" }
        val lines = state.split("\n")
        assertEquals("PLAN: open the list page", lines[3], "PLAN follows TITLE, before TASK PROGRESS")
        assertEquals("PLAN: resolve every listed target", lines[4])
        assertTrue(lines[5].startsWith("TASK PROGRESS:"), "TASK PROGRESS follows PLAN on a multi-target task")
        assertTrue(lines.any { it.startsWith("HISTORY:") }, "HISTORY follows")
    }

    @Test
    fun `the action-multi tracker's progress lines ride TASK PROGRESS`() {
        val engine = runLoop("action-multi", observations = listOf(page))
        val (_, state) = engine.states.first { it.first == "action" }
        val progress = state.split("\n").filter { it.startsWith("TASK PROGRESS:") }
        assertTrue(progress.isNotEmpty(), "an action-multi step carries the section")
        assertTrue(
            progress.first().contains("0 processed") && progress.first().contains("2 pending"),
            "the counts line reflects the tracker's working memory: ${progress.first()}",
        )
    }

    @Test
    fun `the default path omits both sections - the pre-08H layout`() {
        val engine = runLoop("lookup", planSource = PlanSource.None)
        val (_, state) = engine.states.first { it.first == "action" }
        assertFalse("PLAN:" in state, "no plan source -> no PLAN section")
        assertFalse("TASK PROGRESS:" in state, "not an action-multi task -> no TASK PROGRESS section")
        val lines = state.split("\n")
        assertEquals("TITLE: ", lines[2], "TITLE renders even when the page title is blank")
        assertTrue(lines[3].startsWith("HISTORY:"), "HISTORY follows TITLE directly on the default path")
    }

    /** A one-shot plan source returning a fixed proposal. */
    private class ProposedPlanPlanSource(private val plan: ProposedPlan) : PlanSource {
        override fun propose(goal: String, triageClass: TriageClass): ProposedPlan? = plan
    }
}
