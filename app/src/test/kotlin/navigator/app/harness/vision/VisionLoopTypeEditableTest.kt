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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The SIXTH actual-app live-test repair's JVM gates (2026-10-19,
 * `task-1792398381736`, $0 — fully scripted transport, fabricated capture, no device
 * and no provider call): the EDITABLE-NODE RESOLUTION before a TYPE executes.
 *
 * The defect: the label arm grounded node n3 (the address-bar CONTAINER) at IoU 1.00,
 * the loop called `enterText` at the container's centre, and `GestureExecutor`'s fresh
 * walk found no EDITABLE node there (the real EditText is a CHILD with different
 * bounds) — `causes=[NO_FIELD_AT_POINT]` every step. The law now: before a TYPE (native
 * or tap-converted) whose grounded node is NOT editable, resolve the actual field —
 * the editable containing the ground point, else the nearest editable (containment,
 * then max IoU >= 0.1) — and type at ITS centre, the step line carrying
 * `(editable: n<id>)`. No resolution = the loud refusal naming the ground node. A
 * grounded EDITABLE node keeps today's behaviour byte-identically.
 */
class VisionLoopTypeEditableTest {

    // -- scripted edges (the VisionLoopEnterTextTest shapes) ------------------------------

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

    private val plan = """
        {"summary":"search hotels","taskClass":"action","subtasks":[
          {"instruction":"enter the hotel search into the address bar",
           "done":{"urlHost":"done.test"}}]}
    """.trimIndent()

    private val confirmTrue = """{"complete":true,"reason":"the outcome is visible"}"""

    /** The live defect's shape: both boxes land far from the bar, so the LABEL arm grounds. */
    private val typeAddressBarMiss = """
        {"proposals":[{"description":"the address bar at the top of the screen","action":"type",
          "text":"hotels in Riga",
          "box":{"top":0.60,"bottom":0.65,"left":0.60,"right":0.70},"confidence":0.9}]}
    """.trimIndent()

    /** The Jev box covers the bar exactly (100..900 x 40..100 of 1000x2000). */
    private val typeAddressBarHit = """
        {"proposals":[{"description":"the address bar at the top of the screen","action":"type",
          "text":"hotels in Riga",
          "box":{"top":0.02,"bottom":0.05,"left":0.10,"right":0.90},"confidence":0.9}]}
    """.trimIndent()

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

    // -- the resolution -------------------------------------------------------------------

    @Test
    fun `a type grounded on a container resolves the editable child - the child centre`() = runBlocking {
        // The live defect's exact shape: the label arm grounds the address-bar CONTAINER
        // (n3, IoU 1.00) and the real EditText is a CHILD (n5) whose bounds do NOT contain
        // the container's centre. enterText must land on the CHILD's centre, and the step
        // line must name the resolved field.
        val container = node(
            "n3", left = 100, top = 40, right = 900, bottom = 100,
            clickable = true, editable = false, text = "Address bar",
        )
        val editableChild = node(
            "n5", left = 200, top = 50, right = 400, bottom = 90,
            clickable = false, editable = true, text = "",
        )
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(typeAddressBarMiss), chatContent(confirmTrue))),
            jevResponses = ArrayDeque(
                listOf(
                    jevGround(top = 0.60, bottom = 0.65, left = 0.60, right = 0.70),
                    jevNoul(0.9),
                ),
            ),
        )
        val executor = RecordingExecutor()
        val evidence = VisionEvidence(null, "type-editable-child")
        val page = listOf(container, editableChild)

        val result = loop(
            transport, executor,
            listOf(observation(page), observation(page), doneObservation()),
            evidence,
        ).run("open hotels.test and search hotels in Riga", "type-editable-child") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(
            listOf((300 to 70) to "hotels in Riga"),
            executor.entered,
            "enterText lands on the EDITABLE CHILD's centre (300,70), not the container's (500,70)",
        )
        assertTrue(executor.taps.isEmpty(), "a native TYPE performs no focus tap of its own")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue(
            stepLine.startsWith("step 0: type \"hotels in Riga\" into [node n3 iou=1.00 (label-match)"),
            "the ground target's arm stays truthful: $stepLine",
        )
        assertTrue("(editable: n5)" in stepLine, "the resolved child rides the line: $stepLine")
    }

    @Test
    fun `a type grounded on a container with no editable fails loudly - zero enterText`() = runBlocking {
        // No editable node exists anywhere: the repair must refuse BEFORE calling
        // enterText, naming the ground node and the cause class.
        val container = node(
            "n3", left = 100, top = 40, right = 900, bottom = 100,
            clickable = true, editable = false, text = "Address bar",
        )
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(typeAddressBarMiss))),
            jevResponses = ArrayDeque(
                listOf(jevGround(top = 0.60, bottom = 0.65, left = 0.60, right = 0.70)),
            ),
        )
        val executor = RecordingExecutor()
        val evidence = VisionEvidence(null, "type-editable-absent")

        val result = loop(
            transport, executor,
            listOf(observation(listOf(container)), observation(listOf(container))),
            evidence,
        ).run("open hotels.test and search hotels in Riga", "type-editable-absent") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(executor.entered.isEmpty(), "no enterText may fire without a resolved editable")
        assertTrue(executor.taps.isEmpty(), "the refused step executes nothing")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("FAILED" in stepLine, "the step is a failure, not a churn: $stepLine")
        assertTrue("ground node n3" in stepLine, "the cause names the ground node: $stepLine")
        assertTrue("NO_FIELD_AT_POINT" in stepLine, "the cause class rides the line: $stepLine")
    }

    @Test
    fun `a native type on a grounded editable node is unchanged - no editable marker`() = runBlocking {
        // The pre-repair shape: the Jev box grounds an EDITABLE node at IoU 1.00. The
        // resolution must not touch it — same node, same centre, no "(editable: …)".
        val addressBar = node(
            "n4", left = 100, top = 40, right = 900, bottom = 100,
            clickable = false, editable = true, text = "Address bar",
        )
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(typeAddressBarHit), chatContent(confirmTrue))),
            jevResponses = ArrayDeque(
                listOf(
                    jevGround(top = 0.02, bottom = 0.05, left = 0.10, right = 0.90),
                    jevNoul(0.9),
                ),
            ),
        )
        val executor = RecordingExecutor()
        val evidence = VisionEvidence(null, "type-editable-native")
        val page = listOf(addressBar)

        val result = loop(
            transport, executor,
            listOf(observation(page), observation(page), doneObservation()),
            evidence,
        ).run("open hotels.test and search hotels in Riga", "type-editable-native") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(
            listOf((500 to 70) to "hotels in Riga"),
            executor.entered,
            "the grounded editable node's own centre stays the type point",
        )
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("(jev-box)" in stepLine, "the Jev box grounded the editable node: $stepLine")
        assertFalse("(editable:" in stepLine, "no editable marker when the ground node is already editable: $stepLine")
    }

    // -- the FIELD read-back (PHASE_17 Stage 0.4) -----------------------------------------

    private val typeEtunimi = """
        {"proposals":[{"description":"the Etunimi field under the address form","action":"type",
          "text":"Rune Lauritzen",
          "box":{"top":0.02,"bottom":0.05,"left":0.10,"right":0.90},"confidence":0.9}]}
    """.trimIndent()

    /** A checkout-style page whose single editable field carries [value]. */
    private fun etunimiPage(value: String): StepObservation {
        val label = node(
            "n0", left = 100, top = 0, right = 900, bottom = 30,
            clickable = false, editable = false, text = "Etunimi *",
        )
        val field = node(
            "n1", left = 100, top = 40, right = 900, bottom = 100,
            clickable = false, editable = true, text = value,
        )
        return StepObservation(
            url = "https://cdon.fi/kassa",
            title = "Kassa | CDON",
            lines = listOf("Etunimi *", "n1 \"$value\""),
            nodes = listOf(label, field),
            windowPackage = "com.android.chrome",
        )
    }

    @Test
    fun `a type whose text never lands is named on the SAME field - and feeds the failure path`() =
        runBlocking {
            val transport = ScriptedVisionTransport(
                chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(typeEtunimi))),
                jevResponses = ArrayDeque(
                    listOf(jevGround(top = 0.02, bottom = 0.05, left = 0.10, right = 0.90), jevNoul(0.9)),
                ),
            )
            val executor = RecordingExecutor()
            val base = java.nio.file.Files.createTempDirectory("vexp17-readback").toFile()
            val evidence = VisionEvidence(base, "readback-loss")

            val result = loop(
                transport, executor,
                // initial frame, step 0's frame, then the step-1 frame the read-back reads.
                listOf(etunimiPage(""), etunimiPage(""), etunimiPage("")),
                evidence,
            ).run("fill the delivery name on cdon.fi", "readback-loss") { false }

            assertEquals(listOf((500 to 70) to "Rune Lauritzen"), executor.entered)
            val events = File(base, "readback-loss/events.log").readText()
            assertTrue(
                "field read-back: 'Etunimi *' is STILL EMPTY after typing" in events,
                "the read-back names the FIELD, not the page: $events",
            )
            assertEquals(
                VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal,
                "the lost text feeds the existing failure path: ${result.subtaskOutcomes}",
            )
        }

    @Test
    fun `a type whose text lands is recorded as landed on the same field`() = runBlocking {
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(chatContent(plan), chatContent(typeEtunimi), chatContent("""{"proposals":[]}""")),
            ),
            jevResponses = ArrayDeque(
                listOf(jevGround(top = 0.02, bottom = 0.05, left = 0.10, right = 0.90), jevNoul(0.9)),
            ),
        )
        val executor = RecordingExecutor()
        val base = java.nio.file.Files.createTempDirectory("vexp17-readback-ok").toFile()
        val evidence = VisionEvidence(base, "readback-ok")

        val result = loop(
            transport, executor,
            listOf(etunimiPage(""), etunimiPage(""), etunimiPage("Rune Lauritzen")),
            evidence,
        ).run("fill the delivery name on cdon.fi", "readback-ok") { false }

        val events = File(base, "readback-ok/events.log").readText()
        assertTrue(
            "field read-back: 'Etunimi *' carries the typed text" in events,
            "the landed verdict is recorded: $events",
        )
        assertEquals(
            VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal,
            "the fixture's condition still asks for done.test: ${result.subtaskOutcomes}",
        )
    }

    // -- the pure helper ------------------------------------------------------------------

    @Test
    fun `nearestEditableTo prefers the smallest containing field then the strongest overlap`() {
        val target = node(
            "t", left = 100, top = 40, right = 900, bottom = 100,
            clickable = true, editable = false, text = "Address bar",
        )
        val outer = node("outer", left = 0, top = 0, right = 1000, bottom = 200, editable = true)
        val inner = node("inner", left = 300, top = 50, right = 700, bottom = 90, editable = true)
        assertEquals(
            "inner",
            VisionMath.nearestEditableTo(listOf(target, outer, inner), target)?.id,
            "containment wins, and the smallest containing field is the most specific",
        )

        // No editable contains the target centre (500,70) — the IoU fallback picks the
        // child inside the target's rect (8000 / 48000 = 0.17 >= 0.1).
        val child = node("child", left = 200, top = 50, right = 400, bottom = 90, editable = true)
        assertEquals(
            "child",
            VisionMath.nearestEditableTo(listOf(target, child), target)?.id,
            "the maximum-IoU editable at/above the 0.1 floor resolves the child",
        )

        // A sliver overlaps far below the floor (1200 / 48000 = 0.025): no resolution.
        val sliver = node("sliver", left = 100, top = 40, right = 120, bottom = 100, editable = true)
        assertNull(
            VisionMath.nearestEditableTo(listOf(target, sliver), target),
            "an overlap below the 0.1 floor is not a resolution",
        )
    }
}
