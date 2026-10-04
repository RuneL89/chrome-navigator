package navigator.core.wire

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The provider-reported `usage` object is observed exactly once per successful wire call
 * (the TaskBudget accounting input, Phase 6): recorded for a primary success, recorded
 * once across a failover, and recorded NOT at all when nothing succeeded.
 */
class UsageObservationTest {

    private val fixture = WireGateSupport.loadTranscript("choice-search-results.json")

    private val primaryUrl = "http://primary.test/decisions"
    private val fallbackUrl = "http://fallback.test/decisions"

    private fun success(): WireHttpResponse = WireHttpResponse(200, fixture.responseBody.toString())

    @Test
    fun `every successful decision reports its usage exactly once`() {
        val seen = mutableListOf<Usage>()
        val client = SystemOneClient(
            transport = CountingWireTransport { success() },
            primary = EngineConfig(primaryUrl, "test-key-not-a-secret", fixture.model),
            onUsage = { seen += it },
        )
        val decision = runBlocking { client.decide(fixture.state, fixture.questions) }

        assertEquals(listOf(decision.usage), seen, "one usage observation per successful call")
        assertTrue(decision.usage.inputTokens >= 0 && decision.usage.outputTokens >= 0)
    }

    @Test
    fun `a failover decision reports the SERVING config's usage once`() {
        val seen = mutableListOf<Usage>()
        val client = SystemOneClient(
            transport = CountingWireTransport { call ->
                if (call.url == primaryUrl) throw WireTransportException("simulated transport failure")
                success()
            },
            primary = EngineConfig(primaryUrl, "test-key-not-a-secret", fixture.model),
            fallback = EngineConfig(fallbackUrl, "test-key-not-a-secret", fixture.model),
            onUsage = { seen += it },
        )
        val decision = runBlocking { client.decide(fixture.state, fixture.questions) }

        assertTrue(decision.usedFallback)
        assertEquals(
            listOf(decision.usage), seen,
            "failed primary attempts returned no body, so only the fallback's usage exists",
        )
    }

    @Test
    fun `a protocol failure reports no usage`() {
        val seen = mutableListOf<Usage>()
        val client = SystemOneClient(
            transport = CountingWireTransport { WireHttpResponse(400, """{"error":"bad request"}""") },
            primary = EngineConfig(primaryUrl, "test-key-not-a-secret", fixture.model),
            onUsage = { seen += it },
        )
        runBlocking {
            kotlin.test.assertFailsWith<WireProtocolException> {
                client.decide(fixture.state, fixture.questions)
            }
        }
        assertEquals(emptyList(), seen, "a non-2xx response carries no usage to report")
    }
}
