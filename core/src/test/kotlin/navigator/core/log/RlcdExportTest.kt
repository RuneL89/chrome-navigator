package navigator.core.log

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import navigator.core.decision.Intents
import navigator.core.decision.NoulQuestion
import navigator.core.decision.band
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 5.2 — export correctness (vision 05 §4 v1.6.0): each exported case is the
 * upstream `docs/finetune.md` row shape in ONE of THREE kinds — `choose` (state
 * verbatim, `questions` the **fixed intent choice form** — cascade stage 1, the
 * vocabulary a graduated Laya will actually be asked — and `gold` the **full probability
 * distribution** over the intent ids, never a bare label) from decision rows; `score`
 * (state under the export's view discipline, `questions` the batched per-candidate
 * rubric, `gold` the **authoritative per-candidate expected levels** — the executed
 * slate) from decision rows with `targetScores`; and `noul` (state verbatim, `question`
 * the anchored payload with its version, `gold` the **authoritative pTrue**) from
 * exit-check rows. Every line loads through a strict validator written from that shape.
 * Laws pinned here: authoritative gold (the executed answer — the fallback's when
 * escalated; a student is never its own gold), format scoping (only current-serve-shape
 * rows export; others are counted loudly in the manifest), QUESTION-SCHEMA scoping (the
 * choose form must be the fixed intent vocabulary — legacy flat rows are excluded and
 * counted), and the mixed-teacher flag (two teacher pins present, vision 02 §5). The
 * accumulation restarts are pinned on BOTH counters: the state-format bump (v4) and the
 * choose-question-schema version ([Intents.INTENTS_VERSION]).
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
        val instructions: String,
        val options: List<FinetuneOption>,
    )

    @Serializable
    private data class FinetuneOption(
        val id: String,
        val label: String,
        val description: String,
    )

    /** STRICT decoder of one exported score line. */
    @Serializable
    private data class ScoreRow(
        val kind: String,
        val state: String,
        val stateFormatVersion: Int,
        val questions: ScoreQuestions,
        val gold: Map<String, Double>,
    )

    @Serializable
    private data class ScoreQuestions(
        val rubric: List<String>,
        val candidates: List<String>,
    )

    /** Strict load + shape law of one exported choose line. */
    private fun validateCase(line: String, source: DecisionLogRow): FinetuneRow {
        val row = strict.decodeFromString(FinetuneRow.serializer(), line)
        assertEquals("choose", row.kind, "a decision row exports as the choose kind (vision 05 §4)")
        // state is the canonical state text, verbatim — the export never rewrites it
        assertEquals(source.stateText, row.state, "export must not rewrite state text")
        assertEquals(source.stateFormatVersion, row.stateFormatVersion)
        // questions are the fixed intent form: the 9-category vocabulary, verbatim
        assertEquals(Intents.QUESTION.name, row.questions.name)
        assertEquals(Intents.INTENT_INSTRUCTIONS, row.questions.instructions)
        assertEquals(
            Intents.IDS,
            row.questions.options.map { it.id },
            "the intent options carry the fixed vocabulary in the table order (vision 02 §3)",
        )
        // gold is the teacher's distribution over the intent ids — normalized
        val ids = Intents.IDS.toSet()
        assertTrue(row.gold.isNotEmpty(), "gold must be a distribution, not empty")
        val foreign = row.gold.keys - ids
        assertTrue(foreign.isEmpty(), "gold keys outside the fixed intent ids: $foreign")
        val sum = row.gold.values.sum()
        assertTrue(kotlin.math.abs(sum - 1.0) < 1e-6, "gold must be normalized, Σp = $sum")
        return row
    }

    /** Strict load + shape law of one exported score line. */
    private fun validateScoreCase(line: String, source: DecisionLogRow): ScoreRow {
        val row = strict.decodeFromString(ScoreRow.serializer(), line)
        assertEquals("score", row.kind, "a target-bearing step exports a score case (vision 05 §4 v1.6.0)")
        assertEquals(
            source.targetStateText,
            row.state,
            "the score case's state IS the executed stage-2 request's TARGET STATE VIEW " +
                "(the row's recorded target view, verbatim — doc 05 §4 v1.6.0)",
        )
        assertEquals(source.stateFormatVersion, row.stateFormatVersion)
        assertEquals(
            navigator.core.decision.TargetRubric.LEVEL_DESCRIPTIONS,
            row.questions.rubric,
            "the score case's rubric is the declared 5-level relevance rubric, verbatim",
        )
        assertEquals(
            source.targetScores!!.keys.sorted().map { navigator.core.decision.TargetRubric.questionName(it) },
            row.questions.candidates,
            "the score case names the slate's cand-<id> questions in sorted order",
        )
        assertEquals(
            source.targetScores,
            row.gold,
            "gold = the authoritative per-candidate expected levels (the executed slate)",
        )
        return row
    }

    /** Export lines for cascade rows (a target-bearing row emits choose + score lines). */
    private fun exportLines(rows: List<DecisionLogRow>): List<String> {
        val jsonl = exporter.export(rows)
        if (jsonl.isEmpty()) return emptyList()
        assertFalse(jsonl.endsWith("\n"), "no trailing newline")
        return jsonl.split("\n")
    }

    private val cascadeRow: DecisionLogRow = LogRowFixtures.cascadeRow()
    private val plainCascadeRow: DecisionLogRow =
        LogRowFixtures.cascadeRow(rowId = "row-cascade-plain", targetScores = null)

    // ---- export shape ----

    @Test
    fun `every exported line loads with the strict finetune validator`() {
        val rows = listOf(cascadeRow, plainCascadeRow)
        val lines = exportLines(rows)
        // the target-bearing row emits choose + score; the plain row choose only
        assertEquals(3, lines.size, "choose, score (target-bearing), choose")
        validateCase(lines[0], cascadeRow)
        validateScoreCase(lines[1], cascadeRow)
        validateCase(lines[2], plainCascadeRow)
    }

    @Test
    fun `gold is the full teacher distribution not a bare label`() {
        val line = exportLines(listOf(plainCascadeRow)).single()
        val parsed = validateCase(line, plainCascadeRow)
        assertEquals(LogRowFixtures.intentTeacherAnswer.probabilities, parsed.gold, "gold = teacher distribution verbatim")
        assertTrue(parsed.gold.size > 1, "a distribution has more than one option: ${parsed.gold}")
        // no chosen/label fields anywhere in the case — distribution, not bare label
        assertFalse("\"chosen\"" in line)
        assertFalse("\"goldLabel\"" in line)
    }

    @Test
    fun `questions are the fixed intent form - the legacy flat form never leaks`() {
        val line = exportLines(listOf(plainCascadeRow)).single()
        val parsed = validateCase(line, plainCascadeRow)
        assertEquals("intent", parsed.questions.name)
        assertEquals(
            Intents.IDS.toSet(),
            parsed.gold.keys.toSet(),
            "the gold lives on the fixed vocabulary's option space",
        )
        assertFalse(
            "\"next-action\"" in line,
            "the legacy flat primary question must not leak into the export",
        )
    }

    @Test
    fun `question-schema scoping - legacy flat rows are excluded and counted loudly`() {
        // vision 05 §4 v1.6.0: question schemas are format-scoped — a current-format row
        // whose primary question is NOT the fixed intent form (a legacy triage/recovery
        // shape) is excluded and counted in the manifest, never silently dropped or
        // reshaped.
        val cascade = plainCascadeRow
        val legacy = LogRowFixtures.complete(rowId = "row-legacy")
        val lines = exportLines(listOf(cascade, legacy))
        assertEquals(1, lines.size, "only the cascade-schema row exports")
        assertTrue("\"kind\":\"choose\"" in lines.single().replace(" ", ""))
        val manifest = exporter.manifest(listOf(cascade, legacy))
        assertEquals(1, manifest.rowCount, "the counter counts only the current-schema rows")
        assertEquals(1, manifest.excludedByQuestionSchema, "the legacy row is counted loudly")
        assertEquals(
            emptyMap<Int, Int>(),
            manifest.excludedByStateFormatVersion,
            "its FORMAT matches — only the schema mismatches",
        )
    }

    @Test
    fun `a schema-mismatched row can never be reshaped by hand`() {
        assertFailsWith<IllegalArgumentException> {
            exporter.toChooseCase(LogRowFixtures.complete())
        }
    }

    @Test
    fun `export is byte-stable and deterministic`() {
        val rows = listOf(cascadeRow, plainCascadeRow, LogRowFixtures.denied())
        assertEquals(exporter.export(rows), exporter.export(rows))
    }

    @Test
    fun `empty window exports nothing`() {
        assertEquals("", exporter.export(emptyList()))
        val manifest = exporter.manifest(emptyList())
        assertEquals(0, manifest.rowCount)
        assertFalse(manifest.mixedTeacher)
        assertNull(manifest.chooseQuestionVersion, "no choose cases — no choose-schema version")
    }

    // ---- the score kind (vision 05 §4 v1.6.0) --------------------------------------

    @Test
    fun `a target-bearing step exports choose AND score cases - the batched rubric + authoritative slate`() {
        val lines = exportLines(listOf(cascadeRow))
        assertEquals(2, lines.size, "choose first, then the row's score case")
        validateCase(lines[0], cascadeRow)
        val score = validateScoreCase(lines[1], cascadeRow)
        assertTrue(
            score.questions.candidates.all { it.startsWith("cand-") },
            "the stage-2 question names carry the cand- prefix (vision 02 §3): ${score.questions.candidates}",
        )
        assertTrue("\"kind\":\"score\"" in lines[1].replace(" ", ""))
    }

    @Test
    fun `a step without a stage-2 slate exports no score case`() {
        val lines = exportLines(listOf(plainCascadeRow))
        assertEquals(1, lines.size)
        assertFalse("\"kind\":\"score\"" in lines[0].replace(" ", ""))
    }

    @Test
    fun `a score case without its executed slate is incoherent`() {
        assertFailsWith<IllegalArgumentException> { exporter.toScoreCase(plainCascadeRow) }
    }

    @Test
    fun `a score case's state is the row's TARGET STATE VIEW - never the canonical stand-in`() {
        // The Round-2 carry (doc 05 §4 v1.6.0): the row records the executed stage-2
        // request's target view (targetStateText) and the score case exports it — a
        // canonical-text stand-in is not training-ready and fails loudly.
        val row = LogRowFixtures.cascadeRow()
        assertTrue(row.targetStateText != row.stateText, "the fixture's two texts differ")
        val score = validateScoreCase(exportLines(listOf(row))[1], row)
        assertEquals(LogRowFixtures.targetStateText, score.state)
        // A row with stage-2 evidence but no recorded target view is incoherent at the
        // row boundary AND at the export boundary.
        assertFailsWith<IllegalArgumentException> {
            LogRowFixtures.cascadeRow(rowId = "row-no-view").copy(targetStateText = null)
        }
        assertFailsWith<IllegalArgumentException> {
            exporter.toScoreCase(LogRowFixtures.cascadeRow(rowId = "row-no-view").copy(targetStateText = null))
        }
    }

    // ---- manifest ----

    @Test
    fun `mixed-teacher manifest fires with two teacher pins`() {
        val rows = listOf(
            LogRowFixtures.cascadeRow(rowId = "row-1"),
            LogRowFixtures.cascadeRow(
                rowId = "row-2",
                shadowAnswer = null,
            ).copy(
                teacher = EngineIdentity(configId = "fallback-1", model = LogRowFixtures.PINNED_FALLBACK_MODEL),
                escalated = false,
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
        val manifest = exporter.manifest(listOf(plainCascadeRow, LogRowFixtures.cascadeRow(rowId = "row-2")))
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
        // version — never silently dropped. Since the Phase-10B bump
        // (STATE_FORMAT_VERSION = 4) the retired v3 rows are the foreign ones (the Road
        // L accumulation counter restarts at the bump, doc 01 Road L Phase 3) — the v4
        // export's manifest shows the v3 rows excluded + counted and the rowCount
        // restarted.
        val current = plainCascadeRow
        val foreign = LogRowFixtures.cascadeRow(rowId = "row-v3").copy(stateFormatVersion = 3)
        val jsonl = exporter.export(listOf(current, foreign))
        val lines = jsonl.split("\n")
        assertEquals(1, lines.size, "only the current-format row exports")
        assertTrue("row-v3" !in lines.single())
        val manifest = exporter.manifest(listOf(current, foreign))
        assertEquals(1, manifest.rowCount, "the accumulation counter restarted at the v4 bump")
        assertEquals(listOf(4), manifest.stateFormatVersions)
        assertEquals(1, manifest.excludedRowCount)
        assertEquals(mapOf(3 to 1), manifest.excludedByStateFormatVersion)
    }

    @Test
    fun `the accumulation restarts on BOTH counters - state format v4 AND the choose-question schema`() {
        // vision 05 §4 v1.6.0: the manifest carries a choose-question-schema version and
        // the accumulation counter restarts when it bumps, as it does for every
        // state-format/view/question-version bump. The Phase-10B window restarts BOTH.
        val manifest = exporter.manifest(listOf(cascadeRow, plainCascadeRow))
        assertEquals(Intents.INTENTS_VERSION, manifest.chooseQuestionVersion, "intents-v1 rides the manifest")
        assertEquals(listOf(navigator.core.STATE_FORMAT_VERSION), manifest.stateFormatVersions)
        assertEquals(2, manifest.rowCount, "the counter counts ONLY current-serve-shape rows")
        // an older choose-schema row (a foreign vocabulary) is excluded + counted: the
        // restart is visible in what the counter does NOT count
        val foreignVocabulary = plainCascadeRow.copy(
            rowId = "row-old-vocab",
            questionPrimary = navigator.core.decision.ChoiceQuestion(
                name = "intent",
                options = listOf(
                    navigator.core.decision.Option("open-site", "open-site", "old wording"),
                    navigator.core.decision.Option("tap", "tap", "the retired flat action"),
                ),
            ),
            teacherAnswer = TeacherAnswer(
                chosen = "open-site",
                probabilities = mapOf("open-site" to 0.70, "tap" to 0.30),
                confidence = 0.70,
            ),
            shadow = null,
            shadowAnswer = null,
            agreementExactMatch = null,
            agreementKL = null,
            targetScores = null,
            targetAgreement = null,
        )
        val mixed = exporter.manifest(listOf(plainCascadeRow, foreignVocabulary))
        assertEquals(1, mixed.rowCount, "the counter restarted on the intents-v1 schema")
        assertEquals(1, mixed.excludedByQuestionSchema)
    }

    @Test
    fun `manifest serializes and records state format versions of the exported rows`() {
        val manifest = exporter.manifest(listOf(plainCascadeRow, LogRowFixtures.cascadeRow(rowId = "row-2")))
        assertEquals(listOf(navigator.core.STATE_FORMAT_VERSION), manifest.stateFormatVersions)
        val encoded = exporter.encodeManifest(manifest)
        val decoded = strict.decodeFromString(RlcdExportManifest.serializer(), encoded)
        assertEquals(manifest, decoded)
        assertEquals(2, decoded.rowCount)
        assertFalse(decoded.mixedTeacher, "a stateFormatVersion bump alone is not a teacher bump")
    }

    @Test
    fun `manifest carries the companion row count informationally - training rows unchanged (amendment rule e)`() {
        val rows = listOf(cascadeRow, plainCascadeRow)
        val manifest = exporter.manifest(rows, companionRowCount = 7)
        assertEquals(7, manifest.companionRowCount)
        assertEquals(2, manifest.rowCount, "rowCount stays the decision-row (training) count")
        val encoded = exporter.encodeManifest(manifest)
        val decoded = strict.decodeFromString(RlcdExportManifest.serializer(), encoded)
        assertEquals(7, decoded.companionRowCount)
    }

    @Test
    fun `companion row count defaults to zero`() {
        assertEquals(0, exporter.manifest(listOf(plainCascadeRow)).companionRowCount)
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
        anchoredNoulVersion = 3,
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
        assertFalse("\"questions\"" in jsonl, "a noul case has no questions field")
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
            chosenId = "verify-done",
            probabilities = Intents.IDS.associate { id ->
                id to if (id == "verify-done") 0.70 else 0.0375
            },
            confidence = 0.72,
        )
        val escalatedRow = LogRowFixtures.cascadeRow(
            escalated = true,
            fallback = FallbackInfo(
                engine = "fallback-1",
                model = LogRowFixtures.PINNED_FALLBACK_MODEL,
                answer = fallbackChoice,
            ),
        )
        val line = exportLines(listOf(escalatedRow)).first()
        val parsed = strict.decodeFromString(FinetuneRow.serializer(), line)
        assertEquals(fallbackChoice.probabilities, parsed.gold, "gold = the executed (fallback) distribution")
        assertFalse(
            parsed.gold == LogRowFixtures.intentTeacherAnswer.probabilities,
            "the primary's own distribution is NOT the gold of an escalated row",
        )
    }

    @Test
    fun `authoritative gold - a non-escalated choose case keeps the teacher's distribution`() {
        val line = exportLines(listOf(plainCascadeRow)).single()
        val parsed = strict.decodeFromString(FinetuneRow.serializer(), line)
        assertEquals(LogRowFixtures.intentTeacherAnswer.probabilities, parsed.gold)
    }

    @Test
    fun `the manifest counts case kinds and carries the schema versions`() {
        val decisionRows = listOf(cascadeRow, plainCascadeRow)
        val exitChecks = listOf(exitCheck(), exitCheck(rowId = "exit-check:t:10", pTrue = 0.58))
        val manifest = exporter.manifest(decisionRows, exitChecks)
        assertEquals(4, manifest.rowCount)
        assertEquals(mapOf("choose" to 2, "score" to 1, "noul" to 2), manifest.caseKinds)
        assertEquals(3, manifest.anchoredNoulVersion, "the v3 anchored templates ride the manifest")
        assertEquals(Intents.INTENTS_VERSION, manifest.chooseQuestionVersion)
        assertNull(
            manifest.layaStateViewVersion,
            "canonical export (the default): the rows' state text is the corpus view of record",
        )
        assertEquals(0, manifest.excludedRowCount)
        // the JSONL itself: the cascade row's choose + score, the plain choose, then noul
        val lines = exporter.export(decisionRows, exitChecks).split("\n")
        assertEquals(5, lines.size)
        assertTrue("\"kind\":\"choose\"" in lines[0].replace(" ", ""))
        assertTrue("\"kind\":\"score\"" in lines[1].replace(" ", ""))
        assertTrue("\"kind\":\"choose\"" in lines[2].replace(" ", ""))
        assertTrue("\"kind\":\"noul\"" in lines[4].replace(" ", ""))
    }

    @Test
    fun `a mixed anchoredNoulVersion window is incoherent and fails loudly`() {
        val older = exitCheck(rowId = "exit-check:t:1").copy(anchoredNoulVersion = 2)
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
        return LogRowFixtures.cascadeRow(targetScores = null).copy(stateText = state)
    }

    @Test
    fun `the laya-view export derives the view through the versioned builder - rows keep the canonical`() {
        val row = oversizedRow()
        val case = viewExporter.toChooseCase(row)
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
        val foreign = LogRowFixtures.cascadeRow(rowId = "row-v1", targetScores = null).copy(stateFormatVersion = 1)
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
        val rows = listOf(oversizedRow(), LogRowFixtures.cascadeRow(rowId = "row-2", targetScores = null))
        assertEquals(viewExporter.export(rows), viewExporter.export(rows))
    }
}
