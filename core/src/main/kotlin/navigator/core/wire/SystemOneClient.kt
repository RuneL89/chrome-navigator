package navigator.core.wire

import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.decision.TypedQuestion
import navigator.core.decision.TypedResult
import navigator.core.decision.scoreExpectedLevel

/**
 * Provider-reported cost of one wire call, parsed from the response envelope's `usage`
 * object (real protocol, verified live: `.tools/live-smoke-3.json` / `-5.json`). Exposed
 * for TaskBudget accounting (Phase-6 integration); [costUsd] is the provider-reported
 * authoritative USD figure.
 */
data class Usage(
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
)

/** The parsed wire response: typed answers keyed by question NAME plus the call's [Usage]. */
data class WireResponse(
    val answers: Map<String, TypedResult>,
    val usage: Usage,
)

/** A completed wire decision: parsed typed answers, usage, and the failover tag (phase 3 §2.2). */
data class WireDecision(
    val answers: Map<String, TypedResult>,
    val usage: Usage,
    val usedFallback: Boolean,
    val servedBy: EngineConfig,
)

/**
 * The one wire client for the systemone-family JSON contract (ART-2, vision 02 §2).
 * Every cloud engine is a config of this client; nothing else in the codebase speaks to a
 * provider. This is also what Phase 8 wraps (shadow) and Phase 10 contrasts.
 *
 * The shapes below are the REAL provider protocol, reconciled live (HTTP 200 evidence:
 * `.tools/live-smoke-3.json` choice+noul, `.tools/live-smoke-5.json` score; the rejected
 * hand-crafted shapes are recorded in `live-smoke-1/2/4.json` as 400 schema errors).
 *
 * Request: `POST {baseUrl}` with `Authorization: Bearer {key}` and JSON body
 * `{"state": ..., "model": <pinned id>, "questions": {"<name>": {<typed shape>}}}`:
 *  - `choice`: `{"type":"choice","instructions":"<string>","criteria":{"<option-id>":"<description>"}}`
 *    — `criteria` is a RECORD keyed by the option ids (our ids are unique names; the
 *    name-keyed law then maps answers straight back to options). Duplicate ids are a loud
 *    construction error.
 *  - `noul`: `{"type":"noul","instructions":"<string>","proposition":"..."}`
 *  - `score`: `{"type":"score","instructions":"<string>","criteria":["<level-0>","<level-1>",...]}`
 *    — `criteria` is an ARRAY of the declared rubric levels, in order (the order is the
 *    wire name space, ART-6; ART-5: any numeric mapping is computed in `:core`).
 * `instructions` is a required per-question string. Since vision 02 §3 v1.3.0 (Phase
 * 10B) EVERY question carries its own complete [navigator.core.decision.TypedQuestion]
 * `instructions` text — the shared per-primitive constants below ([CHOICE_INSTRUCTIONS],
 * [NOUL_INSTRUCTIONS], [SCORE_INSTRUCTIONS]) are RETIRED as emitted defaults and live on
 * only as the legacy question types' default field values, so pre-cascade construction
 * sites keep byte-identical wire bytes. The prompt surface is owned by the question's
 * author (the cascade constants in [navigator.core.decision.Intents] /
 * [navigator.core.decision.TargetRubric]); [PROMPT_VERSION] records the emitted
 * instruction surface generation.
 *
 * Response: `{"model", "answers": {"<name>": <answer>}, "usage": {"input_tokens",
 * "output_tokens", "cost"}, "id", "provider"}`. `answers` is keyed by question NAME —
 * key order shifts between calls, so answers are always indexed by name, never position.
 *  - choice: `{"type":"choice","choice":"<option-id>","probabilities":{...},"confidence":0.xx}`
 *  - noul: `{"type":"noul","noul":0.xx}` (no confidence — uncertain band means "don't know")
 *  - score: `{"type":"score","score":<float>,"legend":{"0":"<level-0>",...},
 *    "probabilities":{"0":0.xx,...},"confidence":0.xx}` — distribution keys are 0-BASED
 *    indices; `legend` maps each index back to the level string. The parser checks the
 *    legend against the declared rubric and converts to 1-based ordinals internally. The
 *    provider's `score` float is IGNORED: the expected value is computed in `:core` code
 *    ([scoreExpectedLevel], ART-5), never taken from the model.
 *
 * Field law: providers may add unknown FIELDS (extra answer/top-level fields are ignored);
 * the client REQUIRES the fields it uses — a missing question name, missing answer field,
 * a wrong answer `type` discriminator, a legend that disagrees with the declared rubric,
 * or a distribution key outside the declared names is a loud [WireProtocolException],
 * never a default.
 *
 * Retry law (vision 02 §2): only transport errors ([WireTransportException]) are retried,
 * at most [maxRetries] times (law maximum 2) with exponential backoff — never an answer
 * the caller dislikes. Failover law: when the primary exhausts its retries with transport
 * errors, the single fallback config gets a fresh attempt budget and the decision is
 * tagged `usedFallback=true`; only one failover hop (Phase-8 adds engines as configs).
 */
class SystemOneClient(
    private val transport: WireTransport,
    primary: EngineConfig,
    fallback: EngineConfig? = null,
    val maxRetries: Int = MAX_RETRIES,
    val backoffBaseMs: Long = DEFAULT_BACKOFF_BASE_MS,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    /**
     * Observes the provider-reported [Usage] of every successful wire call, exactly once
     * per successful response (a retried transport failure reports nothing — the provider
     * returned no body). Spend accounting (TaskBudget, ART-5) is the consumer: recorded
     * token counts × pinned prices. The hook never sees secrets — [Usage] carries token
     * counts and the provider's own cost figure only.
     */
    private val onUsage: (Usage) -> Unit = {},
) {
    init {
        require(maxRetries in 0..MAX_RETRIES) {
            "retry law allows at most $MAX_RETRIES retries, transport errors only (vision 02 §2): $maxRetries"
        }
        require(backoffBaseMs >= 0) { "backoffBaseMs must be >= 0: $backoffBaseMs" }
    }

    private val configs: List<EngineConfig> = buildList {
        add(primary)
        fallback?.let { add(it) }
    }

    /**
     * Asks one or more typed questions about [state]; answers come back name-keyed.
     * Thread-safety: a client instance is stateless per call and reusable across calls.
     */
    suspend fun decide(state: String, questions: Map<String, TypedQuestion>): WireDecision {
        require(state.isNotBlank()) { "state must not be blank" }
        require(questions.isNotEmpty()) { "questions must not be empty" }
        require(questions.keys.all { it.isNotBlank() }) { "question names must not be blank" }
        return try {
            decideWith(configs[0], state, questions, usedFallback = false)
        } catch (e: WireTransportException) {
            // Transport failure after exhausting this config's retries: the ONE failover
            // hop. Content errors (WireProtocolException) never reach this branch.
            if (configs.size < 2) throw e
            decideWith(configs[1], state, questions, usedFallback = true)
        }
    }

    private suspend fun decideWith(
        config: EngineConfig,
        state: String,
        questions: Map<String, TypedQuestion>,
        usedFallback: Boolean,
    ): WireDecision {
        val body = buildRequestJson(state, config.model, questions).toString()
        var attempt = 0
        while (true) {
            try {
                val response = transport.execute(
                    WireHttpRequest(
                        url = config.baseUrl,
                        headers = mapOf(
                            "Authorization" to "Bearer ${config.apiKey}",
                            "Content-Type" to "application/json; charset=utf-8",
                        ),
                        body = body,
                    ),
                )
                if (response.code !in 200..299) {
                    // Any HTTP response — 4xx or 5xx — is content, not transport: never
                    // retried, never failed over (vision 02 §2; a 4xx is a content problem).
                    throw WireProtocolException(
                        "HTTP ${response.code} from ${config.baseUrl} (model ${config.model}) " +
                            "— not retried, not failed over (wire law). Body: ${response.body.take(200)}",
                    )
                }
                val parsed = parseResponse(response.body, config, questions)
                onUsage(parsed.usage)
                return WireDecision(parsed.answers, parsed.usage, usedFallback, config)
            } catch (e: WireTransportException) {
                if (attempt >= maxRetries) throw e
                val delayMs = backoffBaseMs shl attempt
                retryDelay(delayMs)
                attempt++
            }
        }
    }

    companion object {
        const val MAX_RETRIES = 2
        const val DEFAULT_BACKOFF_BASE_MS = 200L

        /**
         * Version of the emitted `instructions` surface. Bump on any wording change (the
         * prompt text is part of the wire contract the fixtures record verbatim).
         *
         * `jev-instructions-2` (Phase 10B, vision 02 §3 v1.3.0): instructions moved onto
         * the question types — the emitted text is `question.instructions` verbatim; the
         * legacy constants below survive as the typed questions' default field values so
         * pre-cascade construction sites keep byte-identical request bodies (the 13
         * jev-instructions-1 transcripts still pass untouched).
         */
        const val PROMPT_VERSION = "jev-instructions-3"

        /** The Phase-3 shared choice instruction — now the legacy [navigator.core.decision.ChoiceQuestion] default. */
        const val CHOICE_INSTRUCTIONS =
            "Choose the one option that best advances the goal given in the state. " +
                "Answer with the chosen option key, the full probability distribution over " +
                "all offered option keys, and your confidence."
        /** The Phase-3 shared noul instruction — now the legacy [navigator.core.decision.NoulQuestion] default. */
        const val NOUL_INSTRUCTIONS =
            "Judge whether the proposition is true of the state. " +
                "Answer with P(true), a single probability in [0,1]."
        /** The Phase-3 shared score instruction — now the legacy [navigator.core.decision.ScoreQuestion] default. */
        const val SCORE_INSTRUCTIONS =
            "Rate the state against the ordered rubric levels. " +
                "Answer with a probability for every rubric level, in order, and your confidence."

        private val json = Json { ignoreUnknownKeys = true }

        /** Serializes the request body in the exact provider shape (see class KDoc). */
        internal fun buildRequestJson(
            state: String,
            model: String,
            questions: Map<String, TypedQuestion>,
        ): JsonObject = buildJsonObject {
            put("state", state)
            put("model", model)
            put("questions", buildJsonObject {
                questions.forEach { (name, question) -> put(name, questionJson(question)) }
            })
        }

        internal fun questionJson(question: TypedQuestion): JsonObject = when (question) {
            is ChoiceQuestion -> {
                // The criteria record is keyed by option id — ids ARE the wire names, so
                // duplicates would silently collapse keys. Fail loudly here too (the typed
                // question already enforces this; the wire boundary repeats it).
                val ids = question.options.map { it.id }
                require(ids.size == ids.toSet().size) {
                    "choice question '${question.name}': option ids are wire keys and must be " +
                        "unique (name-keyed law): ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}"
                }
                buildJsonObject {
                    put("type", "choice")
                    put("instructions", question.instructions)
                    put("criteria", buildJsonObject {
                        question.options.forEach { put(it.id, it.description) }
                    })
                }
            }

            is NoulQuestion -> buildJsonObject {
                put("type", "noul")
                // THE corrected noul shape (live-verified 2026-10-11, `.state/phase-10B-gate105/
                // noul-vs-choice-experiment.md`): the noul QUESTION rides `instructions` — the
                // SDK-documented shape ("refund": {"type":"noul","instructions":"Is the customer
                // asking for money back?"}). The Phase-3 shape put the question in a
                // `proposition` field the model IGNORES while `instructions` held generic
                // boilerplate, so every noul answered the boilerplate and emitted a
                // state-dependent constant (proven live: true/false/repeat indistinguishable
                // at 0.32-0.37; the same value floor across unrelated propositions). With the
                // question in `instructions` the pinned snapshot discriminates perfectly on
                // our exact state format (host-true 0.98-0.99, host-false 0.01, word-true
                // 0.99, word-false 0.01 — both surfaces). The `proposition` field is no longer
                // emitted: an unknown field the model ignores is a footgun, and the typed
                // [NoulQuestion.proposition] IS this question's complete instruction.
                put("instructions", question.proposition)
            }

            is ScoreQuestion -> buildJsonObject {
                put("type", "score")
                put("instructions", question.instructions)
                // Ordered level array: declared rubric order is the wire name space
                // (ART-6); the typed ScoreLevel.id stays internal.
                put("criteria", buildJsonArray {
                    question.levels.forEach { level -> add(level.description) }
                })
            }
        }

        /**
         * Parses the response envelope: `answers` by question NAME plus the call `usage`.
         * The interpretation of each answer body comes from the question asked under that
         * name, cross-checked against the body's `type` discriminator. Unknown question
         * names, missing answers, and missing/invalid required fields fail loudly.
         */
        internal fun parseResponse(
            responseBody: String,
            config: EngineConfig,
            questions: Map<String, TypedQuestion>,
        ): WireResponse {
            val root = parseJsonObject(responseBody, "response body from ${config.baseUrl}")
            val answers = root["answers"] as? JsonObject
                ?: throw WireProtocolException(
                    "response from ${config.baseUrl} is missing the required 'answers' object " +
                        "(answers are keyed by question name); top-level keys were ${root.keys}",
                )
            val usageObject = root["usage"] as? JsonObject
                ?: throw WireProtocolException(
                    "response from ${config.baseUrl} is missing the required 'usage' object " +
                        "({input_tokens, output_tokens, cost}); top-level keys were ${root.keys}",
                )
            val usage = Usage(
                inputTokens = intValue(usageObject, "input_tokens", "response usage"),
                outputTokens = intValue(usageObject, "output_tokens", "response usage"),
                costUsd = numberValue(usageObject, "cost", "response usage"),
            )
            val parsed = LinkedHashMap<String, TypedResult>()
            for ((name, question) in questions) {
                val answer = answers[name] as? JsonObject
                    ?: throw WireProtocolException(
                        "response is missing an answer for asked question '$name' — loud failure, " +
                            "never a default (wire law, vision 02 §2)",
                    )
                parsed[name] = parseAnswer(name, question, answer)
            }
            val unknown = answers.keys - questions.keys
            if (unknown.isNotEmpty()) {
                throw WireProtocolException(
                    "response contains answers for unknown question names $unknown — asked only " +
                        "${questions.keys} (name-keyed law: loud error, never a default)",
                )
            }
            return WireResponse(parsed, usage)
        }

        private fun parseAnswer(name: String, question: TypedQuestion, answer: JsonObject): TypedResult {
            // The provider tags every answer body with a type discriminator; an answer
            // that does not match the question asked under this name is malformed content.
            val expectedType = when (question) {
                is ChoiceQuestion -> "choice"
                is NoulQuestion -> "noul"
                is ScoreQuestion -> "score"
            }
            val declaredType = stringValue(answer, "type", "answer '$name'")
            if (declaredType != expectedType) {
                throw WireProtocolException(
                    "answer '$name' declares type '$declaredType' but the question asked under " +
                        "that name is a '$expectedType' — mismatched discriminator is a loud error",
                )
            }
            return when (question) {
                is ChoiceQuestion -> {
                    val optionIds = question.options.map { it.id }
                    val chosen = stringValue(answer, "choice", "choice answer '$name'")
                    if (chosen !in optionIds) {
                        throw WireProtocolException(
                            "choice answer '$name': chosen '$chosen' is not one of the offered " +
                                "option ids $optionIds",
                        )
                    }
                    val probabilities = distribution(
                        answer, "probabilities", "choice answer '$name'", optionIds,
                    )
                    val confidence = probability(answer, "confidence", "choice answer '$name'")
                    ChoiceResult(chosen, probabilities, confidence)
                }

                is NoulQuestion -> NoulResult(probability(answer, "noul", "noul answer '$name'"))

                is ScoreQuestion -> {
                    // Wire keys are 0-based indices ("0".."N-1"); legend maps each index
                    // back to the level string it was declared as.
                    val indices = question.levels.indices.map { it.toString() }
                    val legend = answer["legend"] as? JsonObject
                        ?: throw WireProtocolException(
                            "score answer '$name': missing required field 'legend' (index -> " +
                                "level string) — loud failure, never a default (wire law)",
                        )
                    val extraLegend = legend.keys - indices.toSet()
                    if (extraLegend.isNotEmpty()) {
                        throw WireProtocolException(
                            "score answer '$name': 'legend' has entries outside the declared " +
                                "indices $indices: $extraLegend (distributions are exact and " +
                                "name-keyed — loud failure)",
                        )
                    }
                    for (index in question.levels.indices) {
                        val level = stringValue(legend, index.toString(), "score answer '$name'.legend")
                        if (level != question.levels[index].description) {
                            throw WireProtocolException(
                                "score answer '$name': legend entry $index is '$level' but the " +
                                    "declared rubric level is '${question.levels[index].description}' " +
                                    "— the provider answered a different rubric (loud error, never remapped)",
                            )
                        }
                    }
                    val distribution = distribution(
                        answer, "probabilities", "score answer '$name'", indices,
                    )
                    // Convert 0-based wire indices to the 1-based internal ordinals, then
                    // ART-5: the expected value is computed in :core code — the provider's
                    // "score" echo, if present, is ignored.
                    val levelProbabilities =
                        distribution.entries.associate { (k, v) -> (k.toInt() + 1) to v }
                    ScoreResult(scoreExpectedLevel(levelProbabilities), levelProbabilities)
                }
            }
        }

        private fun parseJsonObject(text: String, what: String): JsonObject = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            throw WireProtocolException("malformed JSON $what: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw WireProtocolException("malformed JSON $what: ${e.message}")
        }

        private fun stringValue(o: JsonObject, key: String, what: String): String {
            val primitive = o[key] as? JsonPrimitive
                ?: throw WireProtocolException(
                    "$what: missing required field '$key' — loud failure, never a default (wire law)",
                )
            return primitive.content
        }

        private fun intValue(o: JsonObject, key: String, what: String): Long {
            val value = numberValue(o, key, what)
            if (value % 1.0 != 0.0) throw WireProtocolException("$what: field '$key' must be an integer, got $value")
            return value.toLong()
        }

        private fun probability(o: JsonObject, key: String, what: String): Double {
            val value = numberValue(o, key, what)
            if (value !in 0.0..1.0) throw WireProtocolException("$what: field '$key' out of range [0,1]: $value")
            return value
        }

        private fun numberValue(o: JsonObject, key: String, what: String): Double {
            val primitive = o[key] as? JsonPrimitive
                ?: throw WireProtocolException(
                    "$what: missing required field '$key' — loud failure, never a default (wire law)",
                )
            return primitive.content.toDoubleOrNull()
                ?: throw WireProtocolException("$what: field '$key' must be a number, got '${primitive.content}'")
        }

        /**
         * A distribution must cover EXACTLY the declared names (choice: option ids; score:
         * 0-based indices "0".."N-1") — keyed by name, complete, each value in [0,1].
         * Unknown keys and values for names we did not declare are malformed content, not
         * provider variance.
         */
        private fun distribution(
            o: JsonObject,
            key: String,
            what: String,
            expectedKeys: Collection<String>,
        ): Map<String, Double> {
            val map = o[key] as? JsonObject
                ?: throw WireProtocolException(
                    "$what: missing required field '$key' (object keyed by name) — loud failure, " +
                        "never a default (wire law)",
                )
            val result = LinkedHashMap<String, Double>()
            for (expected in expectedKeys) {
                result[expected] = probability(map, expected, "$what.$key")
            }
            val unknown = map.keys - result.keys
            if (unknown.isNotEmpty()) {
                throw WireProtocolException(
                    "$what: '$key' has keys outside the declared names $expectedKeys: $unknown " +
                        "(distributions are exact and name-keyed — loud failure)",
                )
            }
            return result
        }
    }
}
