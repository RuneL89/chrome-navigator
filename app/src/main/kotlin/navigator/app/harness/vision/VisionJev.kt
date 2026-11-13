package navigator.app.harness.vision

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import navigator.core.wire.ModelCallException
import navigator.core.wire.ModelCallPolicy
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireTransport
import navigator.core.wire.WireTransportException

/**
 * The vision engine's multimodal systemone client (the shared wire law lives in
 * [navigator.core.wire.SystemOneClient]). Speaks the
 * Typesafe images API shapes (docs.typesafe.ai/images, primitives/box):
 *
 * - request `{state, model, assets?, questions}` where [state] is the usual TEXT state
 *   with an image reference string embedded (backticks) wherever the screenshot belongs,
 *   and `assets` maps the reference to `{"type":"image","data_base64":...,"format":"jpeg"}`;
 * - EVERY request carrying an image sends the `TypeSafe-Danger-Zone-Images: true` header;
 * - questions: `box` (one target, returns fractional edges + confidence), `noul`
 *   (the proposition IS the instructions), `choice` (record criteria keyed by option id).
 *   N boxes + 1 choice ride ONE request (the scoreBatch one-call habit).
 *
 * CALL LAW (PHASE_18 CN-08, doc 04 §9.7): every logical call rides [ModelCallPolicy] —
 * max attempts (3), a finite total deadline, status-selective retries (408/429/5xx +
 * bounded `Retry-After`), and a typed final failure; ordinary 4xx and any malformed 200
 * are loud content failures, never retried. (The previous transport-only 20-retry
 * ceiling from the 100x directive is superseded by §9.7; the retained a11y engine's
 * `SystemOneClient` keeps its own vision 02 §2 law — max 2, transport only.)
 * Usage is reported per completed call; images price as text tokens, so the ledger prices tokens
 * at the production decision-engine table.
 *
 * SETTLED 2026-10-18 (all routes/formats tested, known-answer frame):
 * - TypeSafe VENDOR endpoint (`api.typesafe.ai/v1/systemone`, TypeSafe key,
 *   danger header, model `jev-1.14.0`, base64-in-JSON): **the full image contract
 *   works** — `input_tokens: 6188` (image rode), noul 0.98 on "the search result
 *   listing is visible", box IoU ≈ 0.76 vs ground truth (conf 0.46), center inside
 *   the card. This is the DEFAULT route (PHASE_VEXP1 work item 3:
 *   [DEFAULT_VISION_BASE_URL] / [DEFAULT_VISION_MODEL]).
 * - OpenRouter `alpha/decisions`: multipart rejected (`400 invalid content-type` —
 *   JSON-only route), base64 stripped in both state forms (text-only token counts,
 *   blind answers). Registry declares `text+image` — an OpenRouter proxy gap; repro
 *   set in `.state/vision-iter/run1/REPORT.md` for a support report. The alias
 *   `typesafe/jev-1.14` stays available for A/B via the adb extras.
 * The [Question.Box] type is live on the vendor route.
 *
 * Usage/pricing: the vendor response carries no `cost` field — [readUsage] keeps the
 * conservative production price-table fallback ($3/M in, $15/M out); the OpenRouter
 * registry price for reference is ~$0.042/M prompt tokens, so the ledger overcounts by
 * design (never undercounts).
 */
class VisionJev(
    private val transport: WireTransport,
    val baseUrl: String,
    val model: String,
    private val apiKey: String,
    private val onUsage: (VisionUsage) -> Unit = {},

    /**
     * PHASE_18 CN-08: the attempt bound supersedes the 100x directive's 20 — doc 04
     * §9.7's [ModelCallPolicy] maxAttempts (default 3 = maxRetries 2 + the first try).
     * Public read-only so the JVM tests pin the new bound.
     */
    val maxRetries: Int = 2,
    private val backoffBaseMs: Long = 200L,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    /** The call policy (deadline + bounds); the default implements doc 04 §9.7. */
    val policy: ModelCallPolicy = ModelCallPolicy(
        maxAttempts = maxRetries + 1,
        initialBackoffMs = backoffBaseMs,
    ),
    /** Elapsed-realtime seam (the deadline's clock; tests inject a fixed clock). */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** The LAST successful call's usage (PHASE_17 Stage 0.6: the evidence's per-call cost). */
    var lastUsage: VisionUsage? = null
        private set

    /**
     * PHASE_18 CN-16: the LAST call's real attempt count (the ModelCallPolicy retry
     * law) — the evidence's per-call `attempts=` field rides this, never a placeholder.
     * Set on the answered paths (success and the billed content failure); transport
     * failures carry their attempts on the typed [ModelCallException] itself.
     */
    var lastAttempts: Int = 0
        private set

    /** Provider-reported usage of one call (the cost float is preferred when present). */
    data class VisionUsage(val inputTokens: Long, val outputTokens: Long, val costMicroUsd: Long)

    /** One question on the request. [Box] requires an image on the request. */
    sealed interface Question {
        /** Every question's wire key. */
        val name: String

        /** Locate ONE described target on the screenshot; the answer is fractional edges. */
        data class Box(override val name: String, val description: String) : Question

        /** Judge the proposition true of the state (text + screenshot). */
        data class Noul(override val name: String, val proposition: String) : Question

        /** Pick one option; [options] are id-description pairs (ids are the wire keys). */
        data class Choice(
            override val name: String,
            val instructions: String,
            val options: List<Pair<String, String>>,
        ) : Question
    }

    /** One parsed answer, keyed by question name in [VisionJev.ask]'s return map. */
    sealed interface Answer {
        data class Box(
            val top: Double,
            val bottom: Double,
            val left: Double,
            val right: Double,
            val confidence: Double,
        ) : Answer

        data class Noul(val pTrue: Double) : Answer
        data class Choice(val chosen: String, val probabilities: Map<String, Double>) : Answer
    }

    class VisionJevException(message: String) : IllegalStateException(message)

    /** The evidence-event identity of this client (endpoint + model, never the key). */
    val describe: String get() = "endpoint=$baseUrl model=$model"

    private data class BoxEdges(val top: Double, val bottom: Double, val left: Double, val right: Double)

    /**
     * Asks one request. [imageBase64] null = a TEXT-ONLY request (no assets, no danger
     * header) — the degrade path when a capture failed; box questions are then refused
     * at construction (a box without an image is incoherent).
     */
    suspend fun ask(
        state: String,
        questions: List<Question>,
        imageBase64: String?,
        imageRef: String,
        what: String,
        onRaw: ((requestBody: String, responseBody: String) -> Unit)? = null,
    ): Map<String, Answer> {
        require(questions.isNotEmpty()) { "$what: no questions asked" }
        require(questions.none { it is Question.Box } || imageBase64 != null) {
            "$what: box questions require a screenshot (the images API has no text-only box)"
        }
        val body = buildRequestJson(state, model, questions, imageBase64, imageRef).toString()
        var attempt = 0
        val started = nowMs()
        while (true) {
            val elapsed = nowMs() - started
            if (elapsed > policy.totalDeadlineMs) {
                throw ModelCallException(
                    ModelCallException.Category.DEADLINE, attempt, elapsed,
                    "$what: the total call deadline (${policy.totalDeadlineMs}ms) expired",
                )
            }
            val response = try {
                transport.execute(
                    WireHttpRequest(
                        url = baseUrl,
                        headers = buildHeaders(apiKey, imageBase64 != null),
                        body = body,
                    ),
                )
            } catch (e: WireTransportException) {
                attempt++
                if (attempt >= policy.maxAttempts) {
                    throw ModelCallException(
                        ModelCallException.Category.TRANSPORT, attempt, nowMs() - started,
                        "$what: transport errors persisted past the attempts: ${e.message}", e,
                    )
                }
                val d = policy.delayMs(attempt - 1, null, nowMs() - started)
                if (d < 0) {
                    throw ModelCallException(
                        ModelCallException.Category.DEADLINE, attempt, nowMs() - started,
                        "$what: the total call deadline expired before the retry",
                    )
                }
                retryDelay(d)
                continue
            }
            if (response.code !in 200..299) {
                if (ModelCallPolicy.retryableStatus(response.code)) {
                    attempt++
                    if (attempt >= policy.maxAttempts) {
                        throw ModelCallException(
                            ModelCallException.Category.STATUS, attempt, nowMs() - started,
                            "$what: HTTP ${response.code} from the provider persisted past " +
                                "the attempts. Body: ${response.body.take(300)}",
                        )
                    }
                    val d = policy.delayMs(
                        attempt - 1,
                        ModelCallPolicy.retryAfterMs(response.headers),
                        nowMs() - started,
                    )
                    if (d < 0) {
                        throw ModelCallException(
                            ModelCallException.Category.DEADLINE, attempt, nowMs() - started,
                            "$what: the total call deadline expired before the retry",
                        )
                    }
                    retryDelay(d)
                    continue
                }
                throw VisionJevException(
                    "HTTP ${response.code} from $baseUrl (model $model) during $what — not retried. " +
                        "Body: ${response.body.take(300)}",
                )
            }
            val parsed = try {
                parseResponse(response.body, questions, what)
            } catch (e: Exception) {
                // PHASE_17 Stage 0.6: a 2xx answer that fails validation was still
                // billed — meter the envelope's usage when it carries one, then rethrow
                // (the caller's degrade path keeps the call's cost visible).
                lastAttempts = attempt + 1
                lenientUsage(response.body)?.let { lastUsage = it; onUsage(it) }
                throw e
            }
            lastUsage = parsed.second
            lastAttempts = attempt + 1
            onUsage(parsed.second)
            onRaw?.invoke(body, response.body)
            return parsed.first
        }
    }

    companion object {
        /** The images API's required header whenever an asset rides the request. */
        const val DANGER_ZONE_IMAGES_HEADER = "TypeSafe-Danger-Zone-Images"

        /**
         * The DEFAULT route for vision (PHASE_VEXP1 work item 3): the VENDOR endpoint —
         * the only live-proven transport that carries images to Jev (input_tokens 6188 on
         * the known-answer frame). The adb `--es visionJevUrl` extra still overrides for
         * A/B (e.g. the OpenRouter alpha endpoint, which strips image assets).
         */
        const val DEFAULT_VISION_BASE_URL = "https://api.typesafe.ai/v1/systemone"

        /**
         * The vision engine's default Jev snapshot (Box requires Jev 1.14). The
         * VENDOR endpoint takes the dated vendor slug `jev-1.14.0`; the OpenRouter alpha
         * endpoint resolves the ALIAS `typesafe/jev-1.14` instead (`typesafe/jev-1.14-20261014`,
         * live-verified 2026-10-17; the vendor-style slugs 400 on the proxy).
         */
        const val DEFAULT_VISION_MODEL = "jev-1.14.0"

        /**
         * The evidence-event identity of this client rides the instance: [baseUrl] and
         * [model] are public read-only, so callers record
         * `"endpoint=$baseUrl model=$model"` on every image-carrying call's event.
         */

        /**
         * A fresh image reference per request (the docs' `<uuid>-label` pattern; every
         * asset must be referenced or the request 400s, so a unique ref per call keeps
         * repeats honest).
         */
        fun newImageRef(): String = "vision-" + java.util.UUID.randomUUID().toString().take(8) + "-screen"

        internal fun buildHeaders(apiKey: String, imagePresent: Boolean): Map<String, String> =
            buildMap {
                put("Authorization", "Bearer $apiKey")
                put("Content-Type", "application/json; charset=utf-8")
                if (imagePresent) put(DANGER_ZONE_IMAGES_HEADER, "true")
            }

        internal fun buildRequestJson(
            state: String,
            model: String,
            questions: List<Question>,
            imageBase64: String?,
            imageRef: String,
        ): JsonObject = buildJsonObject {
            put("state", state)
            put("model", model)
            if (imageBase64 != null) {
                put("assets", buildJsonObject {
                    put(imageRef, buildJsonObject {
                        put("type", "image")
                        put("data_base64", imageBase64)
                        put("format", "jpeg")
                    })
                })
            }
            put("questions", buildJsonObject {
                questions.forEach { q ->
                    put(q.name, when (q) {
                        is Question.Box -> buildJsonObject {
                            put("type", "box")
                            put("instructions", "${q.description} in `$imageRef`")
                        }
                        is Question.Noul -> buildJsonObject {
                            put("type", "noul")
                            put("instructions", q.proposition)
                        }
                        is Question.Choice -> buildJsonObject {
                            put("type", "choice")
                            put("instructions", q.instructions)
                            put("criteria", buildJsonObject {
                                q.options.forEach { (id, description) -> put(id, description) }
                            })
                        }
                    })
                }
            })
        }

        internal fun parseResponse(
            responseBody: String,
            questions: List<Question>,
            what: String,
        ): Pair<Map<String, Answer>, VisionUsage> {
            val root = parseObject(responseBody, what)
            val answers = root["answers"] as? JsonObject
                ?: throw VisionJevException("$what: response is missing the 'answers' object; keys=${root.keys}")
            val usageObject = root["usage"] as? JsonObject
                ?: throw VisionJevException("$what: response is missing the 'usage' object; keys=${root.keys}")
            val usage = readUsage(usageObject, what)
            val byName = questions.associateBy { it.name }
            val out = LinkedHashMap<String, Answer>()
            for ((name, question) in byName) {
                val answer = answers[name] as? JsonObject
                    ?: throw VisionJevException("$what: missing answer for question '$name'")
                val declared = (answer["type"] as? JsonPrimitive)?.content
                    ?: throw VisionJevException("$what: answer '$name' carries no type discriminator")
                out[name] = when (question) {
                    is Question.Box -> {
                        if (declared != "box") {
                            throw VisionJevException("$what: answer '$name' declares '$declared', asked a box")
                        }
                        val box = answer["box"] as? JsonObject
                            ?: throw VisionJevException("$what: box answer '$name' missing 'box' object")
                        Answer.Box(
                            top = fraction(box, "top", name, what),
                            bottom = fraction(box, "bottom", name, what),
                            left = fraction(box, "left", name, what),
                            right = fraction(box, "right", name, what),
                            confidence = (answer["confidence"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0,
                        )
                    }

                    is Question.Noul -> {
                        if (declared != "noul") {
                            throw VisionJevException("$what: answer '$name' declares '$declared', asked a noul")
                        }
                        Answer.Noul(probability(answer, "noul", name, what))
                    }

                    is Question.Choice -> {
                        if (declared != "choice") {
                            throw VisionJevException("$what: answer '$name' declares '$declared', asked a choice")
                        }
                        val chosen = (answer["choice"] as? JsonPrimitive)?.content
                            ?: throw VisionJevException("$what: choice answer '$name' missing 'choice'")
                        if (question.options.none { it.first == chosen }) {
                            throw VisionJevException(
                                "$what: choice '$chosen' is not one of the offered ids " +
                                    question.options.joinToString(",", "[", "]") { it.first },
                            )
                        }
                        val probabilities = LinkedHashMap<String, Double>()
                        val dist = answer["probabilities"] as? JsonObject
                        if (dist != null) {
                            for ((id, _) in question.options) {
                                probabilities[id] =
                                    (dist[id] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
                            }
                        }
                        Answer.Choice(chosen, probabilities)
                    }
                }
            }
            val unknown = answers.keys - byName.keys
            if (unknown.isNotEmpty()) {
                throw VisionJevException("$what: answers for unknown question names $unknown")
            }
            return out to usage
        }

        /** Integer micro-USD spend: the provider's cost float when present, else tokens at the production decision-engine price table ($3/$15 per M). */
        private fun readUsage(o: JsonObject, what: String): VisionUsage {
            val input = longOf(o, "input_tokens", what)
            val output = longOf(o, "output_tokens", what)
            val costFloat = (o["cost"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            val costMicro = if (costFloat != null) (costFloat * 1_000_000).toLong() else jevCost(input, output)
            return VisionUsage(input, output, costMicro)
        }

        /**
         * PHASE_17 Stage 0.6: the usage of a response whose ANSWERS did not validate —
         * null when the envelope carries no readable usage object. Never throws (the
         * caller is already on an error path).
         */
        internal fun lenientUsage(responseBody: String): VisionUsage? = try {
            val usage = Json.parseToJsonElement(responseBody).jsonObject["usage"] as? JsonObject
            usage?.let { readUsage(it, "lenient") }
        } catch (e: Exception) {
            null
        }

        /** The production decision-engine price table (SpendPrices): $3/M in, $15/M out. */
        fun jevCost(inputTokens: Long, outputTokens: Long): Long =
            inputTokens * 3_000L / 1_000 + outputTokens * 15_000L / 1_000

        private fun parseObject(text: String, what: String): JsonObject = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw VisionJevException("$what: malformed JSON response: ${e.message}")
        }

        private fun longOf(o: JsonObject, key: String, what: String): Long =
            (o[key] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: throw VisionJevException("$what: usage field '$key' missing or not an integer")

        private fun probability(o: JsonObject, key: String, name: String, what: String): Double {
            val v = (o[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
                ?: throw VisionJevException("$what: answer '$name' missing '$key'")
            require(v in 0.0..1.0) { "$what: answer '$name' '$key' out of range: $v" }
            return v
        }

        private fun fraction(o: JsonObject, key: String, name: String, what: String): Double {
            val v = (o[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
                ?: throw VisionJevException("$what: box answer '$name' missing edge '$key'")
            require(v in 0.0..1.0) { "$what: box answer '$name' edge '$key' out of [0,1]: $v" }
            return v
        }
    }
}
