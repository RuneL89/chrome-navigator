package navigator.core.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate 7.1 (part 2): replay determinism (isolation). Two consecutive full runs of the
 * whole harness produce byte-identical report artifacts (both writers), and the committed
 * `.state/eval/` artifacts stay byte-identical to a fresh regeneration from the same
 * pinned timestamp — the drift detector that makes a state-format, wire, taxonomy, or
 * gate change visible the moment it lands.
 */
class ReplayDeterminismTest {

    private fun fullRun(timestamp: String): Pair<String, String> {
        val report = EvalHarness.harness.buildReport(timestamp)
        return report.toJson() to report.toMarkdown()
    }

    @Test
    fun `two consecutive full runs produce byte-identical reports`() {
        val timestamp = TierAHarness.defaultTimestamp()
        val (json1, md1) = fullRun(timestamp)
        val (json2, md2) = fullRun(timestamp)
        assertEquals(json1, json2, "JSON writer is not deterministic across consecutive runs")
        assertEquals(md1, md2, "markdown writer is not deterministic across consecutive runs")
    }

    @Test
    fun `committed artifacts match a fresh regeneration (drift detector)`() {
        val committedJson = EvalHarness.committedReport(EvalReport.JSON_FILE)
        val committedMd = EvalHarness.committedReport(EvalReport.MARKDOWN_FILE)
        // the run timestamp is pinned IN the committed artifact — regenerate with it
        val pinned = TIMESTAMP_REGEX.find(committedJson)?.groupValues?.get(1)
            ?: throw IllegalStateException(
                "committed ${EvalReport.JSON_FILE} has no readable \"timestamp\" field",
            )
        val (freshJson, freshMd) = fullRun(pinned)
        assertEquals(committedJson, freshJson, "committed JSON artifact drifted from the harness output")
        assertEquals(committedMd, freshMd, "committed markdown artifact drifted from the harness output")
    }

    @Test
    fun `report schema covers every fixture with sorted, aligned collections`() {
        val report = EvalHarness.harness.buildReport(TierAHarness.defaultTimestamp())
        val names = EvalHarness.dumps.map { it.first }
        assertEquals(names, report.fixtures, "report fixtures must be the committed set, sorted")
        assertEquals(names, report.outcomes.map { it.fixture }, "outcomes must align with fixtures")
        assertEquals(names, report.tokenCounts.keys.toList(), "tokenCounts must align with fixtures")
        assertEquals(names, report.rotationMatrix.keys.toList(), "rotationMatrix must align with fixtures")
        assertEquals(names.sorted(), report.fixtures, "fixtures must be sorted (deterministic writers)")
        assertTrue(report.rotationStable, "Gate 7.3 must be green for a report to be written")
        assertTrue(report.selfCheckAllCaught, "Gate 7.2 must be green for a report to be written")
    }

    private companion object {
        val TIMESTAMP_REGEX = Regex("\"timestamp\": \"([^\"]+)\"")
    }
}
