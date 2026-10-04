package navigator.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import navigator.core.fixtures.ProviderWireTranscript
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gate 3.1 — wire round-trip vs transcripts (isolation). MockWebServer serves each
 * recorded transcript response; the client's request bytes must match the recorded request
 * shape exactly (JSON key order normalized) and the parsed results must equal the recorded
 * typed results. Unknown question names, missing answers, and missing required fields fail
 * loudly — never a default.
 */
class SystemOneClientTranscriptTest {

    @Test
    fun `gate 3_1 - every phase3 transcript round-trips request bytes and parsed results`() {
        val fixtures = WireGateSupport.loadPhase3Transcripts()
        assertTrue(fixtures.size >= 9, "phase requires >= 9 transcript fixtures, found ${fixtures.size}")
        val perType = fixtures.map { it.second }
            .flatMap { t -> t.questions.values.map { it::class.simpleName!! } }
            .groupingBy { it }.eachCount()
        for (type in listOf("ChoiceQuestion", "NoulQuestion", "ScoreQuestion")) {
            assertTrue(
                (perType[type] ?: 0) >= 3,
                "phase requires >= 3 fixtures per question type; $type has ${perType[type]}",
            )
        }
        assertTrue(
            fixtures.any { it.second.questions.size > 1 },
            "expected a multi-question fixture to prove name-keyed multi-question parsing",
        )

        for ((name, fixture) in fixtures) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(WireGateSupport.successResponse(fixture.responseBody))
                val client = SystemOneClient(
                    WireGateSupport.testTransport(),
                    WireGateSupport.config(server, fixture.model),
                )
                val decision = WireGateSupport.decide(client, fixture)

                val recorded = server.takeRequest(2, TimeUnit.SECONDS)
                    ?: error("no request arrived for $name")
                assertEquals("POST", recorded.method, name)
                assertEquals("/api/alpha/decisions", recorded.path, name)
                assertEquals("Bearer test-key-not-a-secret", recorded.getHeader("Authorization"), name)
                assertTrue(
                    recorded.getHeader("Content-Type")!!.startsWith("application/json"),
                    "$name: Content-Type must be application/json",
                )
                // Request bytes must match the recorded request shape exactly, with JSON
                // key order normalized: JsonElement equality is structural and
                // order-insensitive for objects, exact for arrays (options/levels order).
                assertEquals(
                    fixture.requestBody,
                    Json.parseToJsonElement(recorded.body.readUtf8()),
                    "request body: $name",
                )

                assertEquals(false, decision.usedFallback, name)
                assertEquals(fixture.questions.keys, decision.answers.keys, name)
                for ((qName, expected) in fixture.expectedResults) {
                    WireGateSupport.assertTypedResultEquals(
                        expected, decision.answers.getValue(qName), "$name/$qName",
                    )
                }
                // The real envelope's usage ({input_tokens, output_tokens, cost}) must be
                // parsed through to the decision (TaskBudget raw material).
                val usage = fixture.responseBody.getValue("usage").jsonObject
                assertEquals(
                    Usage(
                        inputTokens = usage.getValue("input_tokens").jsonPrimitive.int.toLong(),
                        outputTokens = usage.getValue("output_tokens").jsonPrimitive.int.toLong(),
                        costUsd = usage.getValue("cost").jsonPrimitive.double,
                    ),
                    decision.usage,
                    "usage: $name",
                )
            }
        }
    }

    @Test
    fun `gate 3_1 - unknown question name in the response fails loudly, never a default`() {
        val fixture = WireGateSupport.loadTranscript("choice-search-results.json")
        val doctored = withAnswers(fixture) { answers ->
            answers + ("surprise-question" to buildJsonObject {
                put("choice", "result-2")
                put("probabilities", buildJsonObject {
                    put("result-1", 0.5)
                    put("result-2", 0.5)
                    put("result-3", 0.0)
                    put("next-page-button", 0.0)
                })
                put("confidence", 0.5)
            })
        }
        MockWebServer().use { server ->
            server.start()
            server.enqueue(WireGateSupport.successResponse(doctored))
            val client = SystemOneClient(
                WireGateSupport.testTransport(),
                WireGateSupport.config(server, fixture.model),
            )
            val e = assertFailsWith<WireProtocolException> { WireGateSupport.decide(client, fixture) }
            assertTrue(
                e.message!!.contains("surprise-question"),
                "error must name the unknown question: ${e.message}",
            )
            assertEquals(1, server.requestCount, "content errors are never retried")
        }
    }

    @Test
    fun `gate 3_1 - a missing answer for an asked question fails loudly`() {
        val fixture = WireGateSupport.loadTranscript("choice-search-results.json")
        val doctored = withAnswers(fixture) { answers -> answers - "next-action" }
        MockWebServer().use { server ->
            server.start()
            server.enqueue(WireGateSupport.successResponse(doctored))
            val client = SystemOneClient(
                WireGateSupport.testTransport(),
                WireGateSupport.config(server, fixture.model),
            )
            val e = assertFailsWith<WireProtocolException> { WireGateSupport.decide(client, fixture) }
            assertTrue(e.message!!.contains("next-action"), "error must name the missing answer: ${e.message}")
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `gate 3_1 - a missing required answer field fails loudly`() {
        val fixture = WireGateSupport.loadTranscript("choice-search-results.json")
        val doctored = withAnswers(fixture) { answers ->
            answers.mapValues { (name, body) ->
                if (name == "next-action") JsonObject(body.jsonObject - "confidence") else body
            }
        }
        MockWebServer().use { server ->
            server.start()
            server.enqueue(WireGateSupport.successResponse(doctored))
            val client = SystemOneClient(
                WireGateSupport.testTransport(),
                WireGateSupport.config(server, fixture.model),
            )
            val e = assertFailsWith<WireProtocolException> { WireGateSupport.decide(client, fixture) }
            assertTrue(e.message!!.contains("confidence"), "error must name the missing field: ${e.message}")
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `gate 3_1 - extra unknown fields are tolerated, distribution keys are exact`() {
        // The round-trip test proves unknown fields ("reasoning", "usage", ...) are ignored.
        // A distribution key outside the declared names is malformed content: loud error.
        val fixture = WireGateSupport.loadTranscript("choice-cookie-consent.json")
        val doctored = withAnswers(fixture) { answers ->
            answers.mapValues { (name, body) ->
                if (name != "consent-choice") return@mapValues body
                val answer = body.jsonObject
                JsonObject(
                    answer + ("probabilities" to JsonObject(
                        answer["probabilities"]!!.jsonObject + ("phantom-option" to Json.parseToJsonElement("0.01")),
                    )),
                )
            }
        }
        MockWebServer().use { server ->
            server.start()
            server.enqueue(WireGateSupport.successResponse(doctored))
            val client = SystemOneClient(
                WireGateSupport.testTransport(),
                WireGateSupport.config(server, fixture.model),
            )
            val e = assertFailsWith<WireProtocolException> { WireGateSupport.decide(client, fixture) }
            assertTrue(
                e.message!!.contains("phantom-option"),
                "error must name the undeclared distribution key: ${e.message}",
            )
        }
    }

    @Test
    fun `gate 3_1 - a score legend that disagrees with the declared rubric fails loudly`() {
        // Real-protocol law: the provider's legend must map each 0-based index back to the
        // exact declared level string; a foreign rubric is never silently remapped.
        val fixture = WireGateSupport.loadTranscript("score-quality-2.json")
        val answers = fixture.responseBody.getValue("answers").jsonObject
        val doctoredAnswers = answers.mapValues { (name, body) ->
            if (name != "page-quality") return@mapValues body
            val answer = body.jsonObject
            val foreignLegend = JsonObject(
                answer.getValue("legend").jsonObject.entries.associate { (index, level) ->
                    index to Json.parseToJsonElement("\"" + level.jsonPrimitive.content + "-FOREIGN\"")
                },
            )
            JsonObject(answer + ("legend" to foreignLegend))
        }
        val doctored = JsonObject(
            fixture.responseBody + ("answers" to JsonObject(doctoredAnswers)),
        )
        MockWebServer().use { server ->
            server.start()
            server.enqueue(WireGateSupport.successResponse(doctored))
            val client = SystemOneClient(
                WireGateSupport.testTransport(),
                WireGateSupport.config(server, fixture.model),
            )
            val e = assertFailsWith<WireProtocolException> { WireGateSupport.decide(client, fixture) }
            assertTrue(
                e.message!!.contains("legend") && e.message!!.contains("different rubric"),
                "error must name the legend mismatch: ${e.message}",
            )
            assertEquals(1, server.requestCount, "content errors are never retried")
        }
    }

    @Test
    fun `gate 3_1 - an answer whose type discriminator mismatches the question fails loudly`() {
        val fixture = WireGateSupport.loadTranscript("noul-goal-achieved.json")
        val doctored = withAnswers(fixture) { answers ->
            answers.mapValues { (name, body) ->
                if (name != "goal-achieved") return@mapValues body
                JsonObject(body.jsonObject + ("type" to Json.parseToJsonElement("\"choice\"")))
            }
        }
        MockWebServer().use { server ->
            server.start()
            server.enqueue(WireGateSupport.successResponse(doctored))
            val client = SystemOneClient(
                WireGateSupport.testTransport(),
                WireGateSupport.config(server, fixture.model),
            )
            val e = assertFailsWith<WireProtocolException> { WireGateSupport.decide(client, fixture) }
            assertTrue(
                e.message!!.contains("mismatched discriminator") && e.message!!.contains("goal-achieved"),
                "error must name the discriminator mismatch: ${e.message}",
            )
            assertEquals(1, server.requestCount, "content errors are never retried")
        }
    }

    /** Rebuilds a transcript response with a transformed `answers` object. */
    private fun withAnswers(
        fixture: ProviderWireTranscript,
        transform: (Map<String, kotlinx.serialization.json.JsonElement>) -> Map<String, kotlinx.serialization.json.JsonElement>,
    ): JsonObject = JsonObject(
        fixture.responseBody + (
            "answers" to JsonObject(transform(fixture.responseBody["answers"]!!.jsonObject))
            ),
    )
}
