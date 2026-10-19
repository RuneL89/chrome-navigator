package navigator.app.harness.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.app.loop.ActionExecutor
import navigator.app.loop.LoopObserver
import navigator.app.loop.StepObservation
import navigator.app.setup.SetupLogic
import navigator.core.enumerate.ObservedNode
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport
import navigator.core.wire.WireTransportException

/**
 * PHASE_VEXP2 — the ENGINE SWITCH's JVM gates: the pure route decision the product
 * [navigator.app.overlay.NavigatorService] consults at its loop-construction block,
 * the bridge's construction (a LIVE VisionLoop over the caller's observer/executor,
 * proven by running a scripted completion through it — no Android), and the terminal
 * funnel's task-state line. Scripted-fake style follows [VisionExperimentTest].
 */
class NavigatorVisionBridgeTest {

    // -- ProductRunRoute.decide: the service's branch point --------------------------------

    @Test
    fun `untoggled routes Legacy regardless of the keys - the old path byte-identical`() {
        assertEquals(ProductRunRoute.Legacy, ProductRunRoute.decide(false, null, null))
        assertEquals(ProductRunRoute.Legacy, ProductRunRoute.decide(false, "or-key", null))
        assertEquals(ProductRunRoute.Legacy, ProductRunRoute.decide(false, null, "ts-key"))
        assertEquals(ProductRunRoute.Legacy, ProductRunRoute.decide(false, "or-key", "ts-key"))
    }

    @Test
    fun `toggled with both keys routes Vision carrying the store-resolved keys`() {
        val route = ProductRunRoute.decide(true, " or-key ", "ts-key")
        assertEquals(ProductRunRoute.Vision("or-key", "ts-key"), route)
        // The keys ride in memory only; the route's toString never renders them.
        assertFalse(route.toString().contains("or-key"))
        assertFalse(route.toString().contains("ts-key"))
        assertTrue(route.toString().contains("redacted"))
    }

    @Test
    fun `toggled with a missing channel refuses politely naming it and the recovery`() {
        val noTypesafe = ProductRunRoute.decide(true, "or-key", null)
        assertTrue(noTypesafe is ProductRunRoute.Refused)
        assertTrue(noTypesafe.detail.contains("TypeSafe"))
        assertTrue(noTypesafe.detail.contains("OpenRouter").not(), "only the missing channel is blamed")
        assertTrue(noTypesafe.detail.contains("setup"), "the recovery path is named")

        val noOpenRouter = ProductRunRoute.decide(true, null, "ts-key")
        assertTrue(noOpenRouter is ProductRunRoute.Refused)
        assertTrue(noOpenRouter.detail.contains("OpenRouter"))
        assertTrue(noOpenRouter.detail.contains("TypeSafe").not())
        assertTrue(noOpenRouter.detail.contains("setup"))

        val none = ProductRunRoute.decide(true, null, null)
        assertTrue(none is ProductRunRoute.Refused)
        assertTrue(none.detail.contains("BOTH"))
        assertTrue(none.detail.contains("OpenRouter") && none.detail.contains("TypeSafe"))

        // Blank is not a key (the store's own trim-to-null law mirrored here).
        assertEquals(ProductRunRoute.decide(true, "  ", "ts-key"), noOpenRouter)
    }

    // -- PHASE_VEXP4, the default flip: the matrix pinned on the DEFAULT (the vision
    // loop IS the app's engine — the absent-key fallback every preference reader uses) --

    @Test
    fun `the DEFAULT with both keys routes Vision - the vision loop is the app's engine`() {
        // The default is the SetupLogic constant every reader falls back to when the
        // pref key is ABSENT — and it is ON.
        assertTrue(SetupLogic.VISION_LOOP_DEFAULT)
        val route = ProductRunRoute.decide(SetupLogic.VISION_LOOP_DEFAULT, "or-key", "ts-key")
        assertEquals(ProductRunRoute.Vision("or-key", "ts-key"), route)
    }

    @Test
    fun `the DEFAULT with a missing key refuses loudly naming the channel and the wizard - never Legacy`() {
        // Default + the TypeSafe key missing (the OpenRouter key present).
        val noTypesafe = ProductRunRoute.decide(SetupLogic.VISION_LOOP_DEFAULT, "or-key", null)
        assertTrue(noTypesafe is ProductRunRoute.Refused)
        assertTrue(noTypesafe.detail.contains("TypeSafe"), "the missing channel is named")
        assertTrue(noTypesafe.detail.contains("OpenRouter").not(), "only the missing channel is blamed")
        assertTrue(noTypesafe.detail.contains("setup"), "the wizard is named")

        // Default + the OpenRouter key missing (the TypeSafe key present).
        val noOpenRouter = ProductRunRoute.decide(SetupLogic.VISION_LOOP_DEFAULT, null, "ts-key")
        assertTrue(noOpenRouter is ProductRunRoute.Refused)
        assertTrue(noOpenRouter.detail.contains("OpenRouter"))
        assertTrue(noOpenRouter.detail.contains("TypeSafe").not())
        assertTrue(noOpenRouter.detail.contains("setup"))

        // Default + neither key stored names BOTH channels.
        val none = ProductRunRoute.decide(SetupLogic.VISION_LOOP_DEFAULT, null, null)
        assertTrue(none is ProductRunRoute.Refused)
        assertTrue(none.detail.contains("OpenRouter") && none.detail.contains("TypeSafe"))

        // The no-silent-fallback law: under the DEFAULT, a missing key NEVER routes
        // the legacy loop (fresh re-derivations, so the check is real).
        assertFalse(
            ProductRunRoute.decide(SetupLogic.VISION_LOOP_DEFAULT, "or-key", null)
                is ProductRunRoute.Legacy,
        )
        assertFalse(
            ProductRunRoute.decide(SetupLogic.VISION_LOOP_DEFAULT, null, "ts-key")
                is ProductRunRoute.Legacy,
        )
        assertFalse(
            ProductRunRoute.decide(SetupLogic.VISION_LOOP_DEFAULT, null, null)
                is ProductRunRoute.Legacy,
        )
    }



    // -- the construction test: the bridge builds a LIVE VisionLoop over the caller's edges --

    private fun node(
        id: String, left: Int, top: Int, right: Int, bottom: Int, text: String = "button",
    ) = ObservedNode(
        id = id, text = text, desc = "", clickable = true, editable = false,
        scrollable = false, left = left, top = top, right = right, bottom = bottom,
        className = "android.widget.Button", sourceOrder = 0, browserChrome = false,
    )

    private fun observationAt(url: String, vararg lines: String) = StepObservation(
        url = url,
        title = "Bridge",
        lines = lines.toList(),
        nodes = listOf(node("n1", left = 100, top = 200, right = 300, bottom = 260, text = "Go")),
    )

    /** Routes by URL: chat-completions pops [chatResponses], everything else [jevResponses]. */
    private class ScriptedTransport(
        private val chatResponses: ArrayDeque<String>,
        private val jevResponses: ArrayDeque<String>,
    ) : WireTransport {
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            val body = (
                if (request.url.contains("chat/completions")) chatResponses.removeFirstOrNull()
                else jevResponses.removeFirstOrNull()
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
    fun `the bridge constructs a live VisionLoop over the service observer and executor`() = runBlocking {
        // The toggled service path's EXACT construction, exercised end to end on JVM
        // fakes: the loop observes through the CALLER's observer, acts through the
        // CALLER's executor, and completes the scripted goal (VisionCapture(null)
        // degrades every screenshot to the loud text-only path, as in the guard tests).
        val plan = """
            {"summary":"open example","taskClass":"read","subtasks":[
              {"instruction":"open the example page","done":{"urlHost":"next.test"}}]}
        """.trimIndent()
        val propose = """{"proposals":[{"description":"the 'Go' button","action":"tap"}]}"""
        val confirm = """{"complete":true,"reason":"the example page is visible"}"""
        val executor = RecordingExecutor()
        val logLines = mutableListOf<String>()
        // A real evidence dir (the service passes its external one): a failure's event
        // log names the exact refusal — the assertion carries it.
        val evidenceDir = java.io.File("build/tmp/bridge-test-evidence").apply { deleteRecursively(); mkdirs() }
        val loop = NavigatorVisionBridge.newVisionLoop(
            observer = ScriptedObserver(
                observationAt("https://old.test/", "landing page"),
                observationAt("https://old.test/", "landing page two"),
                observationAt("https://next.test/page", "the example page"),
            ),
            executor = executor,
            capture = VisionCapture(null),
            evidenceDir = evidenceDir,
            runId = "bridge-test",
            openRouterKey = "fixture-openrouter-key",
            typesafeKey = "fixture-typesafe-key",
            log = { logLines.add(it) },
            transport = ScriptedTransport(
                chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(propose), chatContent(confirm))),
                jevResponses = ArrayDeque(listOf(jevNoul(0.9))),
            ),
        )
        val result = loop.run("open the example page on next.test", "bridge-test") { false }
        val events = runCatching {
            java.io.File(evidenceDir, "bridge-test/events.log").readText()
        }.getOrElse { "(no events written)" }
        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal, "events:\n$events")
        assertEquals(1, executor.taps.size, "the tap acted through the caller's executor")
        assertTrue(logLines.any { it.contains("step 0: tap") }, "the step lines stream to the log sink")
    }

    // -- the terminal funnel's task-state line -----------------------------------------------

    @Test
    fun `the vision terminal maps to the product funnel's task-state line shape`() {
        val achieved = NavigatorVisionBridge.taskStateLine(
            VisionLoop.Result(
                terminal = VisionLoop.Terminal.GOAL_ACHIEVED,
                stepsUsed = 7,
                ladderSummary = "s",
                subtaskOutcomes = emptyList(),
                answer = "the cheapest airfryer is the X model",
                replans = 2,
                screenshots = 5,
            ),
        )
        assertTrue(achieved.startsWith("task GOAL_ACHIEVED"))
        assertTrue(achieved.contains("steps 7"))
        assertTrue(achieved.contains("replans 2"))
        assertTrue(achieved.contains("the cheapest airfryer is the X model"))

        assertEquals(
            "task CANCELLED — by owner",
            NavigatorVisionBridge.taskStateLine(
                VisionLoop.Result(VisionLoop.Terminal.CANCELLED, 3, null, emptyList(), null, 0, 1),
            ),
        )
        val budgetLine = NavigatorVisionBridge.taskStateLine(
            VisionLoop.Result(VisionLoop.Terminal.BUDGET_EXHAUSTED, 6_001, null, emptyList(), null, 0, 10),
        )
        assertTrue(budgetLine.startsWith("task BUDGET_EXHAUSTED"))
    }
}
