package navigator.app.harness.vision

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * PHASE 18 STAGE 18.A — CHARACTERIZATION (the refactor baseline; NOT a spec).
 *
 * Pins the CURRENT grounding-fallback LADDER's route vocabulary and its last arm:
 * `Jev box` -> `haiku box fallback` -> `label match` -> `raw`. The first three arms
 * and the loud TYPE refusal are already pinned by [VisionLoopGroundingTest]; THIS
 * suite pins what that test deliberately does not: **the RAW arm executes a TAP at
 * the unmatched rectangle centre — with no node match and with the Jev box BELOW the
 * confidence floor** (the Haiku proposal box has no floor). The step line names the
 * arm; the ledger records the miss. This is the exact behavior Stage 18.B CN-09
 * removes (LOW_CONFIDENCE_GROUND never dispatches); when that lands, this suite is
 * the before-picture it must consciously break and re-pin.
 *
 * Vision law it will change: Project Vision/04 §9.8 (grounding never silently
 * degrades). Doc-audit finding #19 (.state/phase-18-docs-audit.md) is this quirk.
 */
class Phase18GroundingRouteCharacterizationTest {

    /** Routes by URL: chat-completions pops the chat script, everything else the Jev script. */
    private class ScriptedTransport(
        chatResponses: List<String>,
        jevResponses: List<String>,
    ) : WireTransport {
        private val chat = ArrayDeque(chatResponses)
        private val jev = ArrayDeque(jevResponses)
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            val queue = if (request.url.contains("chat/completions")) chat else jev
            val body = if (queue.size > 1) queue.removeFirst() else queue.firstOrNull()
                ?: throw WireTransportException("script exhausted for ${request.url}")
            return WireHttpResponse(200, body)
        }
    }

    private class RepeatingObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        private var last: StepObservation = observations.last()
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull()?.also { last = it } ?: last
    }

    private class RecordingExecutor : ActionExecutor {
        val taps = mutableListOf<Pair<Int, Int>>()
        val nodeClicks = mutableListOf<Pair<Int, Int>>()
        override fun tap(x: Int, y: Int): Boolean { taps.add(x to y); return true }
        override fun clickNode(x: Int, y: Int): Boolean { nodeClicks.add(x to y); return true }
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean = true
        override fun back(): Boolean = true
        override fun submit(): Boolean = true
        override fun enterText(x: Int, y: Int, text: String): Boolean = true
    }

    private class FakeCapture(private val shot: VisionCapture.ScreenShot) : VisionCapture(null) {
        override suspend fun capture(exclusion: VisionMath.RectPx?): VisionCapture.ScreenShot = shot
    }

    private fun shot() = VisionCapture.ScreenShot(
        base64 = "QUJD", width = 1000, height = 2000,
        screenWidth = 1000, screenHeight = 2000, sha256 = "route-fixture",
        jpegBytes = byteArrayOf(1, 2, 3),
    )

    private fun chatContent(innerJson: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5) })
    }.toString()

    private fun jevGround(confidence: Double): String = buildJsonObject {
        put("answers", buildJsonObject {
            put("b0", buildJsonObject {
                put("type", JsonPrimitive("box"))
                put("box", buildJsonObject {
                    put("top", JsonPrimitive(0.60)); put("bottom", JsonPrimitive(0.65))
                    put("left", JsonPrimitive(0.60)); put("right", JsonPrimitive(0.70))
                })
                put("confidence", JsonPrimitive(confidence))
            })
            put("pick", buildJsonObject {
                put("type", JsonPrimitive("choice"))
                put("choice", JsonPrimitive("p0"))
                put("probabilities", buildJsonObject {
                    put("p0", JsonPrimitive(0.9)); put("none", JsonPrimitive(0.1))
                })
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    private fun jevNoul(pTrue: Double): String = buildJsonObject {
        put("answers", buildJsonObject {
            put("done", buildJsonObject {
                put("type", JsonPrimitive("noul"))
                put("noul", JsonPrimitive(pTrue))
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    @Test
    fun `the route vocabulary is the four documented arms`() {
        assertEquals("Jev box", VisionLoop.GROUND_JEV_BOX)
        assertEquals("haiku box fallback", VisionLoop.GROUND_HAIKU_BOX)
        assertEquals("label match", VisionLoop.GROUND_LABEL)
        assertEquals("raw", VisionLoop.GROUND_RAW)
        // The similarity floor the README documents is 0.20 (IOU_PARTIAL).
        assertEquals(0.2, VisionEvidence.GroundingMetrics.IOU_PARTIAL)
    }

    @Test
    fun `the raw arm is refused (LOW_CONFIDENCE_GROUND) on a sub-floor Jev box with no node match`() =
        runBlocking {
            // Layout: the only node is far from the boxes; the description matches no
            // label. The Jev box is confidence 0.3 (BELOW the 0.6 accept floor) and its
            // rectangle overlaps nothing. UPDATED by Stage 18.B CN-09 (doc 04 §9.8):
            // the raw arm no longer dispatches a location-dependent action without a
            // node match — LOW_CONFIDENCE_GROUND, zero executor calls, and the honest
            // refusal feeds the failed-step/re-plan gate (maxReplans 0 -> REPLANS_EXHAUSTED).
            val homeNode = ObservedNode(
                id = "n1", text = "Home", desc = "", clickable = true, editable = false,
                scrollable = false, left = 800, top = 100, right = 900, bottom = 160,
                className = "android.widget.Button", sourceOrder = 0,
            )
            val proposal = """
                {"proposals":[{"description":"the mystery control with no label here","action":"tap",
                  "box":{"top":0.60,"bottom":0.65,"left":0.60,"right":0.70},"confidence":0.9}]}
            """.trimIndent()
            val plan = """
                {"summary":"tap the mystery control on shoptest.test","taskClass":"action","subtasks":[
                  {"instruction":"tap the mystery control","done":{"urlHost":"done.test"}}]}
            """.trimIndent()
            val transport = ScriptedTransport(
                chatResponses = listOf(chatContent(plan), chatContent(proposal), chatContent(confirmTrue)),
                jevResponses = listOf(jevGround(confidence = 0.3), jevNoul(0.9)),
            )
            val executor = RecordingExecutor()
            val base = Files.createTempDirectory("p18-raw").toFile()
            val evidence = VisionEvidence(base, "raw-route")
            var iterations = 0
            val loop = VisionLoop(
                observer = RepeatingObserver(
                    StepObservation(
                        url = "https://shoptest.test/page", title = "Shop",
                        lines = listOf("home page"), nodes = listOf(homeNode),
                        windowPackage = "com.android.chrome",
                    ),
                    StepObservation(
                        url = "https://shoptest.test/page2", title = "Shop",
                        lines = listOf("home page 2"), nodes = listOf(homeNode),
                        windowPackage = "com.android.chrome",
                    ),
                    StepObservation(
                        url = "https://done.test/finished", title = "Done",
                        lines = listOf("the task is done"), nodes = emptyList(),
                        windowPackage = "com.android.chrome",
                    ),
                ),
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
            val result = loop.run("tap the mystery control on shoptest.test", "raw-route") {
                ++iterations > 10
            }

            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal, result.subtaskOutcomes.toString())
            // NEW behavior (doc 04 §9.8): ZERO dispatches — the raw-centre tap is gone.
            assertTrue(executor.taps.isEmpty(), "no raw tap may fire without a node match")
            assertTrue(executor.nodeClicks.isEmpty(), "no node action backs an unmatched box")
            val stepLine = result.subtaskOutcomes.first { it.startsWith("step") }
            assertTrue(
                "LOW_CONFIDENCE_GROUND" in stepLine,
                "the step line names the typed refusal: $stepLine",
            )
            val log = java.io.File(base, "raw-route/events.log").readText()
            assertTrue(
                "jev box sub-floor" in log,
                "the sub-floor Jev box is logged loudly before the refusal",
            )
            assertTrue(
                "LOW_CONFIDENCE_GROUND" in log,
                "the refusal rides the evidence: $log".take(200),
            )
            // The ledger records the truth: a MISS (bestIou below the PARTIAL floor).
            val summary = evidence.grounding.summarize()
            assertEquals("1.0", summary["missRate"]?.jsonPrimitive?.content, summary.toString())
        }

    private val confirmTrue = """{"complete":true,"reason":"the outcome is visible"}"""
}
