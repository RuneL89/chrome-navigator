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
     * of both distributions' keys, missing keys zero-filled. Both inputs are asserted
     * normalized (Σp = 1 within [NORMALIZATION_TOLERANCE], every value in [0,1]) — a
     * non-normalized distribution fails loudly, never silently skews the metric.
     * Mathematically ≥ 0; bitwise-identical distributions return exactly 0.0.
     */
    fun klDivergence(teacher: TeacherAnswer, shadow: ShadowAnswer): Double =
        klDivergence(teacher.probabilities, shadow.element.probabilities)

    /** Distribution-level overload of [klDivergence] (same law, no answer wrappers). */
    fun klDivergence(teacher: Map<String, Double>, shadow: Map<String, Double>): Double {
        assertNormalized("teacher", teacher)
        assertNormalized("shadow", shadow)
        var kl = 0.0
        for (key in teacher.keys + shadow.keys) {
            val p = teacher[key] ?: 0.0
            val q = shadow[key] ?: 0.0
            if (p == 0.0) continue // limit convention: 0·ln(0/q) = 0
            kl += p * ln(p / maxOf(q, ZERO_PROBABILITY_FLOOR))
        }
        return kl
    }

    private fun assertNormalized(name: String, distribution: Map<String, Double>) {
        require(distribution.isNotEmpty()) { "KL: $name distribution is empty" }
        val outOfRange = distribution.values.filter { it !in 0.0..1.0 }
        require(outOfRange.isEmpty()) { "KL: $name probabilities out of range [0,1]: $outOfRange" }
        val sum = distribution.values.sum()
        require(abs(sum - 1.0) <= NORMALIZATION_TOLERANCE) {
            "KL: $name distribution is not normalized (Σp = $sum, tolerance $NORMALIZATION_TOLERANCE)"
        }
    }
}
