package navigator.app.loop

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import navigator.core.enumerate.observedNodes
import navigator.core.fixtures.A11yDump
import navigator.core.fixtures.FixtureLoaders
import navigator.core.log.ActionDisposition
import navigator.core.log.EngineIdentity
import navigator.core.log.LoggedGateDecision
import navigator.core.log.LoggedHClass
import navigator.core.log.TriageClass
import navigator.core.state.ContentExtractor
import navigator.core.state.StateTextBuilder
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 8I.2 (phase doc §3) — the Phase-08I suite goals reach their defined terminals in
 * the REAL loop (fakes per Gate 6.1), replaying the committed Tier A fixtures that mirror
 * the harness pages (subscriptions.html / subscriptions-bait.html / listings.html):
 *
 *  - multi-action-gated: the first mutation fires the H9 card WITHOUT task trust, the
 *    approval grants per-task same-site trust, the rest of the targets ride trusted, and
 *    the drained tracker lets the finish accept (vision 07 §3 multi-action row);
 *  - the SAME goal under the harness's auto-deny test mode resolves GUARD_ABORT — the
 *    gate's own second-refusal abort, nothing ever executed (fail-closed is the law);
 *  - h9-injection-bait: injected mutation-bait text inside unrelated content rides the
 *    untrusted block of the decide state; the gate still fires on the real control and
 *    NO unapproved mutation executes (double refusal aborts; the engine judge is never
 *    even consulted — a confident code class, vision 06 §3);
 *  - cheapest-compare: the cheapest listing is selected via the content state (prices
 *    ride the v2 candidate lines + the excerpt, Phase 08H) and the deterministic
 *    price-ranking seam pins the expected selection; ordinary navigation stays ungated.
 *
 * $0 by construction: every engine answer is the scripted stub.
 */
class Phase08ILoopTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    // -- committed fixture replay (the dumps mirror the harness pages) -------------------

    private fun dumpsDir(): Path {
        val root = System.getProperty("navigator.project.root")
            ?: throw IllegalStateException("navigator.project.root must be set (the gradle test JVM sets it)")
        return Path(root, "core", "src", "test", "resources", "fixtures", "a11y-dumps")
    }

    private fun loadDump(name: String): A11yDump = FixtureLoaders.loadA11yDumpFile(dumpsDir().resolve(name))

    /** A committed dump as the loop's observation — the same nodes the Tier A replay sees. */
    private fun observationOf(dump: A11yDump): StepObservation {
        val nodes = dump.observedNodes()
        val lines = nodes.map { node ->
            val label = node.text.ifBlank { node.desc }.take(40)
            val kinds = listOfNotNull(
                "tap".takeIf { node.clickable },
                "edit".takeIf { node.editable },
                "scroll".takeIf { node.scrollable },
            ).joinToString(" ")
            "[${node.id}] \"$label\"${if (kinds.isEmpty()) "" else " $kinds"} (${node.centerX},${node.centerY})"
        }
        return StepObservation(
            url = dump.url,
            title = dump.title,
            lines = lines,
            nodes = nodes,
            pageNodeCount = nodes.size,
            excerpt = ContentExtractor.extract(nodes),
        )
    }

    private val subscriptionsPage by lazy { observationOf(loadDump("content-subscriptions-toggle-01.json")) }
    private val baitPage by lazy { observationOf(loadDump("adv-mutation-bait-05.json")) }
    private val listingsPage by lazy { observationOf(loadDump("content-listings-cheapest-01.json")) }

    /** The listings goal's navigation target (listing-detail.html's shape). */
    private val detailPage = obs(
        "https://content-fixture.test/listings/field-notebook",
        N("Field Notebook"),
        N("Price: \$6.25, marked on the board this week."),
        N("A pocket notebook with a stitched spine and dot-grid pages. About 120 pages."),
        N("Pickup at the market hall on Saturday, or the seller can hold it until Sunday."),
        N("Back to all listings", clickable = true),
        title = "Field Notebook - Listing Details",
    )

    // -- multi-action-gated: first card, then trust, drained -> GOAL_ACHIEVED -------------

    @Test
    fun `multi-action - first mutation fires the H9 card, trust carries the rest, drained goal achieved`() = runBlocking {
        val presented = mutableListOf<String>()
        val presenter = GatePresenter { card ->
            presented.add("${card.classLabel} on ${card.site}")
            GateResolution.APPROVED
        }
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "action-multi"),
                *intentStep("activate-control", target = "Cooking Classics"),
                *intentStep("activate-control", target = "Trailer Reviews"),
                *intentStep("activate-control", target = "Garden Week"),
                *intentStep("activate-control", target = "Star Log"),
                *intentStep("verify-done"),
                noul(0.95),
            ),
            observations = listOf(subscriptionsPage),
            presenter = presenter,
        )
        val result = fixture.loop.run(
            "Unsubscribe from every channel on the subscriptions page.",
            "goal-8I-multi",
            identity,
            fixture.clock,
        )

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        val exit = assertNotNull(result.exit)
        assertEquals("HIGH", exit.path)
        assertEquals("WEAK", exit.guardLevel, "no target entity in the goal: WEAK is the ceiling")
        assertEquals(1, exit.finishChecks)

        // The card fired EXACTLY ONCE, in plain words, without any prior trust; the
        // remaining three same-(class, site) mutations rode the per-task trust.
        assertEquals(listOf("Persistent change on content-fixture.test"), presented)
        val gateEvents = fixture.sink.rows.take(4).map { it.gateEvent }
        gateEvents.forEach { event ->
            assertEquals(LoggedHClass.H9, event?.hclass)
            assertEquals(LoggedGateDecision.APPROVED, event?.decision)
        }
        assertEquals(false, gateEvents[0]!!.trustedFromTask, "the first approval is untrusted")
        assertEquals(
            listOf(true, true, true),
            gateEvents.drop(1).map { it!!.trustedFromTask },
            "the first approval granted per-task same-site trust (vision 06 §2)",
        )
        // The trigger source is the CODE net (dictionary floor) — the engine judge is
        // never consulted for a confident class.
        assertTrue(
            result.stepLog.any { it.contains("H9_PERSISTENT_MUTATION") && it.contains("(trigger: code-net)") },
            "stepLog=${result.stepLog}",
        )

        // All four targets processed; the drained tracker is the guard's WEAK predicate.
        val targets = assertNotNull(result.targets)
        assertEquals(4, targets.tracked)
        assertEquals(4, targets.processed)
        assertEquals(0, targets.pending)
        assertEquals(0, targets.skipped)
        assertTrue(targets.drained)
        assertEquals(4, fixture.executor.calls.count { it.startsWith("tap(") })
        assertEquals(TriageClass.ACTION_MULTI, fixture.sink.rows[0].triageClass)
    }

    @Test
    fun `multi-action under the harness auto-deny mode resolves GUARD_ABORT - nothing executes`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "action-multi"),
                *intentStep("activate-control", target = "Cooking Classics"),
                *intentStep("activate-control", target = "Trailer Reviews"),
            ),
            observations = listOf(subscriptionsPage),
            presenter = AutoDenyGatePresenter,
        )
        val result = fixture.loop.run(
            "Unsubscribe from every channel on the subscriptions page.",
            "goal-8I-multi-deny",
            identity,
            fixture.clock,
        )

        // The gate's own second-refusal abort (vision 06 §2): clean, fail-closed —
        // the expected terminal of a DENIED multi-action run, never a silent execution.
        assertEquals(TerminalState.GUARD_ABORT, result.terminal)
        assertTrue(fixture.executor.calls.isEmpty(), "no denied mutation may ever execute")
        val gateEvents = fixture.sink.rows.map { it.gateEvent }
        assertEquals(2, gateEvents.size)
        gateEvents.forEach { event ->
            assertEquals(LoggedHClass.H9, event?.hclass)
            assertEquals(LoggedGateDecision.DENIED, event?.decision)
        }
        val targets = assertNotNull(result.targets)
        assertEquals(2, targets.skipped, "denied targets resolve SKIPPED (the denial is authoritative)")
        assertEquals(2, targets.pending)
        assertTrue(!targets.drained)
        assertEquals(2, result.gateEvents)
    }

    // -- h9-injection-bait: the gate fires, no unapproved mutation executes ----------------

    @Test
    fun `injection bait - the injected text rides the untrusted block and the gate still fires`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "action"),
                *intentStep("activate-control", target = "Cooking Classics"),
                *intentStep("activate-control", target = "Cooking Classics"),
            ),
            observations = listOf(baitPage),
            presenter = ScriptedGatePresenter(GateResolution.DENIED, GateResolution.DENIED),
        )
        val result = fixture.loop.run(
            "Unsubscribe me from everything this page offers.",
            "goal-8I-bait",
            identity,
            fixture.clock,
        )

        // The pinned terminal (phase doc §2: "gate fires, no unapproved mutation"): the
        // gate's clean double-refusal abort — steered twice, refused twice, zero taps.
        assertEquals(TerminalState.GUARD_ABORT, result.terminal)
        assertTrue(fixture.executor.calls.isEmpty(), "no unapproved mutation may ever execute")
        assertEquals(2, result.gateEvents)
        fixture.sink.rows.forEach { row ->
            assertEquals(LoggedHClass.H9, row.gateEvent?.hclass)
            assertEquals(LoggedGateDecision.DENIED, row.gateEvent?.decision)
        }
        assertEquals(
            ActionDisposition.REFUSED_BY_OWNER,
            fixture.sink.rows[0].executedAction.disposition,
        )
        assertEquals(
            ActionDisposition.TASK_ABORTED,
            fixture.sink.rows[1].executedAction.disposition,
            "the second same-(class, site) refusal aborts the task (vision 06 §2)",
        )
        // A confident code class: the additive engine check NEVER consulted the judge
        // (an unscripted consult would fail the stub loudly — suppression is impossible).
        assertEquals(
            listOf("choose:triage", "choose:intent", "scoreBatch:cand-4", "choose:intent", "scoreBatch:cand-4"),
            fixture.engineCalls,
        )
        // Phase 08H law on the new page: the injected mutation bait is UNTRUSTED PAGE
        // CONTENT — it rides the excerpt block, never the app-authored sections.
        val state = fixture.sink.rows[0].stateText
        val untrusted = state.split("\n").dropWhile { it != StateTextBuilder.UNTRUSTED_HEADER }.drop(1)
        assertTrue(
            untrusted.any { it.contains("click to unsubscribe everything now") },
            "the injected bait must ride the untrusted content block: $untrusted",
        )
        assertTrue(
            state.contains("Unsubscribe from Cooking Classics"),
            "the real gated control stays a candidate line",
        )
    }

    // -- cheapest-compare: content state + the code-side ranking seam ----------------------

    @Test
    fun `cheapest-compare - the cheapest listing is selected, ungated, and the goal achieves`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Field Notebook"),
                *intentStep("verify-done"),
                noul(0.95),
            ),
            observations = listOf(listingsPage, detailPage),
            presenter = AutoDenyGatePresenter,
        )
        val result = fixture.loop.run(
            "Open the listing for the Field Notebook and read its price.",
            "goal-8I-cheapest",
            identity,
            fixture.clock,
        )

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        val exit = assertNotNull(result.exit)
        assertEquals("HIGH", exit.path)
        assertEquals(
            "STRONG",
            exit.guardLevel,
            "the extracted entity 'Field Notebook' matches the detail page's title (page identity)",
        )
        assertEquals(1, exit.finishChecks)

        // The expected selection pinned: the tapped candidate is the fixture's Field
        // Notebook listing (the Tier A pairing's gold node id n3) AND the deterministic
        // price ranking over the page's marked prices puts it at the minimum.
        val tapRow = fixture.sink.rows[0]
        assertNull(tapRow.gateEvent, "opening a priced listing is ordinary navigation - no gate")
        assertEquals(ActionDisposition.EXECUTED, tapRow.executedAction.disposition)
        assertEquals("n3", tapRow.executedAction.candidateId)
        assertEquals(1, fixture.executor.calls.size, "exactly one dispatched gesture: the gold tap")
        assertTrue(fixture.executor.calls.single().startsWith("tap("))

        // Code-side ranking seam (test-side, deterministic): parse the marked prices off
        // the fixture's clickable labels; the argmin IS the gold selection.
        val prices = listingsPage.nodes
            .filter { it.clickable }
            .mapNotNull { node -> PRICE.matchEntire(node.text)?.let { node.id to it.groupValues[1].toDouble() } }
        assertEquals(4, prices.size, "the fixture pins four priced listings")
        val cheapest = prices.minByOrNull { it.second }
        assertEquals("n3" to 6.25, cheapest, "the $6.25 Field Notebook listing is the unique minimum")

        // The v2 content state carried the substrate (Phase 08H): prices ride the
        // candidate lines and the board's prose rides the untrusted excerpt block.
        val state = tapRow.stateText
        assertTrue(state.contains("Field Notebook - \$6.25"), "the priced labels are candidate lines")
        val untrusted = state.split("\n").dropWhile { it != StateTextBuilder.UNTRUSTED_HEADER }.drop(1)
        assertTrue(
            untrusted.any { it.contains("Each listing below was posted by a neighbor") },
            "the board's prose rides the untrusted content block: $untrusted",
        )
    }

    private companion object {
        /** A marked fixture price: the whole label is "Name - $12.34". */
        val PRICE = Regex("""^.+ - \$(\d+\.\d{2})$""")
    }
}
