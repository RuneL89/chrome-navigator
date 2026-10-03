package navigator.core.wire

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gate 3.2 — failover + retry law (isolation, vision 02 §2). The primary times out on
 * every attempt: exactly 2 retries (the law maximum) with exponential backoff, then ONE
 * failover hop to fallback-1, decision tagged `usedFallback=true`. A 4xx (or any HTTP
 * status — content) does NOT retry and does NOT fail over; a malformed 200 is never
 * answer-shopped either.
 */
class FailoverRetryLawTest {

    private val fixture = WireGateSupport.loadTranscript("choice-search-results.json")

    private fun hangingServer(): MockWebServer = MockWebServer().also { server ->
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }
    }

    @Test
    fun `gate 3_2 - primary transport exhaustion means exactly 2 retries, exponential backoff, one failover hop`() {
        val primary = hangingServer()
        val fallback = MockWebServer().also { it.start() }
        fallback.enqueue(WireGateSupport.successResponse(fixture.responseBody))
        val recordedDelays = mutableListOf<Long>()

        try {
            val client = SystemOneClient(
                transport = WireGateSupport.testTransport(callTimeoutMs = 250),
                primary = WireGateSupport.config(primary, fixture.model),
                fallback = WireGateSupport.config(fallback, fixture.model),
                // Record (don't sleep) the law's backoff schedule: defaults are in force.
                retryDelay = { ms -> recordedDelays += ms },
            )
            val decision = runBlocking { client.decide(fixture.state, fixture.questions) }

            assertTrue(decision.usedFallback, "failover must tag the decision usedFallback=true")
            assertEquals(fixture.questions.keys, decision.answers.keys)
            for ((qName, expected) in fixture.expectedResults) {
                WireGateSupport.assertTypedResultEquals(expected, decision.answers.getValue(qName), qName)
            }
            assertEquals(3, primary.requestCount, "initial attempt + exactly 2 retries (law max)")
            assertEquals(1, fallback.requestCount, "exactly one failover hop — no second hop")
            assertEquals(
                listOf(200L, 400L), recordedDelays,
                "exponential backoff with the law defaults (200ms base, 2 retries)",
            )
        } finally {
            primary.shutdown()
            fallback.shutdown()
        }
    }

    @Test
    fun `gate 3_2 - a 4xx response does not retry and does not fail over`() {
        val primary = MockWebServer().also { it.start() }
        primary.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(400).setBody("""{"error":{"message":"unknown question type"}}""")
        }
        val fallback = MockWebServer().also { it.start() }
        fallback.enqueue(WireGateSupport.successResponse(fixture.responseBody))

        try {
            val client = SystemOneClient(
                transport = WireGateSupport.testTransport(),
                primary = WireGateSupport.config(primary, fixture.model),
                fallback = WireGateSupport.config(fallback, fixture.model),
            )
            val e = assertFailsWith<WireProtocolException> {
                runBlocking { client.decide(fixture.state, fixture.questions) }
            }
            assertTrue(e.message!!.contains("400"), "error must carry the status: ${e.message}")
            assertEquals(1, primary.requestCount, "a 4xx is a content problem: never retried")
            assertEquals(0, fallback.requestCount, "a 4xx never fails over")
        } finally {
            primary.shutdown()
            fallback.shutdown()
        }
    }

    @Test
    fun `gate 3_2 - a 5xx response does not retry and does not fail over either`() {
        val primary = MockWebServer().also { it.start() }
        primary.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(500).setBody("""{"error":"internal"}""")
        }
        val fallback = MockWebServer().also { it.start() }
        fallback.enqueue(WireGateSupport.successResponse(fixture.responseBody))

        try {
            val client = SystemOneClient(
                transport = WireGateSupport.testTransport(),
                primary = WireGateSupport.config(primary, fixture.model),
                fallback = WireGateSupport.config(fallback, fixture.model),
            )
            val e = assertFailsWith<WireProtocolException> {
                runBlocking { client.decide(fixture.state, fixture.questions) }
            }
            assertTrue(e.message!!.contains("500"), "error must carry the status: ${e.message}")
            assertEquals(1, primary.requestCount, "any HTTP status is content: never retried")
            assertEquals(0, fallback.requestCount, "any HTTP status never fails over")
        } finally {
            primary.shutdown()
            fallback.shutdown()
        }
    }

    @Test
    fun `gate 3_2 - a malformed 200 is not retried and not failed over (no answer-shopping)`() {
        val primary = MockWebServer().also { it.start() }
        primary.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"nope":1}"""),
        )
        primary.enqueue(WireGateSupport.successResponse(fixture.responseBody)) // would-be retry answer
        val fallback = MockWebServer().also { it.start() }
        fallback.enqueue(WireGateSupport.successResponse(fixture.responseBody))

        try {
            val client = SystemOneClient(
                transport = WireGateSupport.testTransport(),
                primary = WireGateSupport.config(primary, fixture.model),
                fallback = WireGateSupport.config(fallback, fixture.model),
            )
            val e = assertFailsWith<WireProtocolException> {
                runBlocking { client.decide(fixture.state, fixture.questions) }
            }
            assertTrue(
                e.message!!.contains("answers"),
                "error must name the missing required field: ${e.message}",
            )
            assertEquals(1, primary.requestCount, "content problems are never retried (no answer-shopping)")
            assertEquals(0, fallback.requestCount, "content problems never fail over")
        } finally {
            primary.shutdown()
            fallback.shutdown()
        }
    }

    @Test
    fun `gate 3_2 - one transport failure that recovers stays on primary (retry, no failover tag)`() {
        // First attempt times out (NO_RESPONSE + a short client call timeout) — a REAL
        // transport error. (DISCONNECT_AT_START is NOT usable here: through a custom
        // dispatcher MockWebServer serves it as an empty 200, which is content, not
        // transport — the client rightly refuses to retry that.)
        val primary = MockWebServer().also { it.start() }
        primary.dispatcher = object : Dispatcher() {
            private var calls = 0
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += 1
                return if (calls == 1) {
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                } else {
                    WireGateSupport.successResponse(fixture.responseBody)
                }
            }
        }
        val fallback = MockWebServer().also { it.start() }
        fallback.enqueue(WireGateSupport.successResponse(fixture.responseBody))

        try {
            val client = SystemOneClient(
                transport = WireGateSupport.testTransport(callTimeoutMs = 250),
                primary = WireGateSupport.config(primary, fixture.model),
                fallback = WireGateSupport.config(fallback, fixture.model),
                retryDelay = { },
            )
            val decision = runBlocking { client.decide(fixture.state, fixture.questions) }
            assertEquals(false, decision.usedFallback, "a retry that recovers is not a failover")
            assertEquals(2, primary.requestCount, "one transport error, one retry (within the law max)")
            assertEquals(0, fallback.requestCount)
        } finally {
            primary.shutdown()
            fallback.shutdown()
        }
    }

    @Test
    fun `gate 3_2 - transport exhaustion without a fallback configured fails loud`() {
        val primary = hangingServer()
        try {
            val client = SystemOneClient(
                transport = WireGateSupport.testTransport(callTimeoutMs = 250),
                primary = WireGateSupport.config(primary, fixture.model),
                retryDelay = { },
            )
            assertFailsWith<WireTransportException> {
                runBlocking { client.decide(fixture.state, fixture.questions) }
            }
            assertEquals(3, primary.requestCount, "initial attempt + exactly 2 retries even without a fallback")
        } finally {
            primary.shutdown()
        }
    }
}
