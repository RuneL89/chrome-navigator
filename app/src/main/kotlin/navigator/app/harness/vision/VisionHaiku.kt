package navigator.app.harness.vision

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import navigator.app.gen.GenClient
import navigator.app.gen.GenEngineConfigs
import navigator.core.wire.ModelCallException
import navigator.core.wire.ModelCallPolicy
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireTransport
import navigator.core.wire.WireTransportException

/**
 * The vision engine's multimodal writer client (the a11y writer stays
 * [navigator.app.gen.GenClient], whose request body is string-content only). Speaks
 * OpenRouter chat-completions with IMAGE CONTENT PARTS — the pinned writer model
 * `anthropic/claude-haiku-5.5` is text+image->text (verified against the OpenRouter
 * model registry 2026-10-17):
 *
 * `content: [{"type":"text","text":...}, {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}]`
 *
 * [ablateVision] = the text-only ABLATION arm: identical calls, the image part never
 * attached — so a before/after pair attributes gains to VISION rather than to "another
 * model looked". CALL LAW (PHASE_18 CN-08, doc 04 §9.7): every logical call rides
 * [ModelCallPolicy] — max attempts (3), a finite total deadline, status-selective
 * retries (408/429/5xx + bounded `Retry-After`), and a typed final failure; ordinary
 * 4xx and malformed 2xx bodies are loud content failures, never retried. (The previous
 * 20-retry/unchecked-backoff ceiling from the 100x directive is superseded by §9.7.)
 * Usage is priced with the same pinned conservative table
 * ([GenClient.writerCostMicroUsd]).
 */
class VisionHaiku(
    private val transport: WireTransport,
    private val apiKey: String,
    private val model: String = GenEngineConfigs.GENERATIVE_MODEL,
    val ablateVision: Boolean = false,
    private val onUsage: (VisionJev.VisionUsage) -> Unit = {},

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
    var lastUsage: VisionJev.VisionUsage? = null
        private set

    /**
     * PHASE_18 CN-16: the LAST call's real attempt count (the ModelCallPolicy retry
     * law's transport/status retries + the first try) — the evidence's per-call
     * `attempts=` field rides this, never a placeholder.
     */
    var lastAttempts: Int = 0
        private set

    class VisionHaikuException(message: String) : IllegalStateException(message)

    /**
     * One chat call. [imageBase64] null or [ablateVision] = text-only user message.
     * Returns the assistant content.
     */
    suspend fun chat(
        system: String,
        userText: String,
        imageBase64: String?,
        what: String,
        onRaw: ((requestBody: String, responseBody: String) -> Unit)? = null,
    ): String {
        val body = buildChatBody(model, system, userText, imageBase64?.takeUnless { ablateVision })
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
                        url = GenEngineConfigs.OPENROUTER_CHAT_URL,
                        headers = mapOf(
                            "Authorization" to "Bearer $apiKey",
                            "Content-Type" to "application/json; charset=utf-8",
                        ),
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
                throw VisionHaikuException(
                    "HTTP ${response.code} from ${GenEngineConfigs.OPENROUTER_CHAT_URL} (model $model) " +
                        "during $what — not retried. Body: ${response.body.take(300)}",
                )
            }
            // PHASE_17 Stage 0.6: the usage is read and metered BEFORE the content parse
            // — a 2xx response that answers badly was still billed, and the run's spend
            // ledger must say so (the caller's degrade path can then record the call).
            val root = try {
                Json.parseToJsonElement(response.body).jsonObject
            } catch (e: Exception) {
                throw VisionHaikuException("$what: malformed JSON response: ${e.message}")
            }
            // PHASE_18 CN-16 (doc 05 §8): the usage parse distinguishes the provider
            // OMITTING the usage object — recorded UNKNOWN downstream (lastUsage null),
            // never a silent zero — from a present-but-partial object, whose declared
            // fields parse leniently. An unknown spend is never metered as $0.
            val usage = root["usage"] as? kotlinx.serialization.json.JsonObject
            if (usage == null) {
                lastUsage = null
            } else {
                val input = longOf(usage, "prompt_tokens")
                val output = longOf(usage, "completion_tokens")
                val used = VisionJev.VisionUsage(input, output, GenClient.writerCostMicroUsd(input, output))
                lastUsage = used
                onUsage(used)
            }
            lastAttempts = attempt + 1
            val content = parseContent(response.body, what)
            onRaw?.invoke(body, response.body)
            return content
        }
    }

    companion object {
        internal fun buildChatBody(
            model: String,
            system: String,
            userText: String,
            imageBase64: String?,
        ): String = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        if (imageBase64 == null) JsonPrimitive(userText) else buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", userText)
                            })
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", "data:image/jpeg;base64,$imageBase64")
                                })
                            })
                        },
                    )
                })
            })
        }.toString()

        internal fun parseContent(responseBody: String, what: String): String {
            val root = try {
                Json.parseToJsonElement(responseBody).jsonObject
            } catch (e: Exception) {
                throw VisionHaikuException("$what: malformed JSON response: ${e.message}")
            }
            val choices = root["choices"] as? kotlinx.serialization.json.JsonArray
            val first = choices?.firstOrNull() as? kotlinx.serialization.json.JsonObject
            val message = first?.get("message") as? kotlinx.serialization.json.JsonObject
            return (message?.get("content") as? JsonPrimitive)?.content
                ?: throw VisionHaikuException(
                    "$what: response missing choices[0].message.content — loud failure, never a default",
                )
        }

        private fun longOf(usage: kotlinx.serialization.json.JsonObject?, key: String): Long =
            (usage?.get(key) as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
    }
}
