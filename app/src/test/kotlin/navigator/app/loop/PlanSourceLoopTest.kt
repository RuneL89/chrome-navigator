package navigator.app.loop

import kotlinx.coroutines.runBlocking
import navigator.core.log.EngineIdentity
import navigator.core.log.TriageClass
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 8G.3 (loop half) — the PlanSource seam (vision 04 §1 plan-proposal law): the
 * loop consults it ONCE at triage; the deterministic default supplies NO plan (the
 * default path is byte-identical behavior: no step-log line, every row plan=null); a
 * supplied plan rides every decision row as provenance — context, never executor
 * (ART-2: it never touches the question, the candidates, or the gate).
 */
class PlanSourceLoopTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    /** A minimal completing lookup task (seed page -> contentful result -> finish). */
    private fun fixture(planSource: PlanSource = PlanSource.None) = LoopFixture(
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
    )

    @Test
    fun `the deterministic default supplies no plan - the default path is byte-identical`() = runBlocking {
        val fixture = fixture()
        val result = fixture.loop.run("find the answer", "plan-default", identity, fixture.clock)

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertNull(result.plan)
        assertTrue(
            fixture.sink.rows.isNotEmpty() && fixture.sink.rows.all { it.plan == null },
            "every row of an unplanned task carries plan=null",
        )
        assertTrue(
            result.stepLog.none { it.contains("plan:") },
            "the default path adds no plan provenance line",
        )
    }

    @Test
    fun `an explicit PlanSource_None behaves exactly like the default`() = runBlocking {
        val implicit = fixture()
        val explicit = fixture(PlanSource.None)
        val a = implicit.loop.run("find the answer", "plan-a", identity, implicit.clock)
        val b = explicit.loop.run("find the answer", "plan-b", identity, explicit.clock)
        assertEquals(a.terminal, b.terminal)
        assertEquals(a.stepLog, b.stepLog, "explicit None: identical step logs")
        assertEquals(
            implicit.sink.rows.map { it.stateText },
            explicit.sink.rows.map { it.stateText },
            "explicit None: identical state texts (no plan ever reached the state)",
        )
        assertEquals(
            implicit.sink.rows.map { it.plan },
            explicit.sink.rows.map { it.plan },
        )
    }

    @Test
    fun `a proposed plan rides every row as provenance - consulted once at triage`() = runBlocking {
        var consultations = 0
        val plan = ProposedPlan("test-plan-v1", listOf("open the results", "read the answer"))
        val fixture = fixture(
            object : PlanSource {
                override fun propose(goal: String, triageClass: TriageClass): ProposedPlan? {
                    consultations += 1
                    assertEquals(TriageClass.LOOKUP, triageClass)
                    return plan
                }
            },
        )
        val result = fixture.loop.run("find the answer", "plan-provenance", identity, fixture.clock)

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals(1, consultations, "the plan is proposed ONCE, at triage")
        assertEquals(plan, result.plan)
        assertTrue(
            fixture.sink.rows.isNotEmpty() && fixture.sink.rows.all { it.plan != null },
            "the plan provenance rides EVERY decision row (vision 05 §2)",
        )
        fixture.sink.rows.forEach { row ->
            assertEquals("test-plan-v1", row.plan?.version)
            assertEquals(listOf("open the results", "read the answer"), row.plan?.subgoals)
        }
        assertTrue(
            result.stepLog.any { it.contains("plan: proposed vtest-plan-v1 with 2 subgoal(s)") },
            "the provenance is logged at triage",
        )
    }
}
