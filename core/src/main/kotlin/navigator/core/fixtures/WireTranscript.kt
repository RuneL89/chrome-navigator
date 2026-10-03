package navigator.core.fixtures

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.decision.TypedQuestion
import navigator.core.decision.TypedResult
import kotlinx.serialization.Serializable

/**
 * Golden-fixture wire transcript (PHASE_00 §2.3): one recorded request/response pair
 * against a `/v1/systemone`-family endpoint, in the exact provider shape. Used by the
 * eval harness to replay decisions without network (Phase 7+).
 *
 * @param engine engine config key that produced the recording (e.g. `jev`); never a
 *   provider URL — configs name providers, code does not (ART-2, vision 02 §1)
 * @param model pinned model snapshot ID (e.g. `typesafe/jev-1.13-20260917`, vision 02 §5)
 * @param request the recorded state text plus one typed question
 * @param response the recorded typed answer
 */
@Serializable
data class WireTranscript(
    val engine: String,
    val model: String,
    val request: WireRequest,
    val response: TypedResult,
) {
    init {
        require(engine.isNotBlank()) { "WireTranscript.engine must not be blank" }
        require(model.isNotBlank()) { "WireTranscript.model must not be blank" }
        require(request.state.isNotBlank()) { "WireTranscript.request.state must not be blank" }
        requireCompatible(request.question, response)
    }

    /** Cross-checks the answer against its question — mismatched or incomplete
     *  distributions are malformed fixtures, not data (vision 02 §2 wire rules). */
    private fun requireCompatible(question: TypedQuestion, response: TypedResult) {
        when (question) {
            is ChoiceQuestion -> {
                val result = response as? ChoiceResult ?: failType("choice", response)
                val ids = question.options.map { it.id }.toSet()
                require(result.chosenId in ids) {
                    "choice transcript '${question.name}': chosenId '${result.chosenId}' is not one of the option ids $ids"
                }
                require(result.probabilities.keys == ids) {
                    "choice transcript '${question.name}': probability keys ${result.probabilities.keys} " +
                        "must equal the option ids $ids (name-keyed, vision 02 §2)"
                }
            }

            is NoulQuestion ->
                if (response !is NoulResult) failType("noul", response)

            is ScoreQuestion -> {
                val result = response as? ScoreResult ?: failType("score", response)
                val ordinals = (1..question.levels.size).toSet()
                require(result.levelProbabilities.keys == ordinals) {
                    "score transcript '${question.name}': level probability keys ${result.levelProbabilities.keys} " +
                        "must equal the 1-based level ordinals $ordinals"
                }
                require(result.expectedLevel in 1.0..question.levels.size.toDouble()) {
                    "score transcript '${question.name}': expectedLevel ${result.expectedLevel} " +
                        "outside [1, ${question.levels.size}]"
                }
            }
        }
    }

    private fun failType(expected: String, actual: TypedResult): Nothing =
        throw FixtureValidationException(
            "wire transcript expects a '$expected' response, got '${actual::class.simpleName}'",
        )
}

/** Recorded request: the canonical state text plus the typed question asked of it. */
@Serializable
data class WireRequest(
    val state: String,
    val question: TypedQuestion,
)
