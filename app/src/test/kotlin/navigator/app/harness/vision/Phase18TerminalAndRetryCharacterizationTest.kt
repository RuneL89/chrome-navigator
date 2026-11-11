package navigator.app.harness.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport
import navigator.core.wire.WireTransportException

/**
 * PHASE 18 STAGE 18.A — CHARACTERIZATION (the refactor baseline; NOT a spec).
 *
 * Two pieces of the baseline that later stages consciously change, pinned so the
 * change is deliberate:
 *
 * 1. THE TERMINAL VOCABULARY — the exact eight vision terminals, in declaration
 *    order (the corner log's task-state line renders these names). Stage 18.D CN-15
 *    ADDS terminals additively (doc 04 §9.9: CLARIFICATION_REQUIRED, NEEDS_USER,
 *    MODEL_UNAVAILABLE, CAPTURE_UNAVAILABLE, STALE_CONTEXT_UNRESOLVED, GOAL_BLOCKED);
 *    the Phase 18 change gate (doc 07 §4) treats the vocabulary as load-bearing.
 *    The END-TO-END terminal behavior is already pinned by the existing suites and is
 *    referenced, not duplicated here: GOAL_ACHIEVED / MODELS_CONFIRM_DONE /
 *    REPLANS_EXHAUSTED / CANCELLED (VisionTerminalEvidenceTest,
 *    VisionLoopEscapeHatchTest, VisionLoopPrefixRollbackTest).
 *
 * 2. THE WRITER/JEV RETRY LAW — transport-only retries, capped; any HTTP status and
 *    any malformed 200 are loud content failures, never retried. [GenClientTest]
 *    pins the SAME law for the a11y writer (GenClient: "transport errors retry at
 *    most twice"; "any HTTP status is content — never retried"; "a malformed 200 is
 *    loud content failure with no retry"); this suite pins the vision clients
 *    ([VisionHaiku], [VisionJev]), which are separate implementations with their own
 *    100x budget-directive cap (maxRetries = 20 in production). Stage 18.B CN-08
 *    adds the finite total deadline, status-selective retry (408/429/5xx) and
 *    coroutine-cancellable transport ON TOP of this law.
 */
class Phase18TerminalAndRetryCharacterizationTest {

    // -- the terminal vocabulary -------------------------------------------------------------

    @Test
    fun `the vision terminal vocabulary is exactly the eight current terminals`() {
        assertEquals(
            listOf(
                "GOAL_ACHIEVED",
                "MODELS_CONFIRM_DONE",
                "STEPS_EXHAUSTED",
                "BUDGET_EXHAUSTED",
                "REPLANS_EXHAUSTED",
                "CANCELLED",
                "PLAN_REFUSED",
                "GUARD_ABORT",
            ),
            VisionLoop.Terminal.entries.map { it.name },
            "the vocabulary is load-bearing (doc 07 §4's change gate); a change is deliberate",
        )
    }

    // -- the retry law: the scripted edges -----------------------------------------------------

    /** A transport whose first [failures] calls throw, then it returns [body]. */
    private class FlakyTransport(
        private val failures: Int,
        private val body: String,
    ) : WireTransport {
        var attempts = 0
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            attempts++
            return if (attempts <= failures) throw WireTransportException("fixture transport flap")
            else WireHttpResponse(200, body)
        }
    }

    private class CountingTransport(private val body: String, private val code: Int = 200) : WireTransport {
        var attempts = 0
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            attempts++
            return WireHttpResponse(code, body)
        }
    }

    private fun jevNoulBody(): String = buildJsonObject {
        put("answers", buildJsonObject {
            put("done", buildJsonObject {
                put("type", JsonPrimitive("noul"))
                put("noul", JsonPrimitive(0.9))
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    private fun chatBody(): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive("""{"ok":true}""")) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5) })
    }.toString()

    private suspend fun jevAsk(transport: WireTransport, maxRetries: Int, onDelay: () -> Unit = {}): VisionJev.Answer {
        val jev = VisionJev(
            transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
            backoffBaseMs = 1, retryDelay = { _ -> onDelay() }, maxRetries = maxRetries,
        )
        return jev.ask(
            state = "fixture state",
            questions = listOf(VisionJev.Question.Noul("done", "the outcome is visible")),
            imageBase64 = null,
            imageRef = VisionJev.newImageRef(),
            what = "fixture",
        ).getValue("done")
    }

    private suspend fun haikuChat(transport: WireTransport, maxRetries: Int): String {
        val haiku = VisionHaiku(
            transport, "fixture-key", backoffBaseMs = 1, retryDelay = { _ -> },
            maxRetries = maxRetries,
        )
        return haiku.chat(system = "fixture system", userText = "fixture user", imageBase64 = null, what = "fixture")
    }

    // -- VisionJev: transport-only retries, capped; statuses and malformed 200s never retried --

    @Test
    fun `jev - transport failures retry up to the cap and then succeed`() = runBlocking {
        val transport = FlakyTransport(failures = 2, body = jevNoulBody())
        var delays = 0
        val answer = jevAsk(transport, maxRetries = 5, onDelay = { delays++ })
        assertEquals(0.9, (answer as VisionJev.Answer.Noul).pTrue)
        assertEquals(3, transport.attempts, "two transport flaps, the third call answers")
        assertEquals(2, delays, "every retry delays once")
    }

    @Test
    fun `jev - an HTTP status is a loud content failure and is never retried`() = runBlocking {
        val transport = CountingTransport(body = "server said no", code = 503)
        try {
            jevAsk(transport, maxRetries = 5)
            error("the 503 must throw")
        } catch (e: VisionJev.VisionJevException) {
            assertTrue("HTTP 503" in (e.message ?: ""), e.message ?: "")
            assertTrue("not retried" in (e.message ?: ""), e.message ?: "")
        }
        assertEquals(1, transport.attempts, "a status answer is never retried")
    }

    @Test
    fun `jev - a malformed 200 is a loud content failure and is never retried`() = runBlocking {
        val transport = CountingTransport(body = "not json at all")
        try {
            jevAsk(transport, maxRetries = 5)
            error("the malformed 200 must throw")
        } catch (e: Exception) {
            assertTrue(e !is WireTransportException, "the failure is content, not transport")
        }
        assertEquals(1, transport.attempts, "a malformed 200 is never retried")
    }

    // -- VisionHaiku: the same law --------------------------------------------------------------

    @Test
    fun `haiku - transport failures retry up to the cap and then succeed`() = runBlocking {
        val transport = FlakyTransport(failures = 1, body = chatBody())
        val content = haikuChat(transport, maxRetries = 5)
        assertTrue("ok" in content, "the retried call answers: $content")
        assertEquals(2, transport.attempts, "one transport flap, the second call answers")
    }

    @Test
    fun `haiku - an HTTP status is a loud content failure and is never retried`() = runBlocking {
        val transport = CountingTransport(body = "no", code = 429)
        try {
            haikuChat(transport, maxRetries = 5)
            error("the 429 must throw")
        } catch (e: VisionHaiku.VisionHaikuException) {
            assertTrue("HTTP 429" in (e.message ?: ""), e.message ?: "")
            assertTrue("not retried" in (e.message ?: ""), e.message ?: "")
        }
        assertEquals(1, transport.attempts, "a status answer is never retried")
    }

    @Test
    fun `haiku - a malformed 200 is a loud content failure and is never retried`() = runBlocking {
        val transport = CountingTransport(body = "{\"choices\": broken")
        try {
            haikuChat(transport, maxRetries = 5)
            error("the malformed 200 must throw")
        } catch (e: Exception) {
            assertTrue(e !is WireTransportException, "the failure is content, not transport")
        }
        assertEquals(1, transport.attempts, "a malformed 200 is never retried")
    }
}
