package navigator.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Gate 8B.1 (model half, PHASE_08B §3) — the companion shadow-result row (vision 05 §2,
 * amendment 2026-10-04): canonical-JSON round-trip with no field loss, and strict
 * validation in `init` (blank identity, non-positive timestamps, negative latency,
 * incoherent answer stages all fail loudly). Persistence law (duplicate-key ABORT,
 * guard, migration) is exercised in `:app`.
 */
class ShadowResultRowTest {

    private val shadowRef = ShadowRef(checkpoint = "laya", precision = ShadowPrecision.INT8)
    private val answer = LogRowFixtures.agreeingShadowAnswer

    private fun row(
        rowId: String = ShadowResultRow.rowIdFor("task-apple-pie", 7),
        taskId: String = "task-apple-pie",
        stepIndex: Int = 7,
        timestamp: Long = 1_760_200_000_100L,
        latencyMs: Long = 5_900,
        shadowAnswer: ShadowAnswer = answer,
    ): ShadowResultRow = ShadowResultRow(
        rowId = rowId,
        taskId = taskId,
        stepIndex = stepIndex,
        timestamp = timestamp,
        shadow = shadowRef,
        shadowAnswer = shadowAnswer,
        latencyMs = latencyMs,
    )

    // ---- round-trip ----

    @Test
    fun `companion row round-trips through the canonical JSON with no field loss`() {
        val json = DECISION_LOG_JSON.encodeToString(ShadowResultRow.serializer(), row())
        val decoded = DECISION_LOG_JSON.decodeFromString(ShadowResultRow.serializer(), json)
        assertEquals(row(), decoded)
        // schema-complete: every declared field is always written (DECISION_LOG_JSON law)
        for (field in listOf("rowId", "taskId", "stepIndex", "timestamp", "shadow", "shadowAnswer", "latencyMs")) {
            assertTrue("\"$field\"" in json, "$field must always be encoded")
        }
        assertTrue("\"checkpoint\"" in json && "\"precision\"" in json, "the shadow ref must survive")
        assertTrue("\"bucket\"" in json && "\"element\"" in json, "both answer stages must survive")
    }

    @Test
    fun `companion fields carry the full answer - checkpoint, precision, distributions, latency`() {
        val companion = row()
        assertEquals("laya", companion.shadow.checkpoint)
        assertEquals(ShadowPrecision.INT8, companion.shadow.precision)
        assertEquals(LogRowFixtures.agreeingShadowAnswer.element.chosen, companion.shadowAnswer.element.chosen)
        assertEquals(LogRowFixtures.agreeingShadowAnswer.element.probabilities, companion.shadowAnswer.element.probabilities)
        assertEquals(5_900, companion.latencyMs)
    }

    // ---- strict validation ----

    @Test
    fun `blank rowId and taskId fail loudly`() {
        assertFailsWith<IllegalArgumentException> { row(rowId = " ") }
        assertFailsWith<IllegalArgumentException> { row(taskId = "") }
    }

    @Test
    fun `negative stepIndex and non-positive timestamp fail loudly`() {
        assertFailsWith<IllegalArgumentException> { row(stepIndex = -1) }
        assertFailsWith<IllegalArgumentException> { row(timestamp = 0) }
        assertFailsWith<IllegalArgumentException> { row(timestamp = -5) }
    }

    @Test
    fun `negative latency fails loudly`() {
        assertFailsWith<IllegalArgumentException> { row(latencyMs = -1) }
    }

    @Test
    fun `an answer stage whose chosen is missing from its distribution fails loudly`() {
        // the stage types themselves reject it at construction (init validation, core log law)
        assertFailsWith<IllegalArgumentException> {
            row(shadowAnswer = ShadowAnswer(
                bucket = ShadowStageAnswer("nav-links", mapOf("nav-links" to 1.0)),
                element = ShadowStageAnswer("open-first-result", mapOf("open-second-result" to 1.0)),
            ))
        }
    }

    // ---- key identity ----

    @Test
    fun `the derived rowId is deterministic per key and unique across keys`() {
        assertEquals(ShadowResultRow.rowIdFor("t", 3), ShadowResultRow.rowIdFor("t", 3))
        assertNotEquals(ShadowResultRow.rowIdFor("t", 3), ShadowResultRow.rowIdFor("t", 4))
        assertNotEquals(ShadowResultRow.rowIdFor("t", 3), ShadowResultRow.rowIdFor("u", 3))
    }
}
