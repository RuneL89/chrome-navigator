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

/** A completed wire decision: parsed typed answers plus the failover tag (phase 3 §2.2). */
data class WireDecision(
    val answers: Map<String, TypedResult>,
    val usedFallback: Boolean,
    val servedBy: EngineConfig,
)

/**
 * The one wire client for the `/v1/systemone`-family JSON contract (ART-2, vision 02 §2).
 * Every cloud engine is a config of this client; nothing else in the codebase speaks to a
 * provider. This is also what Phase 8 wraps (shadow) and Phase 10 contrasts.
 *
 * Request: `POST {baseUrl}` with `Authorization: Bearer {key}` and JSON body
 * `{"state": ..., "model": <pinned id>, "questions": {"<name>": {<typed shape>}}}`.
 * Question shapes (researched provider contract):
 *  - `choice`: `{"type":"choice","options":[{"id","label","description"}]}`
 *  - `noul`: `{"type":"noul","proposition":"..."}`
 *  - `score`: `{"type":"score","levels":[{"id":<1-based ordinal>,"description":"..."}]}`
 *    (rubric levels are ordinal; wire ids are their 1-based declared order, ART-5/ART-6).
 *
 * Response: JSON whose required `answers` object maps question NAME to answer body —
 * key order shifts between calls, so answers are always indexed by name, never position.
 *  - choice: `{"choice":"<option-id>","probabilities":{...},"confidence":0.xx}`
 *  - noul: `{"noul":0.xx}` (no separate confidence — uncertain band means "don't know")
 *  - score: `{"score":<float>,"level_probabilities":{"1":0.xx,...}}` — the provider's
 *    `score` echo is IGNORED: the expected value is computed in `:core` code
 *    ([scoreExpectedLevel], ART-5), never taken from the model.
 *
 * Field law: providers may add unknown FIELDS (extra answer/top-level fields are ignored);
 * the client REQUIRES the fields it uses — a missing question name, missing answer field,
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
                val answers = parseAnswers(response.body, config, questions)
                return WireDecision(answers, usedFallback, config)
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
            is ChoiceQuestion -> buildJsonObject {
                put("type", "choice")
                put("options", buildJsonArray {
                    question.options.forEach { option ->
                        add(buildJsonObject {
                            put("id", option.id)
                            put("label", option.label)
                            put("description", option.description)
                        })
                    }
                })
            }

            is NoulQuestion -> buildJsonObject {
                put("type", "noul")
                put("proposition", question.proposition)
            }

            is ScoreQuestion -> buildJsonObject {
                put("type", "score")
                put("levels", buildJsonArray {
                    question.levels.forEachIndexed { index, level ->
                        // Wire id = 1-based ordinal of the declared rubric order (ART-6);
                        // the typed ScoreLevel.id stays internal (ordinal is the wire name).
                        add(buildJsonObject {
                            put("id", index + 1)
                            put("description", level.description)
                        })
                    }
                })
            }
        }

        /**
         * Parses `answers` by question NAME — the interpretation of each answer body comes
         * from the question asked under that name (provider answer bodies carry no type
         * discriminator). Unknown question names, missing answers, and missing/invalid
         * required fields fail loudly.
         */
        internal fun parseAnswers(
            responseBody: String,
            config: EngineConfig,
            questions: Map<String, TypedQuestion>,
        ): Map<String, TypedResult> {
            val root = parseJsonObject(responseBody, "response body from ${config.baseUrl}")
            val answers = root["answers"] as? JsonObject
                ?: throw WireProtocolException(
                    "response from ${config.baseUrl} is missing the required 'answers' object " +
                        "(answers are keyed by question name); top-level keys were ${root.keys}",
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
            return parsed
        }

        private fun parseAnswer(name: String, question: TypedQuestion, answer: JsonObject): TypedResult =
            when (question) {
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
                    val ordinals = (1..question.levels.size).map { it.toString() }
                    val distribution = distribution(
                        answer, "level_probabilities", "score answer '$name'", ordinals,
                    )
                    // ART-5: the numeric mapping (expected value over levels) is computed in
                    // :core code — the provider's "score" echo, if present, is ignored.
                    val levelProbabilities = distribution.entries.associate { (k, v) -> k.toInt() to v }
                    ScoreResult(scoreExpectedLevel(levelProbabilities), levelProbabilities)
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
         * "1".."N") — keyed by name, complete, each value in [0,1]. Unknown keys and values
         * for names we did not declare are malformed content, not provider variance.
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
