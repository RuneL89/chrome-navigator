package navigator.core.decision

import navigator.core.wire.CountingWireTransport
import navigator.core.wire.EngineConfig
import navigator.core.wire.SystemOneClient
import navigator.core.wire.WireGateSupport
import navigator.core.wire.WireGateSupport.assertTypedResultEquals
import navigator.core.wire.WireGateSupport.config
import navigator.core.wire.WireGateSupport.loadTranscript
import navigator.core.wire.WireGateSupport.successResponse
import navigator.core.wire.WireGateSupport.testTransport
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransportException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JevEngine — the DecisionEngine implementation as a pure config of SystemOneClient
 * (vision 02 §2): typed mapping, failover tag, and the ART-5 score numeric mapping
 * computed in code (never taken from the model).
 */
class JevEngineTest {

    @Test
    fun `implements the DecisionEngine gateway (ART-2)`() {
        MockWebServer().use { server ->
            server.start()
            val engine = JevEngine(SystemOneClient(testTransport(), config(server, "typesafe/jev-1.13-20260917")))
            assertTrue(engine is DecisionEngine, "JevEngine must implement DecisionEngine")
        }
    }

    @Test
    fun `choose, judge and score map provider answers to typed results`() {
        val choiceFixture = loadTranscript("choice-cookie-consent.json")
        val noulFixture = loadTranscript("noul-goal-achieved.json")
        val scoreFixture = loadTranscript("score-quality-2.json")
        MockWebServer().use { server ->
            server.start()
            listOf(choiceFixture, noulFixture, scoreFixture).forEach {
                server.enqueue(successResponse(it.responseBody))
            }
            val engine = JevEngine(SystemOneClient(testTransport(), config(server, "typesafe/jev-1.13-20260917")))

            val choice = runBlocking {
                engine.choose(choiceFixture.state, choiceFixture.questions.getValue("consent-choice") as ChoiceQuestion)
            }
            assertTypedResultEquals(choiceFixture.expectedResults.getValue("consent-choice"), choice, "choose")

            val noul = runBlocking {
                engine.judge(noulFixture.state, noulFixture.questions.getValue("goal-achieved") as NoulQuestion)
            }
            assertTypedResultEquals(noulFixture.expectedResults.getValue("goal-achieved"), noul, "judge")

            val score = runBlocking {
                engine.score(scoreFixture.state, scoreFixture.questions.getValue("page-quality") as ScoreQuestion)
            }
            assertTypedResultEquals(scoreFixture.expectedResults.getValue("page-quality"), score, "score")
        }
    }

    @Test
    fun `failover surfaces usedFallback=true through EngineResult`() {
        // Deterministic in-memory transport (CountingWireTransport): transport failures
        // without sockets or timing. The earlier NO_RESPONSE + real-call-timeout harness
        // was load-flaky — a cancelled mid-backlog attempt was never dispatched, so the
        // SERVER-side requestCount undercounted the client's 3 attempts (see its KDoc).
        val fixture = loadTranscript("choice-search-results.json")
        val primaryUrl = "http://primary.test/decisions"
        val fallbackUrl = "http://fallback.test/decisions"
        val transport = CountingWireTransport { call ->
            if (call.url == primaryUrl) throw WireTransportException("simulated transport failure")
            WireHttpResponse(200, fixture.responseBody.toString())
        }
        val engine = JevEngine(
            SystemOneClient(
                transport = transport,
                primary = EngineConfig(primaryUrl, "test-key-not-a-secret", fixture.model),
                fallback = EngineConfig(fallbackUrl, "test-key-not-a-secret", fixture.model),
            ),
        )
        val outcome = runBlocking {
            engine.chooseResult(fixture.state, fixture.questions.getValue("next-action") as ChoiceQuestion)
        }
        assertTrue(outcome.usedFallback, "the failover tag must surface through EngineResult")
        assertTypedResultEquals(fixture.expectedResults.getValue("next-action"), outcome.result, "result")
        assertEquals(3, transport.callsTo(primaryUrl), "exactly 2 retries before the failover hop")
        assertEquals(1, transport.callsTo(fallbackUrl))
    }

    @Test
    fun `scoreExpectedLevel computes the ART-5 mapping in code`() {
        assertEquals(1.75, scoreExpectedLevel(mapOf(1 to 0.25, 2 to 0.75)))
        assertEquals(1.0, scoreExpectedLevel(mapOf(1 to 1.0)))
        assertEquals(2.3, scoreExpectedLevel(mapOf(1 to 0.2, 2 to 0.3, 3 to 0.5)), 1e-9)
        assertEquals(3.4, scoreExpectedLevel(mapOf(1 to 0.05, 2 to 0.15, 3 to 0.3, 4 to 0.35, 5 to 0.15)), 1e-9)
        assertFailsWith<IllegalArgumentException> { scoreExpectedLevel(emptyMap()) }
        assertFailsWith<IllegalArgumentException> { scoreExpectedLevel(mapOf(0 to 1.0)) }
    }

    @Test
    fun `the provider score echo never wins - ART-5`() {
        // score-relevance-5.json records a provider "score" of 4.2 while the true expected
        // value over its level distribution is 3.4 — the parser must compute in code and
        // ignore the model's echo.
        val fixture = loadTranscript("score-relevance-5.json")
        MockWebServer().use { server ->
            server.start()
            server.enqueue(successResponse(fixture.responseBody))
            val engine = JevEngine(SystemOneClient(testTransport(), config(server, fixture.model)))
            val score = runBlocking {
                engine.score(fixture.state, fixture.questions.getValue("result-relevance") as ScoreQuestion)
            }
            assertEquals(3.4, score.expectedLevel, 1e-9, "expected value is computed in code (ART-5)")
            assertEquals(
                mapOf(1 to 0.05, 2 to 0.15, 3 to 0.3, 4 to 0.35, 5 to 0.15),
                score.levelProbabilities,
            )
        }
    }
}
