package navigator.app.loop

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The ladder-path browser-readiness pre-step (2026-10-17 hotel iteration, doc 04 §1
 * v1.8.3) + the state-truthful refusal detail (doc 03 §5 v2.7.0), JVM-pinned in the
 * REAL loop. The product (ladder) path subsumes the legacy cold-start, so a task-shaped
 * goal (one that names an OUTCOME, not a site) used to observe whatever window was
 * front and refuse with zero decision rows. Pinned here:
 * - a non-browser front window + an installed browser + a wired home launcher fires the
 *   home launch EXACTLY ONCE (readiness, not navigation) and the run then proceeds to a
 *   normal terminal over the browser-front tree;
 * - the launch is NOT fired when the browser is already front, when no launcher is
 *   wired (every pre-existing path), when no browser exists (probe null), or when the
 *   front window's package is blank;
 * - the refusal detail is STATE-TRUTHFUL: with no browser EVER front this run it names
 *   the browser-never-front truth (and the last front window); once a browser WAS front
 *   the doc-03 message stands byte-identically;
 * - the recovery-row facts ride the additive listener callback (terminal name +
 *   ever-browser-front), fire-and-forget.
 */
class LadderReadinessPrestepTest {

    // -- fakes ------------------------------------------------------------------------------

    /** Records the home launches (the pre-step's one edge). */
    private class RecordingHomeLauncher : BrowserHomeLauncher {
        val launches = mutableListOf<String>()

        override fun launchHome() {
            launches.add("home")
        }
    }

    /** The browser probe: a fixed state, or absent when constructed with null. */
    private class StaticBrowserProbe(private val state: BrowserState?) : BrowserProbe {
        override fun probe(): BrowserState? = state
    }

    /** Serves ONE ladder (a single urlContains subtask over the identity page). */
    private class OneLadderSource(private val ladder: Ladder) : LadderSource {
        override suspend fun restructure(
            goal: String,
            generation: Int,
            failure: navigator.core.ladder.ReplanContext?,
        ): RestructuredLadder? =
            if (generation == 1) RestructuredLadder(ladder, ladder.versionTag("test", 1), 0) else null

        override suspend fun repairCondition(
            subtaskInstruction: String,
            current: DoneCondition,
            pageUrl: String,
            pageTitle: String,
        ): RepairedCondition? = null
    }

    /** A judge-only engine: only the code-triggered check's noul (the engine never chooses). */
    private class JudgeOnlyEngine(noulValues: List<Double>) : DecisionEngine {
        val askedQuestions = mutableListOf<String>()
        private val nouls = ArrayDeque(noulValues)

        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult =
            throw UnsupportedOperationException("no choose is scripted")

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult {
            askedQuestions.add(question.name)
            return NoulResult(nouls.removeFirstOrNull() ?: error("no scripted noul answer"))
        }

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            throw UnsupportedOperationException("no score is scripted")

        override suspend fun scoreBatch(
            state: String,
            questions: Map<String, ScoreQuestion>,
        ): Map<String, ScoreResult> = throw UnsupportedOperationException("no stage-2 is scripted")
    }

    /** Records the recovery-row facts the loop fires at the finish funnel. */
    private class RecoveryRecordingListener : LoopUiListener {
        val offers = mutableListOf<Pair<String, Boolean>>()

        override fun onThinking(active: Boolean) {}
        override fun onPlan(subgoals: List<String>) {}
        override fun onStep(facts: LoopUiListener.StepFacts) {}
        override fun onTaskState(state: LoopUiListener.TaskStateUpdate) {}
        override fun onRecoveryOffer(terminal: String, everBrowserFront: Boolean) {
            offers.add(terminal to everBrowserFront)
        }
    }

    // -- fixtures ---------------------------------------------------------------------------

    /** The identity page the subtask's urlContains condition reads STRONG on. */
    private val identityPage = obs(
        "http://m0.test/page",
        N("The marked page body", clickable = false),
        N("A second body paragraph carrying more prose.", clickable = false),
        N("Open the article", clickable = true),
        N("A page footer note that is just prose.", clickable = false),
        N("One more readable line so the tree clears the usable floor.", clickable = false),
    )

    /** The hotel-run shape: a READABLE tree from some other app's window (not a browser). */
    private val otherAppScreen = obs(
        "",
        N("A readable screen of some other app.", clickable = false),
        N("A second readable line of that app.", clickable = false),
        N("A button of that app", clickable = true),
        N("A fourth readable line of that app.", clickable = false),
        N("A fifth readable line of that app.", clickable = false),
        windowPackage = "com.example.otherapp",
    )

    /** The hotel-run wall: the same non-browser window, but UNUSABLE as a tree. */
    private val unusableOtherAppScreen = obs(
        "",
        N("canvas"),
        pageNodes = 1,
        windowPackage = "com.example.otherapp",
    )

    /** An unusable tree read from a real browser window (the pre-fix refusal shape). */
    private val unusableBrowserScreen = obs(
        "",
        N("canvas"),
        pageNodes = 1,
    )

    private val browserPresent = BrowserState("com.android.chrome", null)

    private fun ladder() = Ladder(
        "Read the marked page.",
        listOf(Subtask("the page is displayed", DoneCondition.UrlContains("m0.test/page"))),
    )

    private fun runLadder(
        engine: DecisionEngine,
        observations: List<StepObservation>,
        probe: BrowserProbe? = null,
        launcher: BrowserHomeLauncher? = null,
        config: NavigationLoop.Config = NavigationLoop.Config(settleMs = 0),
        uiListener: LoopUiListener? = null,
        goal: String = "read the marked page",
    ): RunResult {
        val sink = CollectingLogSink()
        val loop = NavigationLoop(
            engine = engine,
            observer = FakeObserver(observations),
            executor = FakeExecutor(Journal()),
            gatePresenter = AutoDenyGatePresenter,
            logSink = sink,
            config = config,
            ladderSource = OneLadderSource(ladder()),
            browserProbe = probe,
            homeLauncher = launcher,
            uiListener = uiListener,
        )
        return runBlocking {
            loop.run(goal = goal, goalId = "g", engineIdentity = EngineIdentity("stub", "stub"))
        }
    }

    /** The refusal runs must stay fast: tiny re-read bounds and a zero readiness budget. */
    private fun refusalConfig() = NavigationLoop.Config(
        settleMs = 0,
        unusableRetryDelayMs = 1,
        restoreReadinessMs = 0,
        refuseAfterUnusableReads = 2,
    )

    // -- the pre-step fires exactly once and the run proceeds -------------------------------

    @Test
    fun `readiness pre-step - a non-browser front window fires the home launch once and the run proceeds`() {
        val launcher = RecordingHomeLauncher()
        val engine = JudgeOnlyEngine(listOf(0.9))
        val listener = RecoveryRecordingListener()
        val result = runLadder(
            engine,
            observations = listOf(otherAppScreen, identityPage),
            probe = StaticBrowserProbe(browserPresent),
            launcher = launcher,
            uiListener = listener,
        )

        assertEquals(1, launcher.launches.size, "at most ONE attempt per run")
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, result.stepLog.toString())
        assertTrue(
            result.stepLog.any {
                it.contains(
                    "readiness: front window com.example.otherapp is not a browser - " +
                        "firing the browser's home launch (readiness, not navigation)",
                )
            },
            "the loud, state-truthful readiness line rides the step log",
        )
        // The pre-step's probe and observation consumed no steps and no budget: the one
        // code check IS step 0 (the model test's shape — no engine choose ever ran).
        assertEquals(listOf("goal-achieved"), engine.askedQuestions, "only the judge was consulted")
        assertEquals(0, result.rowsLogged)
        assertEquals(1, result.steps)
        // The recovery-row facts rode the finish funnel: an achieved goal is never eligible.
        assertEquals(listOf<Pair<String, Boolean>>("GOAL_ACHIEVED" to true), listener.offers)
    }

    @Test
    fun `readiness pre-step - the browser already front never fires the home launch`() {
        val launcher = RecordingHomeLauncher()
        val engine = JudgeOnlyEngine(listOf(0.9))
        val result = runLadder(
            engine,
            observations = listOf(identityPage),
            probe = StaticBrowserProbe(browserPresent),
            launcher = launcher,
        )

        assertTrue(launcher.launches.isEmpty(), "a browser-front start is not a readiness gap")
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertTrue(result.stepLog.none { it.contains("readiness:") })
    }

    @Test
    fun `readiness pre-step - no home launcher wired never fires (existing paths byte-identical)`() {
        val engine = JudgeOnlyEngine(listOf(0.9))
        // No launcher wired: the pre-step is disabled entirely (it returns before its
        // observation), so the step loop reads the browser page as ever.
        val result = runLadder(
            engine,
            observations = listOf(identityPage),
            probe = StaticBrowserProbe(browserPresent),
            launcher = null,
        )

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertTrue(result.stepLog.none { it.contains("readiness:") })
    }

    @Test
    fun `readiness pre-step - browser absent (probe null) never fires the home launch`() {
        val launcher = RecordingHomeLauncher()
        val engine = JudgeOnlyEngine(listOf(0.9))
        val result = runLadder(
            engine,
            observations = listOf(identityPage),
            probe = StaticBrowserProbe(null),
            launcher = launcher,
        )

        assertTrue(launcher.launches.isEmpty(), "no browser - nothing to launch")
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
    }

    @Test
    fun `readiness pre-step - a blank front window never fires the home launch`() {
        val launcher = RecordingHomeLauncher()
        val engine = JudgeOnlyEngine(emptyList())
        val blank = obs("", N("canvas"), pageNodes = 1, windowPackage = "")
        val result = runLadder(
            engine,
            observations = listOf(blank),
            probe = StaticBrowserProbe(browserPresent),
            launcher = launcher,
            config = refusalConfig(),
        )

        assertTrue(launcher.launches.isEmpty(), "a blank package is nothing truthful to act on")
        assertEquals(TerminalState.REFUSED, result.terminal)
    }

    // -- the state-truthful refusal (doc 03 §5 v2.7.0) ---------------------------------------

    @Test
    fun `the never-browser-front refusal names the truth and offers the recovery row`() {
        val launcher = RecordingHomeLauncher()
        val engine = JudgeOnlyEngine(emptyList())
        val listener = RecoveryRecordingListener()
        val result = runLadder(
            engine,
            observations = listOf(unusableOtherAppScreen),
            probe = StaticBrowserProbe(browserPresent),
            launcher = launcher,
            config = refusalConfig(),
            uiListener = listener,
        )

        assertEquals(TerminalState.REFUSED, result.terminal)
        // The pre-step fired (the readiness attempt is not wasted on a refusal either).
        assertEquals(1, launcher.launches.size)
        assertEquals(
            "the browser never came to the front, so there was no page to read " +
                "(front window: com.example.otherapp)",
            result.terminalDetail,
            "the refusal detail is state-truthful - the site was never the problem",
        )
        assertTrue(
            result.stepLog.none { it.contains(NavigationLoop.REFUSAL_MESSAGE) },
            "the misattributed doc-03 message never renders for a never-browser-front run",
        )
        assertEquals(listOf<Pair<String, Boolean>>("REFUSED" to false), listener.offers)
    }

    @Test
    fun `a refusal after a browser WAS front keeps the doc-03 message`() {
        val launcher = RecordingHomeLauncher()
        val engine = JudgeOnlyEngine(emptyList())
        val listener = RecoveryRecordingListener()
        val result = runLadder(
            engine,
            observations = listOf(unusableBrowserScreen),
            probe = StaticBrowserProbe(browserPresent),
            launcher = launcher,
            config = refusalConfig(),
            uiListener = listener,
        )

        assertEquals(TerminalState.REFUSED, result.terminal)
        assertTrue(launcher.launches.isEmpty(), "the browser was front - the pre-step never fires")
        assertEquals(
            NavigationLoop.REFUSAL_MESSAGE,
            result.terminalDetail,
            "a genuinely unreadable page keeps the doc-03 message byte-identically",
        )
        assertEquals(listOf<Pair<String, Boolean>>("REFUSED" to true), listener.offers)
        assertFalse(
            navigator.app.overlay.LiveLogRecovery.recoveryEligible("REFUSED", everBrowserFront = true),
            "a browser-front refusal is not recovery-row eligible",
        )
    }
}
