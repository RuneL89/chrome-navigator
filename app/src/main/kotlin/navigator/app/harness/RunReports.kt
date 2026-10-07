package navigator.app.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The harness's exported run reports (the Gate 6.2 evidence artifacts), as pure data +
 * serializers — own file so the JSON contract is JVM-testable without the activity
 * (and so the always-include-shadow guarantee, owner-directed 2026-10-06, is pinned by
 * a unit test).
 */

/**
 * The exit block of a GOAL_ACHIEVED run (vision 04 §3 v1.2.0, Phase 8J): the run
 * report's counterpart of the terminal row's `exit` provenance (doc 05 v1.4.0) plus
 * the family counters the owner reads per UAT: how many finish checks it took and
 * whether the completion card arbitrated. Null for every other terminal.
 */
@Serializable
data class ExitReport(
    /** HIGH | SUSTAINED | OWNER_CONFIRM (vision 04 §3 v1.2.0). */
    val path: String,
    /** WEAK | STRONG (vision 04 §3 v1.2.0). */
    val guardLevel: String,
    val noul: Double,
    /** Finish checks the exit policy evaluated before accepting. */
    val finishChecks: Int,
    /** Whether the completion card was shown in this task (max one per task). */
    val completionCardShown: Boolean,
    /** CONFIRM_DONE | KEEP_GOING | TIMEOUT, when the card was shown. */
    val completionCardResolution: String? = null,
)

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
    /**
     * Phase 8D (vision 04 §1/§5): the triage class serial name and the per-class
     * budget profile that governed the run. Null when triage did not resolve to a
     * task class (impossible / failed pre-triage) — never ambiguous with a resolved
     * class, and absent from the JSON on those paths (defaults omitted).
     */
    val triageClass: String? = null,
    val budgetProfile: String? = null,
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
    /**
     * Phase 8J exit provenance (vision 04 §3 v1.2.0): the goal-achieved path, guard
     * level, noul, finish-check count and completion-card record. Null unless the run
     * ended GOAL_ACHIEVED — never ambiguous with "no exit provenance" on other terminals.
     */
    val exit: ExitReport? = null,
    /**
     * Phase 08G (vision 04 §1): the action-multi tracker's final counters. Null for
     * every non-action-multi run.
     */
    val targets: TargetTrackerReport? = null,
    /**
     * Phase 08G plan-proposal provenance (vision 04 §1, vision 05 §2 `plan`): context,
     * never executor (ART-2). Null on the deterministic default path.
     */
    val plan: PlanReport? = null,
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
 * Phase 08G (vision 04 §1): the action-multi target tracker's final counters — the
 * working memory the completion guard drains and the L1 stuck hint quotes. Present only
 * on action-multi runs; the report Json omits the key elsewhere (defaults omitted).
 */
@Serializable
data class TargetTrackerReport(
    val tracked: Int,
    val processed: Int,
    val pending: Int,
    val skipped: Int,
    /** The WEAK guard predicate: none pending (skipped rows count as resolved). */
    val drained: Boolean,
)

/**
 * Phase 08G (vision 04 §1 plan-proposal law, vision 05 §2 `plan`): the triage-time plan
 * provenance — which prompt surface drafted the subgoal plan and the subgoals. The plan
 * is context, never executor (ART-2). Null on the deterministic default path (no plan
 * until the Phase-10 writer); omitted from the JSON there.
 */
@Serializable
data class PlanReport(
    val version: String,
    val subgoals: List<String>,
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
