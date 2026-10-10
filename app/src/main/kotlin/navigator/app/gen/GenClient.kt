package navigator.app.gen

import kotlinx.coroutines.delay
import navigator.core.ladder.Ladder
import navigator.core.wire.WireTransport
import navigator.core.wire.WireTransportException

/**
 * Everything that identifies the generative writer endpoint (Phase 10, vision 01 §3's
 * "cloud LLM (generative)" component; ART-2 unaffected — the writer never decides, it
 * only writes). Same pinning law as [navigator.core.wire.EngineConfig] (vision 02 §5):
 * the model id lives HERE only; upgrading it is an eval-harness-gated bump, never a
 * runtime value.
 *
 * The writer speaks the OpenRouter `/api/v1/chat/completions` protocol (chat
 * completions — a DIFFERENT surface from the decision engines' systemone wire), so it
 * gets its own client ([GenClient]) over the SAME injected transport seam — no second
 * HTTP speaker exists in the repo.
 */
data class GenConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    init {
        require(baseUrl.startsWith("https://")) { "GenConfig.baseUrl must be https: '$baseUrl'" }
        require(model.isNotBlank()) { "GenConfig.model must not be blank (pinned id, vision 02 §5 law)" }
        require(apiKey.isNotEmpty()) { "GenConfig.apiKey must not be empty — an empty Bearer key must fail loudly" }
    }

    /** Redacts the key: writer configs must never leak secrets through logs or errors. */
    override fun toString(): String = "GenConfig(baseUrl=$baseUrl, model=$model, apiKey=REDACTED)"
}

/**
 * The pinned generative config + its price table (ART-5: numbers in code). The writer
 * key is the SAME OpenRouter key as the decision primary (one key, Round 4); the pinning
 * law is the decision engines' (vision 02 §5): a bump is an eval-gated commit, never a
 * drift.
 */
object GenEngineConfigs {

    /** The OpenRouter chat-completions surface (the writer roles' endpoint). */
    const val OPENROUTER_CHAT_URL = "https://openrouter.ai/api/v1/chat/completions"

    /**
     * The pinned generative writer model. Pinned snapshot id (never a floating alias);
     * changing it requires the vision 02 §5 pinning law (eval-gated bump commit).
     * 2026-10-09 verifier bump: the original pre-commit guess (`anthropic/claude-3.5-haiku`)
     * is NOT a live OpenRouter slug (0 hits on the live models index); bumped to the
     * verified-live haiku-class slug before first commit — the golden fixture's model
     * field moved with it (the fixture had never been committed).
     */
    const val GENERATIVE_MODEL = "anthropic/claude-haiku-5.5"

    /**
     * The writer's integer micro-USD prices (ART-5, SpendMeter discipline): micro-USD
     * per 1K tokens, applied to PROVIDER-REPORTED token counts. Changing a price is a
     * reviewed amendment. NOTE (2026-10-09 pin bump): the table still carries the
     * ORIGINAL conservative prices ($0.80/$4 per M) — an UPPER BOUND for the cheaper
     * haiku-5.5 tier (~$0.1-0.5/M), so writer-cap accounting never understates spend;
     * re-measure and amend with live Gate 10.4 usage in hand.
     */
    const val INPUT_MICRO_USD_PER_1K: Long = 800L // $0.80 / 1M input tokens (upper bound)
    const val OUTPUT_MICRO_USD_PER_1K: Long = 4_000L // $4 / 1M output tokens (upper bound)

    fun generative(apiKey: String): GenConfig =
        GenConfig(OPENROUTER_CHAT_URL, apiKey, GENERATIVE_MODEL)
}

/** Provider-reported usage of one writer call, cost in INTEGER micro-dollars (ART-5). */
data class GenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    /** input*price + output*price in integer micro-dollars — no float ever touches spend. */
    val costMicroUsd: Long,
)

/** One completed restructure: the validated ladder + its usage + provenance. */
data class RestructureResult(
    val ladder: Ladder,
    val usage: GenUsage,
    /** The restructure prompt version that drafted this ladder (rides every row). */
    val promptVersion: String,
    /** The ladder generation this call produced (1 = the initial restructure). */
    val generation: Int,
)

/** One completed answer extraction (Phase 11's result-screen input; rides the report). */
data class ExtractionResult(
    /** The phrased answer — content only (doc 06 §4), never navigation instructions. */
    val answer: String,
    val usage: GenUsage,
    val promptVersion: String,
)

/**
 * The generative writer client (Phase 10, phase doc §2.1): ONE OpenRouter
 * chat-completions caller for both writer roles — `restructure(goal): Ladder` (ONE call,
 * versioned prompt) and `extractAnswer(goal, finalState)` (doc 06 §4: reports content,
 * not instructions). The HTTP client sits in `:app` beside the wire client's patterns
 * and reuses the SAME injected transport seam — no second HTTP speaker.
 *
 * Laws (mirroring the wire client, vision 02 §2):
 * - RETRY LAW: only transport errors ([WireTransportException]) are retried, at most
 *   [maxRetries] times (law maximum 2), exponential backoff — never an answer the caller
 *   dislikes. Any HTTP status (4xx/5xx) and any malformed 200 are content failures with
 *   no retry.
 * - BOUNDED CONTENT RETRY (restructure only): schema-invalid output ([LadderSchema.
 *   LadderSchemaException]) earns exactly ONE fresh attempt (the model may self-correct);
 *   a second invalid output throws [GenRefusedException] — the caller's clean
 *   pre-navigation refusal (nothing navigates on an invalid ladder).
 * - NO SECRET EVER ENTERS A WRITER PROMPT (doc 06 §2a): the caller supplies the prompt
 *   texts; the restructure call receives the owner's goal only, and the extraction call
 *   receives the code-MASKED final state (the loop folds its redaction keys over it
 *   first). This client never touches the credential path.
 * - Spend is INTEGER micro-dollars from PROVIDER-REPORTED token counts × the pinned
 *   [GenEngineConfigs] prices (ART-5; the provider's own float `cost`, when present, is
 *   ignored for accounting — the wire-client precedent).
 */
class GenClient(
    private val transport: WireTransport,
    private val config: GenConfig,
    private val maxRetries: Int = MAX_RETRIES,
    private val backoffBaseMs: Long = DEFAULT_BACKOFF_BASE_MS,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    /** Observes every successful writer call's usage exactly once (the writer-cap meter). */
    private val onUsage: (GenUsage) -> Unit = {},
    private val maxSubtasks: Int = navigator.core.ladder.LadderLimits.MAX_SUBTASKS,
) {
    init {
        require(maxRetries in 0..MAX_RETRIES) {
            "retry law allows at most $MAX_RETRIES retries, transport errors only (vision 02 §2): $maxRetries"
        }
        require(backoffBaseMs >= 0) { "backoffBaseMs must be >= 0: $backoffBaseMs" }
    }

    /**
     * The task-ladder restructure (vision 04 §1 v1.4.0): ONE writer call turning the
     * free-text [goal] into a code-validated task ladder. Invalid output retries ONCE;
     * a second refusal is [GenRefusedException] — nothing navigates.
     */
    suspend fun restructure(goal: String, generation: Int = 1): RestructureResult {
        require(goal.isNotBlank()) { "goal must not be blank" }
        val body = chatBody(Prompts.RESTRUCTURE_SYSTEM, Prompts.restructureUser(goal, maxSubtasks))
        var lastError: LadderSchema.LadderSchemaException? = null
        for (attempt in 1..CONTENT_ATTEMPTS) {
            val (content, usage) = post(body, "restructure")
            try {
                val ladder = LadderSchema.parse(content, maxSubtasks)
                onUsage(usage)
                return RestructureResult(ladder, usage, Prompts.PROMPT_VERSION, generation)
            } catch (e: LadderSchema.LadderSchemaException) {
                lastError = e
            }
        }
        throw GenRefusedException(
            "the goal could not be restructured into a valid task ladder after " +
                "$CONTENT_ATTEMPTS attempts (${lastError?.message}) — refusing cleanly, " +
                "nothing navigates on an invalid ladder",
        )
    }

    /**
     * The answer extraction (vision 04 §6 / doc 06 §4): ONE call over the goal and the
     * CODE-MASKED final state; returns the phrased answer. The caller (the loop) masks
     * sanctioned secrets out of [finalState] BEFORE this call (doc 06 §2a).
     */
    suspend fun extractAnswer(goal: String, finalState: String): ExtractionResult {
        require(goal.isNotBlank()) { "goal must not be blank" }
        val body = chatBody(Prompts.EXTRACTION_SYSTEM, Prompts.extractionUser(goal, finalState))
        val (content, usage) = post(body, "extract")
        val answer = content.trim()
        if (answer.isEmpty()) {
            throw GenProtocolException("extraction returned an empty answer")
        }
        onUsage(usage)
        return ExtractionResult(answer, usage, Prompts.EXTRACTION_PROMPT_VERSION)
    }

    /**
     * Posts one chat-completions request and returns the assistant message content with
     * the call's usage. Transport errors retry (≤ [maxRetries], exponential backoff);
     * any HTTP status and any malformed/mis-shaped 200 are loud content failures with
     * no retry.
     */
    private suspend fun post(body: String, what: String): Pair<String, GenUsage> {
        var attempt = 0
        while (true) {
            try {
                val response = transport.execute(
                    navigator.core.wire.WireHttpRequest(
                        url = config.baseUrl,
                        headers = mapOf(
                            "Authorization" to "Bearer ${config.apiKey}",
                            "Content-Type" to "application/json; charset=utf-8",
                        ),
                        body = body,
                    ),
                )
                if (response.code !in 200..299) {
                    throw GenProtocolException(
                        "HTTP ${response.code} from ${config.baseUrl} (model ${config.model}) " +
                            "during $what — not retried (wire law). Body: ${response.body.take(200)}",
                    )
                }
                return parseContent(response.body, what)
            } catch (e: WireTransportException) {
                if (attempt >= maxRetries) throw e
                val delayMs = backoffBaseMs shl attempt
                retryDelay(delayMs)
                attempt++
            }
        }
    }

    /**
     * Parses the chat-completions envelope: `choices[0].message.content` +
     * `usage {prompt_tokens, completion_tokens}` (OpenRouter shape; a reported float
     * `cost` is ignored for accounting — ART-5 integer spend from tokens × pinned
     * prices). Missing required fields are loud errors, never defaults.
     */
    private fun parseContent(responseBody: String, what: String): Pair<String, GenUsage> {
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(responseBody) as? kotlinx.serialization.json.JsonObject
        } catch (e: Exception) {
            throw GenProtocolException("malformed JSON $what response: ${e.message}")
        } ?: throw GenProtocolException("$what response root is not a JSON object")
        val choices = root["choices"] as? kotlinx.serialization.json.JsonArray
        val message = (choices?.firstOrNull() as? kotlinx.serialization.json.JsonObject)
            ?.get("message") as? kotlinx.serialization.json.JsonObject
        val content = (message?.get("content") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: throw GenProtocolException(
                "$what response is missing choices[0].message.content — loud failure, never a default",
            )
        val usage = root["usage"] as? kotlinx.serialization.json.JsonObject
        val input = longOf(usage, "prompt_tokens")
        val output = longOf(usage, "completion_tokens")
        return content to GenUsage(
            inputTokens = input,
            outputTokens = output,
            costMicroUsd = writerCostMicroUsd(input, output),
        )
    }

    private fun longOf(usage: kotlinx.serialization.json.JsonObject?, key: String): Long =
        (usage?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    private fun chatBody(system: String, user: String): String =
        kotlinx.serialization.json.buildJsonObject {
            put("model", kotlinx.serialization.json.JsonPrimitive(config.model))
            put(
                "messages",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        kotlinx.serialization.json.buildJsonObject {
                            put("role", kotlinx.serialization.json.JsonPrimitive("system"))
                            put("content", kotlinx.serialization.json.JsonPrimitive(system))
                        },
                    )
                    add(
                        kotlinx.serialization.json.buildJsonObject {
                            put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                            put("content", kotlinx.serialization.json.JsonPrimitive(user))
                        },
                    )
                },
            )
        }.toString()

    companion object {
        /** Vision 02 §2's retry law maximum (transport errors only). */
        const val MAX_RETRIES = 2
        const val DEFAULT_BACKOFF_BASE_MS = 200L

        /** The bounded content-retry budget for restructure: exactly ONE fresh attempt. */
        const val CONTENT_ATTEMPTS = 2

        /** Integer micro-dollar writer spend from provider-reported token counts (ART-5). */
        fun writerCostMicroUsd(inputTokens: Long, outputTokens: Long): Long =
            inputTokens * GenEngineConfigs.INPUT_MICRO_USD_PER_1K / 1_000 +
                outputTokens * GenEngineConfigs.OUTPUT_MICRO_USD_PER_1K / 1_000
    }
}

/** A writer-protocol content failure (never retried, never defaulted). */
class GenProtocolException(message: String) : IllegalStateException(message)

/**
 * The clean pre-navigation refusal: the goal could not be restructured into a valid
 * ladder within the bounded content-retry budget (vision 04 §1 v1.4.0: "invalid output
 * = one bounded retry, then a clean pre-navigation refusal — nothing navigates on an
 * invalid ladder").
 */
class GenRefusedException(message: String) : IllegalStateException(message)
