package navigator.core.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import navigator.core.enumerate.Bucket
import navigator.core.log.serialName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tier A report law (PHASE_07 §2.3): the report is deterministic data — the JSON parses
 * against the declared schema, carries every phase-doc field (fixtures, outcomes,
 * tokenCounts, bucketStats, timestamp) plus the Gate 7.2/7.3 evidence, the markdown lists
 * one row per fixture, and the writers never emit locale-dependent text. Bad reports fail
 * loudly in `init` (core DOX: validation lives in the types).
 */
class EvalReportSchemaTest {

    private val report: EvalReport by lazy {
        EvalHarness.harness.buildReport("2026-10-03T12:00:00+00:00")
    }

    @Test
    fun `json artifact parses and carries the full schema`() {
        val root = Json.parseToJsonElement(report.toJson()).jsonObject
        assertEquals(report.fixtures, root["fixtures"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(report.outcomes.size, root["outcomes"]!!.jsonArray.size)
        assertEquals(report.timestamp, root["timestamp"]!!.jsonPrimitive.content)
        for (field in listOf(
            "tokenCounts", "bucketStats", "rotationMatrix",
            "rotationStable", "selfCheckMutations", "selfCheckAllCaught", "stateFormatVersion",
        )) {
            assertTrue(root.containsKey(field), "report JSON must carry '$field'")
        }
        // outcomes round-trip through the strict configuration (defaults always written)
        val first = root["outcomes"]!!.jsonArray[0].jsonObject
        for (field in listOf(
            "fixture", "chosenNodeId", "gateClass", "stateTokenCount",
            "bucketSizes", "flatSize", "flatTruncated", "truncatedBuckets",
            "stateCompressedBy", "droppedCandidateCount",
            "excerptLineCount", "droppedExcerptLines",
        )) {
            assertTrue(first.containsKey(field), "outcome JSON must carry '$field'")
        }
    }

    @Test
    fun `markdown artifact lists every fixture with its measured row`() {
        val md = report.toMarkdown()
        for (outcome in report.outcomes) {
            val row = md.lineSequence().firstOrNull { it.startsWith("| ${outcome.fixture} |") }
            assertTrue(row != null, "markdown must have a replay row for ${outcome.fixture}")
            assertTrue(
                row!!.contains(outcome.chosenNodeId),
                "${outcome.fixture}: the markdown row must name the chosen node",
            )
            assertTrue(
                row.contains(outcome.gateClass ?: "none"),
                "${outcome.fixture}: the markdown row must show the gate class",
            )
        }
        for (bucket in Bucket.entries) {
            assertTrue(
                md.contains("| ${bucket.serialName} |"),
                "markdown bucket-stats table must list ${bucket.serialName}",
            )
        }
        assertTrue(md.contains("timestamp: ${report.timestamp}"))
    }

    @Test
    fun `bucket stats aggregate the per-fixture shadow-form sizes in taxonomy order`() {
        val expected = Bucket.entries.associateWith { bucket ->
            report.outcomes.sumOf { it.bucketSizes.getValue(bucket.serialName) }
        }.mapKeys { it.key.serialName }
        assertEquals(expected, report.bucketStats)
    }

    @Test
    fun `malformed reports fail loudly in init`() {
        val outcomes = report.outcomes
        assertFailsWith<IllegalArgumentException> {
            report.copy(fixtures = outcomes.map { it.fixture }.shuffled())
        }
        assertFailsWith<IllegalArgumentException> {
            report.copy(outcomes = outcomes.dropLast(1))
        }
        assertFailsWith<IllegalArgumentException> {
            report.copy(bucketStats = report.bucketStats - Bucket.entries.first().serialName)
        }
        assertFailsWith<IllegalArgumentException> {
            report.copy(rotationMatrix = report.rotationMatrix - report.fixtures.first())
        }
    }

    @Test
    fun `token counts mirror the outcomes (the measurement lens)`() {
        assertEquals(
            report.outcomes.associate { it.fixture to it.stateTokenCount },
            report.tokenCounts,
        )
    }
}
