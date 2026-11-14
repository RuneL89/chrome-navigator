package navigator.app.harness.vision

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.core.ladder.ReplanContext
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport
import navigator.core.wire.WireTransportException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PHASE_17 Stage 2 — the hybrid's WRITER over RAW DUAL EVIDENCE: the event call carries the
 * screenshot AND the a11y page digest, the plan passes the SAME schema/goal-fidelity laws the
 * a11y route enforces, and the provenance/cost ride the host's contract.
 *
 * $0: a scripted transport captures the request; no provider call.
 */
class VisionLadderSourceTest {

    private class ScriptedTransport(private val responses: ArrayDeque<String>) : WireTransport {
        val bodies = mutableListOf<String>()
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            bodies.add(request.body)
            val body = responses.removeFirstOrNull()
                ?: throw WireTransportException("script exhausted (#${bodies.size})")
            return WireHttpResponse(200, body)
        }
    }

    private fun chatContent(innerJson: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 100); put("completion_tokens", 50) })
    }.toString()

    private fun shot() = VisionCapture.ScreenShot(
        base64 = "QUJD", width = 1000, height = 2000,
        screenWidth = 1000, screenHeight = 2000, sha256 = "fixture",
        jpegBytes = byteArrayOf(1, 2, 3),
    )

    private val plan = """
        {"summary":"search then open the page","taskClass":"read","subtasks":[
          {"instruction":"Search Google for celsius drink","enterText":"celsius drink",
           "done":{"elementPresent":"a search result linking to a page about the celsius drink"}},
          {"instruction":"open the celsius page on shop.test","done":{"urlHost":"shop.test"}}]}
    """.trimIndent()

    @Test
    fun `the plan call carries the screenshot and the a11y digest - and the ladder passes the laws`() =
        runBlocking {
            val transport = ScriptedTransport(ArrayDeque(listOf(chatContent(plan))))
            val source = VisionLadderSource(
                haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
                capture = { shot() },
            )

            val result = assertNotNull(
                source.restructure("find a celsius drink on shop.test", generation = 1),
            )

            assertEquals(2, result.ladder.subtasks.size)
            assertEquals("vision-exp-v20/gen1", result.version)
            assertTrue(result.costMicroUsd > 0, "the call's provider usage prices: ${result.costMicroUsd}")
            val body = transport.bodies.single()
            assertTrue("image_url" in body, "the screenshot rides the plan event (raw dual evidence): $body")
            assertTrue("<goal>" in body, "the goal rides the event")
        }

    @Test
    fun `a re-plan carries the failure cause and the page facts with the replan skill`() = runBlocking {
        val transport = ScriptedTransport(ArrayDeque(listOf(chatContent(plan))))
        val source = VisionLadderSource(
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            capture = { shot() },
        )
        val failure = ReplanContext(
            failingOrdinal = 1,
            failingInstruction = "Search Google for celsius drink",
            failingDone = "the address contains 'google.com/search?q=celsius'",
            cause = "no unique candidate/ground: the cascade stayed uncertain",
            causeClass = ReplanContext.CauseClass.UNCERTAIN_AFTER_HANDSHAKE,
            pageHost = "google.test",
            pageTitle = "Google",
            pageExcerptLines = listOf("search box", "results"),
            slateIntent = null,
            slateBucket = null,
            slateLines = emptyList(),
            otherBucketLines = emptyList(),
            historyLines = emptyList(),
            assistText = null,
            verifiedCount = 0,
            verifiedPrefixLines = emptyList(),
            totalSubtasks = 2,
            generation = 1,
        )

        val result = assertNotNull(
            source.restructure("find a celsius drink on shop.test", generation = 2, failure = failure),
        )

        assertEquals("vision-exp-v20/gen2", result.version)
        val body = transport.bodies.single()
        assertTrue("no unique candidate/ground" in body, "the cause rides the re-plan: $body")
        assertTrue("google.test" in body, "the page host rides it: $body")
        assertTrue("THE RE-PLAN SKILL" in body, "the re-plan addendum is present: $body")
        assertTrue("image_url" in body, "the current page's screenshot rides it: $body")
    }

    @Test
    fun `a capture failure degrades to text-only with a loud line`() = runBlocking {
        val transport = ScriptedTransport(ArrayDeque(listOf(chatContent(plan))))
        val lines = mutableListOf<String>()
        val source = VisionLadderSource(
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            capture = { throw IllegalStateException("no frame") },
            log = { lines.add(it) },
        )

        val result = assertNotNull(source.restructure("find a celsius drink on shop.test", 1))

        assertEquals(2, result.ladder.subtasks.size)
        assertTrue("image_url" !in transport.bodies.single(), "the text-only degrade is real")
        assertTrue(lines.any { it.contains("capture failed") }, lines.toString())
    }

    @Test
    fun `a goal-fidelity violation is refused - never installed`() = runBlocking {
        // The goal names NO site, so the FIRST subtask must be a search; a store-first
        // ladder violates the site-first law and the source refuses it.
        val storeFirst = """
            {"summary":"open the store","taskClass":"read","subtasks":[
              {"instruction":"open shop.test","done":{"urlHost":"shop.test"}}]}
        """.trimIndent()
        val transport = ScriptedTransport(ArrayDeque(listOf(chatContent(storeFirst))))
        val lines = mutableListOf<String>()
        val source = VisionLadderSource(
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            capture = { shot() },
            log = { lines.add(it) },
        )

        assertNull(source.restructure("find a good pair of running shoes", 1))
        assertTrue(lines.any { it.contains("goal fidelity") }, lines.toString())
    }

    @Test
    fun `a malformed plan is refused - never defaulted`() = runBlocking {
        val transport = ScriptedTransport(ArrayDeque(listOf(chatContent("""{"summary":"x"}"""))))
        val source = VisionLadderSource(
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            capture = { shot() },
        )
        assertNull(source.restructure("find a celsius drink on shop.test", 1))
    }
}
