package navigator.core.log

import kotlin.math.abs
import kotlin.math.ln

/**
 * The agreement calculators (vision 05 §3 — ratified definition):
 * - the **UI metric** is top-1 exact match between the shadow's element choice and the
 *   teacher's choice, aggregated over all rows where both answers exist ("Laya agreed
 *   with the teacher on X% of decisions"; graduation consumes this figure, vision 02 §6);
 * - the **logged metric** is per-decision KL divergence KL(teacher ∥ shadow) over the
 *   shared option space, retained for plateau analysis (vision 02 §6).
 */
object Agreement {

    /** Tolerance for the "distributions are normalized" assertion (Σp = 1). */
    const val NORMALIZATION_TOLERANCE: Double = DecisionLogRow.NORMALIZATION_TOLERANCE

    /**
     * The provider emits 2-decimal probabilities (wire contract, vision 02 §2), so a
     * LIVE distribution over ~20 options can sum to e.g. 0.99 (measured: 6 of 37 rows in
     * the 2026-10-05 pre-UAT live sweep). A distribution within [NORMALIZATION_TOLERANCE]
     * of 1.0 is used EXACTLY as stored; within [WIRE_ROUNDING_TOLERANCE] it is
     * renormalized (divided by its sum — a sub-2% uniform rescale, far below the metric's
     * reporting precision); beyond that, the non-normalized input still fails loudly.
     * Without this tolerance, live-rounded rows either fail the enrichment (counted as a
     * shadow failure) or throw out of the joined-view read — killing the Phase-8B
     * companion amendment on exactly the live data it exists for.
     */
    const val WIRE_ROUNDING_TOLERANCE: Double = 0.02

    /**
     * Zero-probability edge convention (documented, vision 05 §3 "KL over the shared
     * option space"): the KL limit `Σ p·ln(p/q)` is taken literally for `p → 0`
     * (`0·ln(0/q) = 0` — those terms contribute nothing), while `q → 0` under a
     * `p > 0` term is floored at [ZERO_PROBABILITY_FLOOR] — the standard epsilon
     * convention. Flooring keeps the metric finite and loggable (JSON cannot carry
     * Infinity): a shadow assigning zero mass where the teacher has mass earns a large
     * but bounded penalty (`p·ln(p/1e-12) ≈ 27.6·p` per option) instead of +∞.
     */
    const val ZERO_PROBABILITY_FLOOR: Double = 1e-12

    /**
     * Top-1 exact match (the UI metric): the shadow's final element choice equals the
     * teacher's choice. The comparison is on the shared flat option-id space — the
     * shadow's intermediate bucket choice is not compared.
     */
    fun exactMatch(teacher: TeacherAnswer, shadow: ShadowAnswer): Boolean =
        teacher.chosen == shadow.element.chosen

    /**
     * KL(teacher ∥ shadow) in nats (natural log) over the shared option space: the union
     * of both distributions' keys, missing keys zero-filled. Both inputs are asserted in
     * range and normalized ([NORMALIZATION_TOLERANCE] exact; within
     * [WIRE_ROUNDING_TOLERANCE] renormalized for the computation; beyond, a
     * non-normalized distribution fails loudly, never silently skews the metric).
     * Mathematically ≥ 0; bitwise-identical distributions return exactly 0.0.
     */
    fun klDivergence(teacher: TeacherAnswer, shadow: ShadowAnswer): Double =
        klDivergence(teacher.probabilities, shadow.element.probabilities)

    /** Distribution-level overload of [klDivergence] (same law, no answer wrappers). */
    fun klDivergence(teacher: Map<String, Double>, shadow: Map<String, Double>): Double {
        val teacherScale = normalizationScale("teacher", teacher)
        val shadowScale = normalizationScale("shadow", shadow)
        var kl = 0.0
        for (key in teacher.keys + shadow.keys) {
            val p = (teacher[key] ?: 0.0) / teacherScale
            val q = (shadow[key] ?: 0.0) / shadowScale
            if (p == 0.0) continue // limit convention: 0·ln(0/q) = 0
            kl += p * ln(p / maxOf(q, ZERO_PROBABILITY_FLOOR))
        }
        return kl
    }

    /**
     * Validates the distribution (non-empty, in [0,1], Σp within
     * [WIRE_ROUNDING_TOLERANCE] of 1) and returns the divisor that normalizes it:
     * exactly-normalized inputs (within [NORMALIZATION_TOLERANCE]) return 1.0 so stored
     * fixture values feed the computation unchanged.
     */
    private fun normalizationScale(name: String, distribution: Map<String, Double>): Double {
        require(distribution.isNotEmpty()) { "KL: $name distribution is empty" }
        val outOfRange = distribution.values.filter { it !in 0.0..1.0 }
        require(outOfRange.isEmpty()) { "KL: $name probabilities out of range [0,1]: $outOfRange" }
        val sum = distribution.values.sum()
        require(abs(sum - 1.0) <= WIRE_ROUNDING_TOLERANCE) {
            "KL: $name distribution is not normalized (Σp = $sum, tolerance $WIRE_ROUNDING_TOLERANCE)"
        }
        return if (abs(sum - 1.0) <= NORMALIZATION_TOLERANCE) 1.0 else sum
    }
}
