package navigator.app.loop

import kotlinx.coroutines.runBlocking
import navigator.app.stub.ScriptedAnswer
import navigator.core.log.EngineIdentity
import navigator.core.log.LoggedActionKind
import navigator.core.log.LoggedExitPath
import navigator.core.log.LoggedGuardLevel
import navigator.core.log.Outcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gates 8J.3 + 8J.4 — the exit flow inside the REAL loop (fakes per Gate 6.1):
 * anti-oscillation (the refused finish is structurally un-choosable for the next M=2
 * decisions, cleared on a hash change; the sixth stuck signal rides the L1 hint) and
 * the completion card (confirm / keep-going / timeout; once per task; logged).
 */
class ExitFlowLoopTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    private suspend fun LoopFixture.run(goal: String = "Open Wikipedia article Tarte Tatin"): RunResult =
        loop.run(goal, "goal-8J", identity, clock)

    // The terminal page of every scenario: an entity-bearing contentful page the
    // lookup guard accepts, reached after one tap from the seed page. [extra] varies
    // the lines so consecutive observations carry DIFFERENT screen hashes — the loop
    // observes twice per step (pre-decision + post-step), and hash progress keeps the
    // stuck detector out of these scenarios (its own tests cover it separately).
    private fun articlePage(url: String = "http://m0.test/tarte-tatin", extra: String = "Served warm with cream") = obs(
        url,
        N("Tarte Tatin"),
        N("The Tarte Tatin is a caramelised fruit tart"),
        N(extra),
        N("Related recipes", clickable = true),
        N("Footer"),
        N("Spacer", scrollable = true),
    )

    /** A generous per-OBSERVATION page sequence: seed first, then fresh pages. */
    private fun freshPages(): List<StepObservation> =
        listOf(seedPage()) + (0 until 40).map { i -> articlePage(url = "http://m0.test/tarte-tatin?page=$i", extra = "Visit record $i") }

    private fun seedPage() = obs(
        "http://m0.test/search",
        N("search results page"),
        N("Tarte Tatin recipe"),
        N("More results"),
        N("Tarte Tatin Caramelised fruit tart", clickable = true),
        N("Footer"),
    )

    /**
     * The tap-then-finish-checks script: between consecutive finish checks it scrolls
     * (alternating down/up — an unchanged screen plus one repeated action would trip
     * the ORIGINAL stuck signals long before the exit machinery is exercised) because
     * EVERY refusal starts the M=2 anti-oscillation window: the engine cannot re-finish
     * before two other decisions ran.
     */
    private fun seriesScript(checks: List<Double>): List<ScriptedAnswer> {
        val answers = mutableListOf<ScriptedAnswer>(
            choose("triage", "lookup"),
            *intentStep("follow-link", target = "Tarte Tatin Caramelised fruit tart"),
        )
        checks.forEachIndexed { index, noul ->
            answers += intent("verify-done")
            answers += noul(noul)
            if (index < checks.size - 1) {
                // two non-done decisions meter the M=2 window down; go-back + scroll keep
                // the action keys DISTINCT (the stuck detector's same-action signal must
                // stay out of the exit machinery's way)
                answers += intent("go-back")
                answers += intent("scroll")
            }
        }
        return answers
    }

    // -- 8J.3: anti-oscillation -----------------------------------------------------------

    @Test
    fun `a refused done claim is withheld for the next M decisions - not evaluated`() = runBlocking {
        // The engine OSCILLATES (scripted verify-done right after the refusal): under
        // the law the done claim is WITHHELD for the next M=2 decisions - the loop
        // refuses it WITHOUT the judge consult (no noul consumed, no ExitCheckRow).
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Tarte Tatin Caramelised fruit tart"),
                *intentStep("verify-done"),
                noul(0.60),
                *intentStep("verify-done"), // decision 1 after the refusal: WITHHELD
            ),
            observations = listOf(seedPage(), articlePage()),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 3)),
        )
        val result = fixture.run()
        assertFalse(result.terminal == TerminalState.GOAL_ACHIEVED)
        // exactly ONE judged check (the refusal); the withheld claim consumed no noul
        assertEquals(1, fixture.engineCalls.count { it.startsWith("judge") })
        assertEquals(1, fixture.sink.exitChecks.size, "the withheld claim writes no ExitCheckRow")
        val withheld = fixture.sink.rows.last()
        assertEquals(
            navigator.core.log.ActionDisposition.REFUSED_BY_GUARD,
            withheld.executedAction.disposition,
            "the withheld claim is a logged refusal",
        )
        assertTrue(result.stepLog.any { it.contains("verify-done withheld") }, "the withholding is loud: ${result.stepLog}")
    }

    @Test
    fun `each withheld done claim meters the M-window - the claim reaches the judge again`() = runBlocking {
        // The 2026-10-13 emulator battery's live livelock (the allrecipes run): the engine
        // insists on verify-done on a STATIC page, every repeat is withheld, and because a
        // withheld claim never metered the M=2 window the loop spun ~50 steps to the hard
        // step cap. The law's meter counts "the next M DECISIONS" — a withheld claim IS
        // one — so after M claims the window closes and the next claim is JUDGED
        // (refused, ART-4), feeding the stuck machinery as consecutive refusals.
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Tarte Tatin Caramelised fruit tart"),
                intent("verify-done"), // step 2: judged -> refused (window armed, M=2)
                noul(0.60),
                intent("verify-done"), // step 3: WITHHELD (metered: M=1)
                intent("verify-done"), // step 4: WITHHELD (metered: M=0)
                intent("verify-done"), // step 5: JUDGED again -> refused
                noul(0.60),
            ),
            observations = listOf(seedPage()),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 5)),
        )
        val result = fixture.run()
        assertEquals(
            2,
            fixture.engineCalls.count { it.startsWith("judge") },
            "the second claim reaches the judge after M withheld claims " +
                "(pre-fix: 1 judge, the window never closed): ${result.stepLog}",
        )
        assertEquals(2, fixture.sink.exitChecks.size, "each judged check writes its ExitCheckRow")
        assertEquals(
            2,
            result.stepLog.count { it.contains("verify-done withheld") },
            "exactly M withheld claims close the window: ${result.stepLog}",
        )
        assertEquals(
            2,
            result.stepLog.count { it.contains("verify-done REFUSED") },
            "the metered window returns the claim to the judge: ${result.stepLog}",
        )
    }

    @Test
    fun `path A accepts on the first check - no window is ever involved`() = runBlocking {
        val fixture = LoopFixture(
            script = seriesScript(listOf(0.99)),
            observations = listOf(seedPage(), articlePage()),
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.run("Open Wikipedia article Tarte Tatin")
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals("HIGH", result.exit?.path)
        assertEquals(1, result.exit?.finishChecks)
    }

    @Test
    fun `a page-hash change clears the window - the done claim evaluates immediately`() = runBlocking {
        // Refusal on one page; the very NEXT decision is a done claim on a DIFFERENT
        // page: the window is gone (the hash changed) and the accept lands at check 2.
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Tarte Tatin Caramelised fruit tart"),
                *intentStep("verify-done"),
                noul(0.60), // refused on page 0 (hash H)
                *intentStep("verify-done"), // next observation: a NEW page -> evaluated
                noul(0.99),
            ),
            observations = freshPages(),
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.run("Open Wikipedia article Tarte Tatin")
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals(2, result.exit?.finishChecks, "refusal, then an immediate accept after the hash change")
    }

    @Test
    fun `the done-tap cycling of the specimen is impossible`() = runBlocking {
        // The Test-1 specimen's death loop was done-claim, tap, done-claim, ... on one
        // unchanged page. Under the law every refusal starts the M=2 window, so the
        // scripted cycle hits a WITHHELD claim (no judge, no ExitCheckRow) and the run
        // ends without ever accepting.
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Tarte Tatin Caramelised fruit tart"),
                *intentStep("verify-done"),
                noul(0.60),
                *intentStep("follow-link", target = "Related recipes"), // window decision 1 of 2
                *intentStep("verify-done"), // window decision 2: STILL WITHHELD
            ),
            observations = listOf(seedPage(), articlePage()),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 3)),
        )
        val result = fixture.run()
        assertFalse(result.terminal == TerminalState.GOAL_ACHIEVED)
        assertEquals(1, fixture.sink.exitChecks.size, "only the first claim was ever judged")
    }

    @Test
    fun `consecutive refused finishes inject the exit-conflict L1 hint with the evidence level`() = runBlocking {
        // Three refused finishes (the streak broken by the 0.30), no screen change: the
        // sixth signal trips with the unchanged-window signals on the third refusal, the
        // loop runs out of scripted answers right after, and the budget stops it cleanly.
        val fixture = LoopFixture(
            script = seriesScript(listOf(0.60, 0.30, 0.60)),
            observations = listOf(seedPage(), articlePage()),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 8)),
        )
        val result = fixture.run("Open Wikipedia article Tarte Tatin")

        assertFalse(result.terminal == TerminalState.GOAL_ACHIEVED)
        assertTrue(result.stepLog.any { it.contains("exit-conflict") }, "sixth signal in the assessment: ${result.stepLog}")
        assertTrue(
            result.stepLog.any { it.contains("engine wants to finish, evidence says STRONG") },
            "the L1 hint carries the evidence level: ${result.stepLog}",
        )
    }

    // -- 8J.4: the completion card ---------------------------------------------------------

    @Test
    fun `confirm on the card exits via OWNER_CONFIRM on the third guard-pass refusal`() = runBlocking {
        // 0.60 (refusal 1, streak 1), 0.30 (refusal 2, streak reset), 0.60 (refusal 3)
        // -> the completion card -> CONFIRM exits through Path C.
        val cards = ScriptedCompletionCardPresenter(CompletionResolution.CONFIRM_DONE)
        val fixture = LoopFixture(
            script = seriesScript(listOf(0.60, 0.30, 0.60)),
            observations = freshPages(),
            presenter = ScriptedGatePresenter(),
            completionCardPresenter = cards,
        )
        val result = fixture.run("Open Wikipedia article Tarte Tatin")

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertTrue(result.terminalDetail.contains("OWNER_CONFIRM"))
        // the terminal row carries the exit provenance (doc 05 v1.4.0)
        val terminalRow = fixture.sink.rows.last()
        assertEquals(Outcome.EXITED, terminalRow.outcome)
        assertEquals(LoggedActionKind.FINISH, terminalRow.executedAction.kind)
        assertEquals(LoggedExitPath.OWNER_CONFIRM, terminalRow.exit?.path)
        assertEquals(LoggedGuardLevel.STRONG, terminalRow.exit?.guardLevel)
        // the run report block
        assertEquals("OWNER_CONFIRM", result.exit?.path)
        assertEquals(3, result.exit?.finishChecks)
        assertEquals(true, result.exit?.completionCardShown)
        assertEquals("CONFIRM_DONE", result.exit?.completionCardResolution)
        // exactly one card, presented with the law's payload
        assertEquals(1, cards.presented.size)
        assertEquals("Tarte Tatin", cards.presented.first().entity)
        assertEquals(3, cards.presented.first().refusedChecks)
        // no earlier row ever carried exit provenance
        assertTrue(fixture.sink.rows.dropLast(1).all { it.exit == null })
    }

    @Test
    fun `keep-going continues the loop and the card never asks twice`() = runBlocking {
        // 0.60 (1), 0.30 (reset), 0.60 (card -> keep going), 0.60 (2), 0.60 (3) ->
        // the sustained streak completes AFTER the owner's keep-going.
        val cards = ScriptedCompletionCardPresenter(CompletionResolution.KEEP_GOING)
        val fixture = LoopFixture(
            script = seriesScript(listOf(0.60, 0.30, 0.60, 0.60, 0.60)),
            observations = freshPages(),
            presenter = ScriptedGatePresenter(),
            completionCardPresenter = cards,
        )
        val result = fixture.run("Open Wikipedia article Tarte Tatin")

        // after the keep-going, the sustained streak completes on the two later checks:
        // 0.60 (1), 0.30 (reset), 0.60 (1, card), 0.60 (2), 0.60 (3) -> SUSTAINED
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals("SUSTAINED", result.exit?.path)
        assertEquals(5, result.exit?.finishChecks)
        assertEquals(1, cards.presented.size, "once per task: a second ask would fail the script loudly")
        assertEquals("KEEP_GOING", result.exit?.completionCardResolution)
        val terminalRow = fixture.sink.rows.last()
        assertEquals(LoggedExitPath.SUSTAINED, terminalRow.exit?.path)
    }

    @Test
    fun `timeout on the card equals keep going (fail-safe)`() = runBlocking {
        val cards = ScriptedCompletionCardPresenter(CompletionResolution.TIMEOUT)
        val fixture = LoopFixture(
            script = seriesScript(listOf(0.60, 0.30, 0.60, 0.60, 0.60)),
            observations = freshPages(),
            presenter = ScriptedGatePresenter(),
            completionCardPresenter = cards,
        )
        val result = fixture.run("Open Wikipedia article Tarte Tatin")
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals("SUSTAINED", result.exit?.path)
        assertEquals("TIMEOUT", result.exit?.completionCardResolution)
    }

    // -- exit summary on non-achieved terminals ---------------------------------------------

    @Test
    fun `a run that never accepts carries no exit summary`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Tarte Tatin Caramelised fruit tart"),
                *intentStep("verify-done"),
                noul(0.10),
                *intentStep("scroll"),
            ),
            observations = listOf(seedPage(), articlePage()),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 2)),
        )
        val result = fixture.run("Open Wikipedia article Tarte Tatin")
        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertNull(result.exit)
        assertNull(fixture.sink.rows.first().exit)
    }
}
