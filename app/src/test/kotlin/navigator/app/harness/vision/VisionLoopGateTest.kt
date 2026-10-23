package navigator.app.harness.vision

import kotlinx.coroutines.runBlocking
import navigator.app.loop.ActionExecutor
import navigator.app.loop.LoopObserver
import navigator.app.loop.ScriptedGatePresenter
import navigator.app.loop.StepObservation
import navigator.app.loop.TextSource
import navigator.core.enumerate.ObservedNode
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport
import navigator.core.wire.WireTransportException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PHASE_VEXP3 WI3 — the vision loop's gate battery (JVM, $0): the code-owned
 * [navigator.core.gate.RiskGate] classifies before TAP/TYPE/SUBMIT dispatch, the
 * presenter's card resolutions steer execution, the credential card swaps the typed
 * text for the card secret, per-task trust + the double-refusal abort ride the
 * [navigator.core.gate.TaskGate], and a NULL presenter keeps the ungated behavior.
 * Scroll/back NEVER consult the gate (the law). Every run is fully scripted over the
 * in-memory transport — no provider call.
 */
class VisionLoopGateTest {

    // -- scripted edges (the VisionExperimentTest shapes) --------------------------------

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
        val scrolls = mutableListOf<Boolean>()
        val backs = mutableListOf<Int>()
        val submits = mutableListOf<Int>()
        val entered = mutableListOf<Pair<Pair<Int, Int>, String>>()
        override fun tap(x: Int, y: Int): Boolean { taps.add(x to y); return true }
        override fun clickNode(x: Int, y: Int): Boolean { nodeClicks.add(x to y); return true }
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean { scrolls.add(down); return true }
        override fun back(): Boolean { backs.add(backs.size); return true }
        override fun submit(): Boolean { submits.add(submits.size); return true }
        override fun enterText(x: Int, y: Int, text: String): Boolean {
            entered.add((x to y) to text); return true
        }
    }

    /** Chat-completions envelope around the writer's inner JSON content. */
    private fun chatContent(innerJson: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5) })
    }.toString()

    private fun jevNoul(name: String, pTrue: Double): String = buildJsonObject {
        put("answers", buildJsonObject {
            put(name, buildJsonObject {
                put("type", JsonPrimitive("noul"))
                put("noul", JsonPrimitive(pTrue))
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    private fun node(
        id: String, left: Int, top: Int, right: Int, bottom: Int,
        clickable: Boolean = true, editable: Boolean = false, text: String = "button",
        isPassword: Boolean = false, scrollable: Boolean = false,
    ) = ObservedNode(
        id = id, text = text, desc = "", clickable = clickable, editable = editable,
        scrollable = scrollable, left = left, top = top, right = right, bottom = bottom,
        className = "android.widget.Button", sourceOrder = 0, isPassword = isPassword,
    )

    private fun observationAt(url: String, vararg nodes: ObservedNode, line: String = "some page") =
        StepObservation(url = url, title = "Page", lines = listOf(line), nodes = nodes.toList())

    private val plan = """
        {"summary":"one gated action","taskClass":"action","subtasks":[
          {"instruction":"do the thing","done":{"urlHost":"done.test"}}]}
    """.trimIndent()

    private val proposeUnsubscribeTap =
        """{"proposals":[{"description":"Unsubscribe from Garden Week","action":"tap"}]}"""
    private val proposeEmpty = """{"proposals":[]}"""
    private val confirmTrue = """{"complete":true,"reason":"the outcome is visible"}"""
    private val confirmFalse = """{"complete":false,"reason":"nothing yet"}"""

    private val unsubscribePage =
        observationAt(
            "https://gardenweek.test/settings",
            node("n1", 100, 200, 300, 260, clickable = true, text = "Unsubscribe from Garden Week"),
        )
    private val donePage = observationAt("https://done.test/finished")

    /** The standard single-subtask loop over [transport] with the given presenter. */
    private fun loop(
        transport: ScriptedVisionTransport,
        executor: RecordingExecutor,
        observations: ArrayDeque<StepObservation>,
        gatePresenter: navigator.app.loop.GatePresenter?,
        maxReplans: Int = 1,
        stepFailuresBeforeReplan: Int = 2,
    ): VisionLoop {
        val observer = object : LoopObserver {
            override suspend fun observe(): StepObservation = observations.removeFirstOrNull()
                ?: error("observer script exhausted")
        }
        return VisionLoop(
            observer = observer,
            executor = executor,
            capture = VisionCapture(null), // null service -> every capture degrades text-only
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(
                transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                backoffBaseMs = 1, retryDelay = {},
            ),
            budget = ExpBudget(),
            evidence = VisionEvidence(null, "gate-test"),
            gatePresenter = gatePresenter,
            config = VisionLoop.Config(
                settleMs = 0,
                maxReplans = maxReplans,
                stepFailuresBeforeReplan = stepFailuresBeforeReplan,
            ),
        )
    }

    // -- the battery -----------------------------------------------------------------------

    @Test
    fun `an approved gate executes the action and marks the step line`() = runBlocking {
        val transport = ScriptedVisionTransport(
            // plan, propose(tap), the accept's confirm
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(proposeUnsubscribeTap), chatContent(confirmTrue))),
            // the accept's recorded noul
            jevResponses = ArrayDeque(listOf(jevNoul("done", 0.9))),
        )
        val executor = RecordingExecutor()
        val presenter = ScriptedGatePresenter(navigator.app.loop.GateResolution.APPROVED)
        val result = loop(
            transport, executor,
            ArrayDeque(listOf(unsubscribePage, unsubscribePage, donePage)),
            presenter,
        ).run("open gardenweek.test and unsubscribe from the garden week channel", "gate-approve") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(1, executor.taps.size, "the approved action executed")
        assertEquals(200 to 230, executor.taps.single(), "the tap landed on the node center")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("(gate H9 approved)" in stepLine, "the gate marker rides the step line: $stepLine")
        assertFalse("refused" in stepLine)
    }

    @Test
    fun `a denied gate refuses the step with the owner disposition`() = runBlocking {
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(proposeUnsubscribeTap))),
            jevResponses = ArrayDeque(),
        )
        val executor = RecordingExecutor()
        val presenter = ScriptedGatePresenter(navigator.app.loop.GateResolution.DENIED)
        val result = loop(
            transport, executor,
            ArrayDeque(listOf(unsubscribePage, unsubscribePage)),
            presenter,
            maxReplans = 0,
            stepFailuresBeforeReplan = 1,
        ).run("open gardenweek.test and unsubscribe from the garden week channel", "gate-deny") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal, "the refusal feeds the failure budget")
        assertTrue(executor.taps.isEmpty(), "a denied action NEVER executes")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("refused-by-owner" in stepLine, "the disposition rides the step line: $stepLine")
        assertTrue("(gate H9 denied)" in stepLine, "the gate marker rides the step line: $stepLine")
    }

    @Test
    fun `a gate timeout refuses the step with the timeout disposition`() = runBlocking {
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(proposeUnsubscribeTap))),
            jevResponses = ArrayDeque(),
        )
        val executor = RecordingExecutor()
        val presenter = ScriptedGatePresenter(navigator.app.loop.GateResolution.TIMEOUT)
        val result = loop(
            transport, executor,
            ArrayDeque(listOf(unsubscribePage, unsubscribePage)),
            presenter,
            maxReplans = 0,
            stepFailuresBeforeReplan = 1,
        ).run("open gardenweek.test and unsubscribe from the garden week channel", "gate-timeout") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(executor.taps.isEmpty(), "a timed-out action NEVER executes (timeout = deny, fail-safe)")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("gate-timeout" in stepLine, "the disposition rides the step line: $stepLine")
    }

    @Test
    fun `the credential card swaps the typed text for the card secret`() = runBlocking {
        val loginPage = observationAt(
            "https://accounts.test/login",
            node("n1", 100, 100, 300, 150, clickable = false, editable = true, text = "Email"),
            node("n2", 100, 200, 300, 250, clickable = false, editable = true, text = "Password", isPassword = true),
        )
        val proposeType =
            """{"proposals":[{"description":"Password","action":"type","text":"proposal-plain-text"}]}"""
        val transport = ScriptedVisionTransport(
            // plan, propose(type), the accept's confirm
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(proposeType), chatContent(confirmTrue))),
            // the accept's recorded noul
            jevResponses = ArrayDeque(listOf(jevNoul("done", 0.9))),
        )
        val executor = RecordingExecutor()
        val secret = "s3cret-value-XYZ"
        val presenter = ScriptedGatePresenter(navigator.app.loop.GateResolution.ApproveWithSecret(secret))
        val result = loop(
            transport, executor,
            ArrayDeque(listOf(loginPage, loginPage, donePage)),
            presenter,
        ).run("open accounts.test and sign in to the site", "gate-credential") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(1, executor.entered.size, "the type executed once")
        assertEquals(secret, executor.entered.single().second, "the TYPED value is the card's secret")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue(TextSource.REDACTED in stepLine, "the secret renders redacted on the step line: $stepLine")
        assertTrue("(gate H4 approved)" in stepLine, "the gate marker rides the step line: $stepLine")
        // Secret law: the value never renders in any outcome line.
        result.subtaskOutcomes.forEach { line ->
            assertFalse(secret in line, "the card secret leaked into the step log: $line")
        }
        assertFalse("proposal-plain-text" in result.subtaskOutcomes.joinToString(), "the swapped proposal text never types")
    }

    @Test
    fun `a plain approve on a credential card fails loudly - never falls through`() = runBlocking {
        val loginPage = observationAt(
            "https://accounts.test/login",
            node("n1", 100, 100, 300, 150, clickable = false, editable = true, text = "Email"),
            node("n2", 100, 200, 300, 250, clickable = false, editable = true, text = "Password", isPassword = true),
        )
        val proposeType =
            """{"proposals":[{"description":"Password","action":"type","text":"proposal-plain-text"}]}"""
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(proposeType))),
            jevResponses = ArrayDeque(),
        )
        val executor = RecordingExecutor()
        val presenter = ScriptedGatePresenter(navigator.app.loop.GateResolution.APPROVED)
        val error = assertThrows<IllegalStateException> {
            loop(
                transport, executor,
                ArrayDeque(listOf(loginPage, loginPage)),
                presenter,
            ).run("open accounts.test and sign in to the site", "gate-wrong-source") { false }
        }
        assertTrue("WITHOUT" in error.message!!, "the failure names the wrong source: ${error.message}")
        assertTrue(executor.entered.isEmpty(), "nothing typed on a wrong-source resolution")
    }

    @Test
    fun `a secret on a non-credential card fails loudly - never executes`() = runBlocking {
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(proposeUnsubscribeTap))),
            jevResponses = ArrayDeque(),
        )
        val executor = RecordingExecutor()
        val presenter = ScriptedGatePresenter(navigator.app.loop.GateResolution.ApproveWithSecret("stray-secret"))
        val error = assertThrows<IllegalStateException> {
            loop(
                transport, executor,
                ArrayDeque(listOf(unsubscribePage, unsubscribePage)),
                presenter,
            ).run("open gardenweek.test and unsubscribe from the garden week channel", "gate-secret-on-plain") { false }
        }
        assertTrue("ApproveWithSecret" in error.message!!, "the failure names the wrong resolution: ${error.message}")
        assertTrue(executor.taps.isEmpty(), "nothing executed on a wrong-source resolution")
    }

    @Test
    fun `the second refusal of the same class and site aborts the task - GUARD_ABORT`() = runBlocking {
        val transport = ScriptedVisionTransport(
            // plan, propose(tap) twice (both gated and denied)
            chatResponses = ArrayDeque(
                listOf(chatContent(plan), chatContent(proposeUnsubscribeTap), chatContent(proposeUnsubscribeTap)),
            ),
            jevResponses = ArrayDeque(),
        )
        val executor = RecordingExecutor()
        val presenter = ScriptedGatePresenter(
            navigator.app.loop.GateResolution.DENIED,
            navigator.app.loop.GateResolution.DENIED,
        )
        val result = loop(
            transport, executor,
            // Every observation carries the mutation-worded control (so each attempt
            // gates) but a DISTINCT page line (a static screen would trip the stuck
            // probe's scroll before the second gated attempt).
            ArrayDeque(
                listOf(
                    unsubscribePage,
                    observationAt(
                        "https://gardenweek.test/settings",
                        node("n1", 100, 200, 300, 260, clickable = true, text = "Unsubscribe from Garden Week"),
                        line = "page one",
                    ),
                    observationAt(
                        "https://gardenweek.test/settings",
                        node("n1", 100, 200, 300, 260, clickable = true, text = "Unsubscribe from Garden Week"),
                        line = "page two",
                    ),
                ),
            ),
            presenter,
            maxReplans = 5,
            stepFailuresBeforeReplan = 2,
        ).run("open gardenweek.test and unsubscribe from the garden week channel", "guard-abort") { false }

        assertEquals(VisionLoop.Terminal.GUARD_ABORT, result.terminal, "two same-(class, site) refusals abort the task")
        assertTrue(executor.taps.isEmpty(), "a refused action NEVER executes")
        val last = result.subtaskOutcomes.last()
        assertTrue(
            "refused twice" in last && "task aborted" in last && "refused-by-owner" in last,
            "the abort is loud: $last",
        )
    }

    @Test
    fun `per-task trust executes the same class and site without a second card`() = runBlocking {
        val transport = ScriptedVisionTransport(
            // plan, propose(tap) twice (second is trusted - no consult), the accept's confirm
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(plan),
                    chatContent(proposeUnsubscribeTap),
                    chatContent(proposeUnsubscribeTap),
                    chatContent(confirmTrue),
                ),
            ),
            // the accept's recorded noul
            jevResponses = ArrayDeque(listOf(jevNoul("done", 0.9))),
        )
        val executor = RecordingExecutor()
        // ONE scripted resolution: a second consult would exhaust the script and fail the run.
        val presenter = ScriptedGatePresenter(navigator.app.loop.GateResolution.APPROVED)
        val result = loop(
            transport, executor,
            ArrayDeque(listOf(unsubscribePage, unsubscribePage, unsubscribePage, donePage)),
            presenter,
        ).run("open gardenweek.test and unsubscribe from the garden week channel", "gate-trust") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(2, executor.taps.size, "both taps executed")
        val trustedLine = result.subtaskOutcomes.last { it.startsWith("step 1:") }
        assertTrue("(gate H9 approved (trusted))" in trustedLine, "trust marks the step line: $trustedLine")
    }

    @Test
    fun `the ungated seam (null presenter) never consults a gate`() = runBlocking {
        val transport = ScriptedVisionTransport(
            // plan, propose(tap), the accept's confirm
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(proposeUnsubscribeTap), chatContent(confirmTrue))),
            // the accept's recorded noul
            jevResponses = ArrayDeque(listOf(jevNoul("done", 0.9))),
        )
        val executor = RecordingExecutor()
        val result = loop(
            transport, executor,
            ArrayDeque(listOf(unsubscribePage, unsubscribePage, donePage)),
            gatePresenter = null,
        ).run("open gardenweek.test and unsubscribe from the garden week channel", "gate-ungated") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(1, executor.taps.size, "the mutation-classed tap executed ungated (the default seam)")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertFalse("(gate" in stepLine, "no gate marker on the ungated path: $stepLine")
    }

    @Test
    fun `scroll and back proposals never consult the gate`() = runBlocking {
        val proposeScroll = """{"proposals":[{"description":"the results list","action":"scroll_down"}]}"""
        val proposeBack = """{"proposals":[{"description":"the back control","action":"back"}]}"""
        val transport = ScriptedVisionTransport(
            // plan, propose(scroll), propose(back), propose(empty), propose(empty),
            // the failure gate's confirm (the proposer-exhausted MODELS check)
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(plan),
                    chatContent(proposeScroll),
                    chatContent(proposeBack),
                    chatContent(proposeEmpty),
                    chatContent(proposeEmpty),
                    chatContent(confirmFalse),
                ),
            ),
            // the failure gate's recorded noul (low -> no confirm-done escape)
            jevResponses = ArrayDeque(listOf(jevNoul("done", 0.1))),
        )
        val executor = RecordingExecutor()
        // ZERO scripted resolutions: ANY gate consult exhausts the script and fails the test.
        val presenter = ScriptedGatePresenter()
        // Text-only page (no screenshot): the no-shot match still needs a label match,
        // so the page carries the two named controls; every line DISTINCT (a static
        // screen would trip the stuck probe's scroll).
        val pageNodes = arrayOf(
            node("n1", 100, 300, 400, 900, clickable = false, scrollable = true, text = "results list"),
            node("n2", 100, 950, 300, 1000, clickable = true, text = "back control"),
        )
        val result = loop(
            transport, executor,
            ArrayDeque(
                listOf(
                    observationAt("https://gardenweek.test/feed", *pageNodes, line = "feed start"),
                    observationAt("https://gardenweek.test/feed", *pageNodes, line = "page one"),
                    observationAt("https://gardenweek.test/feed", *pageNodes, line = "page two"),
                    observationAt("https://gardenweek.test/feed", *pageNodes, line = "page three"),
                    observationAt("https://gardenweek.test/feed", *pageNodes, line = "page four"),
                ),
            ),
            presenter,
            maxReplans = 0,
        ).run("open done.test and find the thing", "scroll-back-never-gated") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertEquals(1, executor.scrolls.size, "the scroll executed ungated")
        assertEquals(1, executor.backs.size, "the back executed ungated")
        assertTrue(executor.taps.isEmpty() && executor.entered.isEmpty())
    }
}
