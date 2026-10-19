package navigator.app.harness.vision

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The FOURTH actual-app live-test repair's JVM gates (2026-10-19,
 * `task-1792397135186`, $0 — fully scripted transport, fabricated capture, no device
 * and no provider call): the EXPLICIT grounding-quality ladder in [VisionLoop].
 *
 * The defect: a confidence-high Jev Box overlapped NO a11y node (IoU 0.00), the loop
 * accepted it as a grounding, and a TYPE fired `enterText` at a non-editable spot —
 * `causes=[NO_FIELD_AT_POINT]` — across re-plans. The law now: Jev box only at
 * IoU >= IOU_PARTIAL, then the Haiku proposal box (`haiku-box-fallback`), then the
 * description's label match (`label-match`), then the raw rectangle's centre — where a
 * TYPE must land inside an editable node or the step FAILS loudly.
 */
class VisionLoopGroundingTest {

    // -- scripted edges -------------------------------------------------------------------

    /** Routes by URL: chat-completions pops [chatResponses], everything else [jevResponses]. */
    private class ScriptedVisionTransport(
        private val chatResponses: ArrayDeque<String>,
        private val jevResponses: ArrayDeque<String>,
    ) : WireTransport {
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            val isChat = request.url.contains("chat/completions")
            val body = (
                if (isChat) chatResponses.removeFirstOrNull() else jevResponses.removeFirstOrNull()
                ) ?: throw WireTransportException("script exhausted for ${request.url}")
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
        val entered = mutableListOf<Pair<Pair<Int, Int>, String>>()
        override fun tap(x: Int, y: Int): Boolean { taps.add(x to y); return true }
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean = true
        override fun back(): Boolean = true
        override fun submit(): Boolean = true
        override fun enterText(x: Int, y: Int, text: String): Boolean {
            entered.add((x to y) to text)
            return true
        }
    }

    /** A fabricated capture: the box arms need a frame, never a device (VisionCapture's JVM seam). */
    private class FakeCapture(private val shot: VisionCapture.ScreenShot) : VisionCapture(null) {
        override suspend fun capture(exclusion: VisionMath.RectPx?): VisionCapture.ScreenShot = shot
    }

    private fun shot(width: Int = 1000, height: Int = 2000) = VisionCapture.ScreenShot(
        base64 = "QUJD",
        width = width,
        height = height,
        screenWidth = width,
        screenHeight = height,
        sha256 = "fixture",
        jpegBytes = byteArrayOf(1, 2, 3),
    )

    /** Chat-completions envelope around the writer's inner JSON content. */
    private fun chatContent(innerJson: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5) })
    }.toString()

    /** The GROUND answer: one Box for the only proposal (b0) + the pick-Choice choosing p0. */
    private fun jevGround(
        top: Double,
        bottom: Double,
        left: Double,
        right: Double,
        confidence: Double = 0.9,
    ): String = buildJsonObject {
        put("answers", buildJsonObject {
            put("b0", buildJsonObject {
                put("type", JsonPrimitive("box"))
                put("box", buildJsonObject {
                    put("top", JsonPrimitive(top))
                    put("bottom", JsonPrimitive(bottom))
                    put("left", JsonPrimitive(left))
                    put("right", JsonPrimitive(right))
                })
                put("confidence", JsonPrimitive(confidence))
            })
            put("pick", buildJsonObject {
                put("type", JsonPrimitive("choice"))
                put("choice", JsonPrimitive("p0"))
                put("probabilities", buildJsonObject {
                    put("p0", JsonPrimitive(0.9))
                    put("none", JsonPrimitive(0.1))
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

    private fun node(
        id: String, left: Int, top: Int, right: Int, bottom: Int,
        clickable: Boolean = true, editable: Boolean = false, text: String = "button",
    ) = ObservedNode(
        id = id, text = text, desc = "", clickable = clickable, editable = editable,
        scrollable = false, left = left, top = top, right = right, bottom = bottom,
        className = "android.widget.Button", sourceOrder = 0,
    )

    private fun observation(vararg nodes: ObservedNode, line: String = "some page") = StepObservation(
        url = "https://booking.test/search",
        title = "Booking",
        lines = listOf(line),
        nodes = nodes.toList(),
    )

    private fun doneObservation() = StepObservation(
        url = "https://done.test/finished",
        title = "Done",
        lines = listOf("the task is done"),
        nodes = emptyList(),
    )

    private val plan = """
        {"summary":"act on the page","taskClass":"action","subtasks":[
          {"instruction":"act on the page","done":{"urlHost":"done.test"}}]}
    """.trimIndent()

    private val confirmTrue = """{"complete":true,"reason":"the outcome is visible"}"""

    /** The single-subtask loop over [transport] with the fabricated capture. */
    private fun loop(
        transport: ScriptedVisionTransport,
        executor: RecordingExecutor,
        observations: List<StepObservation>,
        evidence: VisionEvidence,
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

    // -- the ladder -----------------------------------------------------------------------

    @Test
    fun `a confident jev box that overlaps nothing falls back to the haiku box node centre`() = runBlocking {
        // The live defect's exact shape: the Jev box is confidence-high (0.9) but its
        // rectangle (600,1200..700,1300) overlaps NOTHING (IoU 0.00). The Haiku proposal
        // box covers the a11y node (IoU 1.00), so arm (b) grounds and the tap must land
        // on the NODE centre — not the Jev rectangle's centre.
        val searchNode = node("n1", left = 100, top = 400, right = 300, bottom = 500, text = "Search")
        val proposal = """
            {"proposals":[{"description":"the 'Search' button","action":"tap",
              "box":{"top":0.20,"bottom":0.25,"left":0.10,"right":0.30},"confidence":0.9}]}
        """.trimIndent()
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(proposal), chatContent(confirmTrue))),
            jevResponses = ArrayDeque(
                listOf(
                    jevGround(top = 0.60, bottom = 0.65, left = 0.60, right = 0.70),
                    jevNoul(0.9),
                ),
            ),
        )
        val executor = RecordingExecutor()
        val evidence = VisionEvidence(null, "haiku-box-fallback")

        val result = loop(
            transport, executor,
            listOf(observation(searchNode, line = "search page"), observation(searchNode, line = "search page 2"), doneObservation()),
            evidence,
        ).run("tap the search control", "haiku-box-fallback") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(listOf(200 to 450), executor.taps, "the tap lands on the Haiku-grounded node centre")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("haiku-box-fallback" in stepLine, "the step line names the arm that grounded: $stepLine")
        assertTrue("iou=1.00" in stepLine, "the FINAL arm's IoU rides the line: $stepLine")
        assertEquals(1, evidence.grounding.size(), "ONE ledger attempt per step — the final arm")
        assertEquals("1.0", evidence.grounding.summarize()["hitRate"]?.jsonPrimitive?.content)
    }

    @Test
    fun `when both boxes miss a label match grounds the step and names the arm`() = runBlocking {
        // Both rectangles are disjoint from the node; only the description's label
        // ("Search" in the node text) can ground the control.
        val searchNode = node("n1", left = 100, top = 400, right = 300, bottom = 500, text = "Search")
        val proposal = """
            {"proposals":[{"description":"the 'Search' button","action":"tap",
              "box":{"top":0.60,"bottom":0.65,"left":0.60,"right":0.70},"confidence":0.8}]}
        """.trimIndent()
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(proposal), chatContent(confirmTrue))),
            jevResponses = ArrayDeque(
                listOf(
                    jevGround(top = 0.60, bottom = 0.65, left = 0.60, right = 0.70),
                    jevNoul(0.9),
                ),
            ),
        )
        val executor = RecordingExecutor()
        val evidence = VisionEvidence(null, "label-match")

        val result = loop(
            transport, executor,
            listOf(observation(searchNode, line = "search page"), observation(searchNode, line = "search page 2"), doneObservation()),
            evidence,
        ).run("tap the search control", "label-match") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(listOf(200 to 450), executor.taps, "the label-matched node's centre is the tap")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("label-match" in stepLine, "the step line names the label arm: $stepLine")
        assertEquals(1, evidence.grounding.size(), "the final (label) arm is the one recorded")
    }

    @Test
    fun `a type whose arms all miss fails loudly - no doomed enterText`() = runBlocking {
        // The NO_FIELD_AT_POINT churn: no box reaches PARTIAL, the description matches no
        // node, and the raw rectangle's centre (650,1250) lands on no editable field.
        // The step must FAIL with the loud cause and never call enterText.
        val inertNode = node("n1", left = 800, top = 100, right = 900, bottom = 160, text = "Home")
        val proposal = """
            {"proposals":[{"description":"the address bar at the top of the screen","action":"type",
              "text":"hotels in Riga next weekend",
              "box":{"top":0.60,"bottom":0.65,"left":0.60,"right":0.70},"confidence":0.8}]}
        """.trimIndent()
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(proposal))),
            jevResponses = ArrayDeque(
                listOf(jevGround(top = 0.60, bottom = 0.65, left = 0.60, right = 0.70)),
            ),
        )
        val executor = RecordingExecutor()
        val evidence = VisionEvidence(null, "type-refused")

        val result = loop(
            transport, executor,
            listOf(observation(inertNode, line = "home page"), observation(inertNode, line = "home page 2")),
            evidence,
        ).run("search hotels in Riga next weekend", "type-refused") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(executor.entered.isEmpty(), "no enterText may fire at a non-editable spot")
        assertTrue(executor.taps.isEmpty(), "the refused step executes nothing")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("FAILED" in stepLine, "the step is a failure, not a churn: $stepLine")
        assertTrue("no editable field at the raw point (650,1250)" in stepLine, "the cause is loud: $stepLine")
        assertTrue("NO_FIELD_AT_POINT" in stepLine, "the cause class rides the line: $stepLine")
    }
}
