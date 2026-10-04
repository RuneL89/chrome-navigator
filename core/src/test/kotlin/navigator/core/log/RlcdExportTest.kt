package navigator.core.log

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import navigator.core.enumerate.Bucket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gate 5.2 — export correctness (vision 05 §4): each exported case is the upstream
 * `docs/finetune.md` row shape — `state` verbatim, `questions` the **bucketed shadow
 * form**, `gold` the **teacher probability distribution** (never a bare label) — and
 * every line loads through a strict validator written from that shape. The manifest
 * flags mixed-teacher corpora (two teacher pins present, vision 02 §5).
 */
class RlcdExportTest {

    private val exportedAt = 1_760_300_000_000L
    private val exporter = RlcdExporter(now = { exportedAt })

    /** STRICT decoder of the exported JSONL (unknown fields are errors, vision 05 §4). */
    private val strict = Json { ignoreUnknownKeys = false }

    // ---- the finetune.md row shape, written from vision 05 §4 (the validator under test) ----

    @Serializable
    private data class FinetuneRow(
        val state: String,
        val stateFormatVersion: Int,
        val questions: FinetuneQuestions,
        val gold: Map<String, Double>,
    )

    @Serializable
    private data class FinetuneQuestions(
        val name: String,
        val buckets: Map<String, List<FinetuneOption>>,
    )

    @Serializable
    private data class FinetuneOption(
        val id: String,
        val label: String,
        val description: String,
    )

    /** Strict load + shape law of one exported JSONL line. */
    private fun validateCase(line: String, source: DecisionLogRow): FinetuneRow {
        val row = strict.decodeFromString(FinetuneRow.serializer(), line)
        // state is the canonical state text, verbatim — the export never rewrites it
        assertEquals(source.stateText, row.state, "export must not rewrite state text")
        assertEquals(source.stateFormatVersion, row.stateFormatVersion)
        // questions are the bucketed shadow form: known buckets, ≤20 options each
        assertEquals(source.questionShadow.name, row.questions.name)
        val validBuckets = Bucket.entries.map { it.serialName }.toSet()
        for ((bucket, options) in row.questions.buckets) {
            assertTrue(bucket in validBuckets, "unknown bucket '$bucket' in exported questions")
            assertTrue(options.isNotEmpty() && options.size <= 20, "bucket $bucket outside 1..20 options")
            val ids = options.map { it.id }
            assertEquals(ids.size, ids.toSet().size, "duplicate option ids in bucket $bucket")
        }
        // gold is the teacher's distribution over the question's option ids — normalized
        val optionIds = row.questions.buckets.values.flatten().map { it.id }.toSet()
        assertTrue(row.gold.isNotEmpty(), "gold must be a distribution, not empty")
        val foreign = row.gold.keys - optionIds
        assertTrue(foreign.isEmpty(), "gold keys outside the bucketed option ids: $foreign")
        val sum = row.gold.values.sum()
        assertTrue(kotlin.math.abs(sum - 1.0) < 1e-6, "gold must be normalized, Σp = $sum")
        return row
    }

    private fun exportLines(rows: List<DecisionLogRow>): List<String> {
        val jsonl = exporter.export(rows)
        if (rows.isEmpty()) return emptyList()
        assertFalse(jsonl.endsWith("\n"), "no trailing newline")
        val lines = jsonl.split("\n")
        assertEquals(rows.size, lines.size, "one case per row")
        return lines
    }

    // ---- export shape ----

    @Test
    fun `every exported line loads with the strict finetune validator`() {
        val rows = listOf(LogRowFixtures.complete(), LogRowFixtures.shadowMiss())
        val lines = exportLines(rows)
        for ((line, row) in lines.zip(rows)) validateCase(line, row)
    }

    @Test
    fun `gold is the full teacher distribution not a bare label`() {
        val row = LogRowFixtures.complete()
        val line = exportLines(listOf(row)).single()
        val parsed = validateCase(line, row)
        assertEquals(row.teacherAnswer.probabilities, parsed.gold, "gold = teacher distribution verbatim")
        assertTrue(parsed.gold.size > 1, "a distribution has more than one option: ${parsed.gold}")
        // no chosen/label fields anywhere in the case — distribution, not bare label
        assertFalse("\"chosen\"" in line)
        assertFalse("\"goldLabel\"" in line)
    }

    @Test
    fun `questions are the bucketed shadow form`() {
        val row = LogRowFixtures.complete()
        val line = exportLines(listOf(row)).single()
        val parsed = validateCase(line, row)
        assertEquals("next-action-bucketed", parsed.questions.name)
        assertEquals(
            setOf("form-fields", "nav-links", "buttons"),
            parsed.questions.buckets.keys,
            "buckets keyed by the shadow's training vocabulary (vision 03 §3)",
        )
        assertFalse(
            "\"next-action\"" in line,
            "the flat primary question must not leak into the export (only the bucketed form)",
        )
    }

    @Test
    fun `export is byte-stable and deterministic`() {
        val rows = listOf(LogRowFixtures.complete(), LogRowFixtures.denied(), LogRowFixtures.shadowMiss())
        assertEquals(exporter.export(rows), exporter.export(rows))
    }

    @Test
    fun `empty window exports nothing`() {
        assertEquals("", exporter.export(emptyList()))
        val manifest = exporter.manifest(emptyList())
        assertEquals(0, manifest.rowCount)
        assertFalse(manifest.mixedTeacher)
    }

    // ---- manifest ----

    @Test
    fun `mixed-teacher manifest fires with two teacher pins`() {
        val rows = listOf(
            LogRowFixtures.complete(rowId = "row-1", teacherConfigId = "primary"),
            LogRowFixtures.complete(
                rowId = "row-2",
                teacherConfigId = "fallback-1",
                teacherModel = LogRowFixtures.PINNED_FALLBACK_MODEL,
            ),
        )
        val manifest = exporter.manifest(rows)
        assertTrue(manifest.mixedTeacher, "two distinct pinned teachers must flag mixed-teacher")
        assertEquals(
            listOf(
                RlcdTeacherPin("primary", LogRowFixtures.PINNED_PRIMARY_MODEL),
                RlcdTeacherPin("fallback-1", LogRowFixtures.PINNED_FALLBACK_MODEL),
            ),
            manifest.teacherPins,
        )
        assertEquals(2, manifest.rowCount)
        assertEquals(exportedAt, manifest.exportedAtEpochMs)
    }

    @Test
    fun `single teacher pin does not flag mixed-teacher`() {
        val manifest = exporter.manifest(listOf(LogRowFixtures.complete(), LogRowFixtures.denied()))
        assertFalse(manifest.mixedTeacher)
        assertEquals(
            listOf(RlcdTeacherPin("primary", LogRowFixtures.PINNED_PRIMARY_MODEL)),
            manifest.teacherPins,
        )
    }

    @Test
    fun `manifest serializes and records state format versions`() {
        val rows = listOf(LogRowFixtures.complete(), LogRowFixtures.complete().copy(stateFormatVersion = 2))
        val manifest = exporter.manifest(rows)
        assertEquals(listOf(1, 2), manifest.stateFormatVersions)
        val encoded = exporter.encodeManifest(manifest)
        val decoded = strict.decodeFromString(RlcdExportManifest.serializer(), encoded)
        assertEquals(manifest, decoded)
        assertEquals(2, decoded.rowCount)
        assertFalse(decoded.mixedTeacher, "a stateFormatVersion bump alone is not a teacher bump")
    }

    @Test
    fun `manifest carries the companion row count informationally - training rows unchanged (amendment rule e)`() {
        val rows = listOf(LogRowFixtures.complete(), LogRowFixtures.shadowMiss())
        val manifest = exporter.manifest(rows, companionRowCount = 7)
        assertEquals(7, manifest.companionRowCount)
        assertEquals(2, manifest.rowCount, "rowCount stays the decision-row (training) count")
        // the JSONL cases themselves are untouched by companions — rule (e)
        val cases = exportLines(rows)
        for ((line, row) in cases.zip(rows)) validateCase(line, row)
        val encoded = exporter.encodeManifest(manifest)
        val decoded = strict.decodeFromString(RlcdExportManifest.serializer(), encoded)
        assertEquals(7, decoded.companionRowCount)
    }

    @Test
    fun `companion row count defaults to zero`() {
        assertEquals(0, exporter.manifest(listOf(LogRowFixtures.complete())).companionRowCount)
        assertEquals(0, exporter.manifest(emptyList()).companionRowCount)
    }
}
