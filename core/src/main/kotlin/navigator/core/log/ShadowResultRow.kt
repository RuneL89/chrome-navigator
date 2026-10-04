package navigator.core.log

import kotlinx.serialization.Serializable

/**
 * The companion shadow-result row (vision 05 §2, amendment 2026-10-04 — owner-ratified):
 * the immutable record of a shadow answer that completed **after** its decision row
 * froze as a `shadowMiss`. One companion, keyed `(taskId, stepIndex)`, per decision —
 * the companion layer that keeps agreement-measurement density independent of shadow
 * latency.
 *
 * Laws (vision 05 §2 amendment, rules a–e):
 * - (a) a companion is legal only for a decision row logged with `shadow == null`
 *   (shadowMiss) — never alongside a decision row that already carries the shadow
 *   answer; exactly one companion per `(taskId, stepIndex)`. This is enforced at the
 *   persistence boundary (`:app` guard + unique index), not by convention.
 * - (b) both row types are immutable inserts — nothing is ever updated or deleted.
 * - (e) companions serve agreement measurement and diagnostics only; the RLCD training
 *   corpus stays complete in decision rows alone.
 *
 * Validation lives in [init] so deserialization of bad data fails loudly (core log
 * law). The comparable top-1 choice against the teacher is [ShadowAnswer.element].
 */
@Serializable
data class ShadowResultRow(
    val rowId: String,
    val taskId: String,
    val stepIndex: Int,
    val timestamp: Long,
    val shadow: ShadowRef,
    val shadowAnswer: ShadowAnswer,
    val latencyMs: Long,
) {
    init {
        require(rowId.isNotBlank()) { "ShadowResultRow.rowId must not be blank" }
        require(taskId.isNotBlank()) { "ShadowResultRow.taskId must not be blank" }
        require(stepIndex >= 0) { "ShadowResultRow: stepIndex must be >= 0, was $stepIndex" }
        require(timestamp > 0) { "ShadowResultRow: wall-clock timestamp must be > 0, was $timestamp" }
        require(latencyMs >= 0) { "ShadowResultRow: latencyMs must be >= 0, was $latencyMs" }
    }

    companion object {
        /**
         * The deterministic companion rowId for a `(taskId, stepIndex)` key: the key IS
         * the identity (rule a: exactly one companion per key), so the derived id is
         * idempotent — a duplicate companion for the same key collides on it and fails
         * the insert-only ABORT loudly.
         */
        fun rowIdFor(taskId: String, stepIndex: Int): String =
            "shadow-result:$taskId:$stepIndex"
    }
}

/**
 * One decision row joined with its (optional, late-arriving) companion shadow result
 * (vision 05 §3, amendment rule d) — the joined view agreement metrics are computed
 * over this pairing, never over either row type alone.
 */
data class JoinedDecisionRow(
    val decision: DecisionLogRow,
    val companion: ShadowResultRow?,
)

/**
 * The joined-view agreement metrics (vision 05 §3, amendment rule d): exact-match % and
 * mean KL over every sampled decision whose shadow answer exists in the joined view
 * (decision-row shadow answers + companion [ShadowResultRow]s), with coverage reported
 * alongside the percentage.
 */
data class JoinedAgreementMetrics(
    /** Decision rows that sampled the shadow (`shadowSampled=true`) — the denominator. */
    val sampledRows: Int,
    /** Sampled rows whose shadow answer exists in the joined view — the numerator. */
    val joinedRows: Int,
    /** Joined rows where the shadow's top-1 equaled the teacher's choice. */
    val exactMatches: Int,
    /** [exactMatches] / [joinedRows]; null over an empty joined view. */
    val exactMatchFraction: Double?,
    /** Mean KL(teacher ∥ shadow) in nats over the joined rows; null over an empty joined view. */
    val meanKl: Double?,
) {
    /**
     * Coverage (amendment rule d): joined / total-sampled — the fraction of sampled
     * decisions the percentage actually speaks for; 0.0 when nothing was sampled.
     */
    val coverage: Double
        get() = if (sampledRows == 0) 0.0 else joinedRows.toDouble() / sampledRows
}

/**
 * The UI-metric contract (vision 05 §3; phase 8B §6): Phases 9/11 read agreement as
 * `(percentage, coverage)` — never the raw row fraction. `coverage` is what makes an
 * 85%-over-a-thin-window figure honest.
 */
data class AgreementUiMetric(
    /** Top-1 exact-match percentage over the joined view; null over an empty joined view. */
    val percentage: Double?,
    /** Coverage of the percentage: joined / total-sampled (amendment rule d). */
    val coverage: Double,
)

/**
 * Agreement aggregation over the joined view (vision 05 §3, amendment 2026-10-04 —
 * pure data + math, zero I/O like the rest of `:core/log`; the `:app` DAO materializes
 * the view). The shadow answer of a sampled decision is, in order: the decision row's
 * own `shadowAnswer`, else its companion's. A view that carries BOTH for one key, or
 * two decision rows for one key, is incoherent data and fails loudly — the aggregation
 * never double-counts.
 */
object JoinedAgreement {

    private fun keyOf(taskId: String, stepIndex: Int): String = "$taskId#$stepIndex"

    /** Aggregates the joined view; incoherent views (double answers, duplicate keys) fail loudly. */
    fun metrics(view: List<JoinedDecisionRow>): JoinedAgreementMetrics {
        val seenKeys = HashSet<String>()
        var sampledRows = 0
        var joinedRows = 0
        var exactMatches = 0
        var klSum = 0.0
        for (entry in view) {
            val row = entry.decision
            val key = keyOf(row.taskId, row.stepIndex)
            require(seenKeys.add(key)) {
                "JoinedAgreement: duplicate decision row for key ($key) — the view would double-count it"
            }
            require(row.shadowAnswer == null || entry.companion == null) {
                "JoinedAgreement: decision row (${row.rowId}) carries a shadowAnswer AND a companion " +
                    "(amendment rule a) — this would double-count the step"
            }
            if (!row.shadowSampled) {
                // A companion implies the shadow ran: an unsampled row must be bare.
                require(entry.companion == null && row.shadowAnswer == null) {
                    "JoinedAgreement: unsampled decision row (${row.rowId}) carries a shadow answer " +
                        "or a companion — incoherent view"
                }
                continue
            }
            sampledRows += 1
            val answer = row.shadowAnswer ?: entry.companion?.shadowAnswer ?: continue
            joinedRows += 1
            if (Agreement.exactMatch(row.teacherAnswer, answer)) exactMatches += 1
            klSum += Agreement.klDivergence(row.teacherAnswer, answer)
        }
        return JoinedAgreementMetrics(
            sampledRows = sampledRows,
            joinedRows = joinedRows,
            exactMatches = exactMatches,
            exactMatchFraction = if (joinedRows == 0) null else exactMatches.toDouble() / joinedRows,
            meanKl = if (joinedRows == 0) null else klSum / joinedRows,
        )
    }

    /** The UI-metric function (phase 8B §6): `(percentage, coverage)`. */
    fun uiMetric(metrics: JoinedAgreementMetrics): AgreementUiMetric = AgreementUiMetric(
        percentage = metrics.exactMatchFraction?.let { it * 100.0 },
        coverage = metrics.coverage,
    )
}
