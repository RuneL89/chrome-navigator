package navigator.app.loop

import kotlinx.coroutines.runBlocking
import navigator.app.stub.ScriptedAnswer
import navigator.core.log.EngineIdentity
import navigator.core.log.TriageClass
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 9, phase doc §2.1 (loop half) — the ADDITIVE [LoopUiListener] wiring:
 *  - the compact facts fire AT the LOG step (one per executed navigation step; the
 *    finish path and pre-navigation aborts fire no step line), with the acted-on label
 *    MASKED at source through the same sanctioned-secret redaction fold as the step
 *    log (doc 05 §2 / doc 06 §2a — a post-injection page echo can never reach the
 *    overlay through a label), the primary's logged confidence, and the gate marker
 *    ("—" ungated; class + decision; trust noted);
 *  - the thinking signal PAIRS around every engine consult (the overlay spinner is
 *    always truthful);
 *  - the plan proposal and the terminal state reach the listener;
 *  - the DEFAULT-NULL listener is byte-identical (step log, rows, result) — the whole
 *    existing suite keeps running unchanged.
 *
 * $0 by construction: the engine is the scripted stub; the secret is a dummy fixture.
 */
class NavigationLoopListenerTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    /** Records everything the loop fires (fire-and-forget — it never steers). */
    private class RecordingListener : LoopUiListener {
        val thinking = mutableListOf<Boolean>()
        val plans = mutableListOf<List<String>>()
        val steps = mutableListOf<LoopUiListener.StepFacts>()
        val taskStates = mutableListOf<LoopUiListener.TaskStateUpdate>()

        override fun onThinking(active: Boolean) {
            thinking.add(active)
        }

        override fun onPlan(subgoals: List<String>) {
            plans.add(subgoals)
        }

        override fun onStep(facts: LoopUiListener.StepFacts) {
            steps.add(facts)
        }

        override fun onTaskState(state: LoopUiListener.TaskStateUpdate) {
            taskStates.add(state)
        }
    }

    private class NoopListener : LoopUiListener {
        override fun onThinking(active: Boolean) = Unit
        override fun onPlan(subgoals: List<String>) = Unit
        override fun onStep(facts: LoopUiListener.StepFacts) = Unit
        override fun onTaskState(state: LoopUiListener.TaskStateUpdate) = Unit
    }

    /** A minimal completing lookup task (seed page -> contentful result -> finish). */
    private fun lookupFixture(listener: LoopUiListener?, planSource: PlanSource = PlanSource.None) = LoopFixture(
        script = scriptOf(
            choose("triage", "lookup"),
            chooseByLabel("action", "Result"),
            choose("action", "finish"),
            noul(0.99),
        ),
        observations = listOf(
            obs(
                "http://m0.test/search",
                N("Search page"),
                N("Result", clickable = true),
                N("Footer"),
                N("Ad"),
                N("Spacer", scrollable = true),
            ),
            obs(
                "http://m0.test/article",
                N("Article body"),
                N("Paragraph one"),
                N("Paragraph two"),
                N("Paragraph three"),
                N("Footer"),
                title = "Article",
            ),
        ),
        presenter = ScriptedGatePresenter(),
        planSource = planSource,
        uiListener = listener,
    )

    @Test
    fun `steps stream compact facts at the LOG step - ungated marker and the logged confidence`() = runBlocking {
        val listener = RecordingListener()
        val fixture = lookupFixture(listener)
        val result = fixture.loop.run("find the answer", "ui-listener-plain", identity, fixture.clock)

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        // One step line per EXECUTED navigation step (the tap); the finish path streams
        // no step line (the terminal state is its fact).
        assertEquals(1, listener.steps.size)
        val step = listener.steps.single()
        assertEquals(0, step.stepIndex)
        assertEquals("tap", step.verb)
        assertEquals("Result", step.label)
        assertEquals("—", step.gateMarker, "an ungated step carries the ungated marker")
        // The confidence is the SAME value the row logs (the primary's own answer).
        val row = fixture.sink.rows.first() // step 0's row (the finish row follows it)
        assertEquals(row.teacherAnswer.confidence, step.confidence)
        // The compact line renders the phase-doc shape for this step.
        assertEquals(
            "#0 tap 'Result' · ${"%.2f".format(row.teacherAnswer.confidence)} · gate: —",
            LoopUiListener.compactLine(step),
        )
    }

    @Test
    fun `thinking pairs around every engine consult`() = runBlocking {
        val listener = RecordingListener()
        val fixture = lookupFixture(listener)
        fixture.loop.run("find the answer", "ui-listener-thinking", identity, fixture.clock)

        // triage choose + the tap's action choose + the finish decision's action choose
        // + the finish check's anchored judge = 4 consults, each wrapped in exactly one
        // (true, false) pair.
        val consults = fixture.engineCalls.count { it.startsWith("choose") || it.startsWith("judge") }
        assertEquals(4, consults)
        assertEquals(8, listener.thinking.size, "one true/false pair per engine consult")
        assertEquals(BooleanRange(consults), listener.thinking, "the pairs strictly alternate")
    }

    private fun BooleanRange(consults: Int): List<Boolean> =
        (0 until consults).flatMap { listOf(true, false) }

    @Test
    fun `the plan proposal and the terminal state reach the listener`() = runBlocking {
        val listener = RecordingListener()
        val plan = ProposedPlan("ui-v1", listOf("open the results", "read the answer"))
        val fixture = lookupFixture(listener, planSource = object : PlanSource {
            override fun propose(goal: String, triageClass: TriageClass): ProposedPlan? = plan
        })
        val result = fixture.loop.run("find the answer", "ui-listener-plan", identity, fixture.clock)

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals(listOf(listOf("open the results", "read the answer")), listener.plans)
        assertEquals(1, listener.taskStates.size)
        assertEquals(TerminalState.GOAL_ACHIEVED, listener.taskStates.single().terminal)
        assertTrue(listener.taskStates.single().detail.contains("exit path HIGH"))
    }

    @Test
    fun `the gate marker names the class, the decision, and per-task trust`() = runBlocking {
        val listener = RecordingListener()
        // Two mutation toggles: the first fires the H9 card (approved), the second runs
        // on per-task same-site trust (doc 06 §2) — the markers must say so.
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "action"),
                chooseByLabel("action", "Cooking"),
                chooseByLabel("action", "Trailer"),
                choose("action", "finish"),
                noul(0.99),
            ),
            observations = listOf(
                obs(
                    "http://channel.test/subscriptions",
                    N("Your channel list"),
                    N("Unsubscribe from Cooking Classics", clickable = true),
                    N("Unsubscribe from Trailer Reviews", clickable = true),
                    N("Community notes"),
                    N("Footer"),
                    pageNodes = 6,
                ),
            ),
            presenter = ScriptedGatePresenter(GateResolution.APPROVED),
            uiListener = listener,
        )
        val result = fixture.loop.run("unsubscribe from the cooking and trailer channels", "ui-listener-gate", identity, fixture.clock)

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals(2, listener.steps.size)
        assertEquals("H9 approved", listener.steps[0].gateMarker)
        assertEquals("H9 approved (trusted)", listener.steps[1].gateMarker)
        assertEquals(listOf("Unsubscribe from Cooking Classics", "Unsubscribe from Trailer Reviews"), listener.steps.map { it.label })
    }

    @Test
    fun `an echoed card secret never reaches a step line - the label masks at source`() = runBlocking {
        val listener = RecordingListener()
        val secret = "sesame-echo-9"
        val flaggedPage = obs(
            "http://fixture.test/flagged.html",
            N("Flagged fields (TEST ONLY)"),
            N("TEST FIXTURE. Two fields, one reader-flagged."),
            N("Field one", editable = true),
            N("Field two", editable = true, password = true),
            N("Go", clickable = true, button = true),
            N("End of the fixture page."),
            pageNodes = 7,
            title = "Flagged fields (TEST ONLY)",
        )
        // After the injection the flagged field's a11y text IS the typed value (the
        // live Chrome echo shape) — the SECOND step acts on that echoed label.
        val echoingPage = obs(
            "http://fixture.test/flagged.html",
            N("Flagged fields (TEST ONLY)"),
            N("TEST FIXTURE. Two fields, one reader-flagged."),
            N("Field one", editable = true),
            N(secret, editable = true, password = true),
            N("Go", clickable = true, button = true),
            N("End of the fixture page."),
            pageNodes = 7,
            title = "Flagged fields (TEST ONLY)",
        )
        val script = mutableListOf<ScriptedAnswer>(
            choose("triage", "form-fill"),
            chooseByLabel("action", "Field two"),
            // The echo is MASKED in the question payload too (the PREUAT2-4 law), so the
            // stub selects the echoed field by its node id — the label the loop acts on
            // (and the listener receives, pre-mask) is still the raw page text.
            choose("action", "n3"),
            // The form-fill guard needs an observed SUBMISSION before the finish is
            // lawfully verifiable; the submit of a flagged-secret form gates H3 (a
            // PLAIN card — the secret rides the already-injected field, not the submit).
            choose("action", "n4"),
            choose("action", "finish"),
            noul(0.99),
        )
        val fixture = LoopFixture(
            script = script,
            observations = listOf(flaggedPage, echoingPage),
            presenter = ScriptedGatePresenter(
                GateResolution.ApproveWithSecret(secret),
                GateResolution.ApproveWithSecret(secret),
                GateResolution.APPROVED,
            ),
            uiListener = listener,
        )
        val result = fixture.loop.run("fill the flagged fixture form", "ui-listener-mask", identity, fixture.clock)

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals(3, listener.steps.size)
        assertEquals("Field two", listener.steps[0].label)
        assertEquals("H8 approved", listener.steps[0].gateMarker)
        // The echo masks AT SOURCE — the fact never carries the raw value.
        assertEquals("[credential-redacted]", listener.steps[1].label)
        assertFalse(listener.steps[1].label.contains(secret))
        assertEquals("H3 approved", listener.steps[2].gateMarker)
        assertFalse(
            listener.steps.joinToString(" ") { LoopUiListener.compactLine(it) }.contains(secret),
            "the raw secret appears nowhere in any compact step line",
        )
    }

    @Test
    fun `the default-null listener keeps the run byte-identical`() = runBlocking {
        val silent = lookupFixture(null)
        val noop = lookupFixture(NoopListener())
        val recording = lookupFixture(RecordingListener())

        val a = silent.loop.run("find the answer", "ui-null-identity", identity, silent.clock)
        val b = noop.loop.run("find the answer", "ui-null-identity", identity, noop.clock)
        val c = recording.loop.run("find the answer", "ui-null-identity", identity, recording.clock)

        // Listener wiring changes NOTHING observable in the loop's own outputs (the
        // fake clock is static, so timestamps and task ids coincide across the runs).
        assertEquals(a, b, "a no-op listener changes no loop output")
        assertEquals(a, c, "a recording listener changes no loop output")
        assertEquals(silent.sink.rows, recording.sink.rows, "the rows are identical")
        assertEquals(silent.sink.exitChecks, recording.sink.exitChecks, "the exit-check rows are identical")
    }
}
