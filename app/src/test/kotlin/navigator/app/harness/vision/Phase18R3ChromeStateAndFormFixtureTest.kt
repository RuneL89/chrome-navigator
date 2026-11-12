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
 * PHASE 18 STAGE 18.A — SANITIZED REGRESSION FIXTURES (R3: the Chrome UI state + the
 * multi-field form). SANITIZED from UAT 17.1 R3-vision / R3-vision-2
 * (`.state/phase-17-uat/17.1/R3-*`): the real checkout, its language, the store, the
 * address and the person names are removed — a generic checkout at `shop.example`,
 * sample values only.
 *
 * What these pin (CURRENT behavior, $0 scripted replay):
 *
 * 1. THE TAB-SWITCHER STATE WITHOUT A LAUNCHER — the off-page detector classifies the
 *    browser-chrome state and, with no code-owned browser launcher wired (the vision
 *    route constructs none), the loop logs `off-page detected, no recovery` and keeps
 *    the ordinary failure/re-plan path. The WITH-launcher recovery (restore the
 *    existing checkout tab) is [CheckoutRecoveryTest] — not duplicated here.
 * 2. THE 3-FIELD FORM WITH ONE REQUIRED FIELD EMPTY — a TYPE lands in the empty
 *    phone/email fields, the per-field read-back sees the text did NOT land (the
 *    fixture page is static), the failures feed the re-plan path, and the run ends
 *    WITHOUT a success terminal: the required-empty form subtask stays open. The
 *    FORM: fact line names the required-empty field in the proposer's request.
 *
 * Changed by: Stage 18.D CN-12 (ChromeUiState recovery) and CN-13 (field-level form
 * law: required-empty keeps the subtask open BY LAW, post-write verification).
 * Vision law: Project Vision/04 §9.10 (Chrome-state and checkout recovery) and §9.3
 * (field value landed as the verified postcondition).
 *
 * Fixture pages: `app/src/test/resources/fixtures/phase18/r3-checkout-form.html` and
 * `r3-tab-switcher.html` (the emulator leg serves them later).
 */
class Phase18R3ChromeStateAndFormFixtureTest {

    /**
     * Routes by URL: chat-completions pops the chat script, everything else the Jev
     * script. A queue's LAST response repeats when the script is spent — the fixture
     * steps are identical, so the tail stays deterministic without a fixed length.
     */
    private class ScriptedTransport(
        chatResponses: List<String>,
        jevResponses: List<String>,
    ) : WireTransport {
        private val chat = ArrayDeque(chatResponses)
        private val jev = ArrayDeque(jevResponses)
        val bodies = mutableListOf<String>()
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            bodies.add(request.body)
            val queue = if (request.url.contains("chat/completions")) chat else jev
            // The LAST scripted response never pops: it repeats for every further call.
            val body = if (queue.size > 1) queue.removeFirst() else queue.firstOrNull()
                ?: throw WireTransportException("script exhausted for ${request.url}")
            return WireHttpResponse(200, body)
        }
    }

    /** Pops the script; repeats the LAST observation once the script is spent. */
    private class ScriptedObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        private var last: StepObservation = observations.last()
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull()?.also { last = it } ?: last
    }

    private class RecordingExecutor : ActionExecutor {
        val entered = mutableListOf<Pair<Pair<Int, Int>, String>>()
        override fun tap(x: Int, y: Int): Boolean = true
        override fun clickNode(x: Int, y: Int): Boolean = true
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean = true
        override fun back(): Boolean = true
        override fun submit(): Boolean = true
        override fun enterText(x: Int, y: Int, text: String): Boolean {
            entered.add((x to y) to text)
            return true
        }
    }

    private class FakeCapture(private val shot: VisionCapture.ScreenShot) : VisionCapture(null) {
        override suspend fun capture(exclusion: VisionMath.RectPx?): VisionCapture.ScreenShot = shot
    }

    private fun chatContent(innerJson: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5) })
    }.toString()

    private fun jevGround(left: Double, right: Double, top: Double, bottom: Double): String =
        buildJsonObject {
            put("answers", buildJsonObject {
                put("b0", buildJsonObject {
                    put("type", JsonPrimitive("box"))
                    put("box", buildJsonObject {
                        put("top", JsonPrimitive(top)); put("bottom", JsonPrimitive(bottom))
                        put("left", JsonPrimitive(left)); put("right", JsonPrimitive(right))
                    })
                    put("confidence", JsonPrimitive(0.9))
                })
                put("pick", buildJsonObject {
                    put("type", JsonPrimitive("choice"))
                    put("choice", JsonPrimitive("p0"))
                    put("probabilities", buildJsonObject {
                        put("p0", JsonPrimitive(0.9)); put("none", JsonPrimitive(0.1))
                    })
                })
            })
            put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
        }.toString()

    private fun label(id: String, text: String, top: Int) = ObservedNode(
        id = id, text = text, desc = "", clickable = false, editable = false,
        scrollable = false, left = 0, top = top, right = 1000, bottom = top + 40,
        className = "android.view.View", sourceOrder = 0,
    )

    private fun field(id: String, value: String, top: Int) = ObservedNode(
        id = id, text = value, desc = "", clickable = false, editable = true,
        scrollable = false, left = 0, top = top, right = 1000, bottom = top + 40,
        className = "android.widget.EditText", sourceOrder = 0,
    )

    private fun chromeCard(id: String, text: String) = ObservedNode(
        id = id, text = text, desc = "", clickable = true, editable = false,
        scrollable = false, left = 100, top = 400, right = 400, bottom = 600,
        className = "android.widget.Button", sourceOrder = 0, browserChrome = true,
    )

    /** The sanitized checkout page: 3 fields, the EMAIL one required and empty. */
    private val checkoutPage = StepObservation(
        url = "https://shop.example/checkout",
        title = "Checkout — Sample Store",
        lines = listOf("Full name", "Sample Buyer", "Email address *", "Phone", "Continue"),
        nodes = listOf(
            label("n0", "Full name", 0),
            field("n1", "Sample Buyer", 50),
            label("n2", "Email address *", 100),
            field("n3", "", 150),
            label("n4", "Phone", 200),
            field("n5", "", 250),
            label("n6", "Continue", 300),
        ),
        windowPackage = "com.android.chrome",
    )

    /** The sanitized tab-switcher state: chrome title, blank URL, chrome card nodes. */
    private val tabSwitcher = StepObservation(
        url = "",
        title = "Tab switcher",
        lines = listOf("Checkout — Sample Store", "Home", "New tab"),
        nodes = listOf(
            chromeCard("n1", "Checkout — Sample Store"),
            chromeCard("n2", "Home"),
            chromeCard("n3", "New tab"),
        ),
        windowPackage = "com.android.chrome",
    )

    private fun shot() = VisionCapture.ScreenShot(
        base64 = "QUJD", width = 1000, height = 2000,
        screenWidth = 1000, screenHeight = 2000, sha256 = "r3-fixture",
        jpegBytes = byteArrayOf(1, 2, 3),
    )

    // -- fixture 1: the tab switcher with no launcher wired ---------------------------------

    @Test
    fun `the R3 tab-switcher state is detected and logged - without a launcher no recovery runs`() =
        runBlocking {
            val base = Files.createTempDirectory("p18-r3-tabs").toFile()
            val evidence = VisionEvidence(base, "r3-tabs")
            val plan = """
                {"summary":"complete the checkout at shop.example","taskClass":"action","subtasks":[
                  {"instruction":"complete the checkout","done":{"markerText":"order placed"}}]}
            """.trimIndent()
            val emptyProposals = """{"proposals":[]}"""
            val transport = ScriptedTransport(
                chatResponses = listOf(
                    chatContent(plan),
                    chatContent(emptyProposals),
                    chatContent(plan),
                    chatContent(emptyProposals),
                ),
                jevResponses = emptyList(),
            )
            var iterations = 0
            val loop = VisionLoop(
                observer = ScriptedObserver(
                    checkoutPage,   // the plan + the checkout state's first record
                    checkoutPage,   // step: the checkout records; the empty proposals fail
                    tabSwitcher,    // the drift into browser chrome (no launcher wired)
                    tabSwitcher,
                ),
                executor = RecordingExecutor(),
                capture = FakeCapture(shot()),
                haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
                jev = VisionJev(
                    transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                    backoffBaseMs = 1, retryDelay = {}, maxRetries = 0,
                ),
                budget = ExpBudget(),
                evidence = evidence,
                config = VisionLoop.Config(settleMs = 0, maxReplans = 1, stepFailuresBeforeReplan = 1),
                // NO browserLauncher: the vision route constructs none by default.
            )
            val result = loop.run("complete the checkout at shop.example", "r3-tabs") {
                ++iterations > 12
            }

            val log = File(base, "r3-tabs/events.log").readText()
            assertTrue(log.contains("checkout state recorded"), log.take(2000))
            assertTrue(
                log.contains("off-page detected, no recovery"),
                "the detector must classify the tab switcher and log the no-recovery path",
            )
            assertTrue(
                result.subtaskOutcomes.none { it.contains("OFF-PAGE RECOVERY") },
                "no recovery may run without a launcher: ${result.subtaskOutcomes}",
            )
            assertTrue(
                result.terminal != VisionLoop.Terminal.GOAL_ACHIEVED &&
                    result.terminal != VisionLoop.Terminal.MODELS_CONFIRM_DONE,
                "no success terminal on the lost checkout: ${result.terminal}",
            )
        }

    // -- fixture 2: the 3-field form, one required field stays empty ------------------------

    @Test
    fun `the R3 required-empty form keeps the subtask open - no false success, facts name the field`() =
        runBlocking {
            val base = Files.createTempDirectory("p18-r3-form").toFile()
            val evidence = VisionEvidence(base, "r3-form")
            val plan = """
                {"summary":"fill the contact form and place the order","taskClass":"action","subtasks":[
                  {"instruction":"fill the contact form and place the order","done":{"markerText":"order placed"}}]}
            """.trimIndent()
            // The proposer types into an empty field; the fixture page is static, so the
            // per-field read-back sees the text never landed (the R3 fill gap).
            fun typeProposal(description: String, text: String, top: Double, bottom: Double) =
                """{"proposals":[{"description":"$description","action":"type","expectedEffect":{"expect":"FieldContainsExpectedValue"},"text":"$text",
                    "box":{"top":$top,"bottom":$bottom,"left":0.0,"right":1.0},"confidence":0.9}]}"""
                    .replace("\n", "")
            val transport = ScriptedTransport(
                chatResponses = listOf(
                    chatContent(plan),
                    chatContent(typeProposal("the 'Email address *' field", "buyer@example.test", 0.075, 0.095)),
                    chatContent(plan),
                    chatContent(typeProposal("the 'Phone' field", "555 0100", 0.125, 0.145)),
                ),
                jevResponses = listOf(
                    jevGround(0.0, 1.0, 0.075, 0.095), // the email field node n3
                    jevGround(0.0, 1.0, 0.125, 0.145), // the phone field node n5
                ),
            )
            val executor = RecordingExecutor()
            var iterations = 0
            val loop = VisionLoop(
                observer = ScriptedObserver(
                    checkoutPage,  // the plan
                    checkoutPage,  // step: type into email; read-back fails next iteration
                    checkoutPage,  // read-back: STILL EMPTY -> re-plan
                    checkoutPage,  // step: type into phone
                    checkoutPage,  // read-back: STILL EMPTY -> re-plans exhausted
                ),
                executor = executor,
                capture = FakeCapture(shot()),
                haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
                jev = VisionJev(
                    transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                    backoffBaseMs = 1, retryDelay = {},
                ),
                budget = ExpBudget(),
                evidence = evidence,
                config = VisionLoop.Config(settleMs = 0, maxReplans = 1, stepFailuresBeforeReplan = 1),
            )
            val result = loop.run(
                "fill the contact form at shop.example and place the order",
                "r3-form",
            ) { ++iterations > 14 }

            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal, result.subtaskOutcomes.toString())
            // The typed text never landed on the static page: the read-back refused it.
            val log = File(base, "r3-form/events.log").readText()
            assertTrue(log.contains("STILL EMPTY"), "the field read-back must fail loudly: ${log.take(2000)}")
            assertTrue(executor.entered.isNotEmpty(), "the scripted types dispatched")
            // The FORM: facts rode the proposer's request and named the required-empty field.
            val proposeBody = transport.bodies.firstOrNull { "PROPOSE" in it || "FORM:" in it }
            assertTrue(proposeBody != null && proposeBody.contains("FORM:"), "the facts ride the propose call")
            assertTrue(
                proposeBody!!.contains("required empty"),
                "the required-empty email field leads the facts: ${proposeBody.take(400)}",
            )
            // No success terminal: the required-empty form never "completed".
            assertTrue(
                result.terminal != VisionLoop.Terminal.GOAL_ACHIEVED &&
                    result.terminal != VisionLoop.Terminal.MODELS_CONFIRM_DONE,
                result.terminal.toString(),
            )
        }
}
