package navigator.app.experiment

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.app.loop.ExperimentProbe
import navigator.app.loop.RunResult
import navigator.app.loop.TerminalState
import java.io.File
import java.util.Locale

/**
 * PHASE_VEXP9 Stage 0 — the a11y engine's experiment evidence, written beside the run.
 *
 * The vision engine has always left `run-reports/vision-exp/<task>/` behind; the a11y
 * product path discarded its `RunResult` (the service used four fields and wrote them to
 * the no-op sink). This writer persists the run so the experiment can be analysed after
 * the fact — the measurement prerequisite in
 * `.state/vision-iter/PHASE_VEXP9_tree_first_vision_escalation.md` §4 Stage 0.
 *
 * Three artifacts per run, all under `<external files>/run-reports/a11y-exp/<taskId>/`:
 *
 * - `run.json` — the whole [RunResult] (terminal, timings, spend, ladder, answer, the
 *   already-scrubbed step log). The [RunResult] is scrubbed at the loop's finish
 *   boundary, so this file never carries a sanctioned secret.
 * - `steps.csv` — one row per DECISION, derived from the step log. Stage 0 is
 *   log-derived; the structured pre-action hook (shadow/rescue columns) arrives with the
 *   Stage-2 probe. Columns that stage has not filled yet are emitted EMPTY, never
 *   dropped — the header is the schema.
 * - `runs.csv` — one row per run.
 *
 * Purely a recorder. It never steers the loop, never calls a model, and never throws
 * into the caller (the service wraps the call).
 */
object ExperimentEvidence {

    /** The product evidence root (relative to the external files dir). */
    const val SUBDIR = "run-reports/a11y-exp"

    const val RUN_JSON = "run.json"
    const val STEPS_CSV = "steps.csv"
    const val RUNS_CSV = "runs.csv"

    /**
     * The run-level metadata the loop does not own. [stopReason] is one of
     * `ENGINE_TERMINAL`, `MONITOR_CARD_STOP`, `OWNER_CANCEL`, `FORCE_STOP`, `UNKNOWN`.
     */
    data class Meta(
        val goalId: String? = null,
        val startUrl: String? = null,
        val arm: String = "tree",
        val resetOk: Boolean? = null,
        val stopReason: String = "ENGINE_TERMINAL",
        val stoppedAtMs: Long? = null,
        val monitorStop: Boolean = false,
    )

    /**
     * Writes the three artifacts. Null [baseDir] = memory-only (a test or a build without
     * an external files dir); returns the run directory when written.
     */
    fun write(
        baseDir: File?,
        runId: String,
        result: RunResult,
        meta: Meta = Meta(),
        seams: List<ExperimentProbe.SeamHit> = emptyList(),
    ): File? {
        val root = baseDir ?: return null
        return try {
            root.mkdirs()
            val dir = File(root, runId).apply { mkdirs() }
            File(dir, RUN_JSON).writeText(runJson(runId, result, meta, seams).toString())
            val rows = parseSteps(runId, result.stepLog)
            File(dir, STEPS_CSV).writeText(stepsCsv(rows))
            File(dir, RUNS_CSV).writeText(runsCsv(runId, result, meta, rows))
            dir
        } catch (e: Exception) {
            // Evidence is a recorder; a full disk must never turn a finished run into a
            // crash on the way out.
            null
        }
    }

    // -- run.json -------------------------------------------------------------------

    fun runJson(
        runId: String,
        r: RunResult,
        m: Meta,
        seams: List<ExperimentProbe.SeamHit> = emptyList(),
    ) = buildJsonObject {
        put("runId", runId)
        put("taskId", r.taskId)
        put("goalId", m.goalId ?: r.goalId)
        put("goal", r.goal)
        put("startUrl", m.startUrl)
        put("arm", m.arm)
        put("terminal", r.terminal.name)
        put("terminalDetail", r.terminalDetail)
        put("success", r.terminal == TerminalState.GOAL_ACHIEVED)
        put("triage", r.triage)
        put("triageClass", r.triageClass)
        put("budgetProfile", r.budgetProfile)
        put("steps", r.steps)
        put("rowsLogged", r.rowsLogged)
        put("metaDecisions", r.metaDecisions)
        put("logGaps", r.logGaps)
        put("gateEvents", r.gateEvents)
        put("spendMicroUsd", r.spendMicroUsd)
        put("writerSpendMicroUsd", r.writerSpendMicroUsd)
        put("elapsedMs", r.elapsedMs)
        put("partialResult", r.partialResult)
        put("softWarnings", buildJsonArray { r.softWarnings.forEach { add(JsonPrimitive(it)) } })
        put("resetOk", m.resetOk)
        put("stopReason", m.stopReason)
        put("stoppedAtMs", m.stoppedAtMs)
        put("monitorStop", m.monitorStop)
        put("exit", (r.exit?.let {
            buildJsonObject {
                put("path", it.path)
                put("guardLevel", it.guardLevel)
                put("noul", it.noul)
                put("finishChecks", it.finishChecks)
                put("completionCardShown", it.completionCardShown)
                put("completionCardResolution", it.completionCardResolution)
            }
        }) ?: JsonNull)
        put("ladder", (r.ladder?.let { l ->
            buildJsonObject {
                put("version", l.version)
                put("generation", l.generation)
                put("summary", l.summary)
                put("taskClass", l.taskClass)
                put("subtasks", buildJsonArray {
                    l.subtasks.forEach { s ->
                        add(
                            buildJsonObject {
                                put("instruction", s.instruction)
                                put("done", s.done)
                                put("completed", s.completed)
                                put("active", s.active)
                            },
                        )
                    }
                })
            }
        } ?: JsonNull))
        put("answer", (r.answer?.let { a ->
            buildJsonObject {
                put("text", a.text)
                put("promptVersion", a.promptVersion)
                put("inputTokens", a.inputTokens)
                put("outputTokens", a.outputTokens)
                put("costMicroUsd", a.costMicroUsd)
            }
        } ?: JsonNull))
        put("targets", (r.targets?.let { t ->
            buildJsonObject {
                put("tracked", t.tracked)
                put("processed", t.processed)
                put("pending", t.pending)
                put("skipped", t.skipped)
                put("drained", t.drained)
            }
        } ?: JsonNull))
        put("plan", (r.plan?.let { p ->
            buildJsonObject {
                put("version", p.version)
                put("subgoals", buildJsonArray { p.subgoals.forEach { add(JsonPrimitive(it)) } })
            }
        } ?: JsonNull))
        put("stepLog", buildJsonArray { r.stepLog.forEach { add(JsonPrimitive(it)) } })
        put("seams", buildJsonArray {
            seams.forEach { s ->
                add(
                    buildJsonObject {
                        put("seam", s.seam)
                        put("step", s.stepIndex)
                        put("subtask", s.subtask)
                        put("detail", s.detail)
                    },
                )
            }
        })
    }

    // -- steps.csv ------------------------------------------------------------------

    /** One decision row. Stage 0 fills the log-derivable columns; later stages add the rest. */
    data class StepRow(
        val runId: String,
        val stepIndex: Int,
        val tMs: Long,
        val subtask: Int,
        val intent: String,
        val pChosen: Double?,
        val margin: Double?,
        val route: String,
        val slateSize: Int?,
        val targetId: String,
        val targetScore: Double?,
        val action: String,
        val outcome: String,
        val pageChanged: String = "unknown",
        val advanced: Boolean = false,
        val seam: String = "NONE",
    )

    val STEPS_HEADER = listOf(
        "run_id", "step_index", "t_ms", "subtask", "intent", "p_chosen", "margin",
        "route", "slate_size", "target_id", "target_score", "action", "outcome",
        "page_changed", "advanced", "seam",
        // Stage 2+ (shadow), Stage 3+ (intervention) — present from the start so the
        // schema never changes between iterations.
        "shadow_sampled", "shadow_mode", "shadow_proposal", "shadow_target_id",
        "shadow_iou", "shadow_confidence", "shadow_agrees",
        "escalated", "escalation_mode", "escalation_result",
        "correctness", "correctness_basis", "recovery_cost_ms",
    )

    fun stepsCsv(rows: List<StepRow>): String = buildString {
        appendLine(STEPS_HEADER.joinToString(","))
        rows.forEach { r ->
            val fields = mutableListOf(
                csvField(r.runId),
                r.stepIndex.toString(),
                r.tMs.toString(),
                if (r.subtask >= 0) r.subtask.toString() else "",
                csvField(r.intent),
                r.pChosen?.let { num(it) } ?: "",
                r.margin?.let { num(it) } ?: "",
                r.route,
                r.slateSize?.toString() ?: "",
                csvField(r.targetId),
                r.targetScore?.let { num(it) } ?: "",
                csvField(r.action),
                csvField(r.outcome),
                r.pageChanged,
                r.advanced.toString(),
                r.seam,
            )
            // The later-stage columns stay EMPTY until their stage fills them — never
            // dropped, so the header is a stable schema across iterations.
            while (fields.size < STEPS_HEADER.size) fields.add("")
            appendLine(fields.joinToString(","))
        }
    }

    // -- runs.csv -------------------------------------------------------------------

    val RUNS_HEADER = listOf(
        "run_id", "goal_id", "arm", "terminal", "success", "steps", "elapsed_ms",
        "t_first_action_ms", "jev_calls", "haiku_calls", "images_sent", "spend_usd",
        "escalations", "escalations_by_mode", "interventions", "interventions_correct",
        "failure_class", "reset_ok", "stop_reason", "stopped_at_ms",
    )

    fun runsCsv(runId: String, r: RunResult, m: Meta, rows: List<StepRow>): String {
        val firstAction = rows.firstOrNull { it.action.isNotEmpty() }?.tMs?.toString() ?: ""
        val success = r.terminal == TerminalState.GOAL_ACHIEVED
        val failureClass = if (success) "" else rows.lastOrNull { it.seam != "NONE" }?.seam ?: ""
        return buildString {
            appendLine(RUNS_HEADER.joinToString(","))
            appendLine(
                listOf(
                    csvField(runId),
                    csvField(m.goalId ?: r.goalId ?: ""),
                    csvField(m.arm),
                    r.terminal.name,
                    success.toString(),
                    r.steps.toString(),
                    r.elapsedMs.toString(),
                    firstAction,
                    "", // jev_calls — the Stage-2 probe fills it
                    "", // haiku_calls
                    "", // images_sent
                    num(r.spendMicroUsd.toDouble() / 1_000_000.0),
                    "0", // escalations
                    "", // escalations_by_mode
                    "0", // interventions
                    "", // interventions_correct
                    csvField(failureClass),
                    m.resetOk?.toString() ?: "",
                    m.stopReason,
                    m.stoppedAtMs?.toString() ?: "",
                ).joinToString(","),
            )
        }
    }

    // -- the log-derived parser -----------------------------------------------------

    private val STAMP = Regex("""^\[t\+(\d+)ms] (.*)$""")
    private val INTENT = Regex("""^decide: intent '([^']+)' \(p=([-0-9.eE+]+), margin=([-0-9.eE+]+)\)""")
    private val INTENT_LOW = Regex("""^decide: intent '([^']+)' LOW \(p=([-0-9.eE+]+)\)""")
    private val INTENT_PINNED = Regex("""^decide: intent '([^']+)' \(the stage-1 re-ask""")
    private val RECOVERY = Regex("""^decide: recovery set -> '([^']+)'""")
    private val TARGET = Regex("""^decide: stage-2 target ".*" \[(n\d+)] \(([-0-9.eE+]+) of (\d+) scored\)""")
    private val EXECUTE = Regex("""^execute: (\w+) ".*" at \(\d+,\d+\).*-> (.+)$""")
    private val ADVANCE = Regex("""^ladder: subtask (\d+)/(\d+) IS-DONE verified""")
    private val SUBTASK_FAILED = Regex("""^ladder: subtask (\d+)/(\d+) FAILED""")
    private val SUBTASK_SEEN = Regex("""^ladder: subtask (\d+)/(\d+) """)
    private val UNUSABLE = Regex("""^observe: (tree still unusable|unusable tree)""")
    private val STUCK = Regex("""^stuck: """)
    private val UNCERTAIN = Regex("""^decide: (UNCERTAIN|still UNCERTAIN)""")

    /**
     * Parses the loop's step log into decision rows. Only the lines the loop's own code
     * emits are recognised — each pattern is pinned by a test against a real log sample.
     *
     * Step boundary: a `decide: intent`/`decide: recovery set` line closes the previous
     * row only once that row has an executed action; otherwise it UPDATES the row's
     * decision (the uncertainty handshake's re-ask must not become a second step).
     */
    fun parseSteps(runId: String, log: List<String>): List<StepRow> {
        val out = mutableListOf<StepRow>()
        var cur: StepRow? = null

        fun close() {
            cur?.let { out.add(it) }
            cur = null
        }

        fun seam(row: StepRow, name: String): StepRow =
            if (row.seam == "NONE") row.copy(seam = name) else row

        log.forEach { raw ->
            val match = STAMP.matchEntire(raw) ?: return@forEach
            val t = match.groupValues[1].toLongOrNull() ?: 0L
            val line = match.groupValues[2]

            val intentMatch = INTENT.find(line)
            val lowMatch = INTENT_LOW.find(line)
            val pinnedMatch = INTENT_PINNED.find(line)
            val recoveryMatch = RECOVERY.find(line)

            if (intentMatch != null || lowMatch != null || pinnedMatch != null || recoveryMatch != null) {
                val id: String
                val p: Double?
                val margin: Double?
                val route: String
                when {
                    intentMatch != null -> {
                        id = intentMatch.groupValues[1]
                        p = intentMatch.groupValues[2].toDoubleOrNull()
                        margin = intentMatch.groupValues[3].toDoubleOrNull()
                        route = "HIGH"
                    }
                    lowMatch != null -> {
                        id = lowMatch.groupValues[1]
                        p = lowMatch.groupValues[2].toDoubleOrNull()
                        margin = null
                        route = "LOW"
                    }
                    pinnedMatch != null -> {
                        id = pinnedMatch.groupValues[1]
                        p = null
                        margin = null
                        route = "HIGH"
                    }
                    else -> {
                        id = recoveryMatch!!.groupValues[1]
                        p = null
                        margin = null
                        route = "HIGH"
                    }
                }
                val existing = cur
                cur = if (existing != null && existing.action.isEmpty()) {
                    // The same decision refined (the handshake re-ask): keep the row, take
                    // the latest decision, and keep a seam already observed.
                    existing.copy(
                        tMs = t,
                        intent = id,
                        pChosen = p,
                        margin = margin,
                        route = if (existing.route == "UNCERTAIN") "UNCERTAIN" else route,
                    )
                } else {
                    close()
                    StepRow(
                        runId = runId,
                        stepIndex = out.size,
                        tMs = t,
                        subtask = -1,
                        intent = id,
                        pChosen = p,
                        margin = margin,
                        route = route,
                        slateSize = null,
                        targetId = "",
                        targetScore = null,
                        action = "",
                        outcome = "",
                    )
                }
            }

            val row = cur ?: return@forEach

            TARGET.find(line)?.let { m ->
                val slate = m.groupValues[3].toIntOrNull()
                cur = row.copy(
                    targetId = m.groupValues[1],
                    targetScore = m.groupValues[2].toDoubleOrNull(),
                    slateSize = slate,
                )
                return@forEach
            }
            EXECUTE.find(line)?.let { m ->
                cur = row.copy(action = m.groupValues[1], outcome = m.groupValues[2].trim())
                return@forEach
            }
            UNUSABLE.find(line)?.let { cur = seam(row, "UNREADABLE"); return@forEach }
            UNCERTAIN.find(line)?.let {
                cur = seam(row, "UNCERTAIN").copy(route = "UNCERTAIN")
                return@forEach
            }
            if (line.contains("empty-slate grace") || line.contains("empty slate for")) {
                cur = seam(row, "EMPTY_SLATE")
                return@forEach
            }
            if (line.contains("no-good-target") || line.contains("NO-GOOD-TARGET")) {
                cur = seam(row, "NO_GOOD_TARGET")
                return@forEach
            }
            ADVANCE.find(line)?.let { m ->
                cur = row.copy(
                    subtask = m.groupValues[1].toIntOrNull() ?: row.subtask,
                    advanced = true,
                )
                return@forEach
            }
            SUBTASK_FAILED.find(line)?.let { m ->
                cur = seam(row, "NO_PROGRESS").copy(
                    subtask = m.groupValues[1].toIntOrNull() ?: row.subtask,
                )
                return@forEach
            }
            SUBTASK_SEEN.find(line)?.let { m ->
                if (row.subtask < 0) {
                    cur = row.copy(subtask = m.groupValues[1].toIntOrNull() ?: row.subtask)
                }
                return@forEach
            }
            if (STUCK.containsMatchIn(line)) {
                cur = seam(row, "NO_PROGRESS")
            }
        }
        close()
        return out
    }

    // -- formatting -----------------------------------------------------------------

    private fun num(v: Double): String = String.format(Locale.ROOT, "%.3f", v)

    /** RFC-4180-ish: quote when the value contains a comma, quote, or newline. */
    fun csvField(value: String?): String {
        val s = value ?: return ""
        val needsQuote = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuote) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}

/** `JsonObjectBuilder.put(String, null)` is ambiguous; this makes the intent explicit. */
private fun JsonObjectBuilder.put(key: String, value: String?) {
    if (value == null) put(key, JsonNull) else put(key, value)
}
