package navigator.app.harness.vision

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.app.loop.ActionExecutor
import navigator.app.loop.GatePresenter
import navigator.app.loop.GateResolution
import navigator.app.loop.LoopObserver
import navigator.app.loop.ScriptedGatePresenter
import navigator.app.loop.StepObservation
import navigator.app.loop.TextSource
import navigator.core.enumerate.ObservedNode
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport
import navigator.core.wire.WireTransportException
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PHASE_VEXP6 WI1/WI2's JVM gates ($0 — fully scripted transports, fabricated capture,
 * no device and no provider call):
 *
 * - the DETERMINISTIC POST-TYPE SUBMIT: a successful TYPE whose text is the active
 *   subtask's plan `enterText` into a BROWSER-CHROME field fires `executor.submit()`
 *   immediately, in the same step — but ONLY when the SUBMIT's `RiskGate.classify`
 *   returns null (an ordinary search) or the presenter is null (the ungated adb path).
 *   A classified submit stays with the normal gated flow; a card-supplied credential is
 *   never auto-submitted;
 * - the PLAN TEXT's authority: a native TYPE's typed AND gate-classified text is the
 *   plan's `enterText`; a divergent proposal text is overridden with `(plan text)`.
 *
 * The run-4 live evidence (`.state/vision-iter/run4/REPORT.md`,
 * `task-1792411667614`): the loop typed the proposer's shortened "hotels in Riga",
 * tapped the omnibox suggestion rows (accepted but inert) and the clear-X, and never
 * submitted the search.
 */
class VisionLoopSearchSubmitTest {

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

    private class RecordingExecutor(private val submitResult: Boolean = true) : ActionExecutor {
        val taps = mutableListOf<Pair<Int, Int>>()
        val nodeClicks = mutableListOf<Pair<Int, Int>>()
        val entered = mutableListOf<Pair<Pair<Int, Int>, String>>()
        var submits = 0
            private set
        override fun tap(x: Int, y: Int): Boolean { taps.add(x to y); return true }
        override fun clickNode(x: Int, y: Int): Boolean { nodeClicks.add(x to y); return true }
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean = true
        override fun back(): Boolean = true
        override fun submit(): Boolean {
            submits += 1
            return submitResult
        }
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
        isPassword: Boolean = false, browserChrome: Boolean = false,
    ) = ObservedNode(
        id = id, text = text, desc = "", clickable = clickable, editable = editable,
        scrollable = false, left = left, top = top, right = right, bottom = bottom,
        className = "android.widget.EditText", sourceOrder = 0,
        isPassword = isPassword, browserChrome = browserChrome,
    )

    private fun observation(nodes: List<ObservedNode>) = StepObservation(
        url = "https://booking.test/search",
        title = "Booking",
        lines = listOf("some page"),
        nodes = nodes,
    )

    private fun loginObservation(nodes: List<ObservedNode>) = StepObservation(
        url = "https://accounts.test/login",
        title = "Sign in",
        lines = listOf("sign in to continue"),
        nodes = nodes,
    )

    private fun doneObservation() = StepObservation(
        url = "https://done.test/finished",
        title = "Done",
        lines = listOf("the task is done"),
        nodes = emptyList(),
    )

    /** The Chrome address bar: an editable node the a11y reader marks as browser chrome. */
    private fun addressBarNode() = node(
        "n4", left = 100, top = 40, right = 900, bottom = 100,
        clickable = false, editable = true, text = "Address bar", browserChrome = true,
    )

    private fun plan(enterText: String?, instruction: String = "enter the hotel search into the address bar"): String {
        val field = enterText?.let { "\"enterText\":\"$it\"," } ?: ""
        return """
            {"summary":"search hotels","taskClass":"action","subtasks":[
              {"instruction":"$instruction",$field
               "done":{"urlHost":"done.test"}}]}
        """.trimIndent()
    }

    /** A native TYPE at the address bar; the DIVERGENT proposal text drops the dates (run-4). */
    private fun nativeTypeAtAddressBar(text: String) = """
        {"proposals":[{"description":"the address bar at the top of the screen","action":"type","expectedEffect":{"expect":"FieldContainsExpectedValue"},
          "text":"$text",
          "box":{"top":0.02,"bottom":0.05,"left":0.10,"right":0.90},"confidence":0.9}]}
    """.trimIndent()

    /** A native TYPE at an IN-PAGE search field (not browser chrome). */
    private fun nativeTypeAtPageField(text: String) = """
        {"proposals":[{"description":"the page's search field","action":"type","expectedEffect":{"expect":"FieldContainsExpectedValue"},
          "text":"$text",
          "box":{"top":0.20,"bottom":0.23,"left":0.10,"right":0.90},"confidence":0.9}]}
    """.trimIndent()

    private val confirmTrue = """{"complete":true,"reason":"the outcome is visible"}"""

    private fun loop(
        transport: ScriptedVisionTransport,
        executor: RecordingExecutor,
        observations: List<StepObservation>,
        evidence: VisionEvidence,
        gatePresenter: GatePresenter? = null,
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
        gatePresenter = gatePresenter,
    )

    private fun standardTransport(plan: String, propose: String): ScriptedVisionTransport =
        ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(propose), chatContent(confirmTrue))),
            jevResponses = ArrayDeque(
                listOf(
                    jevGround(top = 0.02, bottom = 0.05, left = 0.10, right = 0.90),
                    jevNoul(0.9),
                ),
            ),
        )

    private fun newEvidence(runId: String): Pair<VisionEvidence, File> {
        val base = Files.createTempDirectory("vexp6-submit").toFile()
        return VisionEvidence(base, runId) to File(base, "$runId/events.log")
    }

    // -- WI1: the deterministic post-type submit -------------------------------------------

    @Test
    fun `a plan-text native type into the address bar submits in the same step - ungated path`() = runBlocking {
        // The run-4 shape: the proposer types the shortened query; the plan carries the
        // full one. The chrome field grounds through the RAW arm (browser chrome is
        // excluded from node matching). The plan text wins, and the IME submit fires
        // immediately (null presenter = the adb path).
        val bar = addressBarNode()
        val executor = RecordingExecutor()
        val (evidence, eventsFile) = newEvidence("search-submit-ungated")

        val result = loop(
            standardTransport(
                plan(enterText = "hotels in Riga next weekend"),
                nativeTypeAtAddressBar("hotels in Riga"),
            ),
            executor,
            listOf(observation(listOf(bar)), observation(listOf(bar)), doneObservation()),
            evidence,
        ).run("open hotels.test and find a hotel in Riga for next weekend", "search-submit-ungated") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(
            listOf((500 to 70) to "hotels in Riga next weekend"),
            executor.entered,
            "the PLAN's enterText is typed — the proposal's shortened text never wins",
        )
        assertEquals(1, executor.submits, "the IME submit fires immediately, in the same step")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("(plan text)" in stepLine, "the divergent proposal text is overridden visibly: $stepLine")
        assertTrue("(search submitted)" in stepLine, "the submit rides the step line: $stepLine")
        val events = eventsFile.readText()
        assertTrue("search submit: ok node=n4" in events, "the evidence names the submit + field: $events")
    }

    @Test
    fun `the product path auto-submits when the submit classifier is null - no card`() = runBlocking {
        // The product path has a presenter, but an ordinary search submit classifies
        // null (no H-class): the auto-submit still fires, and NO card is presented
        // (an empty ScriptedGatePresenter would throw if one were).
        val bar = addressBarNode()
        val executor = RecordingExecutor()
        val (evidence, _) = newEvidence("search-submit-product")

        val result = loop(
            standardTransport(
                plan(enterText = "hotels in Riga next weekend"),
                nativeTypeAtAddressBar("hotels in Riga"),
            ),
            executor,
            listOf(observation(listOf(bar)), observation(listOf(bar)), doneObservation()),
            evidence,
            gatePresenter = ScriptedGatePresenter(),
        ).run("open hotels.test and find a hotel in Riga for next weekend", "search-submit-product") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(1, executor.submits, "a null submit classification auto-submits on the product path")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("(search submitted)" in stepLine, stepLine)
        assertFalse("(gate" in stepLine, "the ordinary search type/submit never gated: $stepLine")
    }

    @Test
    fun `a classified submit on the product path is NOT auto-submitted - left to the gated flow`() = runBlocking {
        // The page carries a credential PAIR, so a SUBMIT classifies H4 while typing
        // into browser chrome stays ungated. The auto-submit must NOT fire: the
        // classified submit belongs to the normal gated proposer flow.
        val bar = addressBarNode()
        val email = node(
            "n2", left = 100, top = 200, right = 900, bottom = 260,
            clickable = false, editable = true, text = "Email",
        )
        val password = node(
            "n3", left = 100, top = 300, right = 900, bottom = 360,
            clickable = false, editable = true, text = "Password", isPassword = true,
        )
        val page = loginObservation(listOf(bar, email, password))
        val executor = RecordingExecutor()
        val (evidence, eventsFile) = newEvidence("search-submit-classified")

        val result = loop(
            standardTransport(
                plan(enterText = "hotels in Riga next weekend"),
                nativeTypeAtAddressBar("hotels in Riga"),
            ),
            executor,
            listOf(page, page, doneObservation()),
            evidence,
            gatePresenter = ScriptedGatePresenter(),
        ).run("open hotels.test and find a hotel in Riga for next weekend", "search-submit-classified") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(1, executor.entered.size, "the type itself still executes")
        assertEquals(0, executor.submits, "a classified submit is NEVER auto-submitted")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertFalse("(search submitted)" in stepLine, "no auto-submit marker: $stepLine")
        assertFalse("search submit:" in eventsFile.readText(), "no submit evidence either")
    }

    @Test
    fun `a submitted typed field that is NOT browser chrome never auto-submits`() = runBlocking {
        // The plan text still governs the type (WI2), but the field is an in-page
        // search box, not browser chrome: the deterministic submit is the OMNIBOX
        // route's; an in-page submit stays with the proposer.
        val pageField = node(
            "n2", left = 100, top = 400, right = 900, bottom = 460,
            clickable = false, editable = true, text = "Search hotels",
        )
        val executor = RecordingExecutor()
        val (evidence, _) = newEvidence("search-submit-page-field")
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(plan(enterText = "hotels in Riga next weekend")),
                    chatContent(nativeTypeAtPageField("hotels in Riga")),
                    chatContent(confirmTrue),
                ),
            ),
            jevResponses = ArrayDeque(
                listOf(
                    jevGround(top = 0.20, bottom = 0.23, left = 0.10, right = 0.90),
                    jevNoul(0.9),
                ),
            ),
        )

        val result = loop(
            transport,
            executor,
            listOf(observation(listOf(pageField)), observation(listOf(pageField)), doneObservation()),
            evidence,
        ).run("open hotels.test and search for hotels in Riga next weekend", "search-submit-page-field") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(
            listOf((500 to 430) to "hotels in Riga next weekend"),
            executor.entered,
            "the plan text is typed into the in-page field",
        )
        assertEquals(0, executor.submits, "only a browser-chrome field auto-submits")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("(plan text)" in stepLine, stepLine)
        assertFalse("(search submitted)" in stepLine, stepLine)
    }

    @Test
    fun `a failed submit does not throw - the type stays executed and the marker is truthful`() = runBlocking {
        val bar = addressBarNode()
        val executor = RecordingExecutor(submitResult = false)
        val (evidence, eventsFile) = newEvidence("search-submit-failed")

        val result = loop(
            standardTransport(
                plan(enterText = "hotels in Riga next weekend"),
                nativeTypeAtAddressBar("hotels in Riga"),
            ),
            executor,
            listOf(observation(listOf(bar)), observation(listOf(bar)), doneObservation()),
            evidence,
        ).run("open hotels.test and find a hotel in Riga for next weekend", "search-submit-failed") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal, "a false submit never fails the run")
        assertEquals(1, executor.submits)
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("(search submit failed)" in stepLine, "the failure rides the step line: $stepLine")
        assertFalse("(search submitted)" in stepLine, stepLine)
        assertTrue(
            "search submit: failed node=n4" in eventsFile.readText(),
            "the evidence records the failed submit",
        )
    }

    @Test
    fun `a credential card's secret is never auto-submitted`() = runBlocking {
        // A card-supplied secret replaces the typed text at execution; the plan-text
        // authority never overrides it and the post-type submit is structurally off
        // (secretSwap != null), even though the plan carried an enterText.
        val password = node(
            "n2", left = 100, top = 200, right = 900, bottom = 260,
            clickable = false, editable = true, text = "Password", isPassword = true,
        )
        val proposeType = """
            {"proposals":[{"description":"the password field","action":"type","expectedEffect":{"expect":"FieldContainsExpectedValue"},
              "text":"proposal-plain-text",
              "box":{"top":0.10,"bottom":0.13,"left":0.10,"right":0.90},"confidence":0.9}]}
        """.trimIndent()
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(plan(enterText = "plan-secret-value", instruction = "type the password")),
                    chatContent(proposeType),
                    chatContent(confirmTrue),
                ),
            ),
            jevResponses = ArrayDeque(
                listOf(
                    jevGround(top = 0.10, bottom = 0.13, left = 0.10, right = 0.90),
                    jevNoul(0.9),
                ),
            ),
        )
        val executor = RecordingExecutor()
        val (evidence, _) = newEvidence("search-submit-credential")
        val page = loginObservation(listOf(password))

        val result = loop(
            transport,
            executor,
            listOf(page, page, doneObservation()),
            evidence,
            gatePresenter = ScriptedGatePresenter(GateResolution.ApproveWithSecret("card-secret-value")),
        ).run("open accounts.test and sign in to the site", "search-submit-credential") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals("card-secret-value", executor.entered.single().second, "the card's secret is typed")
        assertEquals(0, executor.submits, "a card-supplied secret is NEVER auto-submitted")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue(TextSource.REDACTED in stepLine, stepLine)
        assertFalse("(plan text)" in stepLine, "the plan-text marker must not claim a secret was plan-typed: $stepLine")
        assertFalse("(search submitted)" in stepLine, stepLine)
        result.subtaskOutcomes.forEach { line ->
            assertFalse("card-secret-value" in line, "the secret never renders: $line")
            assertFalse("plan-secret-value" in line, "the plan secret never renders: $line")
        }
    }

    // -- WI2: the plan text's authority -----------------------------------------------------

    @Test
    fun `the gate classifies the plan enterText - a card-shaped plan text gates even when the proposal differs`() = runBlocking {
        // The gate must see the PLAN's text, not the proposer's. A card-number-shaped plan
        // value classifies H1 on a TYPE (the content-shape exfil guard) even though the
        // proposal says a harmless "hotels in Riga": with a presenter, a card is
        // presented, the scripted DENY refuses the step, and NOTHING executes.
        val bar = addressBarNode()
        val executor = RecordingExecutor()
        val (evidence, _) = newEvidence("plan-text-gated")
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(plan(enterText = "4111111111111111")),
                    chatContent(nativeTypeAtAddressBar("hotels in Riga")),
                ),
            ),
            jevResponses = ArrayDeque(listOf(jevGround(top = 0.02, bottom = 0.05, left = 0.10, right = 0.90))),
        )

        val result = loop(
            transport,
            executor,
            listOf(observation(listOf(bar)), observation(listOf(bar))),
            evidence,
            gatePresenter = ScriptedGatePresenter(GateResolution.DENIED),
        ).run("open accounts.test and type the saved card into the address bar", "plan-text-gated") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(executor.entered.isEmpty(), "the denied type NEVER executes")
        assertEquals(0, executor.submits, "no submit after a denied type")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("refused-by-owner" in stepLine, stepLine)
        assertTrue("(gate H1 denied)" in stepLine, "the gate saw the card-shaped PLAN text: $stepLine")
    }

    @Test
    fun `a card-shaped proposal text never reaches the gate when the plan text differs`() = runBlocking {
        // The mirror direction: the proposal carries a card-number-shaped text, but the
        // plan's enterText governs both the gate and the typing — an ordinary query
        // classifies null, so NO card is presented (the empty ScriptedGatePresenter
        // would throw) and the plan text types + submits.
        val bar = addressBarNode()
        val executor = RecordingExecutor()
        val (evidence, _) = newEvidence("plan-text-ordinary")

        val result = loop(
            standardTransport(
                plan(enterText = "hotels in Riga next weekend"),
                nativeTypeAtAddressBar("4111111111111111"),
            ),
            executor,
            listOf(observation(listOf(bar)), observation(listOf(bar)), doneObservation()),
            evidence,
            gatePresenter = ScriptedGatePresenter(),
        ).run("open hotels.test and find a hotel in Riga for next weekend", "plan-text-ordinary") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(
            listOf((500 to 70) to "hotels in Riga next weekend"),
            executor.entered,
            "the plan text types, never the card-shaped proposal",
        )
        assertEquals(1, executor.submits, "the ordinary plan text auto-submits")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertTrue("(plan text)" in stepLine, stepLine)
        assertFalse("(gate" in stepLine, "the proposal's card-shaped text never gated: $stepLine")
    }

    @Test
    fun `a native type whose proposal already carries the plan text executes unchanged - no marker`() = runBlocking {
        // The override is not a rewrite for its own sake: when the proposer's text
        // already IS the plan's enterText, the text stays byte-identical and no
        // `(plan text)` marker appears (the marker means "the proposal was overridden").
        val bar = addressBarNode()
        val executor = RecordingExecutor()
        val (evidence, _) = newEvidence("search-submit-same-text")

        val result = loop(
            standardTransport(
                plan(enterText = "hotels in Riga next weekend"),
                nativeTypeAtAddressBar("hotels in Riga next weekend"),
            ),
            executor,
            listOf(observation(listOf(bar)), observation(listOf(bar)), doneObservation()),
            evidence,
        ).run("open hotels.test and find a hotel in Riga for next weekend", "search-submit-same-text") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(
            listOf((500 to 70) to "hotels in Riga next weekend"),
            executor.entered,
            "the identical text types once",
        )
        assertEquals(1, executor.submits, "the chrome submit still fires")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertFalse("(plan text)" in stepLine, "no override happened, so no marker: $stepLine")
        assertTrue("(search submitted)" in stepLine, stepLine)
    }

    @Test
    fun `without a plan enterText the proposal text executes unchanged - no override and no submit`() = runBlocking {
        // The pre-VEXP6 behavior is preserved byte-identically: no plan text means the
        // proposal's own text types and nothing auto-submits.
        val bar = addressBarNode()
        val executor = RecordingExecutor()
        val (evidence, _) = newEvidence("search-submit-no-plan-text")

        val result = loop(
            standardTransport(plan(enterText = null), nativeTypeAtAddressBar("hotels in Riga")),
            executor,
            listOf(observation(listOf(bar)), observation(listOf(bar)), doneObservation()),
            evidence,
        ).run("open hotels.test and find a hotel in Riga for next weekend", "search-submit-no-plan-text") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(
            listOf((500 to 70) to "hotels in Riga"),
            executor.entered,
            "the proposal's text is used when the plan carries none",
        )
        assertEquals(0, executor.submits, "no plan enterText = no deterministic submit")
        val stepLine = result.subtaskOutcomes.first { it.startsWith("step 0:") }
        assertFalse("(plan text)" in stepLine, stepLine)
    }
}
