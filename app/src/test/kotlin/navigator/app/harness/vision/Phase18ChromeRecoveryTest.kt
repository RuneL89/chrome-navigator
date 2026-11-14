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
import navigator.app.loop.BrowserLauncher
import navigator.app.loop.LoopObserver
import navigator.app.loop.StepObservation
import navigator.app.observe.ChromeUiState
import navigator.app.observe.ChromeUiStateDetector
import navigator.core.enumerate.ObservedNode
import navigator.core.ladder.GoalFidelity
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport

/**
 * PHASE_18 Stage 18.D Gate 18.D1 — the six CN-12 Chrome/tab-switcher/checkout recovery
 * cases (doc 04 §9.10 + doc 06 §4, the plan's §6 CN-12 test list). Scripted transports
 * only ($0, no secrets):
 *
 * 1. checkout followed by a tab switcher → recover the EXISTING checkout tab (identified
 *    from the fresh tab evidence; never a New-tab action);
 * 2. tab identity ambiguous → the owner-handoff outcome, zero new-tab action;
 * 3. a BATTERY re-plan after checkout → no product search, cart recreation, or address
 *    re-entry (the battery path cannot bypass the no-restart guard);
 * 4. the recovered checkout has different contents → stop and reverify, never continue
 *    on stale evidence;
 * 5. the "continue to payment" control → never treated as order confirmation (the
 *    GoalFidelity/PurchaseControl vocabularies referenced, not duplicated);
 * 6. a Chrome menu/dialog during a raw action → the guard refuses with zero
 *    page-coordinate touches.
 */
class Phase18ChromeRecoveryTest {

    // -- scripted edges -------------------------------------------------------------------

    /** Routes chat by the system prompt; repeats the LAST plan when the script is spent. */
    private class ChatTransport(private val responses: List<String>) : WireTransport {
        private val queue = ArrayDeque(responses)
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            if (!request.url.contains("chat/completions")) {
                throw IllegalStateException("no Jev script in this test")
            }
            val body = if (queue.size > 1) queue.removeFirst() else queue.firstOrNull()
                ?: throw IllegalStateException("chat script exhausted")
            return WireHttpResponse(200, body)
        }
    }

    /** Answers the battery's text noul (question name `v<i>`) with a fixed pTrue. */
    private inner class JevBatteryTransport(private val pTrue: Double) : WireTransport {
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            if (request.url.contains("chat/completions")) {
                throw IllegalStateException("no Haiku script in the Jev transport")
            }
            val name = Regex("\"name\":\"(v\\d+)\"").find(request.body)?.groupValues?.get(1) ?: "v0"
            return WireHttpResponse(200, jevNoul(name, pTrue))
        }
    }

    /** Grounds the FIRST proposal at the scripted fractional box (the R3 fixture pattern). */
    private inner class JevGroundTransport(
        private val left: Double, private val right: Double,
        private val top: Double, private val bottom: Double,
    ) : WireTransport {
        /** The part-1 verifier carry: the JEV-CALL COUNTER — a refused touch must cost
         *  ZERO grounding (Jev) calls, not just zero taps (the refusal fires BEFORE
         *  grounding; case 6 pins the ledger). */
        var calls = 0
            private set

        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            if (request.url.contains("chat/completions")) {
                throw IllegalStateException("no Haiku script in the Jev transport")
            }
            calls += 1
            return WireHttpResponse(200, jevGround(left, right, top, bottom))
        }
    }

    private class ScriptedObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        private var last: StepObservation = observations.last()
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull()?.also { last = it } ?: last
    }

    private class FakeCapture(private val shot: VisionCapture.ScreenShot) : VisionCapture(null) {
        override suspend fun capture(exclusion: VisionMath.RectPx?): VisionCapture.ScreenShot = shot
    }

    private fun shot() = VisionCapture.ScreenShot(
        base64 = "QUJD", width = 1000, height = 2000,
        screenWidth = 1000, screenHeight = 2000, sha256 = "p18-chrome",
        jpegBytes = byteArrayOf(1, 2, 3),
    )

    private class RecordingExecutor : ActionExecutor {
        val taps = mutableListOf<Pair<Int, Int>>()
        val backs = mutableListOf<Int>()
        override fun tap(x: Int, y: Int): Boolean { taps.add(x to y); return true }
        override fun clickNode(x: Int, y: Int): Boolean = true
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean = true
        override fun back(): Boolean { backs.add(1); return true }
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

    private fun jevNoul(name: String, pTrue: Double): String = buildJsonObject {
        put("answers", buildJsonObject {
            put(name, buildJsonObject {
                put("type", JsonPrimitive("noul"))
                put("noul", JsonPrimitive(pTrue))
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
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

    // -- fixtures (sanitized shop.example; no real store, address, or person) --------------

    private fun node(id: String, text: String, editable: Boolean = false) = ObservedNode(
        id = id, text = text, desc = "", clickable = !editable, editable = editable,
        scrollable = false, left = 100, top = 200, right = 900, bottom = 280,
        className = if (editable) "android.widget.EditText" else "android.widget.Button",
        sourceOrder = 0,
    )

    private fun chromeCard(id: String, text: String) =
        node(id, text).copy(browserChrome = true)

    private val checkout = StepObservation(
        url = "https://shop.example/checkout",
        title = "Checkout — Sample Store",
        lines = listOf("Checkout", "Delivery address", "43,92 €"),
        nodes = listOf(node("n1", "First name", editable = true), node("n2", "Continue to payment")),
        windowPackage = "com.android.chrome",
    )

    /** The tab switcher: the checkout tab's card, a Home card, and the New-tab card. */
    private val tabSwitcher = StepObservation(
        url = "",
        title = "Tab switcher",
        lines = listOf("Checkout — Sample Store", "Home", "New tab"),
        nodes = listOf(
            chromeCard("c1", "Checkout — Sample Store"),
            chromeCard("c2", "Home"),
            chromeCard("c3", "New tab"),
        ),
        windowPackage = "com.android.chrome",
    )

    /** The AMBIGUOUS tab switcher: TWO cards carry the checkout identity. */
    private val ambiguousTabSwitcher = StepObservation(
        url = "",
        title = "Tab switcher",
        lines = listOf("Checkout — Sample Store", "Checkout — Sample Store", "New tab"),
        nodes = listOf(
            chromeCard("c1", "Checkout — Sample Store"),
            chromeCard("c2", "Checkout — Sample Store"),
            chromeCard("c3", "New tab"),
        ),
        windowPackage = "com.android.chrome",
    )

    /** A DIFFERENT page the recovery must never continue on (doc 06 §4). */
    private val otherPage = StepObservation(
        url = "https://other.example/somewhere",
        title = "Somewhere else",
        lines = listOf("A different page", "nothing checkout-shaped here", "plain content"),
        nodes = listOf(node("n1", "A link"), node("n2", "Another link"), node("n3", "Text")),
        windowPackage = "com.android.chrome",
    )

    /** A Chrome MENU surface: one titled clickable row over chrome-only chrome. */
    private val menuSurface = StepObservation(
        url = "",
        title = "",
        lines = listOf("Menu items", "Item two", "Item three"),
        nodes = listOf(
            chromeCard("m1", "Menu items"),
            node("m2", "Item two").copy(browserChrome = true, clickable = false),
            node("m3", "Item three").copy(browserChrome = true, clickable = false),
        ),
        windowPackage = "com.android.chrome",
    )

    private val shopPage = StepObservation(
        url = "https://shop.example/products",
        title = "Sample Store",
        lines = listOf("Products", "A product", "Another product"),
        nodes = listOf(node("n1", "A product"), node("n2", "Another product"), node("n3", "Text")),
        windowPackage = "com.android.chrome",
    )

    private val buyGoal =
        "open shop.example and buy SoundMagic E11C with delivery to koivuviita 7 B 5 for 60 euros"

    /** The checkout-shaped plan the first install carries (the contract's parameters ride it). */
    private val checkoutPlan = """
        {"summary":"buy the product","taskClass":"action","subtasks":[
          {"instruction":"complete the checkout at shop.example to koivuviita 7 B 5 for 60 euros",
           "done":{"markerText":"order placed"}}]}
    """.trimIndent()

    /** The REDO plan: a fresh product search + cart recreation — no checkout subtask. */
    private val redoPlan = """
        {"summary":"search and buy the product","taskClass":"action","subtasks":[
          {"instruction":"Search Google for SoundMagic E11C and open its product page",
           "done":{"elementPresent":"a search result linking to the SoundMagic E11C product page"}},
          {"instruction":"add the product to the cart and complete the purchase to koivuviita 7 B 5 for 60 euros",
           "done":{"markerText":"order placed"}}]}
    """.trimIndent()

    private val emptyProposals = """{"proposals":[]}"""

    private fun newLoop(
        runId: String,
        observer: LoopObserver,
        chatResponses: List<String>,
        jev: WireTransport,
        executor: ActionExecutor,
        launcher: BrowserLauncher?,
        config: VisionLoop.Config,
        capture: VisionCapture = VisionCapture(null),
    ): Pair<VisionLoop, File> {
        val base = Files.createTempDirectory("p18-chrome").toFile()
        val loop = VisionLoop(
            observer = observer,
            executor = executor,
            capture = capture,
            haiku = VisionHaiku(ChatTransport(chatResponses), "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(jev, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key", backoffBaseMs = 1, retryDelay = {}, maxRetries = 0),
            budget = ExpBudget(),
            evidence = VisionEvidence(base, runId),
            config = config,
            browserLauncher = launcher,
        )
        return loop to File(base, "$runId/events.log")
    }

    private fun noSuccess(result: VisionLoop.Result) {
        assertTrue(
            result.terminal != VisionLoop.Terminal.GOAL_ACHIEVED &&
                result.terminal != VisionLoop.Terminal.MODELS_CONFIRM_DONE,
            "no success terminal in a lost-checkout scenario: ${result.terminal}",
        )
    }

    // -- case 1: checkout followed by a tab switcher -> recover the EXISTING tab ----------

    @Test
    fun `case 1 - checkout then tab switcher recovers the existing checkout tab, never a new tab`() =
        runBlocking {
            val launched = mutableListOf<String>()
            val executor = RecordingExecutor()
            var iterations = 0
            val (loop, events) = newLoop(
                "p18-chrome-1",
                ScriptedObserver(checkout, checkout, tabSwitcher, tabSwitcher, tabSwitcher, tabSwitcher),
                chatResponses = listOf(chatContent(checkoutPlan), chatContent(emptyProposals)),
                jev = JevBatteryTransport(0.0),
                executor = executor,
                launcher = BrowserLauncher { launched.add(it) },
                config = VisionLoop.Config(settleMs = 0, maxReplans = 1, stepFailuresBeforeReplan = 2),
            )
            val result = loop.run(buyGoal, "p18-chrome-1") { ++iterations > 16 }

            val log = events.readText()
            assertTrue(log.contains("checkout state recorded"), log.take(1500))
            assertTrue(log.contains("OFF-PAGE RECOVERY #1"), "the recovery fired: ${log.take(1500)}")
            assertTrue(
                log.contains("identified the checkout tab"),
                "the recovery identified the tab from fresh evidence: ${log.take(1500)}",
            )
            assertTrue(launched.isNotEmpty(), "the recovery re-opened the checkout")
            assertTrue(
                launched.all { it == "https://shop.example/checkout" },
                "every recovery re-opens the EXISTING checkout: $launched",
            )
            assertTrue(
                result.subtaskOutcomes.none { it.contains("New tab") },
                "the New tab is never a recovery action: ${result.subtaskOutcomes}",
            )
            noSuccess(result)
        }

    // -- case 2: ambiguous tab identity -> owner handoff, zero new-tab action --------------

    @Test
    fun `case 2 - ambiguous tab identity hands off to the owner with zero new-tab action`() =
        runBlocking {
            val launched = mutableListOf<String>()
            val executor = RecordingExecutor()
            var iterations = 0
            val (loop, events) = newLoop(
                "p18-chrome-2",
                ScriptedObserver(
                    checkout, checkout, ambiguousTabSwitcher, ambiguousTabSwitcher,
                    ambiguousTabSwitcher, ambiguousTabSwitcher,
                ),
                chatResponses = listOf(chatContent(checkoutPlan), chatContent(emptyProposals)),
                jev = JevBatteryTransport(0.0),
                executor = executor,
                launcher = BrowserLauncher { launched.add(it) },
                config = VisionLoop.Config(settleMs = 0, maxReplans = 1, stepFailuresBeforeReplan = 2),
            )
            val result = loop.run(buyGoal, "p18-chrome-2") { ++iterations > 16 }

            val log = events.readText()
            assertTrue(
                result.subtaskOutcomes.any { it.contains("OFF-PAGE HANDOFF") },
                "the ambiguous identity is the owner-handoff outcome: ${result.subtaskOutcomes}",
            )
            assertTrue(
                log.contains("cannot be identified safely"),
                "the handoff names the checkout-integrity law: ${log.take(1500)}",
            )
            assertTrue(launched.isEmpty(), "zero navigation follows a handoff: $launched")
            assertTrue(
                result.subtaskOutcomes.none { it.contains("New tab") },
                "the New tab is never the recovery: ${result.subtaskOutcomes}",
            )
            // LAWFUL RE-PIN (Stage 18.D CN-15, doc 04 §9.9 + §9.10 + doc 06 §4): the
            // unrecoverable checkout identity IS the owner handoff — the run ends at
            // NEEDS_USER (no re-plan spend, no new tab); the OFF-PAGE HANDOFF line stays.
            assertEquals(VisionLoop.Terminal.NEEDS_USER, result.terminal)
        }

    // -- case 3: a BATTERY re-plan after checkout cannot bypass the no-restart rule --------

    @Test
    fun `case 3 - a battery re-plan after checkout is refused - no product search, cart recreation, or address re-entry`() =
        runBlocking {
            val executor = RecordingExecutor()
            var iterations = 0
            // The battery check is bad-is-true and Jev answers 0.9 → the red flag
            // escalates and QUEUES the battery re-plan; the re-plan gate then runs the
            // SAME no-restart guard the ordinary path runs.
            val batteryPlan = """
                {"summary":"buy the product","taskClass":"action","subtasks":[
                  {"instruction":"complete the checkout at shop.example to koivuviita 7 B 5 for 60 euros",
                   "done":{"markerText":"order placed"},
                   "verify":[{"question":"is a checkout error visible on the page?","badIsTrue":true}]}]}
            """.trimIndent()
            val (loop, events) = newLoop(
                "p18-chrome-3",
                ScriptedObserver(checkout, checkout),
                chatResponses = listOf(chatContent(batteryPlan), chatContent(redoPlan)),
                jev = JevBatteryTransport(0.9),
                executor = executor,
                launcher = null,
                config = VisionLoop.Config(
                    settleMs = 0,
                    // maxReplans = 0: an ORDINARY re-plan is impossible — the only way the
                    // redo ladder can reach the guard is the battery-queued path.
                    maxReplans = 0,
                    stepFailuresBeforeReplan = 1,
                ),
            )
            val result = loop.run(buyGoal, "p18-chrome-3") { ++iterations > 12 }

            val log = events.readText()
            assertTrue(
                result.subtaskOutcomes.any { it.contains("BATTERY ESCALATE") },
                "the battery escalated: ${result.subtaskOutcomes}",
            )
            assertTrue(
                result.subtaskOutcomes.any { it.contains("must RESUME from the checkout, not restart the purchase") },
                "the no-restart guard refused the battery re-plan: ${result.subtaskOutcomes}",
            )
            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
            assertTrue(
                result.subtaskOutcomes.none { it.contains("search and buy the product") },
                "the redo ladder (product search + cart recreation) never installed: ${result.subtaskOutcomes}",
            )
            assertTrue(
                executor.taps.isEmpty() && executor.backs.isEmpty(),
                "no navigation accompanied the refused re-plan",
            )
            assertTrue(log.contains("re-plan refused"), log.take(1500))
        }

    // -- case 4: recovered checkout with different contents -> stop and reverify -----------

    @Test
    fun `case 4 - a recovered checkout with different contents stops and reverifies, never continues stale`() =
        runBlocking {
            val launched = mutableListOf<String>()
            val executor = RecordingExecutor()
            var iterations = 0
            val (loop, events) = newLoop(
                "p18-chrome-4",
                ScriptedObserver(checkout, checkout, tabSwitcher, otherPage, otherPage, otherPage),
                chatResponses = listOf(chatContent(checkoutPlan), chatContent(emptyProposals)),
                jev = JevBatteryTransport(0.0),
                executor = executor,
                launcher = BrowserLauncher { launched.add(it) },
                config = VisionLoop.Config(settleMs = 0, maxReplans = 1, stepFailuresBeforeReplan = 2),
            )
            val result = loop.run(buyGoal, "p18-chrome-4") { ++iterations > 16 }

            val log = events.readText()
            assertTrue(log.contains("OFF-PAGE RECOVERY #1"), "the recovery ran: ${log.take(1500)}")
            assertTrue(
                result.subtaskOutcomes.any { it.contains("CHECKOUT REVERIFY") },
                "the restored surface failed revalidation -> stop and reverify: ${result.subtaskOutcomes}",
            )
            assertTrue(
                log.contains("STOP AND REVERIFY"),
                "the reverify names the stale-evidence law: ${log.take(1500)}",
            )
            assertEquals(listOf("https://shop.example/checkout"), launched, "one restore, no more")
            noSuccess(result)
        }

    // -- case 5: "continue to payment" is NOT an order confirmation -------------------------

    @Test
    fun `case 5 - the continue-to-payment control is never an order-confirmation milestone`() {
        // The control itself: the order-control vocabulary deliberately excludes it.
        val continueControl = node("n2", "Continue to payment")
        assertTrue(
            !navigator.app.harness.vision.PurchaseControl.orderControlVisible(listOf(continueControl)),
            "'continue to payment' is not the order control",
        )
        val finnish = node("n2", "Jatka maksamaan")
        assertTrue(
            !navigator.app.harness.vision.PurchaseControl.orderControlVisible(listOf(finnish)),
            "'Jatka maksamaan' is not the order control",
        )
        // The core vocabulary is order-SPECIFIC (Stage 18.C): a payment continuation is
        // not order evidence.
        assertTrue(
            !GoalFidelity.evidencesOrderConfirmation("continue to payment"),
            "'continue to payment' is not order-confirmation evidence",
        )
        assertTrue(
            !GoalFidelity.evidencesOrderConfirmation("jatka maksamaan"),
            "'jatka maksamaan' is not order-confirmation evidence",
        )
        // The milestone derivation agrees: a payment page stays BELOW order confirmation.
        val paymentPage = StepObservation(
            url = "https://shop.example/payment",
            title = "Payment",
            lines = listOf("Continue to payment", "Delivery address", "43,92 €"),
            nodes = listOf(continueControl, node("n1", "First name", editable = true)),
            windowPackage = "com.android.chrome",
        )
        val milestone = navigator.app.loop.BrowserRecoveryController.milestoneOf(paymentPage)
        assertTrue(
            milestone != navigator.app.loop.BrowserRecoveryController.CheckoutMilestone.ORDER_CONFIRMATION,
            "a payment/continue page is never the ORDER_CONFIRMATION milestone: $milestone",
        )
        // The contrast: order-specific evidence DOES reach the milestone.
        val confirmationPage = paymentPage.copy(
            url = "https://shop.example/order/12345",
            title = "Order confirmation",
            lines = listOf("order number 12345", "thank you for your order"),
        )
        assertEquals(
            navigator.app.loop.BrowserRecoveryController.CheckoutMilestone.ORDER_CONFIRMATION,
            navigator.app.loop.BrowserRecoveryController.milestoneOf(confirmationPage),
        )
    }

    // -- case 6: a Chrome menu/dialog during a raw action -> refused, zero page touches -----

    @Test
    fun `case 6 - a chrome menu surface during a raw action refuses the touch with zero page-coordinate dispatches`() =
        runBlocking {
            val executor = RecordingExecutor()
            var iterations = 0
            val tapProposal = """
                {"proposals":[{"description":"Menu items","action":"tap",
                    "expectedEffect":{"expect":"TargetStateChanged"},
                    "box":{"top":0.10,"bottom":0.14,"left":0.10,"right":0.90},"confidence":0.9}]}
            """.trimIndent()
            val jevGround = JevGroundTransport(0.10, 0.90, 0.10, 0.14)
            val plainPlan = """
                {"summary":"browse the store","taskClass":"read","subtasks":[
                  {"instruction":"open the store menu","done":{"markerText":"menu open"}}]}
            """.trimIndent()
            val (loop, events) = newLoop(
                "p18-chrome-6",
                ScriptedObserver(shopPage, menuSurface, menuSurface, menuSurface),
                chatResponses = listOf(chatContent(plainPlan), chatContent(tapProposal)),
                jev = jevGround,
                executor = executor,
                launcher = null,
                config = VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 2),
                capture = FakeCapture(shot()),
            )
            // The detector classifies the menu surface first (pure pin).
            assertEquals(
                ChromeUiState.CHROME_MENU_OR_DIALOG,
                ChromeUiStateDetector.detect(menuSurface),
            )
            val result = loop.run("open the store menu on shop.example", "p18-chrome-6") {
                ++iterations > 12
            }

            val log = events.readText()
            assertTrue(
                log.contains("NOT_DISPATCHED_OFF_PAGE"),
                "the raw-action guard refused the touch: ${log.take(1500)}",
            )
            assertTrue(
                executor.taps.isEmpty(),
                "ZERO page-coordinate dispatches on the menu surface: ${executor.taps}",
            )
            assertTrue(
                executor.backs.isEmpty(),
                "no escape back fired without a checkout milestone (the guard owns the refusal)",
            )
            // The part-1 verifier carry: the refusal fires BEFORE grounding — the Jev
            // call counter must read ZERO grounding calls for the refused touches (not
            // just zero taps).
            assertEquals(
                0,
                jevGround.calls,
                "a refused off-page touch costs ZERO grounding (Jev) calls",
            )
            noSuccess(result)
        }
}
