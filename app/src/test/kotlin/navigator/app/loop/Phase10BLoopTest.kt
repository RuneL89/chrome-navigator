package navigator.app.loop

import navigator.app.gen.AssistResult
import navigator.app.gen.GenUsage
import navigator.app.gen.PhraseResult
import navigator.core.decision.Intents
import navigator.core.log.ActionDisposition
import navigator.core.log.EngineIdentity
import navigator.core.log.LoggedActionKind
import navigator.core.log.Outcome
import navigator.app.stub.ScriptedAnswer
import navigator.app.stub.StubEngine
import navigator.app.stub.StubScript
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 10B (Gate 10B.2 — the cascade execution in the REAL loop, JVM, $0): the routing
 * matrix (every intent reaches its code path), the uncertainty handshake's bounds (ONE
 * assist per page-hash, ONE re-ask, the honesty skip, the code fallback), the fill-field
 * phrase law (writer-phrased; a refusal routes to recovery — never raw-goal typing), the
 * LOW wrong-page handling, the held-hash re-check cadence, and the cascade ROW shapes.
 * The writer seams are scripted lambdas — no live provider anywhere (isolation law).
 */
class Phase10BLoopTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    private suspend fun LoopFixture.run(goal: String = "open the demo article"): RunResult =
        loop.run(goal, "goal-10b", identity, clock)

    /** The target page: one nav-link (follow-link) + one button (activate-control). */
    private fun targetPage(url: String = "http://m0.test/page") = obs(
        url,
        N("Header prose line"),
        N("Open the article", clickable = true),
        N("Subscribe now", clickable = true, button = true),
        N("Mid prose line"),
        N("Footer prose line"),
    )

    /** A two-field page (the fill-field routing). */
    private fun formPage() = obs(
        "http://m0.test/form",
        N("A form page heading with prose."),
        N("Guest name", editable = true),
        N("Submit form", clickable = true, button = true),
        N("Extra prose line for the floor."),
        N("More prose for the usable floor."),
    )

    // -- the routing matrix: every intent reaches its code path ---------------------------

    @Test
    fun `follow-link - stage-2 over the nav-links slate taps the argmax target`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Open the article"),
                *intentStep("verify-done"),
                noul(0.95),
            ),
            observations = listOf(targetPage(), obs(
                "http://m0.test/article",
                N("The article headline"),
                N("Article body paragraph one."),
                N("Article body paragraph two."),
                N("Article body paragraph three."),
                N("Article body paragraph four."),
            )),
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.run()

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, result.stepLog.toString())
        // the argmax target executed at its own coordinates (n1: y=100 -> center 120)
        assertTrue(fixture.executor.calls.contains("tap(50,120)"), fixture.executor.calls.toString())
        val row = fixture.sink.rows[0]
        assertEquals("intent", row.questionPrimary.name, "the row's question IS the fixed intent form")
        assertEquals(
            Intents.IDS,
            row.questionPrimary.options.map { it.id },
            "the stage-1 option space is the fixed vocabulary in table order",
        )
        assertEquals("follow-link", row.teacherAnswer.chosen)
        assertEquals("n1", row.executedAction.candidateId, "executedAction carries the stage-2 target")
        assertEquals(
            setOf("n1"),
            assertNotNull(row.targetScores).keys,
            "the executed nav-links slate's expected levels ride the row",
        )
        assertNotNull(row.targetStateText, "the row carries the TARGET STATE VIEW (the score case's state)")
        assertTrue(row.targetStateText!!.startsWith("SUBTASK:"), "the target view is the stage-view template")
    }

    @Test
    fun `empty slate - the bounded grace gives the page observe-only steps before the fallback`() = runBlocking {
        // Gate-105: a partially-served tree (prose present, links not yet materialized)
        // made a target-bearing intent's slate empty; the first occurrence must NOT kill
        // the task — up to EMPTY_SLATE_GRACE_STEPS observe-only steps, then the ordinary
        // no-good-target fallback.
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                intent("follow-link"), // step 1: the dark page -> empty nav-links slate
                intent("follow-link"), // step 2: the materialized page -> the real slate
                stage2("Open the article"),
                intent("verify-done"),
                noul(0.95),
            ),
            observations = listOf(
                obs(
                    "http://m0.test/page",
                    N("Header prose line"),
                    N("A prose line"),
                    N("Another prose line"),
                    N("Yet more prose line"),
                    N("Footer prose line"),
                ),
                targetPage(),
                obs(
                    "http://m0.test/article",
                    N("The article headline"),
                    N("Article body paragraph one."),
                    N("Article body paragraph two."),
                    N("Article body paragraph three."),
                    N("Article body paragraph four."),
                ),
            ),
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.run()

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, result.stepLog.toString())
        // the dark step logged its row with NOTHING executed and no tap
        val graceRow = fixture.sink.rows[0]
        assertEquals(Outcome.NO_CHANGE, graceRow.outcome)
        assertEquals(ActionDisposition.REFUSED_BY_GUARD, graceRow.executedAction.disposition)
        assertNull(graceRow.executedAction.candidateId)
        assertEquals(
            listOf("tap(50,120)"),
            fixture.executor.calls,
            "only the post-grace tap executed - the grace step touched nothing",
        )
        assertTrue(
            result.stepLog.any { it.contains("empty-slate grace 1/${NavigationLoop.EMPTY_SLATE_GRACE_STEPS}") },
            result.stepLog.toString(),
        )
        assertEquals(NavigationLoop.EMPTY_SLATE_GRACE_STEPS, 3, "the grace bound is a code constant (ART-5)")
    }

    @Test
    fun `open-site - the code VIEW intent opens the resolved URL (never a tap)`() = runBlocking {
        var launched: String? = null
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("open-site"),
                *intentStep("verify-done"),
                noul(0.95),
            ),
            observations = listOf(
                targetPage(),
                obs("http://m0.test/article", N("Article one"), N("Article two"), N("Article three"), N("Article four"), N("Article five")),
            ),
            presenter = ScriptedGatePresenter(),
            browserLauncher = BrowserLauncher { url -> launched = url },
        )
        // a LADDER source gives open-site a urlHost subtask whose URL resolves in code
        val result = fixture.loop.run(
            "open the demo article",
            "goal-open-site",
            identity,
            fixture.clock,
        )
        // non-ladder: no subtask done-condition to resolve a URL from -> the lawful
        // no-good-target fallback (the site entry folds under the ladder, vision 04 §1)
        assertEquals(TerminalState.STUCK_KILL, result.terminal)
        assertNull(launched, "no subtask, no URL, no VIEW intent - never invented (ART-2)")
        assertTrue(fixture.executor.calls.isEmpty(), "open-site never taps")
        assertTrue(
            result.stepLog.any { it.contains("open-site") && it.contains("no URL resolved") },
            "the fallback is loud: ${result.stepLog}",
        )
    }

    @Test
    fun `go-back and scroll - the bare gestures execute ungated with rows`() = runBlocking {
        val page = obs(
            "http://m0.test/a",
            N("Header"),
            N("Body one"),
            N("Body two"),
            N("A link", clickable = true),
            N("Filler", scrollable = true),
        )
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("go-back"),
                *intentStep("scroll"),
            ),
            observations = listOf(page),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 2)),
        )
        val result = fixture.run()

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertEquals(listOf("back", "scroll(50,420,true)"), fixture.executor.calls, "bare gestures, no taps")
        assertEquals(LoggedActionKind.BACK, fixture.sink.rows[0].executedAction.kind)
        assertEquals(LoggedActionKind.SCROLL, fixture.sink.rows[1].executedAction.kind)
        assertEquals(null, fixture.sink.rows[0].gateEvent, "gestures are never gated (vision 06 §1)")
        assertEquals(null, fixture.sink.rows[0].targetScores, "no stage-2 on code-path intents")
    }

    // -- the uncertainty handshake (ONE assist per page-hash, ONE re-ask, honesty) --------

    /** A scripted assist source counting its calls; returns [answer] every time. */
    private class ScriptedAssist(private val answer: String?, val calls: MutableList<String> = mutableListOf()) :
        AssistSource {
        override suspend fun assist(subtask: String, doneCondition: String, pageMaterial: String): AssistResult? {
            calls.add("$subtask|$doneCondition|$pageMaterial")
            return answer?.let {
                AssistResult(it, GenUsage(10, 5, 28), "assist-v1")
            }
        }
    }

    @Test
    fun `handshake - one assist per page-hash, one re-ask, the re-ask is the decision of record`() = runBlocking {
        val assist = ScriptedAssist(
            "The page is a news index; the target link reads 'Open the article'; no search box is present.",
        )
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                intent("follow-link", p = 0.50), // UNCERTAIN: the handshake fires
                *intentStep("follow-link", target = "Open the article"), // the RE-ASK: HIGH, the decision of record (its stage-2 follows)
                *intentStep("verify-done"),
                noul(0.95),
            ),
            observations = listOf(targetPage(), obs(
                "http://m0.test/article",
                N("Article one"),
                N("Article two"),
                N("Article three"),
                N("Article four"),
                N("Article five"),
            )),
            presenter = ScriptedGatePresenter(),
            assistSource = assist,
        )
        val result = fixture.run("open the demo article")

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, result.stepLog.toString())
        assertEquals(1, assist.calls.size, "ONE assist on the page-hash")
        val (subtask, doneWhen, material) = assist.calls.single().split("|")
        assertEquals("open the demo article", subtask, "non-ladder: the goal is the subtask")
        assertTrue(material.contains("http://m0.test/page"), "the page material carries the observation URL (the fake obs leaves the excerpt empty)")
        // the re-ask's answer executed
        assertEquals("follow-link", fixture.sink.rows[0].teacherAnswer.chosen)
        assertTrue(
            result.stepLog.any { it.contains("ASSIST (untrusted)") },
            "the re-ask logged the assist block: ${result.stepLog}",
        )
        // writer spend metered into the SEPARATE cap
        assertEquals(28, result.writerSpendMicroUsd, "the assist's usage metered into WriterBudget")
    }

    @Test
    fun `handshake bounds - a second UNCERTAIN on the same page-hash falls straight to fallback`() = runBlocking {
        val assist = ScriptedAssist("The page lists archived issues by month.")
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                intent("follow-link", p = 0.50), // UNCERTAIN #1: assist + re-ask
                intent("follow-link", p = 0.50), // the re-ask STILL UNCERTAIN -> fallback (give-up mirror)
                choose("recovery", "give-up"),
            ),
            observations = listOf(targetPage()),
            presenter = ScriptedGatePresenter(),
            assistSource = assist,
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 4)),
        )
        val result = fixture.run()

        assertEquals(1, assist.calls.size, "ONE assist per page-hash - the fallback needs no second assist")
        assertEquals(TerminalState.STUCK_KILL, result.terminal, "still UNCERTAIN -> the code fallback ends it")
        assertTrue(
            result.stepLog.any { it.contains("still UNCERTAIN after the handshake") },
            result.stepLog.toString(),
        )
    }

    @Test
    fun `handshake honesty - the assist skip marker routes straight to fallback without a re-ask`() = runBlocking {
        val assist = ScriptedAssist(null) // the honesty path: null (the skip-to-fallback signal)
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                intent("follow-link", p = 0.50), // UNCERTAIN: the assist refuses -> fallback
                choose("recovery", "give-up"),
            ),
            observations = listOf(targetPage()),
            presenter = ScriptedGatePresenter(),
            assistSource = assist,
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 3)),
        )
        val result = fixture.run()

        assertEquals(1, assist.calls.size)
        assertEquals(TerminalState.STUCK_KILL, result.terminal)
        assertTrue(
            result.stepLog.any { it.contains("assist skipped") },
            "the honesty skip is loud: ${result.stepLog}",
        )
    }

    @Test
    fun `handshake without a source - UNCERTAIN falls straight to the code fallback`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                intent("follow-link", p = 0.50),
                choose("recovery", "give-up"),
            ),
            observations = listOf(targetPage()),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 3)),
        )
        val result = fixture.run()
        assertEquals(TerminalState.STUCK_KILL, result.terminal)
        assertTrue(result.stepLog.any { it.contains("no assist source wired") })
    }

    // -- the LOW band: the wrong-page recovery signal --------------------------------------

    @Test
    fun `LOW - the wrong-page signal executes the go-back-leaning recovery, ungated`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                intent("follow-link", p = 0.10), // LOW: the wrong-page signal
            ),
            observations = listOf(targetPage()),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 1)),
        )
        val result = fixture.run()

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertEquals(listOf("back"), fixture.executor.calls, "the go-back-leaning handling ran")
        assertEquals(LoggedActionKind.BACK, fixture.sink.rows[0].executedAction.kind)
        assertEquals(null, fixture.sink.rows[0].gateEvent, "recovery actions are never gated")
        assertTrue(result.stepLog.any { it.contains("wrong-page signal") }, result.stepLog.toString())
    }

    // -- fill-field: writer-phrased typing + the refusal law --------------------------------

    @Test
    fun `fill-field - the stage-2 target's content is writer-phrased, never the raw goal`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "form-fill"),
                *intentStep("fill-field", target = "Guest name"),
                *intentStep("submit", target = "Submit form"),
                // the H9 net is SUSPICIOUS on the form context; the scripted engine
                // answers the additive consult confidently LOW (no gate)
                navigator.app.stub.ScriptedAnswer(question = "h9-additive-check", pTrue = 0.1),
                *intentStep("verify-done"),
                noul(0.95),
            ),
            observations = listOf(formPage(), formPage(), formPage(), obs(
                "http://m0.test/saved",
                N("Saved headline"),
                N("Saved body one."),
                N("Saved body two."),
                N("Saved body three."),
                N("Saved body four."),
            )),
            presenter = ScriptedGatePresenter(),
            phraseSource = PhraseSource { subtask, fieldLabel ->
                PhraseResult("Ada Lovelace", GenUsage(20, 4, 48), "phrase-v1")
            },
        )
        val result = fixture.run("type the guest name into the form")

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, result.stepLog.toString())
        val typed = fixture.executor.calls.filter { it.startsWith("text(") }
        assertTrue(typed.single().contains("Ada Lovelace"), "the WRITER phrase typed: $typed")
        assertTrue(
            typed.none { it.contains("type the guest name") },
            "the raw goal text is never typed (locked decision #4)",
        )
        assertEquals(48, result.writerSpendMicroUsd, "the phrase's usage metered into WriterBudget")
        val row = fixture.sink.rows[0]
        assertEquals(LoggedActionKind.TYPE, row.executedAction.kind)
        assertEquals(false, row.typedSecret, "a writer phrase is not a credential")
    }

    @Test
    fun `fill-field phrase refusal - the step FAILS to recovery, never raw-goal typing`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "form-fill"),
                *intentStep("fill-field", target = "Guest name"),
                *intentStep("fill-field", target = "Guest name"),
            ),
            observations = listOf(formPage()),
            presenter = ScriptedGatePresenter(),
            // no phrase source wired: every phrase call refuses (the failure law)
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 2)),
        )
        val result = fixture.run("type the guest name into the form")

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertTrue(
            fixture.executor.calls.none { it.startsWith("text(") },
            "nothing was ever typed: ${fixture.executor.calls}",
        )
        assertTrue(
            result.stepLog.any { it.contains("phrasing refused") && it.contains("never raw-goal typing") },
            result.stepLog.toString(),
        )
        assertEquals(Outcome.ERROR, fixture.sink.rows[0].outcome, "the failed step logged its error")
    }

    // -- the verify-done intent + the R1 code-triggered checks (held-hash cadence) ----------

    @Test
    fun `the held-hash re-check - a refused check re-fires every 3rd step on an unchanged page`() = runBlocking {
        // LADDER run: the urlHost subtask is satisfied from step 0. The check refuses at
        // 0.5; the engine keeps tapping (the page never changes); the v1.6.0 re-check
        // re-fires on the SAME hash after 3 steps and accepts at 0.9 -> the FINAL
        // subtask's IS-DONE = goal-achieved (the evidence matured on a static page).
        val ladder = navigator.core.ladder.Ladder(
            "Open the demo site.",
            listOf(
                navigator.core.ladder.Subtask(
                    "the demo site is loaded",
                    navigator.core.ladder.DoneCondition.UrlHost("m0.test"),
                ),
            ),
            navigator.core.ladder.LadderTaskClass.READ,
        )
        val script = scriptOf(
            *intentStep("follow-link", target = "Open the article"),
            *intentStep("follow-link", target = "Open the article"),
            *intentStep("follow-link", target = "Open the article"),
            noul(0.5), // the first (refused) check
            noul(0.9), // the bounded held-hash re-check accepts
        )
        val sink = CollectingLogSink()
        val loop = NavigationLoop(
            engine = StubEngine(StubScript(script)),
            observer = object : LoopObserver {
                override suspend fun observe(): StepObservation = targetPage()
            },
            executor = FakeExecutor(Journal()),
            gatePresenter = AutoDenyGatePresenter,
            logSink = sink,
            config = NavigationLoop.Config(settleMs = 0),
            ladderSource = object : LadderSource {
                override suspend fun restructure(goal: String, generation: Int): RestructuredLadder? =
                    RestructuredLadder(ladder, "test/gen1", 0)
            },
        )
        val result = loop.run("open the demo site", "goal-held-hash", identity, FakeClock())

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        // two judged checks on the SAME page hash: the refusal + the bounded re-check
        assertEquals(2, sink.exitChecks.size, "the re-check matured the evidence: " + result.stepLog)
        assertEquals(
            navigator.core.log.ExitCheckVerdict.REFUSED,
            sink.exitChecks[0].verdict,
        )
        assertEquals(
            navigator.core.log.ExitCheckVerdict.ACCEPTED,
            sink.exitChecks[1].verdict,
        )
        assertTrue(
            sink.exitChecks[1].stepIndex > sink.exitChecks[0].stepIndex,
            "the re-check consumed a LATER step (the cadence matured on the unchanged page)",
        )
    }
}
