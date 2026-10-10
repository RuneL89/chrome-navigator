package navigator.core.log

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.Intents
import navigator.core.decision.NoulQuestion
import navigator.core.STATE_FORMAT_VERSION
import navigator.core.laya.Tokenizer
import navigator.core.state.LayaStateView

/**
 * The training-case kind of one exported RLCD row (vision 05 §4 v1.6.0): a graduated
 * Laya is asked ALL THREE at serve time — `choose` over the fixed intent vocabulary (the
 * cascade's stage 1), `score` over the batched per-candidate rubric (the cascade's
 * stage 2), and `noul` over the anchored goal-achieved proposition — so the corpus
 * carries all three.
 */
@Serializable
enum class RlcdCaseKind {
    @SerialName("choose") CHOOSE,
    @SerialName("score") SCORE,
    @SerialName("noul") NOUL,
}

/**
 * One exported RLCD `choose` case (vision 05 §4 v1.6.0): the upstream `docs/finetune.md`
 * row shape — `state` (canonical state text with its `stateFormatVersion` recorded),
 * `questions` (the **intent choice form** — the FIXED 9-category vocabulary a graduated
 * Laya will actually be asked, cascade stage 1), and `gold` (the **full probability
 * distribution** over the intent ids — never a bare label).
 */
@Serializable
data class RlcdChooseCase(
    val kind: RlcdCaseKind = RlcdCaseKind.CHOOSE,
    val state: String,
    val stateFormatVersion: Int,
    val questions: ChoiceQuestion,
    val gold: Map<String, Double>,
)

/**
 * The batched per-candidate rubric of one exported RLCD `score` case (vision 05 §4
 * v1.6.0): the 5-level relevance rubric VERBATIM (the serve shape's declared levels) and
 * the slate's `cand-<candidate-id>` question names in declaration order. The candidate
 * labels ride the case's state text (the target view's candidate lines) — the pair is
 * the complete serve-time question.
 */
@Serializable
data class RlcdScoreQuestions(
    val rubric: List<String>,
    val candidates: List<String>,
)

/**
 * One exported RLCD `score` case (vision 05 §4 v1.6.0): `state` (the target view the
 * stage-2 request rode, under the export's state-view discipline), `questions` (the
 * batched per-candidate rubric), and `gold` (the **authoritative per-candidate expected
 * levels** — always the executed answer's slate). Source: decision rows with
 * `targetScores`.
 */
@Serializable
data class RlcdScoreCase(
    val kind: RlcdCaseKind = RlcdCaseKind.SCORE,
    val state: String,
    val stateFormatVersion: Int,
    val questions: RlcdScoreQuestions,
    val gold: Map<String, Double>,
)

/**
 * One exported RLCD `noul` case (vision 05 §4 v1.5.0): `state`, `question` (the
 * anchored `NoulQuestion` payload with its `anchoredNoulVersion`), and `gold` (the
 **authoritative** `pTrue`). Source: exit-check rows (vision 05 §2 amendment).
 */
@Serializable
data class RlcdNoulCase(
    val kind: RlcdCaseKind = RlcdCaseKind.NOUL,
    val state: String,
    val stateFormatVersion: Int,
    val question: NoulQuestion,
    val anchoredNoulVersion: Int,
    val gold: Double,
)

/** A distinct teacher pin observed in the exported window (config + pinned model id). */
@Serializable
data class RlcdTeacherPin(
    val configId: String,
    val model: String,
)

/**
 * Which state text an export's cases carry (vision 05 §4 "State view"):
 * [CANONICAL] exports the rows' canonical state text verbatim (the corpus-of-record
 * view); [LAYA_VIEW] exports the **laya state view** — the deterministic derivation
 * through the same versioned builder ([LayaStateView], `LAYA_STATE_VIEW_VERSION` in the
 * manifest) — byte-for-byte what a graduated on-device checkpoint sees at serve time.
 * The canonical text stays verbatim in the rows either way; the export never rewrites
 * state text, it derives the view.
 */
@Serializable
enum class RlcdStateView {
    @SerialName("canonical") CANONICAL,
    @SerialName("laya-state-view") LAYA_VIEW,
}

/**
 * The export manifest (vision 05 §4): teacher pin(s) seen in the window, the
 * mixed-teacher flag, row count, and the state-format versions present. A
 * mixed-teacher corpus (any teacher bump mid-window, vision 02 §5) is flagged so
 * fine-tune runs can subset if desired.
 *
 * [companionRowCount] (vision 05 §2 amendment 2026-10-04, rule e) is INFORMATIONAL
 * only: companion `ShadowResultRow`s serve agreement measurement, never training —
 * the exported cases are never touched by them.
 *
 * v1.6.0 (the cascade corpus): [caseKinds] counts the exported cases by kind (choose +
 * score + noul); [excludedRowCount]/[excludedByStateFormatVersion] count rows of other
 * format versions LOUDLY, and [excludedByQuestionSchema] counts current-format rows
 * whose primary question is NOT the fixed intent form (legacy/triage shapes) — question
 * schemas are format-scoped (never silently dropped); [anchoredNoulVersion] is the noul
 * cases' question-schema version; [chooseQuestionVersion] is the choose cases'
 * question-schema version ([Intents.INTENTS_VERSION]) — **the accumulation counter
 * restarts on it** as on every state-format/view/question-version bump; and
 * [layaStateViewVersion] names the state view the cases carry — null for a canonical
 * export, [LAYA_STATE_VIEW_VERSION] when the export is of the on-device lineage.
 *
 * Road L Phase 3 accumulation law (doc 01): [rowCount] IS the export-eligible
 * accumulation counter — it counts only rows of the current serve shape, so it
 * RESTARTS at every state-format/view/question-version bump (older rows cannot train
 * the newer serve shape). Historical rows remain for replay and diagnostics.
 */
@Serializable
data class RlcdExportManifest(
    val exportedAtEpochMs: Long,
    val rowCount: Int,
    val teacherPins: List<RlcdTeacherPin>,
    val mixedTeacher: Boolean,
    val stateFormatVersions: List<Int>,
    val companionRowCount: Int = 0,
    val caseKinds: Map<String, Int> = emptyMap(),
    val excludedRowCount: Int = 0,
    val excludedByStateFormatVersion: Map<Int, Int> = emptyMap(),
    val excludedByQuestionSchema: Int = 0,
    val anchoredNoulVersion: Int? = null,
    val chooseQuestionVersion: String? = null,
    val layaStateViewVersion: Int? = null,
)

/**
 * The RLCD export (vision 05 §4 — "the training data IS the strategy"): a filtering +
 * reshaping of log rows into JSONL — one [RlcdChooseCase] AND (when the step carried a
 * stage-2 slate) one [RlcdScoreCase] per decision row, plus one [RlcdNoulCase] per
 * exit-check row.
 *
 * Laws:
 * - the canonical state text is copied verbatim — the export never rewrites it; the
 *   on-device lineage's [RlcdStateView.LAYA_VIEW] mode instead DERIVES the laya state
 *   view through the same versioned builder ([LayaStateView]), per vision 05 §4;
 * - **authoritative gold**: gold is always the EXECUTED answer — the fallback's when
 *   `escalated=true`, the primary's otherwise. A student engine is never its own gold:
 *   in laya-primary modes an escalated row's primary answer is the mistake the
 *   fallback corrected, and training on it would teach the mistake (vision 05 §4);
 * - **format scoping**: only rows whose `stateFormatVersion` AND question schema match
 *   the current serve shape export — the choose form must be the FIXED intent
 *   vocabulary (vision 02 §3); rows of other versions or schemas are excluded and
 *   counted loudly in the [manifest] (never silently dropped) — the Road L accumulation
 *   counter restarts at the bumps (doc 01 Road L Phase 3; the manifest records BOTH the
 *   state-format restart and the choose-question-schema restart);
 * - `gold` distributions are key-sorted so exports are byte-canonical;
 * - the [manifest] travels alongside the cases (caller writes it as a sidecar or last
 *   line of its choosing) — the cases themselves stay strictly upstream-shaped;
 * - local-only (ART-8): this class performs no I/O and no network anything.
 *
 * `mixedTeacher` fires when more than one distinct pinned **model** answered as teacher
 * (the pinned snapshot is the teacher of record, vision 02 §5); [RlcdExportManifest.teacherPins]
 * lists the distinct (configId, model) pairs in first-seen order for provenance.
 */
class RlcdExporter(
    private val json: kotlinx.serialization.json.Json = DECISION_LOG_JSON,
    private val now: () -> Long = System::currentTimeMillis,
    /** The current serve shape (vision 05 §4 format scoping, ART-7). */
    private val serveStateFormatVersion: Int = STATE_FORMAT_VERSION,
    /** The current choose-question schema (vision 05 §4 v1.6.0); the fixed intent vocabulary. */
    private val serveChooseQuestion: ChoiceQuestion = Intents.QUESTION,
    /** Which state text the cases carry (vision 05 §4 "State view"; canonical default). */
    private val stateView: RlcdStateView = RlcdStateView.CANONICAL,
    /** The laya view budget (`maxLen - headMaxLen - 1`, ART-5); required iff [stateView] is [RlcdStateView.LAYA_VIEW]. */
    private val layaViewBudgetTokens: Int? = null,
    /** The real tokenizer the view derivation counts with; required iff [stateView] is [RlcdStateView.LAYA_VIEW]. */
    private val tokenizer: Tokenizer? = null,
) {
    init {
        if (stateView == RlcdStateView.LAYA_VIEW) {
            require(layaViewBudgetTokens != null && layaViewBudgetTokens > 0) {
                "a laya-state-view export needs its checkpoint's view budget " +
                    "(maxLen - headMaxLen - 1, vision 03 §4) — got $layaViewBudgetTokens"
            }
            require(tokenizer != null) {
                "a laya-state-view export derives the view through the versioned builder " +
                    "and needs the real tokenizer (vision 05 §4)"
            }
        }
    }

    /** The derived state text for one row's stored canonical text (the view law's seam). */
    private fun stateFor(canonical: String): String = when (stateView) {
        RlcdStateView.CANONICAL -> canonical // verbatim: never rewritten (vision 05 §4)
        RlcdStateView.LAYA_VIEW -> LayaStateView.derive(
            canonical,
            requireNotNull(tokenizer),
            requireNotNull(layaViewBudgetTokens),
        )
    }

    /** Whether [row]'s primary question IS the current serve shape's choose form. */
    private fun matchesChooseSchema(row: DecisionLogRow): Boolean =
        row.questionPrimary.options.map { it.id } == serveChooseQuestion.options.map { it.id }

    /**
     * Exports [decisionRows] + [exitChecks] as JSONL — choose cases first (row order,
     * score cases interleaved per row after its choose case), then noul cases (row
     * order); one case per format- AND schema-matching row (two for a target-bearing
     * cascade step).
     */
    fun export(decisionRows: List<DecisionLogRow>, exitChecks: List<ExitCheckRow> = emptyList()): String {
        val lines = mutableListOf<String>()
        for (row in decisionRows.filter { it.stateFormatVersion == serveStateFormatVersion && matchesChooseSchema(it) }) {
            lines += json.encodeToString(RlcdChooseCase.serializer(), toChooseCase(row))
            if (row.targetScores != null) {
                lines += json.encodeToString(RlcdScoreCase.serializer(), toScoreCase(row))
            }
        }
        for (check in exitChecks.filter { it.stateFormatVersion == serveStateFormatVersion }) {
            lines += json.encodeToString(RlcdNoulCase.serializer(), toCase(check))
        }
        return lines.joinToString(separator = "\n")
    }

    /** Maps one decision row to its exported `choose` case (authoritative-gold law applied). */
    fun toChooseCase(row: DecisionLogRow): RlcdChooseCase {
        require(matchesChooseSchema(row)) {
            "DecisionLogRow ${row.rowId}: the primary question is not the current serve shape's " +
                "choose form (the fixed intent vocabulary, vision 02 §3) — a schema-mismatched " +
                "row is excluded by [export], never reshaped"
        }
        val goldAnswer = if (row.escalated) {
            // The executed answer is the fallback's (vision 02 §7); the primary's own
            // answer — when it even produced one — is the mistake training must NOT teach.
            val fallback = requireNotNull(row.fallback) {
                "DecisionLogRow ${row.rowId}: escalated=true without a fallback answer is incoherent"
            }
            (fallback.answer as? ChoiceResult)?.let { result ->
                TeacherAnswer(result.chosenId, result.probabilities, result.confidence)
            } ?: throw IllegalStateException(
                "DecisionLogRow ${row.rowId}: a choose-case gold needs a choice-typed fallback " +
                    "answer, got ${fallback.answer::class.simpleName}",
            )
        } else {
            row.teacherAnswer
        }
        return RlcdChooseCase(
            state = stateFor(row.stateText),
            stateFormatVersion = row.stateFormatVersion,
            questions = serveChooseQuestion,
            gold = goldAnswer.probabilities.entries
                .sortedBy { it.key }
                .associate { it.key to it.value },
        )
    }

    /** Maps one decision row to its exported `score` case (the executed slate is the gold). */
    fun toScoreCase(row: DecisionLogRow): RlcdScoreCase {
        val scores = requireNotNull(row.targetScores) {
            "DecisionLogRow ${row.rowId}: a score case needs the executed stage-2 slate " +
                "(targetScores, doc 05 §2 v1.6.0)"
        }
        return RlcdScoreCase(
            state = stateFor(row.stateText),
            stateFormatVersion = row.stateFormatVersion,
            questions = RlcdScoreQuestions(
                rubric = navigator.core.decision.TargetRubric.LEVEL_DESCRIPTIONS,
                candidates = scores.keys.map { navigator.core.decision.TargetRubric.questionName(it) }
                    .sorted(),
            ),
            gold = scores.entries.sortedBy { it.key }.associate { it.key to it.value },
        )
    }

    /** Maps one exit-check row to its exported `noul` case (authoritative-gold law applied). */
    fun toCase(check: ExitCheckRow): RlcdNoulCase = RlcdNoulCase(
        state = stateFor(check.stateText),
        stateFormatVersion = check.stateFormatVersion,
        question = check.question,
        anchoredNoulVersion = check.anchoredNoulVersion,
        gold = check.fallbackAnswer?.pTrue ?: check.answer.pTrue,
    )

    /**
     * Builds the manifest for the exported window: format- AND schema-scoped cases +
     * loud exclusion counts. [companionRowCount] is informational (amendment rule e).
     */
    fun manifest(
        decisionRows: List<DecisionLogRow>,
        exitChecks: List<ExitCheckRow> = emptyList(),
        companionRowCount: Int = 0,
    ): RlcdExportManifest {
        val formatMatching = decisionRows.filter { it.stateFormatVersion == serveStateFormatVersion }
        val exportedDecisions = formatMatching.filter { matchesChooseSchema(it) }
        val schemaExcluded = formatMatching.count { !matchesChooseSchema(it) }
        val exportedChecks = exitChecks.filter { it.stateFormatVersion == serveStateFormatVersion }
        val excludedDecisions = decisionRows.filterNot { it.stateFormatVersion == serveStateFormatVersion }
        val excludedChecks = exitChecks.filterNot { it.stateFormatVersion == serveStateFormatVersion }
        val scoreCases = exportedDecisions.count { it.targetScores != null }
        val pins = (exportedDecisions.map { RlcdTeacherPin(configId = it.teacher.configId, model = it.teacher.model) } +
            exportedChecks.map { RlcdTeacherPin(configId = it.teacher.configId, model = it.teacher.model) })
            .distinct()
        val distinctModels = pins.map { it.model }.distinct()
        val caseKinds = buildMap {
            if (exportedDecisions.isNotEmpty()) put("choose", exportedDecisions.size)
            if (scoreCases > 0) put("score", scoreCases)
            if (exportedChecks.isNotEmpty()) put("noul", exportedChecks.size)
        }
        val excludedByVersion = buildMap<Int, Int> {
            for (row in excludedDecisions) merge(row.stateFormatVersion, 1, Int::plus)
            for (check in excludedChecks) merge(check.stateFormatVersion, 1, Int::plus)
        }
        // One exported window is ONE serve shape (vision 05 §4 format scoping covers the
        // question schemas too): noul cases of mixed anchored-question versions cannot
        // share a manifest — that is incoherent data, not a subsettable corpus.
        val checkVersions = exportedChecks.map { it.anchoredNoulVersion }.distinct()
        require(checkVersions.size <= 1) {
            "RLCD export: noul cases of mixed anchoredNoulVersions $checkVersions in one window — " +
                "question schemas are format-scoped (vision 05 §4); export them separately"
        }
        return RlcdExportManifest(
            exportedAtEpochMs = now(),
            rowCount = exportedDecisions.size + exportedChecks.size,
            teacherPins = pins,
            mixedTeacher = distinctModels.size > 1,
            stateFormatVersions = (
                exportedDecisions.map { it.stateFormatVersion } +
                    exportedChecks.map { it.stateFormatVersion }
                ).distinct().sorted(),
            companionRowCount = companionRowCount,
            caseKinds = caseKinds,
            excludedRowCount = excludedDecisions.size + excludedChecks.size,
            excludedByStateFormatVersion = excludedByVersion,
            excludedByQuestionSchema = schemaExcluded,
            anchoredNoulVersion = checkVersions.singleOrNull(),
            chooseQuestionVersion = if (exportedDecisions.isNotEmpty()) Intents.INTENTS_VERSION else null,
            layaStateViewVersion = if (stateView == RlcdStateView.LAYA_VIEW) {
                LayaStateView.LAYA_STATE_VIEW_VERSION
            } else {
                null // canonical export: the rows' state text is the corpus view of record
            },
        )
    }

    /** Serializes a manifest (the caller composes it with the case JSONL). */
    fun encodeManifest(manifest: RlcdExportManifest): String = json.encodeToString(manifest)
}
