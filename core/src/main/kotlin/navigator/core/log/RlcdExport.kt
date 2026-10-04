package navigator.core.log

import kotlinx.serialization.Serializable

/**
 * One exported RLCD case (vision 05 §4): the upstream `docs/finetune.md` row shape —
 * `state` (canonical state text with its `stateFormatVersion` recorded), `questions`
 * (the **bucketed shadow form** — what a graduated Laya will actually be asked), and
 * `gold` (the **teacher's full probability distribution** over option ids — never a
 * bare label).
 */
@Serializable
data class RlcdCase(
    val state: String,
    val stateFormatVersion: Int,
    val questions: BucketedQuestion,
    val gold: Map<String, Double>,
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
 * the exported cases stay decision-row-only and the count never changes them.
 */
@Serializable
data class RlcdExportManifest(
    val exportedAtEpochMs: Long,
    val rowCount: Int,
    val teacherPins: List<RlcdTeacherPin>,
    val mixedTeacher: Boolean,
    val stateFormatVersions: List<Int>,
    val companionRowCount: Int = 0,
)

/**
 * The RLCD export (vision 05 §4 — "the training data IS the strategy"): a filtering +
 * reshaping of log rows into JSONL, one [RlcdCase] per line.
 *
 * Laws:
 * - the state text is copied verbatim — the export never rewrites it;
 * - `gold` is the teacher's distribution, keys sorted so exports are byte-canonical;
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
) {

    /** Exports [rows] as JSONL — one [RlcdCase] per line, in the given order. */
    fun export(rows: List<DecisionLogRow>): String =
        rows.joinToString(separator = "\n") { row -> json.encodeToString(toCase(row)) }

    /** Maps one log row to its exported case (filtering is the caller's window choice). */
    fun toCase(row: DecisionLogRow): RlcdCase = RlcdCase(
        state = row.stateText, // verbatim: never rewritten (vision 05 §4)
        stateFormatVersion = row.stateFormatVersion,
        questions = row.questionShadow,
        gold = row.teacherAnswer.probabilities.entries
            .sortedBy { it.key }
            .associate { it.key to it.value },
    )

    /** Builds the manifest for the exported window. [companionRowCount] is informational (rule e). */
    fun manifest(rows: List<DecisionLogRow>, companionRowCount: Int = 0): RlcdExportManifest {
        val pins = rows.map { RlcdTeacherPin(configId = it.teacher.configId, model = it.teacher.model) }
            .distinct()
        val distinctModels = pins.map { it.model }.distinct()
        return RlcdExportManifest(
            exportedAtEpochMs = now(),
            rowCount = rows.size,
            teacherPins = pins,
            mixedTeacher = distinctModels.size > 1,
            stateFormatVersions = rows.map { it.stateFormatVersion }.distinct().sorted(),
            companionRowCount = companionRowCount,
        )
    }

    /** Serializes a manifest (the caller composes it with the case JSONL). */
    fun encodeManifest(manifest: RlcdExportManifest): String = json.encodeToString(manifest)
}
