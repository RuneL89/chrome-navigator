package navigator.core.wire

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gate 3.2 — failover + retry law (isolation, vision 02 §2). Transport failures are
 * simulated deterministically by [CountingWireTransport] (no sockets, no timing — a real
 * timed-socket harness undercounted server-side request counts under load, see its KDoc),
 * so the asserted attempt counts are the CLIENT's true attempt counts: exactly 2 retries
 * (the law maximum) with exponential backoff, then ONE failover hop to fallback-1, tagged
 * `usedFallback=true`. A 4xx (or any HTTP status — content) does NOT retry and does NOT
 * fail over (those cases keep the real MockWebServer path: immediate responses, no timing
 * race); a malformed 200 is never answer-shopped either. The real-IOException →
 * [WireTransportException] mapping of the production OkHttp transport is covered by the
 * connection-refused test.
 */
class FailoverRetryLawTest {

    private val fixture = WireGateSupport.loadTranscript("choice-search-results.json")

    /** Label URLs for the deterministic in-memory configs (no server behind them). */
    private val primaryUrl = "http://primary.test/decisions"
    private val fallbackUrl = "http://fallback.test/decisions"

    private fun success(): WireHttpResponse = WireHttpResponse(200, fixture.responseBody.toString())

    private fun client(
        transport: CountingWireTransport,
        withFallback: Boolean = true,
        retryDelay: (Long) -> Unit = { },
    ): SystemOneClient = SystemOneClient(
        transport = transport,
        primary = EngineConfig(primaryUrl, "test-key-not-a-secret", fixture.model),
        fallback = if (withFallback) EngineConfig(fallbackUrl, "test-key-not-a-secret", fixture.model) else null,
        retryDelay = retryDelay,
    )

    @Test
    fun `gate 3_2 - primary transport exhaustion means exactly 2 retries, exponential backoff, one failover hop`() {
        val transport = CountingWireTransport { call ->
            if (call.url == primaryUrl) throw WireTransportException("simulated transport failure")
            success()
        }
        val recordedDelays = mutableListOf<Long>()
        val client = client(transport, retryDelay = { ms -> recordedDelays += ms })
        val decision = runBlocking { client.decide(fixture.state, fixture.questions) }

        assertTrue(decision.usedFallback, "failover must tag the decision usedFallback=true")
        assertEquals(fixture.questions.keys, decision.answers.keys)
        for ((qName, expected) in fixture.expectedResults) {
            WireGateSupport.assertTypedResultEquals(expected, decision.answers.getValue(qName), qName)
        }
        assertEquals(3, transport.callsTo(primaryUrl), "initial attempt + exactly 2 retries (law max)")
        assertEquals(1, transport.callsTo(fallbackUrl), "exactly one failover hop — no second hop")
        assertEquals(
            listOf(200L, 400L), recordedDelays,
            "exponential backoff with the law defaults (200ms base, 2 retries)",
        )
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
        // Deterministic: the first primary call is a transport error, the second succeeds.
        // (Previously a NO_RESPONSE + real-call-timeout harness; see CountingWireTransport
        // KDoc for why that oracle was load-flaky.)
        val transport = CountingWireTransport { call ->
            if (call.url == primaryUrl && call.callNumber == 1) {
                throw WireTransportException("simulated transport failure")
            }
            success()
        }
        val client = client(transport)
        val decision = runBlocking { client.decide(fixture.state, fixture.questions) }
        assertEquals(false, decision.usedFallback, "a retry that recovers is not a failover")
        assertEquals(2, transport.callsTo(primaryUrl), "one transport error, one retry (within the law max)")
        assertEquals(0, transport.callsTo(fallbackUrl))
    }

    @Test
    fun `gate 3_2 - transport exhaustion without a fallback configured fails loud`() {
        val transport = CountingWireTransport { throw WireTransportException("simulated transport failure") }
        val client = client(transport, withFallback = false)
        assertFailsWith<WireTransportException> {
            runBlocking { client.decide(fixture.state, fixture.questions) }
        }
        assertEquals(3, transport.callsTo(primaryUrl), "initial attempt + exactly 2 retries even without a fallback")
        assertEquals(0, transport.callsTo(fallbackUrl))
    }

    @Test
    fun `gate 3_2 - a real transport IOException maps to WireTransportException (connection refused)`() {
        // Genuine socket failure through the production OkHttp transport, with no timing
        // involved: bind a loopback port, close it, call it — connection refused on every
        // attempt, so all 3 law attempts are real IOExceptions mapped to the one error
        // class the retry law reacts to.
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
            val refusedUrl = "http://127.0.0.1:${socket.localPort}/decisions"
            val client = SystemOneClient(
                transport = WireGateSupport.testTransport(callTimeoutMs = 5_000),
                primary = EngineConfig(refusedUrl, "test-key-not-a-secret", fixture.model),
                retryDelay = { },
            )
            val e = assertFailsWith<WireTransportException> {
                runBlocking { client.decide(fixture.state, fixture.questions) }
            }
            assertTrue(
                e.message!!.contains(refusedUrl),
                "the transport error must carry the URL (never the key): ${e.message}",
            )
        }
    }
}
