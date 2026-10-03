package navigator.core.decision

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single selectable option in a [ChoiceQuestion].
 *
 * @param id stable identifier; probability maps are keyed by this name, never by
 *   position (wire-client rule, vision 02 §2)
 * @param label short human-readable label
 * @param description short description of what choosing it does
 */
@Serializable
data class Option(
    val id: String,
    val label: String,
    val description: String,
)

/** Polymorphic root for the three typed question primitives (vision 02 §3). */
@Serializable
sealed interface TypedQuestion

/** Polymorphic root for the three typed answer shapes (vision 02 §3). */
@Serializable
sealed interface TypedResult

/**
 * `choice` question: pick one option from a flat list (primary path: ≤255 options,
 * vision 02 §3).
 */
@Serializable
@SerialName("choice")
data class ChoiceQuestion(
    val name: String,
    val options: List<Option>,
) : TypedQuestion {
    init {
        require(name.isNotBlank()) { "ChoiceQuestion.name must not be blank" }
        require(options.isNotEmpty()) { "ChoiceQuestion '$name' must offer at least one option" }
        require(options.size <= 255) {
            "ChoiceQuestion '$name' exceeds the 255-option flat-list limit (vision 02 §3): ${options.size}"
        }
        val ids = options.map { it.id }
        require(ids.all { it.isNotBlank() }) { "ChoiceQuestion '$name' has a blank option id" }
        require(ids.size == ids.toSet().size) {
            "ChoiceQuestion '$name' has duplicate option ids: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}"
        }
    }
}

/**
 * `choice` answer: the chosen option plus the full probability distribution.
 *
 * @param chosenId the [Option.id] that was chosen
 * @param probabilities distribution over option ids (name-keyed, never positional —
 *   vision 02 §2)
 * @param confidence the model's self-reported confidence in `[0, 1]`
 */
@Serializable
@SerialName("choice")
data class ChoiceResult(
    val chosenId: String,
    val probabilities: Map<String, Double>,
    val confidence: Double,
) : TypedResult {
    init {
        require(probabilities.containsKey(chosenId)) {
            "ChoiceResult: chosenId '$chosenId' missing from probability map keys ${probabilities.keys}"
        }
        val outOfRange = probabilities.values.filter { it !in 0.0..1.0 }
        require(outOfRange.isEmpty()) { "ChoiceResult: probabilities out of range [0,1]: $outOfRange" }
        require(confidence in 0.0..1.0) { "ChoiceResult: confidence out of range [0,1]: $confidence" }
    }
}

/** `noul` question: judge whether [proposition] is true of [state]. */
@Serializable
@SerialName("noul")
data class NoulQuestion(
    val name: String,
    val proposition: String,
) : TypedQuestion {
    init {
        require(name.isNotBlank()) { "NoulQuestion.name must not be blank" }
        require(proposition.isNotBlank()) { "NoulQuestion '$name' proposition must not be blank" }
    }
}

/**
 * `noul` answer: P(true) ∈ [0, 1]. No separate confidence field — a value in the
 * uncertain band (see [band]) means "don't know" (vision 02 §2).
 */
@Serializable
@SerialName("noul")
data class NoulResult(
    val pTrue: Double,
) : TypedResult {
    init {
        require(pTrue in 0.0..1.0) { "NoulResult: pTrue out of range [0,1]: $pTrue" }
    }
}

/**
 * One ordinal rubric level of a [ScoreQuestion]. Rubric levels are ordinal, not
 * numeric — any numeric mapping is defined in `:core` code, never by the model
 * (ART-5, vision 02 §3).
 *
 * @param id stable identifier of the level
 * @param description what the level means
 */
@Serializable
data class ScoreLevel(
    val id: String,
    val description: String,
)

/** `score` question: judge [state] against an ordered rubric of 2–10 levels. */
@Serializable
@SerialName("score")
data class ScoreQuestion(
    val name: String,
    val levels: List<ScoreLevel>,
) : TypedQuestion {
    init {
        require(name.isNotBlank()) { "ScoreQuestion.name must not be blank" }
        require(levels.size in 2..10) {
            "ScoreQuestion '$name' must have 2..10 rubric levels (vision 02 §3): ${levels.size}"
        }
        val ids = levels.map { it.id }
        require(ids.all { it.isNotBlank() }) { "ScoreQuestion '$name' has a blank level id" }
        require(ids.size == ids.toSet().size) { "ScoreQuestion '$name' has duplicate level ids" }
    }
}

/**
 * `score` answer: expected value over the ordinal levels plus per-level probabilities.
 *
 * @param expectedLevel expected value over the level distribution (Σ p·level, levels
 *   numbered 1..N in the question's declared order); computed numerically in `:core`,
 *   never by the model (ART-5)
 * @param levelProbabilities per-level distribution keyed by the 1-based level ordinal
 *   (the question's declared order is deterministic, ART-6), never by position in a
 *   wire response
 */
@Serializable
@SerialName("score")
data class ScoreResult(
    val expectedLevel: Double,
    val levelProbabilities: Map<Int, Double>,
) : TypedResult {
    init {
        val outOfRange = levelProbabilities.values.filter { it !in 0.0..1.0 }
        require(outOfRange.isEmpty()) { "ScoreResult: level probabilities out of range [0,1]: $outOfRange" }
    }
}
