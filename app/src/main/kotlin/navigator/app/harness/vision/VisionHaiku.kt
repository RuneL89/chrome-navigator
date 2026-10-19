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
 * model looked". Retry law mirrors the production writer (transport only); the retry
 * ceiling rides the 100x budget directive (owner, 2026-10-19): [maxRetries] defaults
 * to 20. Usage is priced with the same pinned conservative table
 * ([GenClient.writerCostMicroUsd]).
 */
class VisionHaiku(
    private val transport: WireTransport,
    private val apiKey: String,
    private val model: String = GenEngineConfigs.GENERATIVE_MODEL,
    val ablateVision: Boolean = false,
    private val onUsage: (VisionJev.VisionUsage) -> Unit = {},
    /** Public read-only so the JVM tests pin the 100x directive's ceiling. */
    val maxRetries: Int = 20,
    private val backoffBaseMs: Long = 200L,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {

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
        while (true) {
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
                if (attempt >= maxRetries) throw e
                retryDelay(backoffBaseMs shl attempt)
                attempt++
                continue
            }
            if (response.code !in 200..299) {
                throw VisionHaikuException(
                    "HTTP ${response.code} from ${GenEngineConfigs.OPENROUTER_CHAT_URL} (model $model) " +
                        "during $what — not retried. Body: ${response.body.take(300)}",
                )
            }
            val content = parseContent(response.body, what)
            val root = Json.parseToJsonElement(response.body).jsonObject
            val usage = root["usage"] as? kotlinx.serialization.json.JsonObject
            val input = longOf(usage, "prompt_tokens")
            val output = longOf(usage, "completion_tokens")
            onUsage(
                VisionJev.VisionUsage(input, output, GenClient.writerCostMicroUsd(input, output)),
            )
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
