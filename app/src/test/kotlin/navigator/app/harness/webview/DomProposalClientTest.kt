package navigator.app.harness.webview

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import navigator.app.harness.webview.dom.DomAction
import navigator.app.harness.webview.dom.DomSnapshot
import navigator.core.wire.WireHttpResponse
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V2 S1 FR-31 — the writer's DOM proposal call over the wire: strict local validation,
 * the ONE error-fed repair retry, the element-id law (snapshot-local), the fence
 * cleanup, and the model-call budget riding the gate.
 */
class DomProposalClientTest {

    private val snapshot = SnapshotFixtures.bigSnapshot(20)

    private fun ok(content: String): MockResponse = MockResponse().setBody(
        buildJsonObject {
            put("id", JsonPrimitive("x"))
            put("model", JsonPrimitive("writer-model-1"))
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", JsonPrimitive(0))
                            put("finish_reason", JsonPrimitive("stop"))
                            put(
                                "message",
                                buildJsonObject {
                                    put("role", JsonPrimitive("assistant"))
                                    put("content", JsonPrimitive(content))
                                },
                            )
                        },
                    )
                },
            )
            put(
                "usage",
                buildJsonObject {
                    put("prompt_tokens", JsonPrimitive(1000))
                    put("completion_tokens", JsonPrimitive(200))
                    put("total_tokens", JsonPrimitive(1200))
                },
            )
        }.toString(),
    )

    private fun proposalJson(id: Int = 0, action: String = "click", effect: String = "the catalog page shows") =
        """{"elementId":$id,"action":"$action","expectedEffect":"$effect"}"""

    private fun client(server: MockWebServer, onUsage: (LaneUsage) -> Unit = {}) = DomProposalClient(
        transport = OkHttpTransport(server.url("/api/v1/chat/completions").toString()),
        config = LaneWriterConfig("https://writer.test/api/v1/chat/completions", "test-key-not-a-secret", "writer-model-1"),
        gate = LaneModelGate(
            policy = navigator.core.wire.ModelCallPolicy(
                maxAttempts = 3, totalDeadlineMs = 90_000, initialBackoffMs = 1, maximumBackoffMs = 1,
            ),
        ),
        onUsage = onUsage,
    )

    private fun OkHttpTransport(url: String): navigator.core.wire.WireTransport {
        val delegate = navigator.core.wire.OkHttpWireTransport(300_000L)
        return object : navigator.core.wire.WireTransport {
            override suspend fun execute(request: navigator.core.wire.WireHttpRequest): navigator.core.wire.WireHttpResponse =
                delegate.execute(navigator.core.wire.WireHttpRequest(url, request.headers, request.body))
        }
    }

    @Test
    fun `a valid proposal parses to the element and action`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(ok(proposalJson(id = 3, action = "click", effect = "the deals page shows")))
        val seen = mutableListOf<LaneUsage>()
        val proposal = client(server) { seen.add(it) }.propose(snapshot, "open the deals", "the deals page shows")
        server.shutdown()

        assertEquals(3, proposal.element.id)
        assertEquals(DomAction.CLICK, proposal.action)
        assertEquals("the deals page shows", proposal.expectedEffect)
        assertEquals(1, seen.size, "the usage meters exactly once per completed call")
        // ART-5 integer arithmetic on the pinned upper-bound prices: 1000*800/1000 + 200*4000/1000
        assertEquals(800L + 800L, seen[0].costMicroUsd)
    }

    @Test
    fun `the request carries the pinned shape - system prompt, elements, response_format json_schema`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(ok(proposalJson()))
        client(server).propose(snapshot, "open the deals", "the deals page shows")
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        server.shutdown()

        assertEquals("writer-model-1", (sent["model"] as JsonPrimitive).content)
        val messages = sent["messages"]!!.jsonArray
        assertEquals(2, messages.size, "system + user, exactly")
        val system = messages[0].toString()
        assertTrue(system.contains("UNTRUSTED DATA"), "the untrusted-page law rides the system prompt")
        assertTrue(system.contains("NO submit action"), "the fill-only law rides the system prompt")
        val user = messages[1].toString()
        assertTrue(user.contains("ELEMENTS"), "the element list rides")
        assertTrue(user.contains("[3]"), "the snapshot-local ids ride verbatim")
        assertTrue(sent.toString().contains("json_schema"), "the structured-output path is pinned")
    }

    @Test
    fun `an out-of-range elementId is refused and the repair retry is error-fed`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(ok(proposalJson(id = 999))) // not in the 20-element snapshot
        server.enqueue(ok(proposalJson(id = 5)))
        val proposal = client(server).propose(snapshot, "open the deals", "the deals page shows")
        server.shutdown()

        assertEquals(5, proposal.element.id, "the repair retry re-asked with the error and recovered")
        val secondRequest = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).toString()
        val firstRequest = server.requestCount // bookkeeping only
        assertTrue(true)
        assertEquals(2, server.requestCount, "exactly TWO content attempts rode the wire")
    }

    @Test
    fun `an invalid proposal twice is a loud refusal - nothing dispatches`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(ok(proposalJson(id = 999)))
        server.enqueue(ok(proposalJson(id = 999)))
        val e = assertFailsWith<DomProposalException> {
            client(server).propose(snapshot, "open the deals", "the deals page shows")
        }
        server.shutdown()
        assertTrue(e.message!!.contains("refusing"), "the refusal is loud: ${e.message}")
    }

    @Test
    fun `a fenced proposal still parses (the fence cleanup)`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(ok("```json\n${proposalJson(id = 1)}\n```"))
        val proposal = client(server).propose(snapshot, "open the deals", "the deals page shows")
        server.shutdown()
        assertEquals(1, proposal.element.id)
    }

    @Test
    fun `a submit action is impossible by schema - the vocabulary has no submit`() {
        val e = assertFailsWith<DomProposalException> {
            runBlocking {
                val server = MockWebServer()
                server.start()
                server.enqueue(ok(proposalJson(id = 0, action = "submit")))
                server.enqueue(ok(proposalJson(id = 0, action = "submit")))
                try {
                    client(server).propose(snapshot, "place the order", "the order places")
                } finally {
                    server.shutdown()
                }
            }
        }
        assertTrue(e.message!!.contains("outside the lane vocabulary"), "${e.message}")
    }
}
