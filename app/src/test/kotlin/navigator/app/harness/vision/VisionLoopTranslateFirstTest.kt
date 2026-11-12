package navigator.app.harness.vision

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.app.loop.ActionExecutor
import navigator.app.loop.LoopObserver
import navigator.app.loop.StepObservation
import navigator.core.enumerate.ObservedNode
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport
import navigator.core.wire.WireTransportException

/**
 * PHASE_15 UAT repair round 2 — the deterministic translate pick guard ($0: real loop,
 * scripted transports, fabricated capture, temp-dir evidence — no device, no live call).
 *
 * Run 13 Attempt 2: Haiku proposed the Translate control (`b0`) at the product-page
 * steps, but the Jev pick chose the cookie-consent button (`call-032`: p1 0.88 vs the
 * Translate 0.02) or `none` (`call-035/038`: 0.91/0.87). The pick instruction now names
 * the Translate acceptance FIRST, and the loop CODE-PREFERS the proposed control on the
 * image path before the Jev pick/none can stop it.
 */
class VisionLoopTranslateFirstTest {

    private class ScriptedVisionTransport(
        private val chatResponses: ArrayDeque<String>,
        private val jevResponses: ArrayDeque<String>,
    ) : WireTransport {
        var chatCalls = 0
            private set
        var jevCalls = 0
            private set
        val chatBodies = mutableListOf<String>()
        val jevBodies = mutableListOf<String>()

        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            val isChat = request.url.contains("chat/completions")
            if (isChat) {
                chatCalls += 1
                chatBodies.add(request.body)
                val body = chatResponses.removeFirstOrNull()
                    ?: throw WireTransportException("chat script exhausted (#$chatCalls)")
                return WireHttpResponse(200, body)
            }
            jevCalls += 1
            jevBodies.add(request.body)
            val body = jevResponses.removeFirstOrNull()
                ?: throw WireTransportException("jev script exhausted (#$jevCalls)")
            return WireHttpResponse(200, body)
        }
    }

    private class ScriptedObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull() ?: error("observer script exhausted")
    }

    private class RecordingExecutor : ActionExecutor {
        val taps = mutableListOf<Pair<Int, Int>>()
        override fun tap(x: Int, y: Int): Boolean { taps.add(x to y); return true }
        override fun clickNode(x: Int, y: Int): Boolean = true
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean = true
        override fun back(): Boolean = true
        override fun submit(): Boolean = true
        override fun enterText(x: Int, y: Int, text: String): Boolean = true
    }

    private fun chatContent(innerJson: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5) })
    }.toString()

    private fun boxAnswer(top: Double, bottom: Double, left: Double, right: Double): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("box"))
            put("box", buildJsonObject {
                put("top", JsonPrimitive(top)); put("bottom", JsonPrimitive(bottom))
                put("left", JsonPrimitive(left)); put("right", JsonPrimitive(right))
            })
            put("confidence", JsonPrimitive(0.9))
        }

    /** The ground answer: b0 (the Translate box) + optional b1 + the Jev pick. */
    private fun jevGround(pick: String, boxes: Int): String = buildJsonObject {
        put("answers", buildJsonObject {
            put("b0", boxAnswer(0.12, 0.16, 0.77, 0.92))
            if (boxes > 1) put("b1", boxAnswer(0.87, 0.92, 0.73, 0.94))
            put("pick", buildJsonObject {
                put("type", JsonPrimitive("choice"))
                put("choice", JsonPrimitive(pick))
                put("probabilities", buildJsonObject {
                    put("p0", JsonPrimitive(0.1)); put("p1", JsonPrimitive(0.1))
                    put("none", JsonPrimitive(0.8))
                })
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    private fun node(
        id: String,
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) = ObservedNode(
        id = id, text = text, desc = "", clickable = true, editable = false,
        scrollable = false, left = left, top = top, right = right, bottom = bottom,
        className = "android.widget.Button", sourceOrder = 0,
    )

    private fun observation(nodes: List<ObservedNode>) = StepObservation(
        url = "https://shop.test/product",
        title = "Product",
        lines = listOf("product page"),
        nodes = nodes,
    )

    private fun newEvidence(runId: String): Pair<VisionEvidence, File> {
        val base = Files.createTempDirectory("vexp15-translate").toFile()
        return VisionEvidence(base, runId) to File(base, "$runId/events.log")
    }

    private class FakeCapture(private val shot: VisionCapture.ScreenShot) : VisionCapture(null) {
        override suspend fun capture(exclusion: VisionMath.RectPx?): VisionCapture.ScreenShot = shot
    }

    private fun shot() = VisionCapture.ScreenShot(
        base64 = "QUJD",
        width = 1000,
        height = 2000,
        screenWidth = 1000,
        screenHeight = 2000,
        sha256 = "fixture",
        jpegBytes = byteArrayOf(1, 2, 3),
    )

    private fun loop(
        transport: ScriptedVisionTransport,
        observations: List<StepObservation>,
        evidence: VisionEvidence,
        executor: RecordingExecutor,
    ): VisionLoop = VisionLoop(
        observer = ScriptedObserver(*observations.toTypedArray()),
        executor = executor,
        capture = FakeCapture(shot()),
        haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
        jev = VisionJev(
            transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
            backoffBaseMs = 1, retryDelay = {},
        ),
        budget = ExpBudget(),
        evidence = evidence,
        config = VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1),
    )

    private val plan = """
        {"summary":"buy the product","taskClass":"action","subtasks":[
          {"instruction":"add the celsius product to the cart","done":{"urlHost":"done.test"}}]}
    """.trimIndent()

    private val translateOnly = """
        {"proposals":[{"description":"tap \"Translate\" link-button in the 'Translate page? Finnish to English' bar at the top of the screen, right of the settings gear","action":"tap","expectedEffect":{"expect":"TargetStateChanged"},"box":{"top":0.12,"bottom":0.16,"left":0.77,"right":0.92},"confidence":0.9}]}
    """.trimIndent()

    private val translateAndCookie = """
        {"proposals":[
          {"description":"tap \"Translate\" link-style button in the Google Translate prompt bar at the top of the page (Finnish to English), right side of the bar","action":"tap","expectedEffect":{"expect":"TargetStateChanged"},"box":{"top":0.12,"bottom":0.16,"left":0.77,"right":0.92},"confidence":0.9},
          {"description":"tap \"Hyväksy\" orange button in the cookie consent banner at the bottom of the screen, right side","action":"tap","expectedEffect":{"expect":"TargetStateChanged"},"box":{"top":0.87,"bottom":0.92,"left":0.73,"right":0.94},"confidence":0.9}]}
    """.trimIndent()

    private val cartAndCookie = """
        {"proposals":[
          {"description":"tap \"Add to cart\" button in the product page action row","action":"tap","expectedEffect":{"expect":"TargetStateChanged"},"box":{"top":0.12,"bottom":0.16,"left":0.77,"right":0.92},"confidence":0.9},
          {"description":"tap \"Hyväksy\" orange button in the cookie consent banner at the bottom of the screen, right side","action":"tap","expectedEffect":{"expect":"TargetStateChanged"},"box":{"top":0.87,"bottom":0.92,"left":0.73,"right":0.94},"confidence":0.9}]}
    """.trimIndent()

    private val proposeEmpty = """{"proposals":[]}"""

    private fun translateNode() = node("n2", "Translate", 770, 240, 920, 320)

    private fun cookieNode() = node("n3", "Hyväksy", 730, 1740, 940, 1840)

    private fun cartNode() = node("n4", "Add to cart", 770, 240, 920, 320)

    @Test
    fun `the code prefers the proposed Translate over jev's cookie pick`() = runBlocking {
        val transport = ScriptedVisionTransport(
            ArrayDeque(listOf(chatContent(plan), chatContent(translateAndCookie), chatContent(proposeEmpty))),
            ArrayDeque(listOf(jevGround(pick = "p1", boxes = 2))),
        )
        val executor = RecordingExecutor()
        val (evidence, eventsFile) = newEvidence("translate-first")

        val result = loop(
            transport,
            listOf(
                observation(listOf(translateNode())),
                observation(listOf(translateNode(), cookieNode())),
                observation(listOf(translateNode(), cookieNode())),
            ),
            evidence,
            executor,
        ).run("open shop.test and add the celsius product to the cart", "translate-first") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertEquals(listOf(845 to 280), executor.taps, "the Translate bar is tapped, not the cookie")
        assertTrue(
            eventsFile.readText().contains("translate-first: preferring p0"),
            "the code preference is evidenced",
        )
        assertTrue(
            result.subtaskOutcomes.any { it.contains("(translate-first)") },
            "the step line names the preference: ${result.subtaskOutcomes}",
        )
        assertFalse(
            result.subtaskOutcomes.any { it.contains("jev picked none") },
            "the Jev cookie pick never stops the step",
        )
        assertTrue(
            transport.jevBodies.any { it.contains("choose that option FIRST") },
            "the pick instruction carries the v15 translate-first law",
        )
    }

    @Test
    fun `a lone Translate proposal is chosen even when jev says none`() = runBlocking {
        val transport = ScriptedVisionTransport(
            ArrayDeque(listOf(chatContent(plan), chatContent(translateOnly), chatContent(proposeEmpty))),
            ArrayDeque(listOf(jevGround(pick = "none", boxes = 1))),
        )
        val executor = RecordingExecutor()
        val (evidence, eventsFile) = newEvidence("translate-none")

        val result = loop(
            transport,
            listOf(
                observation(listOf(translateNode())),
                observation(listOf(translateNode())),
                observation(listOf(translateNode())),
            ),
            evidence,
            executor,
        ).run("open shop.test and add the celsius product to the cart", "translate-none") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertEquals(listOf(845 to 280), executor.taps, "the lone Translate is executed")
        assertFalse(
            result.subtaskOutcomes.any { it.contains("jev picked none") },
            "the Jev none never stops the translate step: ${result.subtaskOutcomes}",
        )
        assertTrue(eventsFile.readText().contains("translate-first: preferring p0"))
    }

    @Test
    fun `without a translate proposal jev's pick is unchanged`() = runBlocking {
        val transport = ScriptedVisionTransport(
            ArrayDeque(listOf(chatContent(plan), chatContent(cartAndCookie), chatContent(proposeEmpty))),
            ArrayDeque(listOf(jevGround(pick = "p0", boxes = 2))),
        )
        val executor = RecordingExecutor()
        val (evidence, _) = newEvidence("no-translate")

        val result = loop(
            transport,
            listOf(
                observation(listOf(cartNode())),
                observation(listOf(cartNode(), cookieNode())),
                observation(listOf(cartNode(), cookieNode())),
            ),
            evidence,
            executor,
        ).run("open shop.test and add the celsius product to the cart", "no-translate") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertEquals(listOf(845 to 280), executor.taps, "Jev's p0 (the cart control) is executed")
        assertFalse(
            result.subtaskOutcomes.any { it.contains("(translate-first)") },
            "no translate proposal -> no code preference: ${result.subtaskOutcomes}",
        )
    }
}
