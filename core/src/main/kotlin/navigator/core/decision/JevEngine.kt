package navigator.core.decision

import navigator.core.wire.SystemOneClient
import navigator.core.wire.WireProtocolException

/**
 * ART-5 numeric mapping for `score` results (vision 02 §3): the expected value over the
 * ordinal rubric levels, `Σ p_i · i` over the 1-based level ordinals. Computed in `:core`
 * code, NEVER taken from the model — rubric levels are ordinal, not numeric, so any number
 * in a provider response is an echo that must be ignored.
 */
fun scoreExpectedLevel(levelProbabilities: Map<Int, Double>): Double {
    require(levelProbabilities.isNotEmpty()) {
        "scoreExpectedLevel needs a non-empty level distribution"
    }
    require(levelProbabilities.keys.all { it >= 1 }) {
        "scoreExpectedLevel: level ordinals are 1-based, got ${levelProbabilities.keys}"
    }
    return levelProbabilities.entries.sumOf { (ordinal, p) -> p * ordinal }
}

/**
 * A decision plus its engine metadata: which the loop/log need beyond the bare answer.
 * [usedFallback] is true exactly when the primary config exhausted its transport-error
 * retries and the single fallback config served the decision (vision 02 §2 failover law).
 */
data class EngineResult<T : TypedResult>(
    val result: T,
    val usedFallback: Boolean,
)

/**
 * The Jev engine (vision 02 §2): a pure config of [SystemOneClient] — the same wire client
 * every systemone-family engine uses, engines differing only by `EngineConfig`. Maps the
 * typed question primitives to the wire and provider answers back to typed results; the
 * `score` numeric mapping lives here in code ([scoreExpectedLevel], ART-5).
 *
 * Implements [DecisionEngine] for the gateway law (vision 02 §1); the `*Result` variants
 * expose [EngineResult] metadata (failover tag) for the DecisionLog and Phase-8 router.
 */
class JevEngine(private val client: SystemOneClient) : DecisionEngine {

    override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult =
        chooseResult(state, question).result

    override suspend fun judge(state: String, question: NoulQuestion): NoulResult =
        judgeResult(state, question).result

    override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
        scoreResult(state, question).result

    /**
     * The cascade's stage-2 batch (vision 02 §3 v1.3.0): ONE wire request carrying every
     * per-candidate question — the client already accepts a question map and answers
     * name-keyed, so the batch is one `decide` call, not N. The failover tag rides the
     * whole batch (one request, one decision).
     */
    override suspend fun scoreBatch(state: String, questions: Map<String, ScoreQuestion>): Map<String, ScoreResult> {
        if (questions.isEmpty()) return emptyMap()
        val decision = client.decide(state, questions)
        return questions.entries.associate { (name, _) ->
            val result = decision.answers[name]
                ?: throw WireProtocolException("JevEngine: the batch response is missing '$name'")
            name to (result as? ScoreResult ?: typeMismatch(name, "score", result))
        }
    }

    suspend fun chooseResult(state: String, question: ChoiceQuestion): EngineResult<ChoiceResult> {
        val decision = client.decide(state, mapOf(question.name to question))
        return EngineResult(decision.answers.choice(question.name), decision.usedFallback)
    }

    suspend fun judgeResult(state: String, question: NoulQuestion): EngineResult<NoulResult> {
        val decision = client.decide(state, mapOf(question.name to question))
        return EngineResult(decision.answers.noul(question.name), decision.usedFallback)
    }

    suspend fun scoreResult(state: String, question: ScoreQuestion): EngineResult<ScoreResult> {
        val decision = client.decide(state, mapOf(question.name to question))
        return EngineResult(decision.answers.score(question.name), decision.usedFallback)
    }

    private fun Map<String, TypedResult>.choice(name: String): ChoiceResult {
        val result = getValue(name)
        return result as? ChoiceResult ?: typeMismatch(name, "choice", result)
    }

    private fun Map<String, TypedResult>.noul(name: String): NoulResult {
        val result = getValue(name)
        return result as? NoulResult ?: typeMismatch(name, "noul", result)
    }

    private fun Map<String, TypedResult>.score(name: String): ScoreResult {
        val result = getValue(name)
        return result as? ScoreResult ?: typeMismatch(name, "score", result)
    }

    private fun typeMismatch(name: String, expected: String, actual: TypedResult): Nothing =
        throw WireProtocolException(
            "JevEngine expects a '$expected' result for '$name', got '${actual::class.simpleName}'",
        )
}
