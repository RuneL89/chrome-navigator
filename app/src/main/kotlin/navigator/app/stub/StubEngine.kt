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
import navigator.core.decision.TargetRubric

/**
 * One scripted engine answer. Matching is fixture-driven and deterministic (Gate 6.1's
 * stub engine, $0 cloud spend):
 * - `chosen` — exact option id;
 * - `labelContains` — the FIRST option whose label (then id) contains the text,
 *   case-insensitive;
 * - `bucketFirst` — the first option whose description starts with `bucket=<value>`
 *   (the loop tags every action option's description with its bucket serial name);
 * - `intent` — the chosen stage-1 intent id (the cascade's `intent` question, Phase 10B);
 * - `topCandidate` — the stage-2 gold for the NEXT batched score request (question
 *   `stage2`): a candidate id (`cand-<id>` name minus the prefix) or a label substring
 *   matched against the questions' per-candidate instructions.
 *
 * For a `choice` answer the full distribution is synthesized deterministically: the
 * chosen option gets [ScriptedAnswer.p] (default 0.95 — the HIGH band), the remainder
 * splits evenly. For `noul` questions the answer carries `pTrue`. For a stage-2 batch
 * the gold candidate gets the decisive level 5 ([candidateLevels] overrides per
 * candidate, 1..5); every other candidate gets a deterministic pairwise-distinct
 * fractional level below the no-good-target floor's interest — never flat.
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
    /** Phase 10B: the chosen stage-1 intent id (question `intent`). */
    val intent: String? = null,
    /** Phase 10B: the stage-2 gold — candidate id or label substring (question `stage2`). */
    val topCandidate: String? = null,
    /** Phase 10B: explicit expected levels per candidate id (1..5); the gold defaults to 5. */
    val candidateLevels: Map<String, Int>? = null,
) {
    init {
        require(question.isNotBlank()) { "ScriptedAnswer.question must not be blank" }
        require(bucketOrdinal == null || bucketFirst != null) {
            "ScriptedAnswer '$question': bucketOrdinal requires bucketFirst"
        }
        require(fallbackBucketOrdinal == null || labelContains != null) {
            "ScriptedAnswer '$question': fallbackBucketOrdinal requires labelContains"
        }
        require(intent == null || question == INTENT_QUESTION) {
            "ScriptedAnswer '$question': the intent selector answers the '$INTENT_QUESTION' question"
        }
        require(topCandidate == null || question == STAGE2_QUESTION) {
            "ScriptedAnswer '$question': the topCandidate selector answers the '$STAGE2_QUESTION' batch"
        }
        candidateLevels?.let { levels ->
            require(topCandidate != null) {
                "ScriptedAnswer '$question': candidateLevels requires topCandidate"
            }
            val outOfBand = levels.filterValues { it !in 1..TargetRubric.LEVEL_COUNT }
            require(outOfBand.isEmpty()) {
                "ScriptedAnswer '$question': candidateLevels outside the 1..${TargetRubric.LEVEL_COUNT} rubric: $outOfBand"
            }
        }
        val selectors = listOfNotNull(chosen, labelContains, bucketFirst, intent, topCandidate)
        require(pTrue != null || selectors.size == 1) {
            "ScriptedAnswer '$question': give exactly one of chosen/labelContains/bucketFirst/" +
                "intent/topCandidate (or pTrue for a noul answer)"
        }
        require(p in 0.0..1.0) { "ScriptedAnswer '$question': p out of range [0,1]: $p" }
        pTrue?.let { require(it in 0.0..1.0) { "ScriptedAnswer '$question': pTrue out of range: $it" } }
    }

    companion object {
        /** The cascade stage-1 question name ([navigator.core.decision.Intents.INTENT_QUESTION_NAME]). */
        const val INTENT_QUESTION = "intent"

        /** The pseudo question name of a scripted stage-2 batch answer. */
        const val STAGE2_QUESTION = "stage2"
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
 *
 * Phase 10B (the decision cascade): the per-step DECIDE is the two-stage cascade, so a
 * step scripts ONE `intent` answer (stage 1) and — when the intent needs a target — ONE
 * `stage2` answer (the batched score request; [scoreBatch] consumes it and synthesizes
 * the gold-favored level distribution). The `finish` pseudo-action is retired from the
 * cascade: completion scripts `verify-done` intents + `goal-achieved` noul answers.
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
            "StubEngine cannot answer a SINGLE 'score' question (the cascade's stage-2 is a " +
                "batched scoreBatch call); question '${question.name}' is a script bug",
        )

    /**
     * The cascade's stage-2 batch (phase doc §2.9): ONE scripted `stage2` answer names
     * the gold candidate ([ScriptedAnswer.topCandidate] — a candidate id, or a label
     * substring of the question's per-candidate instructions); every candidate's
     * [ScoreResult] is synthesized deterministically: the gold (or its explicit
     * [ScriptedAnswer.candidateLevels] level) decisive, the rest pairwise-distinct and
     * well under the gold (the argmax + no-good-target law must see a decisive target).
     */
    override suspend fun scoreBatch(
        state: String,
        questions: Map<String, ScoreQuestion>,
    ): Map<String, ScoreResult> {
        if (questions.isEmpty()) return emptyMap()
        callLog?.add("scoreBatch:cand-${questions.size}")
        val answer = next(ScriptedAnswer.STAGE2_QUESTION)
        // The gold matches by CANDIDATE ID first (cand-<id> minus the prefix); the label
        // fallback matches the QUOTED label inside the question's per-candidate
        // instruction ("acting on '<label>'") — quoting anchors the match so the subtask
        // text that rides every instruction can never hijack the gold.
        val gold = questions.entries.firstOrNull { (name, _) ->
            name.removePrefix(TargetRubric.CANDIDATE_NAME_PREFIX) == answer.topCandidate
        }?.key ?: questions.entries.firstOrNull { (_, question) ->
            // The per-candidate instruction QUOTES the label ("acting on '<label>'"):
            // match the needle against the quoted label only, so the subtask text that
            // rides every instruction can never hijack the gold.
            val quoted = Regex("'([^']*)'").find(question.instructions)?.groupValues?.get(1)
            quoted != null && answer.topCandidate != null &&
                quoted.contains(answer.topCandidate!!, ignoreCase = true)
        }?.key ?: throw IllegalStateException(
            "scripted stage2 answer: no candidate matches topCandidate '${answer.topCandidate}' " +
                "(candidates: ${questions.keys})",
        )
        var distinct = 0
        return questions.entries.associate { (name, question) ->
            val candidateId = name.removePrefix(TargetRubric.CANDIDATE_NAME_PREFIX)
            val level: Double = when {
                name == gold -> answer.candidateLevels?.get(candidateId)?.toDouble() ?: GOLD_LEVEL
                else -> {
                    val explicit = answer.candidateLevels?.get(candidateId)?.toDouble()
                    if (explicit != null) {
                        explicit
                    } else {
                        // pairwise-distinct fractional levels, deterministic in batch order,
                        // all strictly below the gold (the resolver's argmax is unambiguous)
                        distinct += 1
                        1.0 + ((distinct - 1) % 4) * FRACTION_STEP
                    }
                }
            }
            name to scoreResultAt(question, level)
        }
    }

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
        answer.intent?.let { intentId ->
            return options.firstOrNull { it.id == intentId }
                ?: throw IllegalStateException(
                    "scripted answer '${question.name}': intent '$intentId' is not an option " +
                        "of ${options.map { it.id }}",
                )
        }
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

    companion object {
        /** The scripted gold's expected level: decisively advances (rubric level 5). */
        const val GOLD_LEVEL = 5.0

        /** The non-gold levels' fractional step (pairwise-distinct, deterministic). */
        const val FRACTION_STEP = 0.25

        /**
         * A [ScoreResult] whose expected level is exactly [level] (1..5): whole levels
         * are delta distributions; fractional levels split between the floor and ceiling
         * levels so `Σ p·level` reproduces the value exactly (ART-5 — the stub computes
         * the mapping in code, and the engine never sees the number).
         */
        fun scoreResultAt(question: ScoreQuestion, level: Double): ScoreResult {
            require(level in 1.0..TargetRubric.LEVEL_COUNT.toDouble()) {
                "scripted level out of the rubric band 1..${TargetRubric.LEVEL_COUNT}: $level"
            }
            val floor = level.toInt().coerceIn(1, TargetRubric.LEVEL_COUNT)
            val fraction = level - floor
            val levelProbabilities = buildMap {
                question.levels.forEachIndexed { index, _ ->
                    val ordinal = index + 1
                    when (ordinal) {
                        floor -> put(ordinal, 1.0 - fraction)
                        floor + 1 -> put(ordinal, fraction)
                        else -> put(ordinal, 0.0)
                    }
                }
            }
            return ScoreResult(
                expectedLevel = navigator.core.decision.scoreExpectedLevel(levelProbabilities),
                levelProbabilities = levelProbabilities,
            )
        }
    }
}
