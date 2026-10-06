package navigator.app.harness

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The run-report JSON contract (owner-directed 2026-10-06): whenever a run requested
 * the shadow, the per-run report ALWAYS carries the full `shadowReport` object —
 * coverage, companions, latencies — even on degraded paths. The uat-applepie-02 device
 * report shipped with no shadow block at all; this gate makes that impossible to
 * reintroduce silently. Pure JVM.
 */
class RunReportsJsonTest {

    private fun report(shadow: ShadowReport?) = RunReport(
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
        shadow = shadow,
    )

    @Test
    fun `a shadow-on run report always embeds the full shadowReport object`() {
        val json = RunReports.json(
            report(
                ShadowReport(
                    enabled = true,
                    everyNth = 1,
                    decisions = 7,
                    sampled = 6,
                    completed = 6,
                    missed = 5,
                    failed = 0,
                    coverage = 0.8333,
                    forwardLatencyP50Ms = 4896,
                    forwardLatencyP95Ms = 9039,
                    totalLatencyP95Ms = 9500,
                    companionRows = 5,
                    droppedAfterClose = 1,
                    companionRejected = 0,
                ),
            ),
        )
        for (key in listOf(
            "\"shadow\"",
            "everyNth", "decisions", "sampled", "completed", "missed", "failed",
            "coverage", "forwardLatencyP50Ms", "forwardLatencyP95Ms", "totalLatencyP95Ms",
            "companionRows", "droppedAfterClose", "companionRejected",
        )) {
            assertTrue(key in json, "report JSON must carry $key")
        }
        assertFalse("SUPER-SECRET" in json)
    }

    @Test
    fun `the degraded path still embeds the object - zeroed with a note`() {
        val json = RunReports.json(
            report(
                ShadowReport(
                    enabled = true,
                    everyNth = 1,
                    decisions = 0,
                    sampled = 0,
                    completed = 0,
                    missed = 0,
                    failed = 0,
                    coverage = 0.0,
                    forwardLatencyP50Ms = 0,
                    forwardLatencyP95Ms = 0,
                    totalLatencyP95Ms = 0,
                    companionRows = 0,
                    droppedAfterClose = 0,
                    companionRejected = 0,
                    note = "laya session open failed: IllegalStateException: model missing",
                ),
            ),
        )
        assertTrue("\"shadow\"" in json, "degraded shadow-on reports still carry the block")
        assertTrue("laya session open failed" in json, "the note explains the empty counters")
        assertTrue("companionRows" in json)
    }

    @Test
    fun `shadow-off reports omit the shadow key entirely (unchanged shape)`() {
        // The report Json omits defaulted properties (encodeDefaults=false): a
        // shadow-off run has NO shadow key at all — which is exactly what made the
        // uat-applepie-02 report look like it never ran a shadow.
        val json = RunReports.json(report(shadow = null))
        assertFalse("\"shadow\"" in json)
        assertFalse("companionRows" in json)
    }
}
