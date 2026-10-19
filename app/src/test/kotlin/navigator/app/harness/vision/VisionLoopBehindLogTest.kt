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
import kotlin.test.assertTrue

/**
 * PHASE_VEXP5 WI3: actions behind the live log / soft keyboard, in the REAL loop over
 * scripted transports ($0, fabricated capture, no device). The law: a raw touch is
 * NEVER dispatched at a point under an occluder — a covered TAP activates the page's
 * a11y node (ACTION_CLICK), a covered tap-converted TYPE skips the focus tap and lets
 * enterText work on the field node, and a covered point with no actionable node fails
 * loudly into the re-plan path.
 */
class VisionLoopBehindLogTest {

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
        val scrolls = mutableListOf<Triple<Int, Int, Boolean>>()
        val entered = mutableListOf<Pair<Pair<Int, Int>, String>>()
        override fun tap(x: Int, y: Int): Boolean { taps.add(x to y); return true }
        override fun clickNode(x: Int, y: Int): Boolean { nodeClicks.add(x to y); return true }
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean {
            scrolls.add(Triple(x, y, down))
            return true
        }
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
        clickable: Boolean = true, editable: Boolean = false, scrollable: Boolean = false,
        text: String = "control",
    ) = ObservedNode(
        id = id, text = text, desc = "", clickable = clickable, editable = editable,
        scrollable = scrollable, left = left, top = top, right = right, bottom = bottom,
        className = "android.view.View", sourceOrder = 0,
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

    private fun plan(enterText: String? = null): String {
        val field = enterText?.let { "\"enterText\":\"$it\"," } ?: ""
        return """
            {"summary":"act on the page","taskClass":"action","subtasks":[
              {"instruction":"act on the page",$field
               "done":{"urlHost":"done.test"}}]}
        """.trimIndent()
    }

    private val tapSearch = """
        {"proposals":[{"description":"the 'Search' button","action":"tap",
          "box":{"top":0.20,"bottom":0.25,"left":0.10,"right":0.30},"confidence":0.9}]}
    """.trimIndent()

    private val tapAddressBar = """
        {"proposals":[{"description":"the address bar at the top of the screen","action":"tap",
          "box":{"top":0.02,"bottom":0.05,"left":0.10,"right":0.90},"confidence":0.9}]}
    """.trimIndent()

    private val scrollDown = """
        {"proposals":[{"description":"scroll down the page","action":"scroll_down","confidence":0.8}]}
    """.trimIndent()

    private val tapContinue = """
        {"proposals":[{"description":"the Continue button","action":"tap","confidence":0.9}]}
    """.trimIndent()

    /** The pick-only GROUND answer (a scroll proposal carries no box). */
    private fun jevPick(): String = buildJsonObject {
        put("answers", buildJsonObject {
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

    private val confirmTrue = """{"complete":true,"reason":"the outcome is visible"}"""

    /** The single-subtask loop over [transport] with a fabricated capture + occluders. */
    private fun loop(
        transport: ScriptedVisionTransport,
        executor: RecordingExecutor,
        observations: List<StepObservation>,
        evidence: VisionEvidence,
        covered: List<VisionMath.RectPx>,
        logs: MutableList<String>? = null,
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
        log = { line -> logs?.add(line) },
        config = VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1),
        coveredBounds = { covered },
    )

    // -- the covered-point law -------------------------------------------------------------

    @Test
    fun `a covered tap activates the page node - never a raw touch`() = runBlocking {
        // The search button sits exactly under the live log: a raw tap would press the
        // overlay. The page's clickable node is the lawful activation surface.
        val search = node("n1", left = 100, top = 400, right = 300, bottom = 500, text = "Search")
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan()), chatContent(tapSearch), chatContent(confirmTrue))),
            jevResponses = ArrayDeque(
                listOf(
                    jevGround(top = 0.20, bottom = 0.25, left = 0.10, right = 0.30),
                    jevNoul(0.9),
                ),
            ),
        )
        val executor = RecordingExecutor()
        val evidence = VisionEvidence(null, "behind-log-tap")

        val result = loop(
            transport, executor,
            listOf(observation(listOf(search)), observation(listOf(search)), doneObservation()),
            evidence,
            covered = listOf(VisionMath.RectPx(90, 390, 310, 510)),
        ).run("tap the search control", "behind-log-tap") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertTrue(executor.taps.isEmpty(), "NO raw tap may land on the overlay")
        assertEquals(listOf(200 to 450), executor.nodeClicks, "the page node receives ONE ACTION_CLICK")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("(behind-log: node click)" in stepLine, "the step line names the behind-log route: $stepLine")
    }

    @Test
    fun `a covered point with no actionable node fails loudly - zero touches`() = runBlocking {
        // A scrollable-only node grounds the proposal (bestNodeMatch accepts it), but it
        // is neither clickable nor editable — there is no lawful node action at the
        // covered point, so the step must fail into the re-plan path with zero touches.
        val scrollOnly = node(
            "n1", left = 100, top = 400, right = 300, bottom = 500,
            clickable = false, editable = false, scrollable = true,
        )
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan()), chatContent(tapSearch))),
            jevResponses = ArrayDeque(listOf(jevGround(top = 0.20, bottom = 0.25, left = 0.10, right = 0.30))),
        )
        val executor = RecordingExecutor()
        val evidence = VisionEvidence(null, "behind-log-refused")

        val result = loop(
            transport, executor,
            listOf(observation(listOf(scrollOnly)), observation(listOf(scrollOnly))),
            evidence,
            covered = listOf(VisionMath.RectPx(90, 390, 310, 510)),
        ).run("tap the search control", "behind-log-refused") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(executor.taps.isEmpty(), "no raw touch on the overlay")
        assertTrue(executor.nodeClicks.isEmpty(), "no node action either - nothing actionable there")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("FAILED" in stepLine, "the step is a loud failure: $stepLine")
        assertTrue(
            "is covered by the live log or keyboard and no actionable page node exists there" in stepLine,
            "the refusal names the covered point + the absence of an actionable node: $stepLine",
        )
        assertTrue("refusing a touch that would land on the overlay" in stepLine, stepLine)
    }

    @Test
    fun `a covered tap-converted type skips the focus tap and still enters the plan text`() = runBlocking {
        // The address bar grounds as an editable node and the plan carries enterText, so
        // the step converts to TYPE. The focus tap point is under the log: it is SKIPPED
        // (enterText focuses/clicks/SET_TEXT on the field node itself), and the plan's
        // text is still typed at the field centre.
        val addressBar = node(
            "n4", left = 100, top = 40, right = 900, bottom = 100,
            clickable = false, editable = true, text = "Address bar",
        )
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(chatContent(plan("hotels in Riga next weekend")), chatContent(tapAddressBar), chatContent(confirmTrue)),
            ),
            jevResponses = ArrayDeque(
                listOf(
                    jevGround(top = 0.02, bottom = 0.05, left = 0.10, right = 0.90),
                    jevNoul(0.9),
                ),
            ),
        )
        val executor = RecordingExecutor()
        val evidence = VisionEvidence(null, "behind-log-type")

        val result = loop(
            transport, executor,
            listOf(observation(listOf(addressBar)), observation(listOf(addressBar)), doneObservation()),
            evidence,
            covered = listOf(VisionMath.RectPx(400, 30, 600, 110)),
        ).run("search hotels in Riga next weekend", "behind-log-type") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertTrue(executor.taps.isEmpty(), "the covered focus tap is SKIPPED")
        assertEquals(
            listOf((500 to 70) to "hotels in Riga next weekend"),
            executor.entered,
            "enterText still types the plan text at the field centre",
        )
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("(focus tap skipped: behind-log)" in stepLine, "the skip is named: $stepLine")
        assertTrue("(tap-converted)" in stepLine, "the conversion still happened: $stepLine")
    }

    @Test
    fun `a covered scroll with no uncovered point never dispatches - loud failure`() = runBlocking {
        // The review amendment's STRICTER law: when every uncovered-point candidate is
        // covered there is NO lawful scroll start — the step refuses loudly (re-plan
        // path), never a best-effort raw touch on the overlay.
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan()), chatContent(scrollDown))),
            jevResponses = ArrayDeque(listOf(jevPick())),
        )
        val executor = RecordingExecutor()

        val result = loop(
            transport, executor,
            listOf(observation(emptyList()), observation(emptyList())),
            VisionEvidence(null, "behind-log-scroll-refused"),
            covered = listOf(VisionMath.RectPx(0, 0, 1000, 2000)),
        ).run("scroll down", "behind-log-scroll-refused") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(executor.scrolls.isEmpty(), "NO raw scroll may dispatch at a covered start")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("FAILED" in stepLine, "the step is a loud failure: $stepLine")
        assertTrue(
            "no uncovered point exists inside the captured frame" in stepLine,
            "the refusal names the absent safe point: $stepLine",
        )
        assertTrue("refusing a touch that would land on the overlay" in stepLine, stepLine)
    }

    @Test
    fun `a covered scroll moves to the first uncovered point and dispatches once`() = runBlocking {
        // Only the centre is covered: the upper third (500,666) is the first lawful
        // candidate, and the swipe dispatches THERE — exactly one scroll, named on the
        // step line.
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan()), chatContent(scrollDown), chatContent(confirmTrue))),
            jevResponses = ArrayDeque(listOf(jevPick(), jevNoul(0.9))),
        )
        val executor = RecordingExecutor()

        val result = loop(
            transport, executor,
            listOf(observation(emptyList()), observation(emptyList()), doneObservation()),
            VisionEvidence(null, "behind-log-scroll-moved"),
            covered = listOf(VisionMath.RectPx(400, 900, 600, 1100)),
        ).run("scroll down", "behind-log-scroll-moved") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(listOf(Triple(500, 666, true)), executor.scrolls, "exactly one scroll at the uncovered point")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("(behind-log: moved to (500,666))" in stepLine, "the move is named: $stepLine")
    }

    @Test
    fun `the stuck probe never scrolls from a covered start with no uncovered point`() = runBlocking {
        // The review's first gap: the stuck probe's own scroll was an unguarded raw
        // touch. With every candidate covered (and the probe start at the last node's
        // centreX, y=1200 also covered), the probe is SKIPPED, logged, treated as
        // still-static, and no scroll ever dispatches.
        val continueButton = node("n1", left = 700, top = 400, right = 900, bottom = 500, text = "Continue")
        val page = observation(listOf(continueButton))
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(chatContent(plan()), chatContent(tapContinue), chatContent(tapContinue)),
            ),
            jevResponses = ArrayDeque(listOf(jevPick(), jevPick())),
        )
        val executor = RecordingExecutor()
        val logs = mutableListOf<String>()

        val result = loop(
            transport, executor,
            listOf(page, page, page, page),
            VisionEvidence(null, "behind-log-stuck"),
            covered = listOf(
                // The five uncoveredPoint candidates inside the 1000x2000 frame...
                VisionMath.RectPx(450, 950, 550, 1050), // centre (500,1000)
                VisionMath.RectPx(450, 616, 550, 716), // upper third (500,666)
                VisionMath.RectPx(283, 950, 383, 1050), // left third (333,1000)
                VisionMath.RectPx(116, 283, 216, 383), // upper-left sixth (166,333)
                VisionMath.RectPx(450, 1283, 550, 1383), // lower third (500,1333)
                // ...plus the probe start (800,1200), leaving the tapped node (800,450) free.
                VisionMath.RectPx(750, 1150, 850, 1250),
            ),
            logs = logs,
        ).run("keep going", "behind-log-stuck") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(executor.scrolls.isEmpty(), "the stuck probe must NOT dispatch a raw scroll")
        assertEquals(listOf(800 to 450, 800 to 450), executor.taps, "the two lawful page taps are unchanged")
        assertTrue(
            logs.any { it.contains("stuck probe skipped: covered start and no uncovered point") },
            "the skip is logged loudly: $logs",
        )
    }
}
