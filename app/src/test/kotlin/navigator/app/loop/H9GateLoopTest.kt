package navigator.app.loop

import kotlinx.coroutines.runBlocking
import navigator.app.stub.ScriptedAnswer
import navigator.core.gate.AdditiveEngineCheck
import navigator.core.log.ActionDisposition
import navigator.core.log.EngineIdentity
import navigator.core.log.LoggedGateDecision
import navigator.core.log.LoggedHClass
import navigator.core.log.TriageClass
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 8E.3 — the H9 general gate wired into the loop's GATE step (vision 06 §1a + §3,
 * Phase 8E):
 *  - a code-net H9 fires its card WITHOUT task trust; the first approval grants
 *    per-task same-site trust and the rest ride trusted (existing gate mechanics,
 *    unchanged);
 *  - when the code net is UNCERTAIN, the loop consults the primary engine ADDITIVELY:
 *    a non-LOW answer ADDS the H9 card, a confident LOW leaves the action ungated;
 *  - every gate event's trigger source (code-net vs additive-engine) is visible in the
 *    step log;
 *  - the engine is NEVER consulted for a code-confident class — a script without an
 *    additive answer fails loudly if the wiring ever asked (suppression cannot even be
 *    attempted silently).
 *
 * $0 by construction: the judge is the scripted stub engine.
 */
class H9GateLoopTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    /** A mutation-page shape: two dictionary-worded controls + ordinary fillers. */
    private fun listPage() = obs(
        "http://m0.test/list",
        N("Issue header"),
        N("Body text one"),
        N("Body text two"),
        N("Unsubscribe now", clickable = true, button = true),
        N("Also unsubscribe", clickable = true, button = true),
        N("Footer"),
    )

    /** A mutation-bait shape: one NEUTRAL control on an authenticated surface. */
    private fun noticePage() = obs(
        "http://m0.test/account/notice",
        N("Notice header"),
        N("Notice text one"),
        N("Notice text two"),
        N("Continue", clickable = true, button = true),
        N("Footer"),
    )

    private suspend fun LoopFixture.run(goal: String = "tidy the subscriptions"): RunResult =
        loop.run(goal, "goal-8e", identity, clock)

    // -- code-net H9: card, then per-task same-site trust ---------------------------------

    @Test
    fun `code-net H9 fires the card without trust and the approval carries the rest of the task`() = runBlocking {
        val presented = mutableListOf<String>()
        val presenter = GatePresenter { card ->
            presented.add("${card.classLabel} on ${card.site}")
            GateResolution.APPROVED
        }
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("activate-control", target = "Unsubscribe now"),
                *intentStep("activate-control", target = "Also unsubscribe"),
            ),
            observations = listOf(listPage()),
            presenter = presenter,
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 2)),
        )
        val result = fixture.run()

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        // The card fired exactly once, in plain words, without any prior trust.
        assertEquals(listOf("Persistent change on m0.test"), presented)
        // First approval executes with trustedFromTask=false; the second same-(class,
        // site) action rides the per-task trust (vision 06 §2).
        val first = fixture.sink.rows[0].gateEvent
        val second = fixture.sink.rows[1].gateEvent
        assertEquals(LoggedGateDecision.APPROVED, first?.decision)
        assertEquals(LoggedHClass.H9, first?.hclass)
        assertEquals(false, first?.trustedFromTask)
        assertEquals(LoggedGateDecision.APPROVED, second?.decision)
        assertEquals(LoggedHClass.H9, second?.hclass)
        assertEquals(true, second?.trustedFromTask)
        // Both executed; the trigger source is visible in the step log.
        assertEquals(2, fixture.journal.entries.count { it.startsWith("tap(") })
        assertTrue(
            result.stepLog.any { it.contains("H9_PERSISTENT_MUTATION") && it.contains("(trigger: code-net)") },
            "stepLog=${result.stepLog}",
        )
        // The engine was NEVER consulted for the gate: no additive answer is scripted,
        // so any consult would fail the run loudly.
        assertEquals(
            listOf(
                "choose:triage",
                "choose:intent", "scoreBatch:cand-2",
                "choose:intent", "scoreBatch:cand-2",
            ),
            fixture.engineCalls,
        )
    }

    // -- additive engine trigger: non-LOW adds the H9 card ---------------------------------

    @Test
    fun `additive engine check - a non-LOW answer ADDS the H9 card on an unlisted action`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("activate-control", target = "Continue"),
                ScriptedAnswer(question = AdditiveEngineCheck.QUESTION_NAME, pTrue = 0.9),
            ),
            observations = listOf(noticePage()),
            presenter = ScriptedGatePresenter(GateResolution.APPROVED),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 1)),
        )
        val result = fixture.run()

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        // The engine's non-LOW answer ADDED H9: the card fired and the approval executed.
        val gateEvent = fixture.sink.rows[0].gateEvent
        assertEquals(LoggedGateDecision.APPROVED, gateEvent?.decision)
        assertEquals(LoggedHClass.H9, gateEvent?.hclass)
        assertEquals(ActionDisposition.EXECUTED, fixture.sink.rows[0].executedAction.disposition)
        // The consult ran against the §3 question and its outcome + trigger are logged.
        assertEquals(
            listOf("choose:triage", "choose:intent", "scoreBatch:cand-1", "judge:${AdditiveEngineCheck.QUESTION_NAME}"),
            fixture.engineCalls,
        )
        assertTrue(
            result.stepLog.any { it.contains("engine non-LOW: H9 ADDED") },
            "stepLog=${result.stepLog}",
        )
        assertTrue(
            result.stepLog.any { it.contains("(trigger: additive-engine)") },
            "stepLog=${result.stepLog}",
        )
    }

    // -- additive engine trigger: a confident LOW leaves the action ungated -----------------

    @Test
    fun `additive engine check - a confident LOW leaves the suspicious action ungated`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("activate-control", target = "Continue"),
                ScriptedAnswer(question = AdditiveEngineCheck.QUESTION_NAME, pTrue = 0.1),
            ),
            observations = listOf(noticePage()),
            // Any card would be an over-gate AND exhaust the (empty) presenter script
            // loudly — the run must pass with the card never shown.
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 1)),
        )
        val result = fixture.run()

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertNull(fixture.sink.rows[0].gateEvent, "a confident LOW adds no gate")
        assertEquals(ActionDisposition.EXECUTED, fixture.sink.rows[0].executedAction.disposition)
        assertTrue(
            result.stepLog.any { it.contains("engine LOW: no gate") },
            "stepLog=${result.stepLog}",
        )
        // The consult DID happen (and its clear is logged for the audit trail).
        assertTrue(fixture.engineCalls.contains("judge:${AdditiveEngineCheck.QUESTION_NAME}"))
    }

    // -- the engine can never lower/suppress: a code-confident class is never consulted ----

    @Test
    fun `the engine is never consulted for a code-confident H9 - no additive answer is even scripted`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("activate-control", target = "Unsubscribe now"),
            ),
            observations = listOf(listPage()),
            presenter = ScriptedGatePresenter(GateResolution.APPROVED),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 1)),
        )
        val result = fixture.run()

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        // Green = the judge was never asked (an unscripted 'h9-additive-check' consult
        // fails the stub engine loudly): a "harmless" engine answer cannot un-gate the
        // code net because it never gets to speak.
        assertEquals(
            listOf("choose:triage", "choose:intent", "scoreBatch:cand-2"),
            fixture.engineCalls,
        )
        assertEquals(LoggedHClass.H9, fixture.sink.rows[0].gateEvent?.hclass)
        assertTrue(
            result.stepLog.any { it.contains("(trigger: code-net)") },
            "stepLog=${result.stepLog}",
        )
    }

    // -- the additive H9 rides the ordinary gate mechanics: denial path ----------------------

    @Test
    fun `an additive H9 card denied twice aborts the task like any gated class`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("activate-control", target = "Continue"),
                ScriptedAnswer(question = AdditiveEngineCheck.QUESTION_NAME, pTrue = 0.9),
                *intentStep("activate-control", target = "Continue"),
                ScriptedAnswer(question = AdditiveEngineCheck.QUESTION_NAME, pTrue = 0.9),
            ),
            observations = listOf(noticePage()),
            presenter = ScriptedGatePresenter(GateResolution.DENIED, GateResolution.DENIED),
        )
        val result = fixture.run()

        // Same (class, site) refused twice in one task -> clean abort (vision 06 §2),
        // with the additive provenance on every gate event.
        assertEquals(TerminalState.GUARD_ABORT, result.terminal)
        assertEquals(2, fixture.sink.rows.size)
        assertEquals(LoggedHClass.H9, fixture.sink.rows[0].gateEvent?.hclass)
        assertEquals(LoggedGateDecision.DENIED, fixture.sink.rows[0].gateEvent?.decision)
        assertEquals(LoggedGateDecision.DENIED, fixture.sink.rows[1].gateEvent?.decision)
        assertTrue(fixture.executor.calls.isEmpty(), "nothing ever executed")
        assertEquals(2, fixture.engineCalls.count { it == "judge:${AdditiveEngineCheck.QUESTION_NAME}" })
    }

    // -- triage sanity for the fixture classes the loop ran under ---------------------------

    @Test
    fun `the triage row law holds for the H9 scenarios`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "action"),
                *intentStep("activate-control", target = "Unsubscribe now"),
            ),
            observations = listOf(listPage()),
            presenter = ScriptedGatePresenter(GateResolution.APPROVED),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 1)),
        )
        val result = fixture.run()

        assertEquals(TriageClass.ACTION, fixture.sink.rows[0].triageClass)
        assertEquals("action", result.triageClass)
        assertEquals(LoggedHClass.H9, fixture.sink.rows[0].gateEvent?.hclass)
    }
}
