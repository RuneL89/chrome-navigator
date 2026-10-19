package navigator.app.harness

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The run-report JSON contract (pure JVM). PHASE_VEXP3: the shadow/escalations evidence
 * blocks are retired with the training program (owner directive 2026-10-19) — the
 * retired-block gates below pin their ABSENCE so the keys can never silently return.
 */
class RunReportsJsonTest {

    private fun report() = RunReport(
        goalId = "uat-applepie-02",
        family = "custom",
        goal = "find a highly rated apple pie recipe and open it",
        taskId = "task-123",
        engine = "live",
        autoDeny = true,
        triage = "lookup",
        terminal = "STUCK_KILL",
        terminalDetail = "still stuck after hint and strategy switch - L3 auto-kill",
        expectedTerminal = "GOAL_ACHIEVED",
        terminalMatchesExpected = false,
        steps = 6,
        rowsLogged = 5,
        metaDecisions = 1,
        logGaps = 0,
        gateEvents = 0,
        spendMicroUsd = 36054,
        elapsedMs = 36_250,
        softWarnings = emptyList(),
        partialResult = "",
        startedAt = 1,
        finishedAt = 2,
        stepLog = emptyList(),
    )

    @Test
    fun `the retired training blocks never ride the report JSON`() {
        val json = RunReports.json(report())
        assertFalse("\"shadow\"" in json, "the shadow block is retired (PHASE_VEXP3)")
        assertFalse("companionRows" in json)
        assertFalse("\"escalations\"" in json, "the fallback-composition block is retired")
    }

    // -- Phase 08G: the tracker summary and the plan provenance blocks ------------------

    @Test
    fun `an action-multi run report carries the tracker summary and plan provenance`() {
        val json = RunReports.json(
            report().copy(
                targets = TargetTrackerReport(
                    tracked = 3,
                    processed = 2,
                    pending = 0,
                    skipped = 1,
                    drained = true,
                ),
                plan = PlanReport("plan-prompt-v1", listOf("open the list", "sweep each row")),
            ),
        )
        for (key in listOf("\"targets\"", "\"plan\"", "tracked", "processed", "pending", "skipped", "drained", "subgoals", "plan-prompt-v1")) {
            assertTrue(key in json, "report JSON must carry $key")
        }
    }

    @Test
    fun `lookup and unplanned runs omit the targets and plan keys (additive only)`() {
        val json = RunReports.json(report())
        assertFalse("\"targets\"" in json)
        assertFalse("\"plan\"" in json)
    }
}
