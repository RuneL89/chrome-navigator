package navigator.app.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The harness's exported run reports (the Gate 6.2 evidence artifacts), as pure data +
 * serializers — own file so the JSON contract is JVM-testable without the activity
 * (and so the always-include-shadow guarantee, owner-directed 2026-10-06, is pinned by
 * a unit test).
 */

/** One exported run (the Gate 6.2 evidence artifact). */
@Serializable
data class RunReport(
    val goalId: String?,
    val family: String?,
    val goal: String,
    val taskId: String,
    val engine: String,
    val autoDeny: Boolean,
    val triage: String,
    val terminal: String,
    val terminalDetail: String,
    val expectedTerminal: String?,
    val terminalMatchesExpected: Boolean?,
    val steps: Int,
    val rowsLogged: Int,
    val metaDecisions: Int,
    /**
     * Navigation decisions without a row. The rephrase-recovery stub is a goal-text
     * meta-decision ([metaDecisions], no page action, no row) and is EXCLUDED by the
     * formula (loop: `logGaps = steps - rows - metaDecisions`), so "zero log gaps"
     * stays meaningful: every navigation decision is logged exactly once.
     */
    val logGaps: Int,
    val gateEvents: Int,
    val spendMicroUsd: Long,
    val elapsedMs: Long,
    val softWarnings: List<String>,
    val partialResult: String,
    val startedAt: Long,
    val finishedAt: Long,
    /** How many times this goal was attempted in this suite run (1 = no retry). */
    val attempts: Int = 1,
    /** True when attempt 1 ended in observation starvation and a retry followed. */
    val flakeRetried: Boolean = false,
    val stepLog: List<String>,
    /** Phase 8 shadow evidence. Null ONLY when the shadow was OFF for this run —
     *  whenever the launch requested the shadow, this object is ALWAYS present (see
     *  [ShadowReport.note] for the degraded cases). */
    val shadow: ShadowReport? = null,
    /**
     * Phase 8C fallback-composition evidence (vision 02 §7): the composed engine's
     * escalation count + rate over this run. Null for every single-engine mode
     * (STUB / LIVE(jev) / LAYA) — the field's absence must never be ambiguous with
     * "no escalations" when the owner reads the UAT comparison.
     */
    val escalations: EscalationStats? = null,
)

/**
 * The fallback composition's per-run counters (from [navigator.core.decision.FallbackEngine.stats]):
 * how many decisions the composition settled and how many consulted the fallback —
 * the UAT comparison's escalation-rate evidence.
 */
@Serializable
data class EscalationStats(
    val decisions: Long,
    val escalations: Long,
    /** escalations / decisions over this run (0.0 over zero decisions). */
    val rate: Double,
)

/**
 * The shadow side of one run (Gate 8.4/8B evidence): coverage is the JOINED-VIEW
 * fraction (vision 05 §3 amendment 2026-10-04, rule d: joined rows over total sampled —
 * decision-row shadows + companion `ShadowResultRow`s; the in-memory enrichment ratio
 * is only the fallback when the joined read is unavailable); `companionRows` /
 * `droppedAfterClose` are the amendment's companion counters. Latencies are the
 * MEASURED laya call times (nearest-rank percentiles over the completed decisions).
 */
@Serializable
data class ShadowReport(
    val enabled: Boolean,
    /** The sampling period this run ran with (1 = every step, vision 02 §4.3). */
    val everyNth: Long,
    val decisions: Long,
    val sampled: Long,
    val completed: Long,
    val missed: Long,
    val failed: Long,
    val coverage: Double,
    val forwardLatencyP50Ms: Long,
    val forwardLatencyP95Ms: Long,
    val totalLatencyP95Ms: Long,
    /**
     * Late shadow answers landed as companion rows (amendment 2026-10-04). No default:
     * the report Json omits defaulted properties, and a missing key must never be
     * ambiguous with "no companions" when the owner reads coverage per UAT test.
     */
    val companionRows: Long,
    /** Completions dropped because the logger was already closed (amendment rule c). */
    val droppedAfterClose: Long,
    /** Companion inserts the persistence guard rejected (must stay 0; loud if not). */
    val companionRejected: Long,
    /**
     * Present (non-null) only on the degraded paths introduced by the owner-directed
     * 2026-10-06 fix: the shadow was requested but no router snapshot could be taken
     * (session open failed, harness error before the loop, ...) — the counters are then
     * zeroed and this note says why, so the block is never silently empty.
     */
    val note: String? = null,
)

/** The batch report: every family at its defined terminal state, zero log gaps. */
@Serializable
data class SuiteReport(
    val runs: List<RunReport>,
    val allPassed: Boolean,
    val finishedAt: Long,
)

object RunReports {

    private val pretty = Json { prettyPrint = true }

    fun json(report: RunReport): String = pretty.encodeToString(RunReport.serializer(), report)

    fun suiteJson(suite: SuiteReport): String = pretty.encodeToString(SuiteReport.serializer(), suite)
}
