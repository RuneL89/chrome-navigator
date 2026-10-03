package navigator.app.stub

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.Option
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult

/**
 * One scripted engine answer. Matching is fixture-driven and deterministic (Gate 6.1's
 * stub engine, $0 cloud spend):
 * - `chosen` — exact option id;
 * - `labelContains` — the FIRST option whose label (then id) contains the text,
 *   case-insensitive;
 * - `bucketFirst` — the first option whose description starts with `bucket=<value>`
 *   (the loop tags every action option's description with its bucket serial name).
 *
 * For a `choice` answer the full distribution is synthesized deterministically: the
 * chosen option gets [ScriptedAnswer.p] (default 0.95), the remainder splits evenly.
 * For `noul` questions the answer carries `pTrue`.
 */
@Serializable
data class ScriptedAnswer(
    val question: String,
    val chosen: String? = null,
    val labelContains: String? = null,
    val bucketFirst: String? = null,
    /** 0-based ordinal WITHIN the [bucketFirst] bucket (browser chrome fields sort first). */
    val bucketOrdinal: Int? = null,
    /** Fallback ordinal when the primary selector matches nothing (labels lag on
     *  freshly-loaded pages): selects `bucketFirst[bucketOrdinal]` instead of failing. */
    val fallbackBucketOrdinal: Int? = null,
    val pTrue: Double? = null,
    val p: Double = 0.95,
) {
    init {
        require(question.isNotBlank()) { "ScriptedAnswer.question must not be blank" }
        require(bucketOrdinal == null || bucketFirst != null) {
            "ScriptedAnswer '$question': bucketOrdinal requires bucketFirst"
        }
        require(fallbackBucketOrdinal == null || labelContains != null) {
            "ScriptedAnswer '$question': fallbackBucketOrdinal requires labelContains"
        }
        val selectors = listOfNotNull(chosen, labelContains, bucketFirst)
        require(pTrue != null || selectors.size == 1) {
            "ScriptedAnswer '$question': give exactly one of chosen/labelContains/bucketFirst " +
                "(or pTrue for a noul answer)"
        }
        require(p in 0.0..1.0) { "ScriptedAnswer '$question': p out of range [0,1]: $p" }
        pTrue?.let { require(it in 0.0..1.0) { "ScriptedAnswer '$question': pTrue out of range: $it" } }
    }
}

/** A scripted goal's engine transcript (fixture-driven; ordered per question name). */
@Serializable
data class StubScript(
    val answers: List<ScriptedAnswer>,
) {
    companion object {
        private val jsonFormat = Json { ignoreUnknownKeys = false }

        /** Strict parse: unknown fields fail loudly (fixture discipline). */
        fun parse(json: String): StubScript = jsonFormat.decodeFromString(serializer(), json)
    }
}

/**
 * The STUB engine (phase doc §2.2): a [DecisionEngine] that replays a [StubScript]
 * deterministically — same script in, same answers out, no network, no keys, $0 spend.
 * Answers are consumed per question name in script order; an exhausted script is a loud
 * error (a silent default would mask a broken run). This is also the harness's LIVE-mode
 * counterpart for Gate 6.2: the whole gate path, stuck detection, budgets, guards and
 * logging run identically — only the answers' source differs.
 */
class StubEngine(
    script: StubScript,
    /** Optional observation sink for tests (question name per call, in call order). */
    private val callLog: MutableList<String>? = null,
) : DecisionEngine {

    private val queues: MutableMap<String, ArrayDeque<ScriptedAnswer>> = HashMap()
    private var totalConsumed = 0
    private val totalScripted = script.answers.size

    init {
        for (answer in script.answers) {
            queues.getOrPut(answer.question) { ArrayDeque() }.addLast(answer)
        }
    }

    override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
        callLog?.add("choose:${question.name}")
        val answer = next(question.name)
        val chosen = selectOption(question, answer)
        return ChoiceResult(
            chosenId = chosen.id,
            probabilities = distribution(question.options.map { it.id }, chosen.id, answer.p),
            confidence = answer.p,
        )
    }

    override suspend fun judge(state: String, question: NoulQuestion): NoulResult {
        callLog?.add("judge:${question.name}")
        val answer = next(question.name)
        val pTrue = answer.pTrue
            ?: throw IllegalStateException(
                "scripted answer for '${question.name}' is a choice answer, a noul was asked",
            )
        return NoulResult(pTrue)
    }

    override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
        throw IllegalStateException(
            "StubEngine cannot answer 'score' questions (the loop never asks one); " +
                "question '${question.name}' is a script bug",
        )

    // -- deterministic selection -----------------------------------------------------

    private fun next(name: String): ScriptedAnswer {
        val queue = queues[name]
            ?: throw IllegalStateException(
                "stub script has no answers for question '$name' (scripted: ${queues.keys})",
            )
        val answer = queue.removeFirstOrNull()
            ?: throw IllegalStateException(
                "stub script exhausted for question '$name' " +
                    "($totalConsumed of $totalScripted answers consumed)",
            )
        totalConsumed++
        return answer
    }

    private fun selectOption(question: ChoiceQuestion, answer: ScriptedAnswer): Option {
        val options = question.options
        answer.chosen?.let { id ->
            return options.firstOrNull { it.id == id }
                ?: throw IllegalStateException(
                    "scripted answer '${question.name}': chosen id '$id' is not an option " +
                        "of ${options.map { it.id }}",
                )
        }
        answer.labelContains?.let { needle ->
            val lower = needle.lowercase()
            val byLabel = options.firstOrNull { it.label.lowercase().contains(lower) }
            if (byLabel != null) return byLabel
            val byId = options.firstOrNull { it.id.lowercase().contains(lower) }
            if (byId != null) return byId
            // the page loaded but node labels lag: fall back to a known bucket slot
            answer.fallbackBucketOrdinal?.let { ordinal ->
                return options.filter { it.description.startsWith("bucket=form-fields") }
                    .getOrNull(ordinal)
                    ?: throw IllegalStateException(
                        "scripted answer '${question.name}': fallback form-fields[$ordinal] " +
                            "not found (options: ${options.map { it.label }})",
                    )
            }
            throw IllegalStateException(
                "scripted answer '${question.name}': no option label/id contains '$needle' " +
                    "(options: ${options.map { it.label }})",
            )
        }
        answer.bucketFirst?.let { bucket ->
            val prefix = "bucket=$bucket"
            val inBucket = options.filter { it.description.startsWith(prefix) }
            val ordinal = answer.bucketOrdinal ?: 0
            return inBucket.getOrNull(ordinal)
                ?: throw IllegalStateException(
                    "scripted answer '${question.name}': bucket '$bucket' has no option at " +
                        "ordinal $ordinal (${inBucket.size} options: ${inBucket.map { it.label }})",
                )
        }
        throw IllegalStateException(
            "scripted answer '${question.name}' has no selector (bug in the fixture)",
        )
    }

    /** Deterministic distribution: chosen gets p, the rest split the remainder evenly. */
    private fun distribution(ids: List<String>, chosenId: String, p: Double): Map<String, Double> =
        if (ids.size == 1) {
            mapOf(chosenId to 1.0)
        } else {
            val rest = (1.0 - p) / (ids.size - 1)
            ids.associateWith { if (it == chosenId) p else rest }
        }
}
