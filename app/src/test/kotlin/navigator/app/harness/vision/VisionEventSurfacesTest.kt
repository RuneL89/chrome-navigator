package navigator.app.harness.vision

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport
import navigator.core.wire.WireTransportException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PHASE_17 Stage 2 — the hybrid's EVENT SURFACES over raw dual evidence:
 *
 * - 2.3 the subtask-boundary audit ([VisionBoundaryJudge]): one call at a verified boundary,
 *   the screenshot attached, the remaining-plan verdict parsed, provenance + cost returned;
 * - 2.4 the stuck event ([VisionStuckEvent]): the SAME [navigator.app.loop.AssistSource]
 *   contract the a11y handshake uses, with the screenshot attached and the provider usage
 *   metered — no new seam, no new prompt text.
 *
 * $0: a scripted transport captures each request body.
 */
class VisionEventSurfacesTest {

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

    @Test
    fun `the boundary audit carries the screenshot and the remaining-plan verdict`() = runBlocking {
        val transport = ScriptedTransport(
            ArrayDeque(listOf(chatContent("""{"plan_holds":false,"reason":"the page is a login wall"}"""))),
        )
        val judge = VisionBoundaryJudge(
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            capture = { shot() },
        )

        val verdict = assertNotNull(
            judge.judge(
                goal = "buy the headphones on shop.test",
                completedInstruction = "open shop.test",
                completedDone = "the URL host is shop.test",
                pageUrl = "https://shop.test/login",
                pageTitle = "Sign in",
                pageDigest = "email field\npassword field",
            ),
        )

        assertFalse(verdict.planHolds)
        assertEquals("the page is a login wall", verdict.reason)
        assertEquals("vision-exp-v20", verdict.promptVersion)
        assertTrue(verdict.costMicroUsd > 0, "the call's provider usage prices")
        val body = transport.bodies.single()
        assertTrue("image_url" in body, "the dual evidence rides the boundary: $body")
        assertTrue("open shop.test" in body, "the completed subtask rides it: $body")
    }

    @Test
    fun `a malformed boundary verdict is refused - never defaulted`() = runBlocking {
        val lines = mutableListOf<String>()
        val judge = VisionBoundaryJudge(
            haiku = VisionHaiku(
                ScriptedTransport(ArrayDeque(listOf(chatContent("""{"reason":"no verdict"}"""))))
                    as WireTransport,
                "fixture-key", backoffBaseMs = 1, retryDelay = {},
            ),
            capture = { shot() },
            log = { lines.add(it) },
        )

        assertNull(
            judge.judge("g", "i", "d", "u", "t", "p"),
        )
        assertTrue(lines.any { it.contains("refused") }, lines.toString())
    }

    @Test
    fun `the stuck event re-describes the page over dual evidence with metered usage`() = runBlocking {
        val transport = ScriptedTransport(
            ArrayDeque(listOf(chatContent("The page is a checkout form with empty first-name and postal fields."))),
        )
        val stuck = VisionStuckEvent(
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            capture = { shot() },
        )

        val result = assertNotNull(
            stuck.assist(
                subtask = "fill the delivery address",
                doneCondition = "the page contains 'Kiitos tilauksestasi'",
                pageMaterial = "Etunimi\nKatuosoite",
            ),
        )

        assertTrue(result.text.contains("checkout form"), result.text)
        assertTrue(result.usage.costMicroUsd > 0, "the stuck event meters its usage")
        assertEquals("vision-exp-v20", result.promptVersion)
        val body = transport.bodies.single()
        assertTrue("image_url" in body, "the stuck event carries the screenshot: $body")
    }

    @Test
    fun `the stuck event honours the skip marker and abstains`() = runBlocking {
        val transport = ScriptedTransport(
            ArrayDeque(listOf(chatContent("I cannot describe this page usefully."))),
        )
        val stuck = VisionStuckEvent(
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            capture = { shot() },
        )

        assertNull(stuck.assist("s", "d", "p"))
    }

    @Test
    fun `a capture failure degrades both surfaces to text-only`() = runBlocking {
        val transport = ScriptedTransport(
            ArrayDeque(listOf(chatContent("""{"plan_holds":true,"reason":"fine"}"""))),
        )
        val lines = mutableListOf<String>()
        val judge = VisionBoundaryJudge(
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            capture = { throw IllegalStateException("no frame") },
            log = { lines.add(it) },
        )

        assertNotNull(judge.judge("g", "i", "d", "u", "t", "p"))
        assertFalse("image_url" in transport.bodies.single(), "text-only degrade")
        assertTrue(lines.any { it.contains("capture failed") }, lines.toString())
    }
}
