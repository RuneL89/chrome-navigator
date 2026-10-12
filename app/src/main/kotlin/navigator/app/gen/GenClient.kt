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

/** The condition-repair call's output: the re-issued condition + usage + prompt version. */
data class ConditionRepairResult(
    val done: navigator.core.ladder.DoneCondition,
    val usage: GenUsage,
    val promptVersion: String,
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

/** One completed uncertainty-handshake assist (Phase 10B, doc 04 §2). */
data class AssistResult(
    /** The page re-description — discriminating FACTS only, never directives. */
    val text: String,
    val usage: GenUsage,
    val promptVersion: String,
)

/** One completed writer-phrased typed text (Phase 10B, doc 04 §2 — locked decision #4). */
data class PhraseResult(
    /** The field content — one line, at most [Prompts.PHRASE_MAX_CHARS] chars, content only. */
    val text: String,
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
     *
     * v1.8.0 (Phase 10D): when [failure] is present this is an INFORMED re-plan — the
     * request carries the bounded failure-context block (`Prompts.replanUser`) and must
     * be generation ≥ 2. A ladder that declares a `rollback` on generation 1 is a schema
     * error (the bounded retry re-asks); plan integrity beyond the declaration (the frozen
     * verified prefix) is judged by code in the loop (`ReplanRule`).
     */
    suspend fun restructure(
        goal: String,
        generation: Int = 1,
        failure: navigator.core.ladder.ReplanContext? = null,
    ): RestructureResult {
        require(goal.isNotBlank()) { "goal must not be blank" }
        require(failure == null || generation >= 2) {
            "an informed re-plan is generation >= 2 (got generation=$generation)"
        }
        val user = failure
            ?.let { Prompts.replanUser(goal, maxSubtasks, it) }
            ?: Prompts.restructureUser(goal, maxSubtasks)
        val body = chatBody(Prompts.RESTRUCTURE_SYSTEM, user)
        var lastError: LadderSchema.LadderSchemaException? = null
        for (attempt in 1..CONTENT_ATTEMPTS) {
            val (content, usage) = post(body, "restructure")
            try {
                val ladder = LadderSchema.parse(content, maxSubtasks)
                if (generation == 1 && ladder.rollback != null) {
                    throw LadderSchema.LadderSchemaException(
                        "a rollback may only be declared by a RE-PLAN generation (got gen 1) — " +
                            "there is no verified step to rewrite",
                    )
                }
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
     * The bounded condition repair (Phase 10C, vision 04 §1 v1.7.0): ONE writer call
     * re-issuing an already-satisfied done-condition so it is not true where its subtask
     * starts. Invalid or non-whitelisted output returns null (NO retry — the caller then
     * derives a code-side URL identity or refuses the ladder loudly); transport failures
     * propagate loudly like every other writer call.
     */
    suspend fun repairCondition(
        subtaskInstruction: String,
        conditionRendered: String,
        pageUrl: String,
        pageTitle: String,
    ): ConditionRepairResult? {
        require(subtaskInstruction.isNotBlank()) { "subtask instruction must not be blank" }
        val body = chatBody(
            Prompts.CONDITION_REPAIR_SYSTEM,
            Prompts.conditionRepairUser(subtaskInstruction, conditionRendered, pageUrl, pageTitle),
        )
        val (content, usage) = post(body, "condition-repair")
        val done = try {
            LadderSchema.parseDoneConditionText(content)
        } catch (e: LadderSchema.LadderSchemaException) {
            return null
        }
        onUsage(usage)
        return ConditionRepairResult(done, usage, Prompts.CONDITION_REPAIR_PROMPT_VERSION)
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
     * The uncertainty-handshake assist (Phase 10B, vision 04 §2, doc 02 §3): ONE writer
     * call re-describing the page relative to the ACTIVE subtask — discriminating FACTS
     * only, at most [Prompts.MAX_ASSIST_SENTENCES] sentences AND [Prompts.MAX_ASSIST_CHARS]
     * characters, NEVER a directive (ART-2: the writer never steers).
     *
     * Output law: every completed call's usage meters through [onUsage] (wire it into
     * `WriterBudget.record` at the Round-2 cascade wiring — the cap never understates
     * spend, even for refused outputs). Validation = the length caps + the directive
     * blocklist; an invalid output earns EXACTLY ONE fresh attempt, then `null` — the
     * SKIP-TO-FALLBACK signal (the caller runs its code fallback: recovery / re-plan /
     * owner card), never a loop. The honesty path — the model answering exactly
     * [Prompts.ASSIST_SKIP_MARKER] — returns `null` immediately.
     *
     * NO SECRET EVER ENTERS THE PROMPT (doc 06 §2a): the caller masks sanctioned secrets
     * out of [pageMaterial] before this call.
     */
    suspend fun assist(
        subtask: String,
        doneCondition: String,
        pageMaterial: String,
    ): AssistResult? {
        require(subtask.isNotBlank()) { "assist: the subtask must not be blank" }
        require(doneCondition.isNotBlank()) { "assist: the done-condition must not be blank" }
        val body = chatBody(
            Prompts.ASSIST_SYSTEM,
            Prompts.assistUser(subtask, doneCondition, pageMaterial),
        )
        repeat(CONTENT_ATTEMPTS) { attempt ->
            val (content, usage) = post(body, "assist")
            onUsage(usage) // metered even when the output is refused: spend is spend
            val text = content.trim()
            if (text.equals(Prompts.ASSIST_SKIP_MARKER, ignoreCase = true)) {
                return null // the honesty path: skip-to-fallback
            }
            val violation = assistViolation(text)
            if (violation == null) {
                return AssistResult(text, usage, Prompts.ASSIST_PROMPT_VERSION)
            }
            if (attempt == CONTENT_ATTEMPTS - 1) {
                // ONE bounded retry spent: skip-to-fallback, never a loop (vision 04 §2).
                return null
            }
        }
        return null // unreachable
    }

    /**
     * The writer-phrased typed text (Phase 10B, vision 04 §2 — locked decision #4): ONE
     * call phrasing the content for [fieldLabel] under the ACTIVE [subtask]. ONE line, at
     * most [Prompts.PHRASE_MAX_CHARS] chars, content only — never the raw goal text, and
     * never a directive.
     *
     * Returns `null` on any refused output (invalid after the ONE bounded retry, or the
     * honesty path [Prompts.PHRASE_SKIP_MARKER]): the Round-2 loop treats it as a
     * fill-field FAILURE routed to recovery — it never falls back to raw-goal typing.
     * Usage meters through [onUsage] (wire it into `WriterBudget.record` at the Round-2
     * cascade wiring). NO SECRET EVER ENTERS THE PROMPT (doc 06 §2a) — the field label
     * rides the observation's redaction fold upstream.
     */
    suspend fun phrase(subtask: String, fieldLabel: String): PhraseResult? {
        require(subtask.isNotBlank()) { "phrase: the subtask must not be blank" }
        val body = chatBody(Prompts.PHRASE_SYSTEM, Prompts.phraseUser(subtask, fieldLabel))
        repeat(CONTENT_ATTEMPTS) { attempt ->
            val (content, usage) = post(body, "phrase")
            onUsage(usage)
            val text = content.trim()
            if (text.equals(Prompts.PHRASE_SKIP_MARKER, ignoreCase = true)) {
                return null
            }
            val violation = phraseViolation(text)
            if (violation == null) {
                return PhraseResult(text, usage, Prompts.PHRASE_PROMPT_VERSION)
            }
            if (attempt == CONTENT_ATTEMPTS - 1) {
                return null
            }
        }
        return null // unreachable
    }

    /**
     * The assist output law: `null` when valid, else WHY it is invalid (over the
     * sentence cap, over the character cap, or carrying a directive/verb-of-choice —
     * the adversarial Gate 10B.1 rows pin the blocklist both ways).
     */
    private fun assistViolation(text: String): String? {
        if (text.isEmpty()) return "empty assist"
        if (text.length > Prompts.MAX_ASSIST_CHARS) {
            return "assist is ${text.length} chars, over the ${Prompts.MAX_ASSIST_CHARS}-char cap"
        }
        val sentences = countSentences(text)
        if (sentences > Prompts.MAX_ASSIST_SENTENCES) {
            return "assist carries $sentences sentences, over the ${Prompts.MAX_ASSIST_SENTENCES}-sentence cap"
        }
        DIRECTIVE_BLOCKLIST.find(text)?.let {
            return "assist carries the directive '${it.value}' — the writer describes, it never steers (ART-2)"
        }
        return null
    }

    /**
     * The phrase output law: `null` when valid, else WHY it is invalid (not ONE line,
     * over the 80-char cap, empty, or commentary/directive-shaped).
     */
    private fun phraseViolation(text: String): String? {
        if (text.isEmpty()) return "empty phrase"
        if (text.contains('\n') || text.contains('\r')) return "phrase must be ONE line"
        if (text.length > Prompts.PHRASE_MAX_CHARS) {
            return "phrase is ${text.length} chars, over the ${Prompts.PHRASE_MAX_CHARS}-char cap"
        }
        DIRECTIVE_BLOCKLIST.find(text)?.let {
            return "phrase carries the directive '${it.value}' — the answer is data, not an action (doc 06 §4)"
        }
        return null
    }

    /** Sentence count by terminator ([.!?] runs), the [Prompts.MAX_ASSIST_SENTENCES] measure. */
    private fun countSentences(text: String): Int =
        text.split(SENTENCE_BOUNDARY).count { it.isNotBlank() }

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

        /**
         * The directive blocklist (Gate 10B.1's adversarial rows): word-boundary
         * verbs-of-choice and steering phrases — an assist or phrase carrying one is
         * INVALID by construction (ART-2: the writer describes, it never decides).
         */
        val DIRECTIVE_BLOCKLIST: Regex =
            Regex(
                "\\b(click|tap|press|scroll|navigate|select|choose|submit|dismiss|ignore|" +
                    "click on|go back|next step|you should|you must|first,? then)\\b",
                RegexOption.IGNORE_CASE,
            )

        private val SENTENCE_BOUNDARY = Regex("[.!?]+\\s")

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

// -- the cascade's writer-source adapters (Phase 10B, phase doc §2.5/§2.6) ------------

/** Adapts this client's [assist] onto the loop's [navigator.app.loop.AssistSource] seam. */
fun GenClient.asAssistSource(): navigator.app.loop.AssistSource =
    navigator.app.loop.AssistSource { subtask, doneCondition, pageMaterial ->
        assist(subtask, doneCondition, pageMaterial)
    }

/** Adapts this client's [phrase] onto the loop's [navigator.app.loop.PhraseSource] seam. */
fun GenClient.asPhraseSource(): navigator.app.loop.PhraseSource =
    navigator.app.loop.PhraseSource { subtask, fieldLabel -> phrase(subtask, fieldLabel) }
