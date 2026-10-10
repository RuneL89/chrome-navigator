package navigator.app.loop

import kotlinx.coroutines.runBlocking
import navigator.core.log.EngineIdentity
import navigator.core.log.TriageClass
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 8G.2 (loop half) — the guard law in the REAL loop (fakes per Gate 6.1):
 * - action-multi: a finish attempt while targets are pending is REFUSED and the refusal
 *   names the pending count; the task achieves only when the tracker is drained;
 *   a gate-refused target counts as SKIPPED (resolved).
 * - action: achieved only on a post-action observation (one executed action), and its
 *   refusal carries NO tracker detail (the default reason bytes are unchanged).
 */
class ActionMultiLoopTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    private suspend fun LoopFixture.run(goal: String): RunResult =
        loop.run(goal, "goal-8G", identity, clock)

    // -- the multi-target sweep: two pending rows, one refusal, then drained ----------

    @Test
    fun `finish with pending targets is refused naming the count - drained then achieves`() = runBlocking {
        // Observation pop order per step: START (pre-decision) + POST (post-step), and
        // the refusal path takes no post-step observation:
        //   step 1 = finish check (obs 0; REFUSED, 2 pending)
        //   step 2 = tap cooking (obs 1 -> post obs 2: the row is gone)
        //   step 3 = tap travel (obs 3 -> post obs 4: every target resolved)
        //   step 4 = finish check (obs 4 repeats; drained -> accepted)
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "action-multi"),
                *intentStep("verify-done"),
                noul(0.99),
                *intentStep("follow-link", target = "Cooking channel row"),
                *intentStep("follow-link", target = "Travel channel row"),
                *intentStep("verify-done"),
                noul(0.99),
            ),
            observations = listOf(
                obs(  // 0: step 1 - two pending targets
                    "http://m0.test/channels",
                    N("Subscriptions"),
                    N("Cooking channel row", clickable = true),
                    N("Travel channel row", clickable = true),
                    N("Footer"),
                    N("Spacer", scrollable = true),
                ),
                obs(  // 1: step 2 - the banner changes the hash after the refusal
                    "http://m0.test/channels",
                    N("Sweep in progress"),
                    N("Cooking channel row", clickable = true),
                    N("Travel channel row", clickable = true),
                    N("Footer"),
                    N("Spacer", scrollable = true),
                ),
                obs(  // 2: step 2's post-observation - the cooking row is gone
                    "http://m0.test/channels",
                    N("Sweep in progress"),
                    N("Travel channel row", clickable = true),
                    N("Footer"),
                    N("Tip: changes can take a day"),
                    N("Spacer", scrollable = true),
                ),
                obs(  // 3: step 3 - the travel row remains
                    "http://m0.test/channels",
                    N("Sweep in progress"),
                    N("Travel channel row", clickable = true),
                    N("Footer"),
                    N("Tip: changes can take a day"),
                    N("Spacer", scrollable = true),
                ),
                obs(  // 4: step 3's post-observation and step 4's finish page
                    "http://m0.test/channels",
                    N("Sweep in progress"),
                    N("All channels swept"),
                    N("Footer"),
                    N("Tip: thanks for staying"),
                    N("Spacer", scrollable = true),
                    title = "Retro Cooking",
                ),
            ),
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.run("apply the sweep to every \"Retro Cooking\" channel row")

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        val exit = assertNotNull(result.exit)
        assertEquals("HIGH", exit.path)
        assertEquals("STRONG", exit.guardLevel, "drained predicate AND the title/URL identity match")
        assertEquals(2, exit.finishChecks, "the refused check + the accepting check")

        // the refusal named the pending count (finish is IMPOSSIBLE while pending, and
        // the refusal says why: the gate law's saying-why requirement, vision 04 §3)
        val refusalLines = result.stepLog.filter { it.contains("verify-done REFUSED") }
        assertEquals(1, refusalLines.size)
        assertTrue(
            refusalLines.single().contains("2 target(s) still pending (drained=false)"),
            "the refusal must name the pending count: ${refusalLines.single()}",
        )

        // the tracker summary rides the run result: 2 tracked, both processed, drained
        val targets = assertNotNull(result.targets)
        assertEquals(2, targets.tracked)
        assertEquals(2, targets.processed)
        assertEquals(0, targets.pending)
        assertEquals(0, targets.skipped)
        assertTrue(targets.drained)

        // the refused finish logged its row with the guard-refused disposition
        val refusedRow = fixture.sink.rows
            .first { it.executedAction.disposition == navigator.core.log.ActionDisposition.REFUSED_BY_GUARD }
        assertEquals(TriageClass.ACTION_MULTI, refusedRow.triageClass)
        assertNull(refusedRow.plan, "the default PlanSource supplies no plan (Gate 8G.3)")
    }

    @Test
    fun `a gate-refused target counts as skipped - skipped does not block draining`() = runBlocking {
        // step 1 = the H9 card is DENIED (the target resolves as SKIPPED);
        // step 2 = the finish check: drained via the skip -> accepted.
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "action-multi"),
                *intentStep("activate-control", target = "Unsubscribe demo channel"),
                *intentStep("verify-done"),
                noul(0.99),
            ),
            observations = listOf(
                obs(  // 0: step 1 - one pending target, the card is denied on it
                    "http://m0.test/channels",
                    N("Subscriptions"),
                    N("Unsubscribe demo channel", clickable = true, button = true),
                    N("Footer"),
                    N("Tip: one target on this page"),
                    N("Spacer", scrollable = true),
                ),
                obs(  // 1: step 1's post-observation and step 2's finish page
                    "http://m0.test/channels",
                    N("Sweep in progress"),
                    N("Unsubscribe demo channel", clickable = true, button = true),
                    N("Footer"),
                    N("Tip: one target on this page"),
                    N("Spacer", scrollable = true),
                    title = "Sweep",
                ),
            ),
            presenter = ScriptedGatePresenter(GateResolution.DENIED),
        )
        val result = fixture.run("apply the sweep to every target channel row")

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        val exit = assertNotNull(result.exit)
        assertEquals("HIGH", exit.path)
        assertEquals("WEAK", exit.guardLevel, "no entity in the goal: WEAK is the ceiling")
        val targets = assertNotNull(result.targets)
        assertEquals(1, targets.tracked)
        assertEquals(0, targets.processed)
        assertEquals(1, targets.skipped, "the owner's denial resolves the target as SKIPPED")
        assertEquals(0, targets.pending)
        assertTrue(targets.drained, "skipped rows count as resolved (drained = none pending)")
        // the skipped step's row carries the gate refusal
        val deniedRow = fixture.sink.rows
            .first { it.executedAction.disposition == navigator.core.log.ActionDisposition.REFUSED_BY_OWNER }
        assertEquals(TriageClass.ACTION_MULTI, deniedRow.triageClass)
    }

    // -- the single-action class: post-action observation is the only WEAK path --------

    @Test
    fun `single-action achieves only after a post-action observation - its refusal has no tracker detail`() = runBlocking {
        // step 1 = finish check before ANY executed action (REFUSED, no tracker detail);
        // step 2 = the action executes (obs 1 -> post obs 2); step 3 = finish accepted.
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "action"),
                *intentStep("verify-done"),
                noul(0.99),
                *intentStep("follow-link", target = "Open details"),
                *intentStep("verify-done"),
                noul(0.99),
            ),
            observations = listOf(
                obs(  // 0: step 1 - nothing executed yet (>= 5 lines: a usable page)
                    "http://m0.test/settings",
                    N("Account"),
                    N("Open details", clickable = true),
                    N("Footer"),
                    N("Hint: opens the plan overview"),
                    N("Spacer", scrollable = true),
                ),
                obs(  // 1: step 2 - still the settings page; the action taps here
                    "http://m0.test/settings",
                    N("Account"),
                    N("Open details", clickable = true),
                    N("Footer"),
                    N("Hint: opens the plan overview"),
                    N("Spacer", scrollable = true),
                ),
                obs(  // 2: step 2's post-observation and step 3's finish page
                    "http://m0.test/details",
                    N("Details"),
                    N("Plan active"),
                    N("Footer"),
                    N("Changed today"),
                    N("Spacer", scrollable = true),
                    title = "Details",
                ),
            ),
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.run("open the plan details and confirm the account change")
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals("HIGH", result.exit?.path)

        val refusalLines = result.stepLog.filter { it.contains("verify-done REFUSED") }
        assertEquals(1, refusalLines.size)
        assertTrue(refusalLines.single().contains("the completion guard failed (NONE)"))
        assertFalse(
            refusalLines.single().contains("pending"),
            "the action class has no tracker detail: ${refusalLines.single()}",
        )
        assertNull(result.targets, "the single-action class runs no target tracker")
    }
}
