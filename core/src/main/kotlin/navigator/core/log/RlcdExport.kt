package navigator.core.log

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import navigator.core.decision.ChoiceResult
import navigator.core.decision.NoulQuestion
import navigator.core.STATE_FORMAT_VERSION

/**
 * The training-case kind of one exported RLCD row (vision 05 §4 v1.5.0): a graduated
 * Laya is asked BOTH at serve time — `choose` over the bucketed page action form,
 * `noul` over the anchored goal-achieved proposition — so the corpus carries both.
 */
@Serializable
enum class RlcdCaseKind {
    @SerialName("choose") CHOOSE,
    @SerialName("noul") NOUL,
}

/**
 * One exported RLCD `choose` case (vision 05 §4): the upstream `docs/finetune.md` row
 * shape — `state` (canonical state text with its `stateFormatVersion` recorded),
 * `questions` (the **bucketed shadow form** — what a graduated Laya will actually be
 * asked), and `gold` (the **full probability distribution** over option ids — never a
 * bare label).
 */
@Serializable
data class RlcdChooseCase(
    val kind: RlcdCaseKind = RlcdCaseKind.CHOOSE,
    val state: String,
    val stateFormatVersion: Int,
    val questions: BucketedQuestion,
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
 * The export manifest (vision 05 §4): teacher pin(s) seen in the window, the
 * mixed-teacher flag, row count, and the state-format versions present. A
 * mixed-teacher corpus (any teacher bump mid-window, vision 02 §5) is flagged so
 * fine-tune runs can subset if desired.
 *
 * [companionRowCount] (vision 05 §2 amendment 2026-10-04, rule e) is INFORMATIONAL
 * only: companion `ShadowResultRow`s serve agreement measurement, never training —
 * the exported cases are never touched by them.
 *
 * v1.5.0 (exit-check rows + export kinds): [caseKinds] counts the exported cases by
 * kind; [excludedRowCount]/[excludedByStateFormatVersion] count rows of other format
 * versions LOUDLY (format scoping — never silently dropped); [anchoredNoulVersion] is
 * the noul cases' question-schema version; [layaStateViewVersion] names the state view
 * the noul `state` carries — null pre-08H, where the canonical text is exported
 * verbatim and no laya-view builder exists yet (vision 05 §4 "State view").
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
    val anchoredNoulVersion: Int? = null,
    val layaStateViewVersion: Int? = null,
)

/**
 * The RLCD export (vision 05 §4 — "the training data IS the strategy"): a filtering +
 * reshaping of log rows into JSONL — one [RlcdChooseCase] per decision row and one
 * [RlcdNoulCase] per exit-check row.
 *
 * Laws:
 * - the state text is copied verbatim — the export never rewrites it;
 * - **authoritative gold**: gold is always the EXECUTED answer — the fallback's when
 *   `escalated=true`, the primary's otherwise. A student engine is never its own gold:
 *   in laya-primary modes an escalated row's primary answer is the mistake the
 *   fallback corrected, and training on it would teach the mistake (vision 05 §4);
 * - **format scoping**: only rows whose `stateFormatVersion` matches the current serve
 *   shape export; rows of other versions are excluded and counted by version in the
 *   [manifest] (loudly, never silently dropped);
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
) {

    /** Exports [decisionRows] + [exitChecks] as JSONL — choose cases first (row order),
     *  then noul cases (row order); one case per format-matching row. */
    fun export(decisionRows: List<DecisionLogRow>, exitChecks: List<ExitCheckRow> = emptyList()): String {
        val chooseLines = decisionRows
            .filter { it.stateFormatVersion == serveStateFormatVersion }
            .map { row -> json.encodeToString(RlcdChooseCase.serializer(), toCase(row)) }
        val noulLines = exitChecks
            .filter { it.stateFormatVersion == serveStateFormatVersion }
            .map { check -> json.encodeToString(RlcdNoulCase.serializer(), toCase(check)) }
        return (chooseLines + noulLines).joinToString(separator = "\n")
    }

    /** Maps one decision row to its exported `choose` case (authoritative-gold law applied). */
    fun toCase(row: DecisionLogRow): RlcdChooseCase {
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
            state = row.stateText, // verbatim: never rewritten (vision 05 §4)
            stateFormatVersion = row.stateFormatVersion,
            questions = row.questionShadow,
            gold = goldAnswer.probabilities.entries
                .sortedBy { it.key }
                .associate { it.key to it.value },
        )
    }

    /** Maps one exit-check row to its exported `noul` case (authoritative-gold law applied). */
    fun toCase(check: ExitCheckRow): RlcdNoulCase = RlcdNoulCase(
        state = check.stateText, // verbatim: never rewritten (vision 05 §4)
        stateFormatVersion = check.stateFormatVersion,
        question = check.question,
        anchoredNoulVersion = check.anchoredNoulVersion,
        gold = check.fallbackAnswer?.pTrue ?: check.answer.pTrue,
    )

    /**
     * Builds the manifest for the exported window: format-scoped cases + loud
     * exclusion counts. [companionRowCount] is informational (amendment rule e).
     */
    fun manifest(
        decisionRows: List<DecisionLogRow>,
        exitChecks: List<ExitCheckRow> = emptyList(),
        companionRowCount: Int = 0,
    ): RlcdExportManifest {
        val exportedDecisions = decisionRows.filter { it.stateFormatVersion == serveStateFormatVersion }
        val exportedChecks = exitChecks.filter { it.stateFormatVersion == serveStateFormatVersion }
        val excludedDecisions = decisionRows.filterNot { it.stateFormatVersion == serveStateFormatVersion }
        val excludedChecks = exitChecks.filterNot { it.stateFormatVersion == serveStateFormatVersion }
        val pins = (exportedDecisions.map { RlcdTeacherPin(configId = it.teacher.configId, model = it.teacher.model) } +
            exportedChecks.map { RlcdTeacherPin(configId = it.teacher.configId, model = it.teacher.model) })
            .distinct()
        val distinctModels = pins.map { it.model }.distinct()
        val caseKinds = buildMap {
            if (exportedDecisions.isNotEmpty()) put("choose", exportedDecisions.size)
            if (exportedChecks.isNotEmpty()) put("noul", exportedChecks.size)
        }
        val excludedByVersion = buildMap<Int, Int> {
            for (row in excludedDecisions) merge(row.stateFormatVersion, 1, Int::plus)
            for (check in excludedChecks) merge(check.stateFormatVersion, 1, Int::plus)
        }
        // One exported window is ONE serve shape (vision 05 §4 format scoping covers the
        // question schema too): noul cases of mixed anchored-question versions cannot
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
            anchoredNoulVersion = checkVersions.singleOrNull(),
            layaStateViewVersion = null, // pre-08H: canonical state text, no laya view yet
        )
    }

    /** Serializes a manifest (the caller composes it with the case JSONL). */
    fun encodeManifest(manifest: RlcdExportManifest): String = json.encodeToString(manifest)
}
