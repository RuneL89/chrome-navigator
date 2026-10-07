package navigator.core.log

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import navigator.core.decision.NoulQuestion
import navigator.core.decision.band
import navigator.core.enumerate.Bucket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 5.2 — export correctness (vision 05 §4 v1.5.0): each exported case is the
 * upstream `docs/finetune.md` row shape in ONE of two kinds — `choose` (state verbatim,
 * `questions` the **bucketed shadow form**, `gold` the **full probability
 * distribution**, never a bare label) from decision rows, and `noul` (state verbatim,
 * `question` the anchored payload with its version, `gold` the **authoritative pTrue**)
 * from exit-check rows. Every line loads through a strict validator written from that
 * shape. Laws pinned here: authoritative gold (the executed answer — the fallback's
 * when escalated; a student is never its own gold), format scoping (only current-serve-
 * shape rows export; others are counted loudly in the manifest), and the mixed-teacher
 * flag (two teacher pins present, vision 02 §5).
 */
class RlcdExportTest {

    private val exportedAt = 1_760_300_000_000L
    private val exporter = RlcdExporter(now = { exportedAt })

    /** STRICT decoder of the exported JSONL (unknown fields are errors, vision 05 §4). */
    private val strict = Json { ignoreUnknownKeys = false }

    // ---- the finetune.md row shape, written from vision 05 §4 (the validator under test) ----

    @Serializable
    private data class FinetuneRow(
        val kind: String,
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
        assertEquals("choose", row.kind, "a decision row exports as the choose kind (vision 05 §4)")
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
    fun `format scoping - foreign-format rows are excluded and counted loudly in the manifest`() {
        // vision 05 §4: an export includes only rows whose stateFormatVersion matches
        // the current serve shape; rows of other versions are excluded and counted by
        // version — never silently dropped. Since the Phase-08H bump the retired v1
        // rows are the foreign ones (the Road L accumulation counter restarts at the
        // bump, doc 01 Road L Phase 3).
        val current = LogRowFixtures.complete()
        val foreign = LogRowFixtures.complete(rowId = "row-v1").copy(stateFormatVersion = 1)
        val jsonl = exporter.export(listOf(current, foreign))
        val lines = jsonl.split("\n")
        assertEquals(1, lines.size, "only the current-format row exports")
        assertTrue("row-v1" !in lines.single())
        assertTrue("\"open-first-result\"" in lines.single(), "the surviving case is the v2 choose case")
        val manifest = exporter.manifest(listOf(current, foreign))
        assertEquals(1, manifest.rowCount)
        assertEquals(listOf(2), manifest.stateFormatVersions)
        assertEquals(1, manifest.excludedRowCount)
        assertEquals(mapOf(1 to 1), manifest.excludedByStateFormatVersion)
    }

    @Test
    fun `manifest serializes and records state format versions of the exported rows`() {
        val manifest = exporter.manifest(listOf(LogRowFixtures.complete(), LogRowFixtures.denied()))
        assertEquals(listOf(navigator.core.STATE_FORMAT_VERSION), manifest.stateFormatVersions)
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

    // ---- the noul kind + authoritative gold (vision 05 §4 v1.5.0) -----------------

    /** One refused finish check on the Tarte Tatin page (the calibration-decoy contrast). */
    private fun exitCheck(
        rowId: String = "exit-check:task-apple-pie:9",
        pTrue: Double = 0.61,
        escalated: Boolean = false,
        fallbackPTrue: Double? = null,
        verdict: ExitCheckVerdict = ExitCheckVerdict.REFUSED,
        acceptedPath: LoggedExitPath? = null,
    ) = ExitCheckRow(
        rowId = rowId,
        taskId = "task-apple-pie",
        stepIndex = 9,
        timestamp = 1_760_250_000_000,
        stateText = "GOAL: find an apple pie recipe\n" +
            "URL: https://www.allrecipes.com/recipe/15896/classic-apple-pie\n" +
            "TITLE: Classic Apple Pie Recipe\n" +
            "HISTORY: (none)\n" +
            "UNTRUSTED PAGE CONTENT:\n" +
            "back:\n" +
            "[back] \"Back\" (0,0)",
        stateFormatVersion = navigator.core.STATE_FORMAT_VERSION,
        question = NoulQuestion(
            name = "goal-achieved",
            proposition = "The target page about \"Apple Pie\" is currently displayed.\n" +
                "PAGE IDENTITY: the currently displayed page's title is \"Classic Apple Pie Recipe\" " +
                "and its address is https://www.allrecipes.com/recipe/15896/classic-apple-pie.",
        ),
        anchoredNoulVersion = 2,
        teacher = EngineIdentity(configId = "primary", model = LogRowFixtures.PINNED_PRIMARY_MODEL),
        answer = ExitCheckAnswer(pTrue = pTrue, band = band(pTrue).toLoggedBand()),
        escalated = escalated,
        fallbackAnswer = fallbackPTrue?.let { ExitCheckFallbackAnswer(it) },
        guardLevel = ExitCheckGuardLevel.STRONG,
        verdict = verdict,
        acceptedPath = acceptedPath,
    )

    /** STRICT decoder of one exported noul line (unknown fields are errors). */
    private fun validateNoulLine(line: String, source: ExitCheckRow): RlcdNoulCase {
        val decoded = strict.decodeFromString(RlcdNoulCase.serializer(), line)
        // state is the canonical state text, verbatim — the export never rewrites it
        assertEquals(source.stateText, decoded.state)
        assertEquals(source.stateFormatVersion, decoded.stateFormatVersion)
        assertEquals(source.question.name, decoded.question.name)
        assertEquals(source.question.proposition, decoded.question.proposition)
        assertEquals(source.anchoredNoulVersion, decoded.anchoredNoulVersion)
        assertTrue("\"kind\":\"noul\"" in line.replace(" ", ""), "the noul kind is explicit: $line")
        assertTrue(decoded.gold in 0.0..1.0, "gold is the pTrue scalar, in range")
        return decoded
    }

    @Test
    fun `noul cases carry the anchored question, its version, and the primary's pTrue`() {
        val check = exitCheck(pTrue = 0.58)
        val jsonl = exporter.export(emptyList(), listOf(check))
        val parsed = validateNoulLine(jsonl, check)
        assertEquals(0.58, parsed.gold, "not escalated: gold is the primary's pTrue")
        assertFalse("\"questions\"" in jsonl, "a noul case has no bucketed questions field")
    }

    @Test
    fun `authoritative gold - an escalated noul case trains on the FALLBACK's pTrue, never the student's`() {
        // vision 05 §4: gold is always the EXECUTED answer — the fallback's when
        // escalated=true. The primary's own answer is the mistake training must not teach.
        val check = exitCheck(pTrue = 0.44, escalated = true, fallbackPTrue = 0.81)
        val parsed = validateNoulLine(exporter.export(emptyList(), listOf(check)), check)
        assertEquals(0.81, parsed.gold, "gold = the fallback's (executed) pTrue")
        assertTrue(0.44 != parsed.gold, "the primary's own pTrue is not the gold")
    }

    @Test
    fun `authoritative gold - an escalated choose case trains on the FALLBACK's distribution`() {
        val fallbackChoice = navigator.core.decision.ChoiceResult(
            chosenId = "type-query",
            probabilities = mapOf("type-query" to 0.70, "open-first-result" to 0.20, "scroll-results" to 0.10),
            confidence = 0.72,
        )
        val escalatedRow = LogRowFixtures.complete().copy(
            escalated = true,
            fallback = FallbackInfo(
                engine = "fallback-1",
                model = LogRowFixtures.PINNED_FALLBACK_MODEL,
                answer = fallbackChoice,
            ),
        )
        val line = exportLines(listOf(escalatedRow)).single()
        val parsed = strict.decodeFromString(FinetuneRow.serializer(), line)
        assertEquals(fallbackChoice.probabilities, parsed.gold, "gold = the executed (fallback) distribution")
        assertFalse(
            parsed.gold.equals(LogRowFixtures.teacherAnswer.probabilities),
            "the primary's own distribution is NOT the gold of an escalated row",
        )
    }

    @Test
    fun `authoritative gold - a non-escalated choose case keeps the teacher's distribution`() {
        val line = exportLines(listOf(LogRowFixtures.complete())).single()
        val parsed = strict.decodeFromString(FinetuneRow.serializer(), line)
        assertEquals(LogRowFixtures.teacherAnswer.probabilities, parsed.gold)
    }

    @Test
    fun `the manifest counts case kinds and carries the noul schema versions`() {
        val decisionRows = listOf(LogRowFixtures.complete(), LogRowFixtures.denied())
        val exitChecks = listOf(exitCheck(), exitCheck(rowId = "exit-check:t:10", pTrue = 0.58))
        val manifest = exporter.manifest(decisionRows, exitChecks)
        assertEquals(4, manifest.rowCount)
        assertEquals(mapOf("choose" to 2, "noul" to 2), manifest.caseKinds)
        assertEquals(2, manifest.anchoredNoulVersion)
        assertNull(
            manifest.layaStateViewVersion,
            "canonical export (the default): the rows' state text is the corpus view of record",
        )
        assertEquals(0, manifest.excludedRowCount)
        // the JSONL itself: choose lines first, then noul lines
        val lines = exporter.export(decisionRows, exitChecks).split("\n")
        assertEquals(4, lines.size)
        assertTrue("\"kind\":\"choose\"" in lines[0].replace(" ", ""))
        assertTrue("\"kind\":\"noul\"" in lines[3].replace(" ", ""))
    }

    @Test
    fun `a mixed anchoredNoulVersion window is incoherent and fails loudly`() {
        val older = exitCheck(rowId = "exit-check:t:1").copy(anchoredNoulVersion = 1)
        val newer = exitCheck(rowId = "exit-check:t:2")
        assertFailsWith<IllegalArgumentException> {
            exporter.manifest(emptyList(), listOf(older, newer))
        }
    }

    // ---- the on-device lineage: the laya state view export (vision 05 §4, Phase 08H) ----

    private val tokenizer by lazy { navigator.core.laya.Tokenizer.loadDefault() }

    /** The pinned 421M checkpoint's view budget (maxLen - headMaxLen - 1 = 319). */
    private val viewBudget = 512 - 192 - 1

    private val viewExporter by lazy {
        RlcdExporter(
            now = { exportedAt },
            stateView = RlcdStateView.LAYA_VIEW,
            layaViewBudgetTokens = viewBudget,
            tokenizer = tokenizer,
        )
    }

    /** A row whose canonical state carries an untrusted block far over the view budget. */
    private fun oversizedRow(): DecisionLogRow {
        val excerpt = (0 until 60).map { i -> "Excerpt paragraph $i carries a dozen tokens of page prose." }
        val buckets = (0 until 20).joinToString("\n") { i ->
            "[n$i] \"Result $i www.example.test/item?query=$i\" tap (540,${300 + 60 * i})"
        }
        val state = "GOAL: find the featured article\n" +
            "URL: https://news.example.test/featured\n" +
            "TITLE: Today's Featured Article\n" +
            "HISTORY: (none)\n" +
            "UNTRUSTED PAGE CONTENT:\n" +
            excerpt.joinToString("\n", postfix = "\n") +
            "nav-links:\n" + buckets
        return LogRowFixtures.complete(stateText = state)
    }

    @Test
    fun `the laya-view export derives the view through the versioned builder - rows keep the canonical`() {
        val row = oversizedRow()
        val case = viewExporter.toCase(row)
        // the ROW keeps the canonical text verbatim; the exported CASE state is the view
        val expectedView = navigator.core.state.LayaStateView.derive(row.stateText, tokenizer, viewBudget)
        assertEquals(expectedView, case.state, "the case carries the derived laya state view")
        assertTrue(case.state != row.stateText, "the fixture must exercise real shrinking")
        // the app-authored block is never rewritten: the view's pre-content lines are the row's
        val canonicalLines = row.stateText.split("\n")
        val viewLines = case.state.split("\n")
        val headerIndex = canonicalLines.indexOf("UNTRUSTED PAGE CONTENT:")
        assertEquals(canonicalLines.take(headerIndex + 1), viewLines.take(headerIndex + 1))
        assertTrue(tokenizer.count(case.state) <= viewBudget, "the exported view fits the checkpoint budget")
        assertEquals(row.stateFormatVersion, case.stateFormatVersion)
    }

    @Test
    fun `the laya-view manifest names the view version and keeps the loud exclusion counts`() {
        val current = oversizedRow()
        val foreign = LogRowFixtures.complete(rowId = "row-v1").copy(stateFormatVersion = 1)
        val manifest = viewExporter.manifest(listOf(current, foreign))
        assertEquals(1, manifest.layaStateViewVersion, "LAYA_STATE_VIEW_VERSION rides the manifest")
        assertEquals(1, manifest.rowCount)
        assertEquals(mapOf(1 to 1), manifest.excludedByStateFormatVersion, "v1 rows stay excluded, counted loudly")
        // the manifest round-trips through the strict decoder with the view version set
        val decoded = strict.decodeFromString(RlcdExportManifest.serializer(), viewExporter.encodeManifest(manifest))
        assertEquals(manifest, decoded)
    }

    @Test
    fun `noul cases carry the derived view too (zero train-serve skew per view)`() {
        val check = exitCheck()
        val parsed = strict.decodeFromString(
            RlcdNoulCase.serializer(),
            viewExporter.export(emptyList(), listOf(check)),
        )
        assertEquals(
            navigator.core.state.LayaStateView.derive(check.stateText, tokenizer, viewBudget),
            parsed.state,
            "the noul case's state is the same derivation the serve path feeds the checkpoint",
        )
        assertEquals(check.stateText, parsed.state, "this fixture's state fits the budget: identity view")
    }

    @Test
    fun `a laya-view export without its budget or tokenizer fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            RlcdExporter(now = { exportedAt }, stateView = RlcdStateView.LAYA_VIEW)
        }
        assertFailsWith<IllegalArgumentException> {
            RlcdExporter(now = { exportedAt }, stateView = RlcdStateView.LAYA_VIEW, layaViewBudgetTokens = viewBudget)
        }
    }

    @Test
    fun `the laya-view export is byte-stable and deterministic`() {
        val rows = listOf(oversizedRow(), LogRowFixtures.complete(rowId = "row-2"))
        assertEquals(viewExporter.export(rows), viewExporter.export(rows))
    }
}
