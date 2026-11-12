package navigator.app.harness.vision

import kotlinx.coroutines.runBlocking
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The FIFTH actual-app live-test repair's JVM gates (2026-10-19,
 * `task-1792397809506`, $0 — fully scripted transport, fabricated capture, no device
 * and no provider call): the plan's per-subtask `enterText` and the TAP→TYPE
 * conversion in [VisionLoop].
 *
 * The defect: with the grounding ladder the address bar grounded at IoU 1.00 (label
 * match) and the loop TAPPED it five times in a row, never typing. A tap on an empty
 * editable field achieves nothing. The law now: when the chosen proposal is a TAP, its
 * grounded target is EDITABLE, and the active subtask carries a non-blank plan
 * `enterText`, the action becomes a TYPE with that text — the tap executes first to
 * focus the field, then `enterText` at the node centre ("(tap-converted)" on the step
 * line). Every other shape keeps the pre-repair behavior byte-identical.
 */
class VisionLoopEnterTextTest {

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
        val nodeClicks = mutableListOf<Pair<Int, Int>>()
        val entered = mutableListOf<Pair<Pair<Int, Int>, String>>()
        override fun tap(x: Int, y: Int): Boolean { taps.add(x to y); return true }
        override fun clickNode(x: Int, y: Int): Boolean { nodeClicks.add(x to y); return true }
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean = true
        override fun back(): Boolean = true
        override fun submit(): Boolean = true
        override fun enterText(x: Int, y: Int, text: String): Boolean {
            entered.add((x to y) to text)
            return true
        }
    }

    /** A fabricated capture: the grounding arms need a frame, never a device. */
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
        clickable: Boolean = true, editable: Boolean = false, text: String = "control",
    ) = ObservedNode(
        id = id, text = text, desc = "", clickable = clickable, editable = editable,
        scrollable = false, left = left, top = top, right = right, bottom = bottom,
        className = "android.widget.EditText", sourceOrder = 0,
    )

    private fun observation(nodes: List<ObservedNode>) = StepObservation(
        url = "https://booking.test/search",
        title = "Booking",
        lines = listOf("some page"),
        nodes = nodes,
    )

    private fun doneObservation() = StepObservation(
        url = "https://done.test/finished",
        title = "Done",
        lines = listOf("the task is done"),
        nodes = emptyList(),
    )

    /** The GROUND box covers the address-bar node exactly (100..900 x 40..100 of 1000x2000). */
    private val tapAddressBar = """
        {"proposals":[{"description":"the address bar at the top of the screen","action":"tap","expectedEffect":{"expect":"TargetStateChanged"},
          "box":{"top":0.02,"bottom":0.05,"left":0.10,"right":0.90},"confidence":0.9}]}
    """.trimIndent()

    private fun plan(enterText: String?): String {
        val field = enterText?.let { "\"enterText\":\"$it\"," } ?: ""
        return """
            {"summary":"search hotels","taskClass":"action","subtasks":[
              {"instruction":"enter the hotel search into the address bar",$field
               "done":{"urlHost":"done.test"}}]}
        """.trimIndent()
    }

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

    /** The standard script: plan → proposal → confirm; ground → noul. */
    private fun standardTransport(plan: String): ScriptedVisionTransport = ScriptedVisionTransport(
        chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(tapAddressBar), chatContent(confirmTrue))),
        jevResponses = ArrayDeque(
            listOf(
                jevGround(top = 0.02, bottom = 0.05, left = 0.10, right = 0.90),
                jevNoul(0.9),
            ),
        ),
    )

    // -- the conversion -------------------------------------------------------------------

    @Test
    fun `a tap on a grounded editable field types the plan enterText - tap-converted`() = runBlocking {
        // The live defect's exact shape: the address bar grounds (here the Jev box covers
        // it at IoU 1.00) and the proposer says tap. The plan carries the query, so the
        // step must become a TYPE: the focus tap first, then enterText with that text.
        val addressBar = node(
            "n4", left = 100, top = 40, right = 900, bottom = 100,
            editable = true, text = "Address bar",
        )
        val executor = RecordingExecutor()
        val evidence = VisionEvidence(null, "enter-text-converted")

        val result = loop(
            standardTransport(plan(enterText = "hotels in Riga next weekend")),
            executor,
            listOf(observation(listOf(addressBar)), observation(listOf(addressBar)), doneObservation()),
            evidence,
        ).run("open hotels.test and search hotels in Riga next weekend", "enter-text-converted") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(
            listOf((500 to 70) to "hotels in Riga next weekend"),
            executor.entered,
            "the plan's enterText is typed at the grounded node centre",
        )
        assertEquals(
            listOf(500 to 70),
            executor.taps,
            "the TAP still executes ONCE first, to focus the field",
        )
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue(
            stepLine.startsWith("step 0: type \"hotels in Riga next weekend\" into"),
            "the step line narrates the conversion: $stepLine",
        )
        assertTrue("(tap-converted)" in stepLine, "the conversion marker rides the line: $stepLine")
        assertFalse(stepLine.contains("tap '"), "the proposal no longer narrates as a bare tap: $stepLine")
    }

    @Test
    fun `a tap on a grounded NON-editable node keeps today's tap - no conversion`() = runBlocking {
        // The plan names enterText, but this grounded control is a button: the conversion
        // law requires an EDITABLE node, so the step stays a bare tap and types nothing.
        val searchButton = node(
            "n2", left = 100, top = 40, right = 900, bottom = 100,
            editable = false, text = "Search",
        )
        val executor = RecordingExecutor()
        val evidence = VisionEvidence(null, "enter-text-uneditable")

        val result = loop(
            standardTransport(plan(enterText = "hotels in Riga next weekend")),
            executor,
            listOf(observation(listOf(searchButton)), observation(listOf(searchButton)), doneObservation()),
            evidence,
        ).run("open shop.test and tap the search button", "enter-text-uneditable") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertTrue(executor.entered.isEmpty(), "a non-editable target is never typed into")
        assertEquals(listOf(500 to 70), executor.taps)
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue(stepLine.startsWith("step 0: tap"), "the action stays a tap: $stepLine")
        assertFalse("tap-converted" in stepLine, "no conversion marker without an editable target: $stepLine")
    }

    @Test
    fun `an editable tap without a plan enterText keeps today's tap - no conversion`() = runBlocking {
        // The editable target alone is not enough: the ACTIVE subtask must carry a
        // non-blank plan enterText. Absent = the pre-repair tap, byte-identical.
        val addressBar = node(
            "n4", left = 100, top = 40, right = 900, bottom = 100,
            editable = true, text = "Address bar",
        )
        val executor = RecordingExecutor()
        val evidence = VisionEvidence(null, "enter-text-absent")

        val result = loop(
            standardTransport(plan(enterText = null)),
            executor,
            listOf(observation(listOf(addressBar)), observation(listOf(addressBar)), doneObservation()),
            evidence,
        ).run("open shop.test and focus the address bar", "enter-text-absent") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertTrue(executor.entered.isEmpty(), "no plan text means no typing")
        assertEquals(listOf(500 to 70), executor.taps)
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue(stepLine.startsWith("step 0: tap"), "the action stays a tap: $stepLine")
        assertFalse("tap-converted" in stepLine, "no conversion without plan text: $stepLine")
    }
}
