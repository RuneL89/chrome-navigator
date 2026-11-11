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
 * PHASE 18 STAGE 18.A — CHARACTERIZATION, UPDATED by Stage 18.B CN-10 (doc 04 §9.8
 * ratified; the suite re-pinned consciously).
 *
 * TWO capture regimes now live side by side:
 *
 * 1. THE NULL-SEAM LEGACY (snapshotter absent — the scripted/JVM paths): a capture
 *    failure degrades the step to TEXT-ONLY, loudly, and the run PROCEEDS — no
 *    consecutive-failure ceiling applies (the bound is a PRODUCT-path mechanism; the
 *    scripted seam stays byte-identical legacy, per the Stage 18.B part-1 promise).
 *    The first test pins exactly that.
 *
 * 2. THE WIRED PRODUCT PATH (snapshotter present — CN-10, doc 04 §9.8): consecutive
 *    capture failures are counted, ONE bounded re-capture runs on the first degraded
 *    frame, and past [VisionLoop.Config.captureFailureLimit] (2) the run ends honestly
 *    (GUARD_ABORT with the capture cause) — no unbounded text-only degrade, no
 *    fabricated frame. The wired tests pin that.
 *
 * Vision law: Project Vision/04 §9.8 (perception degradation — implemented) and doc
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

        /** The CURRENT observation (the test identity reader reads this). */
        fun current(): StepObservation = last
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

    /** Fails the FIRST capture, then succeeds — the bounded re-capture's reset case. */
    private class FirstCallBrokenCapture : VisionCapture(null) {
        var calls = 0
        override suspend fun capture(exclusion: VisionMath.RectPx?): VisionCapture.ScreenShot {
            calls += 1
            if (calls == 1) throw IllegalStateException("fixture: first capture unavailable")
            return VisionCapture.ScreenShot(
                base64 = "ZmFrZQ==", width = 100, height = 200,
                screenWidth = 1080, screenHeight = 2400, sha256 = "fixture2",
                jpegBytes = byteArrayOf(1, 2, 3),
            )
        }
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

    private fun searchObservation(url: String) = StepObservation(
        url = url, title = "Page",
        lines = listOf("search page"), nodes = listOf(
            ObservedNode(
                id = "n1", text = "Search", desc = "", clickable = true, editable = false,
                scrollable = false, left = 100, top = 400, right = 300, bottom = 500,
                className = "android.widget.Button", sourceOrder = 0,
            ),
        ),
        windowPackage = "com.android.chrome",
    )

    // -- 1. the null-seam LEGACY pin (byte-identical; the bound does not apply) ----------

    @Test
    fun `a capture failure degrades the step to text-only and the run proceeds`() = runBlocking {
        // Text-only grounding: the proposer names the control by label (no box — no
        // screenshot rode along), the label match grounds it on the a11y tree. The
        // snapshotter is NOT wired (the scripted seam) — the CN-10 bound does not apply.
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
                searchObservation("https://capture-fail.test/page"),
                searchObservation("https://capture-fail.test/page2"),
                searchObservation("https://done.test/finished"),
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

        // NULL-SEAM LEGACY: the capture failure never kills the run — the text-only
        // step proceeds (the label match grounds it) and the run completes.
        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal, result.subtaskOutcomes.toString())
        assertEquals(listOf(200 to 450), executor.taps, "the label-matched node's centre is the tap")
        val log = File(base, "capture-degrade/events.log").readText()
        assertTrue("capture degraded" in log, "the capture failure is logged loudly: ${log.take(1500)}")
        assertTrue("TEXT-ONLY step" in log, "the degrade is a text-only step, said out loud: ${log.take(1500)}")
        assertTrue(
            result.subtaskOutcomes.none { "FAILED" in it },
            "the degraded step is not a failure: ${result.subtaskOutcomes}",
        )
    }

    // -- 2. the WIRED product path (CN-10): the bounded capture failures ------------------

    private fun wiredLoop(
        capture: VisionCapture,
        executor: RecordingExecutor,
        evidence: VisionEvidence,
        chat: List<String>,
    ): VisionLoop {
        val plan = """
            {"summary":"tap the search control on capture-fail.test","taskClass":"action","subtasks":[
              {"instruction":"tap the search control","done":{"urlHost":"done.test"}}]}
        """.trimIndent()
        val proposal = """
            {"proposals":[{"description":"the 'Search' button","action":"tap","confidence":0.9}]}
        """.trimIndent()
        val transport = ScriptedTransport(
            chatResponses = listOf(chatContent(plan)) + chat.map { chatContent(it) },
            jevResponses = listOf(jevNoul(0.9)),
        )
        val observer = RepeatingObserver(
            searchObservation("https://capture-fail.test/page"),
            searchObservation("https://capture-fail.test/page2"),
            searchObservation("https://done.test/finished"),
        )
        val snapshotter = VisionSnapshotter(
            observer = observer,
            identity = VisionSnapshotter.IdentityReader {
                val cur = observer.current()
                navigator.app.harness.vision.SnapshotIdentity(cur.windowPackage, cur.url)
            },
            runId = "capture-bound",
            now = { 1_000L },
        )
        return VisionLoop(
            observer = observer,
            executor = executor,
            capture = capture,
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(
                transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                backoffBaseMs = 1, retryDelay = {},
            ),
            budget = ExpBudget(),
            evidence = evidence,
            config = VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1),
            snapshotter = snapshotter,
            preDispatchGuard = null,
        )
    }

    @Test
    fun `wired - two consecutive capture failures end the run honestly (GUARD_ABORT)`() = runBlocking {
        val executor = RecordingExecutor()
        val base = Files.createTempDirectory("p18-cap-bound").toFile()
        val evidence = VisionEvidence(base, "capture-bound")
        var iterations = 0
        val loop = wiredLoop(BrokenCapture(), executor, evidence, chat = listOf())
        val result = loop.run("tap the search control on capture-fail.test", "capture-bound") {
            ++iterations > 10
        }

        // CN-10: the run-start capture (failure #1), the per-step capture + its ONE
        // bounded re-capture (failures #2 and #3) — past the limit of 2 the run ends
        // GUARD_ABORT with the capture cause in the evidence, ZERO executor calls.
        assertEquals(VisionLoop.Terminal.GUARD_ABORT, result.terminal, result.subtaskOutcomes.toString())
        assertTrue(executor.taps.isEmpty(), "no dispatch may ride a degraded capture: ${executor.taps}")
        val log = File(base, "capture-bound/events.log").readText()
        assertTrue(
            "capture unavailable past the bounded limit" in log,
            "the honest terminal cause is in the evidence: ${log.take(2000)}",
        )
    }

    @Test
    fun `wired - one bounded re-capture on the first degraded frame resets the counter`() = runBlocking {
        // FirstCallBrokenCapture: capture #1 fails (counter 1), the bounded re-capture
        // 2 succeeds (counter resets 0) — the step proceeds WITH the recovered frame.
        val capture = FirstCallBrokenCapture()
        val executor = RecordingExecutor()
        val base = Files.createTempDirectory("p18-cap-rec").toFile()
        val evidence = VisionEvidence(base, "capture-rec")
        var iterations = 0
        val loop = wiredLoop(capture, executor, evidence, chat = listOf(proposal2(), confirmTrue))
        val result = loop.run("tap the search control on capture-fail.test", "capture-rec") {
            ++iterations > 10
        }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal, result.subtaskOutcomes.toString())
        assertTrue(capture.calls >= 2, "the initial capture + the ONE bounded re-capture ran: ${capture.calls}")
        val log2 = File(base, "capture-rec/events.log").readText()
        assertEquals(
            1,
            Regex("capture degraded").findAll(log2).count(),
            "exactly ONE degraded capture (the bounded re-capture recovered — the counter reset)",
        )
    }

    private fun proposal2(): String =
        """{"proposals":[{"description":"the 'Search' button","action":"tap","confidence":0.9}]}"""
}
