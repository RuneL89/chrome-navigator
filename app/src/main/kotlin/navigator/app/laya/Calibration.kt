package navigator.app.laya

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Probability/temperature post-processing (vision 05 §6 "calibration post-processing"),
 * mirroring the upstream decode path (`Agent._decode_answers` + `common.py` at the
 * pinned commit):
 *
 * - the decision head emits one raw logit per option marker;
 * - `p = softmax(logits[:k] / t)` with `t` = the checkpoint's fitted temperature for the
 *   question's `(qtype, option-count)` bucket (`temp_bucket`: "choice:2", "choice:3-5",
 *   "choice:6-10", "choice:11+", "score:2/3-5/6-10/11+", "noul:2"), falling back to the
 *   per-qtype base temperature;
 * - every configured temperature is clamped to [0.5, 5.0] (upstream `clamp_temperature`:
 *   a fitted temperature below 0.5 sharpens — the shipped `choice:11+` = 0.1006 is
 *   refused and applied as 0.5), non-finite values fall back to 1.0;
 * - `answer_confidence = max(p)` — the calibrated quantity upstream gates on;
 * - the `confidence` reported on a [navigator.core.decision.ChoiceResult] is upstream's
 *   `answer_confidence` (max p), the one quantity that means the same thing across
 *   question types (upstream's entropy `confidence` is "not calibrated", #394).
 */
object Calibration {

    /** Upstream `TEMP_MIN` / `TEMP_MAX`. */
    const val TEMP_MIN: Double = 0.5
    const val TEMP_MAX: Double = 5.0

    /**
     * The pinned checkpoint's fitted temperatures (`rl_agent_config.json` at HF revision
     * 55cf4c4e…): per-qtype base `[choice, score, noul]` + the fitted per-bucket map.
     * Changing these is a checkpoint bump (vision 02 §5 pinning law), never a tweak.
     */
    val BASE_TEMPERATURES: List<Double> = listOf(
        1.6369030475616455, // choice
        1.2514300346374512, // score
        1.983399510383606, // noul
    )
    val TEMPERATURE_BY_OPTIONS: Map<String, Double> = mapOf(
        "choice:2" to 1.9063563346862793,
        "choice:3-5" to 1.7601518630981445,
        "choice:6-10" to 1.0000158548355103,
        "choice:11+" to 0.10058280825614929, // clamped to 0.5 at apply time (upstream law)
        "score:3-5" to 1.2514300346374512,
        "noul:2" to 1.983399510383606,
    )

    /**
     * One checkpoint's calibration tables (Phase 8C): the per-qtype base temperatures
     * plus the fitted `(qtype, option-count)` map, exactly as [Calibration] applies them.
     * The v32b checkpoint ships temperature [1,1,1] and an EMPTY map — the identity
     * profile (its rl_agent_config.json, recorded in the provenance).
     */
    data class Profile(
        val baseTemperatures: List<Double>,
        val temperatureByOptions: Map<String, Double>,
    ) {
        init {
            require(baseTemperatures.size == 3) {
                "a calibration profile needs the 3 per-qtype base temperatures, got ${baseTemperatures.size}"
            }
        }
    }

    /** The Phase-8 checkpoint's tables (the constants above; Gate 8.1 fixture law). */
    val LAYA_421M: Profile = Profile(BASE_TEMPERATURES, TEMPERATURE_BY_OPTIONS)

    /** The Phase-8C checkpoint's tables: identity (temperature 1.0 everywhere). */
    val V32B: Profile = Profile(listOf(1.0, 1.0, 1.0), emptyMap())

    /** Upstream `temp_bucket(qtype, k)`. */
    fun temperatureBucket(qtype: Int, optionCount: Int): String {
        val size = when {
            optionCount <= 2 -> "2"
            optionCount <= 5 -> "3-5"
            optionCount <= 10 -> "6-10"
            else -> "11+"
        }
        val name = when (qtype) {
            MarkerBuilder.QTYPE_CHOICE -> "choice"
            MarkerBuilder.QTYPE_SCORE -> "score"
            else -> "noul"
        }
        return "$name:$size"
    }

    /** Upstream `clamp_temperature`: confine to [TEMP_MIN, TEMP_MAX], 1.0 when unusable. */
    fun clampTemperature(t: Double): Double =
        if (t.isNaN() || t.isInfinite()) 1.0 else min(TEMP_MAX, max(TEMP_MIN, t))

    /**
     * The effective temperature for one question row (bucket fit, else per-qtype base)
     * under [profile] — default the Phase-8 checkpoint's tables (the Gate 8.1 law).
     */
    fun temperatureFor(
        qtype: Int,
        optionCount: Int,
        profile: Profile = LAYA_421M,
    ): Double {
        val base = profile.baseTemperatures[if (qtype in 0..2) qtype else throw IllegalArgumentException(
            "qtype must be 0..2, was $qtype",
        )]
        val fitted = profile.temperatureByOptions[temperatureBucket(qtype, optionCount)]
        return clampTemperature(fitted ?: base)
    }

    /**
     * Upstream decode: `z = logits / t; p = exp(z - max(z)); p /= sum(p)`. Raw logits of
     * other markers (the padded `[MASK]` slots masked to -1e4 upstream) are excluded by
     * the caller — this takes exactly the first [optionCount] logits, under [profile].
     */
    fun probabilities(
        logits: DoubleArray,
        qtype: Int,
        optionCount: Int,
        profile: Profile = LAYA_421M,
    ): DoubleArray {
        require(optionCount >= 1) { "optionCount must be >= 1" }
        require(logits.size >= optionCount) {
            "need $optionCount logits, got ${logits.size}"
        }
        val t = temperatureFor(qtype, optionCount, profile)
        val z = DoubleArray(optionCount) { logits[it] / t }
        var maxZ = Double.NEGATIVE_INFINITY
        for (v in z) if (v > maxZ) maxZ = v
        var sum = 0.0
        val p = DoubleArray(optionCount)
        for (i in 0 until optionCount) {
            p[i] = exp(z[i] - maxZ)
            sum += p[i]
        }
        for (i in 0 until optionCount) p[i] /= sum
        return p
    }

    /** Upstream `answer_confidence`: the probability mass on the reported answer, max(p). */
    fun answerConfidence(p: DoubleArray): Double = p.max()

    /**
     * Upstream `confidence_from_probs`: normalized Shannon entropy confidence
     * `1 - H(p)/log(k)` — NOT calibrated (upstream #394); kept for the reference-vector
     * fixtures only, never reported as a [ChoiceResult] confidence.
     */
    fun entropyConfidence(p: DoubleArray): Double {
        val k = p.size
        if (k < 2) return 1.0
        var ent = 0.0
        for (v in p) ent -= v * ln(max(v, 1e-12))
        val c = 1.0 - ent / ln(k.toDouble())
        return min(1.0, max(0.0, c))
    }

    /** Max |Δp| across two aligned distributions (the Gate 8.1 parity metric). */
    fun maxAbsDelta(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size) { "distribution length mismatch: ${a.size} vs ${b.size}" }
        var d = 0.0
        for (i in a.indices) d = max(d, abs(a[i] - b[i]))
        return d
    }
}
