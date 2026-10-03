package navigator.core.wire

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import navigator.core.decision.Band
import navigator.core.decision.NoulResult
import navigator.core.decision.band
import navigator.core.fixtures.ProviderWireTranscript
import okhttp3.mockwebserver.MockWebServer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate 3.3 — name-indexing + banding (isolation). A recorded response whose probability
 * keys and answers are reordered resolves identically (parse by NAME, never position —
 * vision 02 §2), and band boundaries sit at 0.30/0.70 with both ends inclusive-UNCERTAIN.
 */
class NameIndexingBandingTest {

    @Test
    fun `gate 3_3 - the reordered fixture resolves identically to its twin`() {
        val base = WireGateSupport.loadTranscript("choice-search-results.json")
        val reordered = WireGateSupport.loadTranscript("choice-search-results-reordered.json")
        assertEquals(
            base.expectedResults, reordered.expectedResults,
            "the reorder twin must record identical expected results",
        )
        MockWebServer().use { server ->
            server.start()
            server.enqueue(WireGateSupport.successResponse(base.responseBody))
            server.enqueue(WireGateSupport.successResponse(reordered.responseBody))
            val client = SystemOneClient(
                WireGateSupport.testTransport(),
                WireGateSupport.config(server, base.model),
            )
            val first = runBlocking { client.decide(base.state, base.questions) }
            val second = runBlocking { client.decide(reordered.state, reordered.questions) }
            for ((qName, expected) in base.expectedResults) {
                WireGateSupport.assertTypedResultEquals(expected, first.answers.getValue(qName), "base/$qName")
                WireGateSupport.assertTypedResultEquals(expected, second.answers.getValue(qName), "reordered/$qName")
            }
        }
    }

    @Test
    fun `gate 3_3 - shuffled response key orders resolve identically (multi-question, 5 seeds)`() {
        val fixture = WireGateSupport.loadTranscript("combined-multi-question.json")
        MockWebServer().use { server ->
            server.start()
            // Baseline first, then five differently-shuffled copies of the same response.
            server.enqueue(WireGateSupport.successResponse(fixture.responseBody))
            repeat(5) { seed ->
                server.enqueue(
                    WireGateSupport.successResponse(
                        WireGateSupport.deepShuffledKeys(fixture.responseBody, Random(seed)) as JsonObject,
                    ),
                )
            }
            val client = SystemOneClient(
                WireGateSupport.testTransport(),
                WireGateSupport.config(server, fixture.model),
            )
            val baseline = runBlocking { client.decide(fixture.state, fixture.questions) }
            repeat(5) { seed ->
                val variant = runBlocking { client.decide(fixture.state, fixture.questions) }
                assertEquals(
                    baseline.answers, variant.answers,
                    "shuffle seed $seed must resolve identically (name-keyed, never positional)",
                )
            }
            for ((qName, expected) in fixture.expectedResults) {
                WireGateSupport.assertTypedResultEquals(expected, baseline.answers.getValue(qName), qName)
            }
        }
    }

    @Test
    fun `gate 3_3 - band boundaries at 0_30 and 0_70 are inclusive UNCERTAIN (existing Bands)`() {
        assertEquals(Band.LOW, band(0.0))
        assertEquals(Band.LOW, band(0.07))
        assertEquals(Band.LOW, band(0.29999))
        assertEquals(Band.UNCERTAIN, band(0.30), "0.30 is the inclusive lower edge")
        assertEquals(Band.UNCERTAIN, band(0.50))
        assertEquals(Band.UNCERTAIN, band(0.70), "0.70 is the inclusive upper edge")
        assertEquals(Band.HIGH, band(0.70001))
        assertEquals(Band.HIGH, band(0.93))
        assertEquals(Band.HIGH, band(1.0))
    }

    @Test
    fun `gate 3_3 - parsed fixture values land in the law-predicted bands`() {
        // Bind the wire gate to the banding law through real parses: probabilities vary
        // call-to-call, so downstream code branches on bands — never exact values.
        assertParsedBands(
            WireGateSupport.loadTranscript("noul-goal-achieved.json"),
            expectedBands = mapOf("goal-achieved" to Band.HIGH),
        )
        assertParsedBands(
            WireGateSupport.loadTranscript("noul-goal-uncertain.json"),
            expectedBands = mapOf("goal-achieved" to Band.UNCERTAIN), // uncertain noul = "don't know"
        )
        assertParsedBands(
            WireGateSupport.loadTranscript("noul-goal-far.json"),
            expectedBands = mapOf("goal-achieved" to Band.LOW),
        )
    }

    private fun assertParsedBands(
        fixture: ProviderWireTranscript,
        expectedBands: Map<String, Band>,
    ) {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(WireGateSupport.successResponse(fixture.responseBody))
            val client = SystemOneClient(
                WireGateSupport.testTransport(),
                WireGateSupport.config(server, fixture.model),
            )
            val decision = runBlocking { client.decide(fixture.state, fixture.questions) }
            for ((name, expectedBand) in expectedBands) {
                val pTrue = (decision.answers.getValue(name) as NoulResult).pTrue
                assertEquals(expectedBand, band(pTrue), "$name: pTrue=$pTrue must band as the law predicts")
            }
        }
        assertTrue(expectedBands.isNotEmpty())
    }
}
