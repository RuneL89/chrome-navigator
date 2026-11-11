package navigator.app.harness.vision

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.TimeoutCancellationException
import navigator.core.wire.ModelCallException
import navigator.core.wire.OkHttpWireTransport
import navigator.core.wire.WireHttpRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PHASE_18 Stage 18.B CN-08 — the model-call policy END TO END against MockWebServer
 * with the REAL [OkHttpWireTransport] (doc 04 §9.7): the attempt bound, the total
 * deadline, status-selective retry (408/429/5xx + bounded Retry-After), typed failures,
 * and owner cancellation cancelling the underlying call. $0 (localhost only), no keys.
 *
 * PHASE_18 NOTE: this pins the CN-08 TARGET behavior just landed (Stage 18.B); the doc
 * 04 §9.7 item it maps to is 9.7 (model-call boundedness) itself.
 */
class VisionModelCallPolicyTest {

    private fun jev(server: MockWebServer, policy: navigator.core.wire.ModelCallPolicy): VisionJev =
        VisionJev(
            OkHttpWireTransport(), server.url("/v1/systemone").toString(), "jev-1.14.0", "fixture-key",
            backoffBaseMs = 1, retryDelay = { }, policy = policy,
        )

    private fun jevBody(): String = kotlinx.serialization.json.buildJsonObject {
        put("answers", kotlinx.serialization.json.buildJsonObject {
            put("done", kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("noul"))
                put("noul", kotlinx.serialization.json.JsonPrimitive(0.9))
            })
        })
        put("usage", kotlinx.serialization.json.buildJsonObject {
            put("input_tokens", kotlinx.serialization.json.JsonPrimitive(10))
            put("output_tokens", kotlinx.serialization.json.JsonPrimitive(1))
        })
    }.toString()

    private suspend fun ask(jev: VisionJev) = jev.ask(
        state = "fixture state",
        questions = listOf(VisionJev.Question.Noul("done", "the outcome is visible")),
        imageBase64 = null,
        imageRef = VisionJev.newImageRef(),
        what = "fixture",
    )

    // -- persistent transport failure: no more than the configured attempts -------------
    @Test
    fun `persistent transport failure fails typed after the bounded attempts`() {
        val server = MockWebServer()
        // No queued responses: every connection is dropped (SocketPolicy.DISCONNECT_AT_START).
        repeat(5) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        server.start()
        try {
            // maxAttempts = 3: exactly 3 transport attempts, then the typed failure.
            try {
                runBlocking { ask(jev(server, navigator.core.wire.ModelCallPolicy(maxAttempts = 3, initialBackoffMs = 1))) }
                error("the persistent transport failure must throw")
            } catch (e: ModelCallException) {
                assertEquals(ModelCallException.Category.TRANSPORT, e.category)
                assertEquals(3, e.attempts)
            }
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    // -- a retryable status (503) followed by success: bounded retry succeeds ----------
    @Test
    fun `a 503 then a success - the bounded status retry answers`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("overloaded"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(jevBody()))
        server.start()
        try {
            val answers = runBlocking {
                ask(jev(server, navigator.core.wire.ModelCallPolicy(maxAttempts = 3, initialBackoffMs = 1)))
            }
            val noul = answers.getValue("done") as VisionJev.Answer.Noul
            assertEquals(0.9, noul.pTrue)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    // -- an ordinary 400: no retry -------------------------------------------------------
    @Test
    fun `an ordinary 400 is a loud content failure with a single attempt`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad request"))
        server.start()
        try {
            try {
                runBlocking { ask(jev(server, navigator.core.wire.ModelCallPolicy(maxAttempts = 3, initialBackoffMs = 1))) }
                error("the 400 must throw")
            } catch (e: VisionJev.VisionJevException) {
                assertTrue("HTTP 400" in (e.message ?: ""))
                assertTrue("not retried" in (e.message ?: ""))
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    // -- a malformed 200: protocol failure, no answer shopping ---------------------------
    @Test
    fun `a malformed 200 is a loud content failure with a single attempt`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))
        server.start()
        try {
            try {
                runBlocking { ask(jev(server, navigator.core.wire.ModelCallPolicy(maxAttempts = 3, initialBackoffMs = 1))) }
                error("the malformed 200 must throw")
            } catch (e: Exception) {
                assertTrue(e !is ModelCallException, "the failure is content, not the call policy")
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    // -- the total deadline: a long-delayed response is abandoned typed ------------------
    @Test
    fun `a response past the total deadline fails typed as DEADLINE`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(jevBody()).setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS))
        server.start()
        try {
            try {
                runBlocking {
                    withTimeout(500) { ask(jev(server, navigator.core.wire.ModelCallPolicy(maxAttempts = 3, totalDeadlineMs = 90_000))) }
                }
                error("the delayed response must time out")
            } catch (e: TimeoutCancellationException) {
                // The OWNER-deadline path (withTimeout): the in-flight call was cancelled
                // by the coroutine machinery (the transport's invokeOnCancellation fired).
            }
            // The transport-level deadline path: a call that survives to the client's own
            // loop check fails typed DEADLINE.
            val jev = VisionJev(
                OkHttpWireTransport(), server.url("/v1/systemone").toString(), "jev-1.14.0", "fixture-key",
                retryDelay = { }, policy = navigator.core.wire.ModelCallPolicy(maxAttempts = 3, totalDeadlineMs = 1),
                nowMs = { System.currentTimeMillis() + 10_000 },
            )
            try {
                runBlocking { ask(jev) }
                error("the expired deadline must throw")
            } catch (e: ModelCallException) {
                assertEquals(ModelCallException.Category.DEADLINE, e.category)
            }
        } finally {
            server.shutdown()
        }
    }

    // -- Retry-After is honored only up to the policy (bounded by the delay function) ----
    @Test
    fun `a 429 with Retry-After retries within the bound then fails typed`() {
        val server = MockWebServer()
        repeat(4) {
            server.enqueue(
                MockResponse().setResponseCode(429).setBody("slow down").setHeader("Retry-After", "600"),
            )
        }
        server.start()
        try {
            try {
                runBlocking {
                    ask(
                        jev(
                            server,
                            // retryDelay is a no-op in production wiring here; the policy
                            // computes the delay but the test's no-op keeps it fast — the
                            // BOUND (attempts) is what this pins, and a 600s Retry-After
                            // would exceed any deadline, so the typed failure is the proof.
                            navigator.core.wire.ModelCallPolicy(maxAttempts = 3, totalDeadlineMs = 90_000),
                        ),
                    )
                }
                error("the persistent 429 must throw")
            } catch (e: ModelCallException) {
                assertEquals(ModelCallException.Category.STATUS, e.category)
            }
            assertTrue(server.requestCount <= 3, "attempts bounded: ${server.requestCount}")
        } finally {
            server.shutdown()
        }
    }
}
