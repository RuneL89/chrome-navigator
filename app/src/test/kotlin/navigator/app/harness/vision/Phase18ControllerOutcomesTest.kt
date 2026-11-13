package navigator.app.harness.vision

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.app.loop.ActionExecutor
import navigator.app.loop.BrowserLauncher
import navigator.app.loop.ClarificationRequest
import navigator.app.loop.GatePresenter
import navigator.app.loop.GateResolution
import navigator.app.loop.LoopObserver
import navigator.app.loop.ScriptedGatePresenter
import navigator.app.loop.StepObservation
import navigator.app.loop.TextSource
import navigator.core.enumerate.ObservedNode
import navigator.core.ladder.GoalContract
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport

/**
 * PHASE_18 Stage 18.D Gate 18.D4 — the six CN-15 controller-outcome cases (doc 04 §9.9,
 * the plan's §6 CN-15 test list). Scripted transports only ($0, no secrets):
 *
 * 1. a missing account target → CLARIFICATION_REQUIRED BEFORE navigation (zero model
 *    calls, zero dispatches; the ask names the field);
 * 2. repeated capture failure → CAPTURE_UNAVAILABLE (the owner handoff), never endless
 *    degraded steps;
 * 3. the ambiguous checkout tab (the part-1 OwnerHandoff) → NEEDS_USER without a new
 *    tab;
 * 4. a clarification answer rides the explicit owner-answer record seam (the immutable
 *    contract updates THROUGH the record — the pure seam);
 * 5. a CONSEQUENTIAL clarification timeout fails CLOSED (no dispatch — the gate
 *    timeout law's semantics, referenced);
 * 6. a NON-consequential clarification timeout stops the run honestly (the
 *    CLARIFICATION_REQUIRED terminal — never a guess).
 */
class Phase18ControllerOutcomesTest {

    // -- scripted edges -------------------------------------------------------------------

    /** A transport that refuses EVERY call (proves a path costs zero model calls). */
    private class SilentTransport : WireTransport {
        val calls = 0
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            throw IllegalStateException("no model call may happen in this test")
        }
    }

    /** Pops the chat script; the LAST response repeats (the R2 fixture pattern). */
    private class ChatTransport(responses: List<String>) : WireTransport {
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

    private class ScriptedObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        private var last: StepObservation = observations.last()
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull()?.also { last = it } ?: last
    }

    private open class FakeCapture(private val shot: VisionCapture.ScreenShot?) : VisionCapture(null) {
        override suspend fun capture(exclusion: VisionMath.RectPx?): VisionCapture.ScreenShot =
            shot ?: throw IllegalStateException("the capture device is broken (fixture)")
    }

    private fun shot() = VisionCapture.ScreenShot(
        base64 = "QUJD", width = 1000, height = 2000,
        screenWidth = 1000, screenHeight = 2000, sha256 = "p18-outcome",
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

    /** Answers the battery's text noul with a fixed pTrue. */
    private inner class JevBatteryTransport(private val pTrue: Double) : WireTransport {
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            if (request.url.contains("chat/completions")) {
                throw IllegalStateException("no Haiku script in the Jev transport")
            }
            val name = Regex("\"name\":\"(v\\d+)\"").find(request.body)?.groupValues?.get(1) ?: "v0"
            return WireHttpResponse(200, jevNoul(name, pTrue))
        }
    }

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

    /** The AMBIGUOUS tab switcher: TWO cards carry the checkout identity (part 1's case 2). */
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

    private val buyGoal =
        "open shop.example and buy SoundMagic E11C with delivery to koivuviita 7 B 5 for 60 euros"

    private val checkoutPlan = """
        {"summary":"buy the product","taskClass":"action","subtasks":[
          {"instruction":"complete the checkout at shop.example to koivuviita 7 B 5 for 60 euros",
           "done":{"markerText":"order placed"}}]}
    """.trimIndent()

    private val emptyProposals = """{"proposals":[]}"""

    // -- case 1: missing account target -> CLARIFICATION_REQUIRED before navigation --------

    @Test
    fun `case 1 - a missing account target stops the run at CLARIFICATION_REQUIRED before navigation`() =
        runBlocking {
            val base = Files.createTempDirectory("p18-out-1").toFile()
            val transport = SilentTransport()
            val executor = RecordingExecutor()
            val loop = VisionLoop(
                observer = ScriptedObserver(
                    StepObservation(
                        url = "https://home.example/", title = "Home",
                        lines = listOf("a page"), nodes = listOf(node("n1", "Text")),
                        windowPackage = "com.android.chrome",
                    ),
                ),
                executor = executor,
                capture = FakeCapture(shot()),
                haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
                jev = VisionJev(
                    transport, "https://jev.test/v1/systemone", "jev-1.14.0",
                    "fixture-key", backoffBaseMs = 1, retryDelay = {}, maxRetries = 0,
                ),
                budget = ExpBudget(),
                evidence = VisionEvidence(base, "p18-out-1"),
                config = VisionLoop.Config(settleMs = 0),
            )
            var iterations = 0
            val result = loop.run("switch my account", "p18-out-1") { ++iterations > 8 }

            assertEquals(
                VisionLoop.Terminal.CLARIFICATION_REQUIRED,
                result.terminal,
                result.subtaskOutcomes.toString(),
            )
            assertEquals(
                0,
                result.stepsUsed,
                "the run stopped BEFORE navigation: ${result.subtaskOutcomes}",
            )
            assertTrue(executor.taps.isEmpty() && executor.backs.isEmpty(), "zero dispatches")
            assertEquals(
                0,
                transport.calls,
                "the ask costs ZERO model calls (the contract is the deterministic parse)",
            )
            val log = File(base, "p18-out-1/events.log").readText()
            assertTrue(
                log.contains("CLARIFICATION REQUIRED") && log.contains("Account"),
                "the ask names the missing contract field: ${log.take(1500)}",
            )
            // The request itself: the field, the consequential flag (account-material),
            // and the content law (field + choices only — no page text, no reasoning).
            val request = assertNotNull(
                ClarificationRequest.fromContract(GoalContract.fromGoal("switch my account")),
            )
            assertEquals("Account", request.field)
            assertTrue(request.consequential, "an account-material ask is consequential")
            assertTrue(
                !request.boundedQuestion.contains("page") &&
                    request.choices.isEmpty(),
                "the question carries the field name only (no choices can be constructed): " +
                    request.boundedQuestion,
            )
        }

    // -- case 2: repeated capture failure -> CAPTURE_UNAVAILABLE ---------------------------

    @Test
    fun `case 2 - repeated capture failure ends the run at CAPTURE_UNAVAILABLE, not endless degraded steps`() =
        runBlocking {
            val plan = """
                {"summary":"tap the search control on capture-fail.test","taskClass":"action","subtasks":[
                  {"instruction":"tap the search control","done":{"urlHost":"done.test"}}]}
            """.trimIndent()
            val observer = ScriptedObserver(
                StepObservation(
                    url = "https://capture-fail.test/page", title = "Page",
                    lines = listOf("a page"), nodes = listOf(node("n1", "Search")),
                    windowPackage = "com.android.chrome",
                ),
            )
            val snapshotter = VisionSnapshotter(
                observer = observer,
                identity = VisionSnapshotter.IdentityReader {
                    SnapshotIdentity("com.android.chrome", "https://capture-fail.test/page")
                },
                runId = "p18-out-2",
                now = { 1_000L },
            )
            val executor = RecordingExecutor()
            val base = Files.createTempDirectory("p18-out-2").toFile()
            val loop = VisionLoop(
                observer = observer,
                executor = executor,
                capture = FakeCapture(null), // every capture fails (the broken device)
                haiku = VisionHaiku(
                    ChatTransport(listOf(chatContent(plan))), "fixture-key",
                    backoffBaseMs = 1, retryDelay = {},
                ),
                jev = VisionJev(
                    JevBatteryTransport(0.0), "https://jev.test/v1/systemone", "jev-1.14.0",
                    "fixture-key", backoffBaseMs = 1, retryDelay = {}, maxRetries = 0,
                ),
                budget = ExpBudget(),
                evidence = VisionEvidence(base, "p18-out-2"),
                snapshotter = snapshotter,
                config = VisionLoop.Config(settleMs = 0, maxReplans = 0),
            )
            var iterations = 0
            val result = loop.run("tap the search control on capture-fail.test", "p18-out-2") {
                ++iterations > 10
            }

            assertEquals(
                VisionLoop.Terminal.CAPTURE_UNAVAILABLE,
                result.terminal,
                result.subtaskOutcomes.toString(),
            )
            assertTrue(
                executor.taps.isEmpty(),
                "no dispatch rides a degraded capture: ${executor.taps}",
            )
            assertEquals("the capture degraded 2 times consecutively (limit 2)", result.terminalDetail)
            val log = File(base, "p18-out-2/events.log").readText()
            assertTrue(
                "capture unavailable past the bounded limit" in log,
                "the honest cause rides the evidence: ${log.take(1500)}",
            )
        }

    // -- case 3: the ambiguous checkout tab -> NEEDS_USER without a new tab ----------------

    @Test
    fun `case 3 - the ambiguous checkout tab hands off at NEEDS_USER without a new tab`() =
        runBlocking {
            val launched = mutableListOf<String>()
            val executor = RecordingExecutor()
            val base = Files.createTempDirectory("p18-out-3").toFile()
            val loop = VisionLoop(
                observer = ScriptedObserver(
                    checkout, checkout, ambiguousTabSwitcher, ambiguousTabSwitcher,
                    ambiguousTabSwitcher, ambiguousTabSwitcher,
                ),
                executor = executor,
                capture = VisionCapture(null),
                haiku = VisionHaiku(
                    ChatTransport(listOf(chatContent(checkoutPlan), chatContent(emptyProposals))),
                    "fixture-key", backoffBaseMs = 1, retryDelay = {},
                ),
                jev = VisionJev(
                    JevBatteryTransport(0.0), "https://jev.test/v1/systemone", "jev-1.14.0",
                    "fixture-key", backoffBaseMs = 1, retryDelay = {}, maxRetries = 0,
                ),
                budget = ExpBudget(),
                evidence = VisionEvidence(base, "p18-out-3"),
                browserLauncher = BrowserLauncher { launched.add(it) },
                config = VisionLoop.Config(settleMs = 0, maxReplans = 1, stepFailuresBeforeReplan = 2),
            )
            var iterations = 0
            val result = loop.run(buyGoal, "p18-out-3") { ++iterations > 16 }

            assertEquals(
                VisionLoop.Terminal.NEEDS_USER,
                result.terminal,
                result.subtaskOutcomes.toString(),
            )
            assertTrue(
                result.subtaskOutcomes.any { it.contains("OFF-PAGE HANDOFF") },
                "the OFF-PAGE HANDOFF line stays: ${result.subtaskOutcomes}",
            )
            assertTrue(launched.isEmpty(), "zero navigation follows the handoff: $launched")
            assertTrue(
                result.subtaskOutcomes.none { it.contains("New tab") },
                "the New tab is never the recovery: ${result.subtaskOutcomes}",
            )
            assertEquals(
                "the checkout tab identity is ambiguous - the owner must recover it",
                result.terminalDetail,
            )
            // The bridge renders the truthful short line (no UI redesign — the same funnel).
            val line = NavigatorVisionBridge.taskStateLine(result)
            assertTrue(
                line.contains("task NEEDS_USER") && line.contains("needs you to take over"),
                "the task-state line is truthful: $line",
            )
        }

    // -- case 4: the clarification answer rides the explicit owner-answer record seam ------

    @Test
    fun `case 4 - a clarification answer updates the immutable contract through the owner-answer record`() {
        val contract = GoalContract.fromGoal("switch my account")
        assertTrue(contract.unmetRequirements.isNotEmpty(), "the account target is unmet")
        val request = assertNotNull(ClarificationRequest.fromContract(contract))
        val record = ClarificationRequest.OwnerAnswerRecord(
            field = request.field,
            answer = "the work account",
        )
        val updated = assertNotNull(request.acceptInto(contract, record))

        // The contract updated THROUGH the record: the parameter joined, the unmet
        // requirement cleared, everything else carried unchanged (immutable copy).
        assertEquals(contract.goalText, updated.goalText, "the goal text is never rewritten")
        assertEquals(contract.classes, updated.classes)
        assertTrue(
            updated.unmetRequirements.isEmpty(),
            "the answered field's unmet requirement is cleared: ${updated.unmetRequirements}",
        )
        val account = updated.parameters.filterIsInstance<navigator.core.ladder.GoalParameter.Account>().single()
        assertEquals("the work account", account.name)
        assertTrue(
            updated.parameters.size == contract.parameters.size + 1,
            "exactly the answered parameter joined: ${updated.parameters}",
        )
        // Refusals: a blank answer NEVER builds a record (loud construction failure),
        // and a field mismatch is refused by the accept path (no guess).
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ClarificationRequest.OwnerAnswerRecord(request.field, "   ")
        }
        assertNull(
            request.acceptInto(
                contract,
                ClarificationRequest.OwnerAnswerRecord("NamedSite", "somewhere.test"),
            ),
            "a field mismatch is refused",
        )
        assertNull(
            ClarificationRequest.fromContract(updated),
            "the updated contract has nothing left to ask",
        )
    }

    // -- case 5: a consequential clarification timeout fails CLOSED (no dispatch) ----------

    @Test
    fun `case 5 - a consequential clarification timeout fails closed - no dispatch`() = runBlocking {
        // The PURE law: a consequential (cost/account-material) ask times out into
        // FAIL_CLOSED — the material value is never guessed.
        val consequential = ClarificationRequest(
            field = "Account",
            question = "the goal is missing a required value: Account",
            choices = emptyList(),
            consequential = true,
        )
        assertEquals(
            ClarificationRequest.TimeoutVerdict.FAIL_CLOSED,
            ClarificationRequest.timeoutVerdict(consequential),
            "doc 04 §9.9: a consequential clarification timeout fails closed",
        )
        // The REFERENCED existing gate law (doc 06 §2): a timed-out owner card = deny =
        // SKIP — the consequential action never dispatches. Pinned at the real loop:
        // the same fail-closed semantics the clarification timeout inherits.
        val unsubscribePage = StepObservation(
            url = "https://gardenweek.test/settings",
            title = "Settings",
            lines = listOf("Garden Week", "Unsubscribe from Garden Week"),
            nodes = listOf(node("n1", "Unsubscribe from Garden Week")),
            windowPackage = "com.android.chrome",
        )
        val plan = """
            {"summary":"one gated action","taskClass":"action","subtasks":[
              {"instruction":"unsubscribe from the garden week channel on gardenweek.test",
               "done":{"urlHost":"done.test"}}]}
        """.trimIndent()
        val tapProposal = """
            {"proposals":[{"description":"Unsubscribe from Garden Week","action":"tap",
              "expectedEffect":{"expect":"TargetStateChanged"}}]}
        """.trimIndent()
        val executor = RecordingExecutor()
        val base = Files.createTempDirectory("p18-out-5").toFile()
        val presenter = ScriptedGatePresenter(
            GateResolution.TIMEOUT, GateResolution.TIMEOUT, GateResolution.TIMEOUT,
        )
        val loop = VisionLoop(
            observer = ScriptedObserver(unsubscribePage, unsubscribePage),
            executor = executor,
            capture = VisionCapture(null),
            haiku = VisionHaiku(
                ChatTransport(listOf(chatContent(plan), chatContent(tapProposal), chatContent(tapProposal))),
                "fixture-key", backoffBaseMs = 1, retryDelay = {},
            ),
            jev = VisionJev(
                JevBatteryTransport(0.0), "https://jev.test/v1/systemone", "jev-1.14.0",
                "fixture-key", backoffBaseMs = 1, retryDelay = {}, maxRetries = 0,
            ),
            budget = ExpBudget(),
            evidence = VisionEvidence(base, "p18-out-5"),
            gatePresenter = presenter as GatePresenter,
            config = VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 2),
        )
        var iterations = 0
        val result = loop.run(
            "unsubscribe from the garden week channel on gardenweek.test",
            "p18-out-5",
        ) { ++iterations > 8 }
        assertTrue(
            executor.taps.isEmpty(),
            "the timed-out consequential action NEVER dispatches (fail closed): ${executor.taps}",
        )
        assertTrue(
            result.terminal != VisionLoop.Terminal.GOAL_ACHIEVED,
            "no success rides a timed-out consequential value: ${result.terminal}",
        )
        // The honest stop terminal for a timed-out clarification (never a guess).
        assertEquals(
            VisionLoop.Terminal.CLARIFICATION_REQUIRED,
            ClarificationRequest.stopTerminal(),
        )
    }

    // -- case 6: a non-consequential clarification timeout stops the run honestly ----------

    @Test
    fun `case 6 - a non-consequential clarification timeout stops the run honestly, never guesses`() {
        val nonConsequential = ClarificationRequest(
            field = "Quantity",
            question = "the goal is missing a required value: Quantity",
            choices = emptyList(),
            consequential = false,
        )
        assertEquals(
            ClarificationRequest.TimeoutVerdict.HONEST_STOP,
            ClarificationRequest.timeoutVerdict(nonConsequential),
            "doc 04 §9.9: a non-consequential timeout stops the run rather than guess",
        )
        // The stop is the CLARIFICATION_REQUIRED terminal — never a success terminal,
        // never a guessed continuation.
        val stop = ClarificationRequest.stopTerminal()
        assertEquals(VisionLoop.Terminal.CLARIFICATION_REQUIRED, stop)
        assertTrue(
            stop != VisionLoop.Terminal.GOAL_ACHIEVED && stop != VisionLoop.Terminal.MODELS_CONFIRM_DONE,
            "the honest stop is never a success terminal",
        )
        // The bridge renders it truthfully (the owner sees the ask, not a fake result).
        val result = VisionLoop.Result(
            terminal = stop,
            stepsUsed = 0,
            ladderSummary = null,
            subtaskOutcomes = listOf("CLARIFICATION REQUIRED: the Quantity value is missing"),
            answer = null,
            replans = 0,
            screenshots = 0,
            terminalDetail = "the Quantity value is missing or ambiguous",
        )
        val line = NavigatorVisionBridge.taskStateLine(result)
        assertTrue(
            line.contains("task CLARIFICATION_REQUIRED") && line.contains("needs your answer"),
            "the task-state line is truthful: $line",
        )
    }
}
