package navigator.app.harness.vision

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

/**
 * PHASE 18 STAGE 18.A — CHARACTERIZATION (the refactor baseline; NOT a spec).
 *
 * Pins the CURRENT capture-degradation behavior: a capture failure degrades the step
 * to TEXT-ONLY, loudly, and the run PROCEEDS — there is no consecutive-failure
 * ceiling, no image-dependent-work disablement, and no handoff (the header law is
 * "a capture failure never kills a run"). Stage 18.B CN-10 bounds this: typed capture
 * statuses, a consecutive-failure ceiling per subtask/run, image-dependent work
 * disabled while capture is unavailable. When that lands, this suite is the
 * before-picture it must consciously break and re-pin.
 *
 * Vision law it will change: Project Vision/04 §9.8 (perception degradation) and doc
 * 05 §8 (capture status + degradation count in the record).
 */
class Phase18CaptureDegradationCharacterizationTest {

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

    /** The DEGRADED capture: every capture fails (the device seam refuses, loudly). */
    private class BrokenCapture : VisionCapture(null) {
        override suspend fun capture(exclusion: VisionMath.RectPx?): VisionCapture.ScreenShot =
            throw IllegalStateException("fixture: capture unavailable")
    }

    private fun chatContent(innerJson: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5) })
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

    private val confirmTrue = """{"complete":true,"reason":"the outcome is visible"}"""

    @Test
    fun `a capture failure degrades the step to text-only and the run proceeds`() = runBlocking {
        // Text-only grounding: the proposer names the control by label (no box — no
        // screenshot rode along), the label match grounds it on the a11y tree.
        val searchNode = ObservedNode(
            id = "n1", text = "Search", desc = "", clickable = true, editable = false,
            scrollable = false, left = 100, top = 400, right = 300, bottom = 500,
            className = "android.widget.Button", sourceOrder = 0,
        )
        val proposal = """
            {"proposals":[{"description":"the 'Search' button","action":"tap","confidence":0.9}]}
        """.trimIndent()
        val plan = """
            {"summary":"tap the search control on capture-fail.test","taskClass":"action","subtasks":[
              {"instruction":"tap the search control","done":{"urlHost":"done.test"}}]}
        """.trimIndent()
        val transport = ScriptedTransport(
            chatResponses = listOf(chatContent(plan), chatContent(proposal), chatContent(confirmTrue)),
            jevResponses = listOf(jevNoul(0.9)),
        )
        val executor = RecordingExecutor()
        val base = Files.createTempDirectory("p18-capture").toFile()
        val evidence = VisionEvidence(base, "capture-degrade")
        var iterations = 0
        val loop = VisionLoop(
            observer = RepeatingObserver(
                StepObservation(
                    url = "https://capture-fail.test/page", title = "Page",
                    lines = listOf("search page"), nodes = listOf(searchNode),
                    windowPackage = "com.android.chrome",
                ),
                StepObservation(
                    url = "https://capture-fail.test/page2", title = "Page",
                    lines = listOf("search page 2"), nodes = listOf(searchNode),
                    windowPackage = "com.android.chrome",
                ),
                StepObservation(
                    url = "https://done.test/finished", title = "Done",
                    lines = listOf("the task is done"), nodes = emptyList(),
                    windowPackage = "com.android.chrome",
                ),
            ),
            executor = executor,
            capture = BrokenCapture(),
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(
                transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                backoffBaseMs = 1, retryDelay = {},
            ),
            budget = ExpBudget(),
            evidence = evidence,
            config = VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1),
        )
        val result = loop.run("tap the search control on capture-fail.test", "capture-degrade") {
            ++iterations > 10
        }

        // CURRENT behavior: the capture failure never kills the run — the text-only
        // step proceeds (the label match grounds it) and the run completes.
        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal, result.subtaskOutcomes.toString())
        assertEquals(listOf(200 to 450), executor.taps, "the label-matched node's centre is the tap")
        val log = File(base, "capture-degrade/events.log").readText()
        assertTrue(
            "capture degraded" in log,
            "the capture failure is logged loudly: ${log.take(1500)}",
        )
        assertTrue(
            "TEXT-ONLY step" in log,
            "the degrade is a text-only step, said out loud: ${log.take(1500)}",
        )
        assertTrue(
            result.subtaskOutcomes.none { "FAILED" in it },
            "the degraded step is not a failure: ${result.subtaskOutcomes}",
        )
    }
}
