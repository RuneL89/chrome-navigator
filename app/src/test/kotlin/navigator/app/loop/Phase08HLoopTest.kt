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
 * Gate 8H.2 (loop half) under the v3 law (Phase 10, vision 03 §4 v2.2.0): the state the
 * DECIDE step builds carries the content excerpt inside the untrusted block (vision 03
 * §1) and the ACTIVE SUBTASK / LADDER PROGRESS sections — which render their DEGENERATE
 * goal-only forms on every non-ladder path (the v2 PLAN / TASK PROGRESS sections are
 * REPLACED: the 08G plan source stays row/report provenance only, and the legacy
 * tracker's working memory rides the L1 stuck hint + the run report).
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
    fun `the v2 PLAN section is REPLACED - a proposed plan source leaves the degenerate ladder forms`() {
        // Phase 10 (vision 03 §4 v2.2.0): ACTIVE SUBTASK + LADDER PROGRESS replace
        // PLAN / TASK PROGRESS; the 08G plan source stays provenance-only (rows +
        // report — pinned by PlanSourceLoopTest), never a state section again.
        val engine = runLoop(
            "action-multi",
            planSource = ProposedPlanPlanSource(
                ProposedPlan("plan-prompt-v1", listOf("open the list page", "resolve every listed target")),
            ),
            observations = listOf(page),
        )
        val (_, state) = engine.states.first { it.first == "action" }
        assertFalse("PLAN:" in state, "the v2 PLAN section is gone under v3")
        assertFalse("TASK PROGRESS:" in state, "the v2 TASK PROGRESS section is gone under v3")
        val lines = state.split("\n")
        assertEquals(
            "ACTIVE SUBTASK: open every saved item in the reading list",
            lines[3],
            "the degenerate ACTIVE SUBTASK form carries the goal (no ladder)",
        )
        assertEquals(
            "LADDER PROGRESS: ${StateTextBuilder.NO_LADDER_PROGRESS}",
            lines[4],
            "the degenerate LADDER PROGRESS form is byte-stable (no ladder)",
        )
        assertTrue(lines[5].startsWith("HISTORY:"), "the degenerate forms sit between TITLE and HISTORY")
    }

    @Test
    fun `the legacy tracker's working memory no longer rides a state section (v3) - it rides the run report`() {
        val engine = runLoop("action-multi", observations = listOf(page))
        val (_, state) = engine.states.first { it.first == "action" }
        assertFalse("TASK PROGRESS:" in state, "the v2 TASK PROGRESS section is gone under v3")
        val progress = state.split("\n").filter { it.startsWith("LADDER PROGRESS:") }
        assertTrue(progress.size == 1, "exactly the degenerate form renders (no ladder)")
        assertEquals(
            "LADDER PROGRESS: ${StateTextBuilder.NO_LADDER_PROGRESS}",
            progress.single(),
        )
    }

    @Test
    fun `the default path renders the degenerate goal-only ladder sections byte-stably`() {
        val engine = runLoop("lookup", planSource = PlanSource.None)
        val (_, state) = engine.states.first { it.first == "action" }
        assertFalse("PLAN:" in state, "the v2 PLAN section is gone under v3")
        assertFalse("TASK PROGRESS:" in state, "the v2 TASK PROGRESS section is gone under v3")
        val lines = state.split("\n")
        assertEquals("TITLE: ", lines[2], "TITLE renders even when the page title is blank")
        assertEquals(
            "ACTIVE SUBTASK: open every saved item in the reading list",
            lines[3],
            "the degenerate ACTIVE SUBTASK form is the goal itself",
        )
        assertEquals(
            "LADDER PROGRESS: ${StateTextBuilder.NO_LADDER_PROGRESS}",
            lines[4],
            "the degenerate LADDER PROGRESS form",
        )
        assertTrue(lines[5].startsWith("HISTORY:"), "HISTORY follows the degenerate forms")
    }

    /** A one-shot plan source returning a fixed proposal. */
    private class ProposedPlanPlanSource(private val plan: ProposedPlan) : PlanSource {
        override fun propose(goal: String, triageClass: TriageClass): ProposedPlan? = plan
    }
}
