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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PHASE_VEXP6 WI4 — the TERMINAL EVIDENCE wrapper's JVM gate ($0, scripted transport,
 * fabricated capture): every [VisionLoop.run] return writes exactly ONE final
 * `events.log` line `terminal=<TERMINAL> steps=<n> replans=<n>` into the run's evidence
 * directory. The run-4 monitoring gap (`.state/vision-iter/run4/REPORT.md`): the owner's
 * `CANCELLED — by owner` terminal existed only in the corner log and a navcap frame —
 * the evidence dir stopped at the last re-plan, so a pullable record had no terminal.
 */
class VisionTerminalEvidenceTest {

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
        override fun tap(x: Int, y: Int): Boolean { taps.add(x to y); return true }
        override fun clickNode(x: Int, y: Int): Boolean = true
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean = true
        override fun back(): Boolean = true
        override fun submit(): Boolean = true
        override fun enterText(x: Int, y: Int, text: String): Boolean = true
    }

    /** A fabricated capture: the grounding arms need a frame, never a device. */
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

    private fun node(id: String, left: Int, top: Int, right: Int, bottom: Int) = ObservedNode(
        id = id, text = "Go", desc = "", clickable = true, editable = false,
        scrollable = false, left = left, top = top, right = right, bottom = bottom,
        className = "android.widget.Button", sourceOrder = 0,
    )

    private fun observationAt(url: String, nodes: List<ObservedNode> = emptyList()) = StepObservation(
        url = url,
        title = "Page",
        lines = listOf("some page"),
        nodes = nodes,
    )

    private fun loop(
        transport: ScriptedVisionTransport,
        observations: List<StepObservation>,
        evidence: VisionEvidence,
    ): VisionLoop = VisionLoop(
        observer = ScriptedObserver(*observations.toTypedArray()),
        executor = RecordingExecutor(),
        capture = FakeCapture(shot()),
        haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
        jev = VisionJev(
            transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
            backoffBaseMs = 1, retryDelay = {},
        ),
        budget = ExpBudget(),
        evidence = evidence,
        config = VisionLoop.Config(settleMs = 0, maxReplans = 0, planAttempts = 1, stepFailuresBeforeReplan = 1),
    )

    private fun tempEvidence(runId: String): Pair<VisionEvidence, File> {
        val base = Files.createTempDirectory("vexp6-terminal").toFile()
        return VisionEvidence(base, runId) to File(base, "$runId/events.log")
    }

    // -- the terminal line -----------------------------------------------------------------

    @Test
    fun `a completed run writes terminal GOAL_ACHIEVED with its steps and replans`() = runBlocking {
        val plan = """
            {"summary":"open the example page","taskClass":"read","subtasks":[
              {"instruction":"open the example page","done":{"urlHost":"next.test"}}]}
        """.trimIndent()
        val propose = """{"proposals":[{"description":"the 'Go' button","action":"tap"}]}"""
        val confirm = """{"complete":true,"reason":"the example page is visible"}"""
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(propose), chatContent(confirm))),
            jevResponses = ArrayDeque(listOf(jevNoul(0.9))),
        )
        val (evidence, eventsFile) = tempEvidence("terminal-achieved")

        val result = loop(
            transport,
            listOf(
                observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
                observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
                observationAt("https://next.test/page"),
            ),
            evidence,
        ).run("open the example page on next.test", "terminal-achieved") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        val lines = eventsFile.readText().lines().filter { it.isNotBlank() }
        val terminalLines = lines.filter { "terminal=" in it }
        assertEquals(1, terminalLines.size, "exactly ONE terminal line: $lines")
        val terminal = terminalLines.single()
        assertTrue(
            "terminal=GOAL_ACHIEVED steps=${result.stepsUsed} replans=0" in terminal,
            "the terminal line carries the terminal + the run's counting: $terminal",
        )
    }

    @Test
    fun `a cancelled run still writes its terminal line`() = runBlocking {
        // The run-4 shape: the owner stops the task and the evidence dir must still name
        // the terminal. Cancellation fires at the loop top, after the initial plan.
        val plan = """
            {"summary":"find hotels","taskClass":"read","subtasks":[
              {"instruction":"search hotels","done":{"urlHost":"next.test"}}]}
        """.trimIndent()
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan))),
            jevResponses = ArrayDeque(),
        )
        val (evidence, eventsFile) = tempEvidence("terminal-cancelled")

        val result = loop(
            transport,
            listOf(observationAt("https://old.test/")),
            evidence,
        ).run("find hotels", "terminal-cancelled") { true }

        assertEquals(VisionLoop.Terminal.CANCELLED, result.terminal)
        val terminal = eventsFile.readText().lines().filter { "terminal=" in it }.single()
        assertTrue(
            "terminal=CANCELLED steps=0 replans=0" in terminal,
            "the cancelled run's terminal rides the evidence: $terminal",
        )
    }
}
