package navigator.app.harness.vision

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
 * PHASE 18 STAGE 18.C CN-05 — the CONTRACT'S re-plan reuse law, end to end at the loop
 * level (doc 04 §9.4): a failure re-plan that WEAKENS a stated parameter (two cases ->
 * one item) is refused by the goal contract — zero install, the original goal stands,
 * and the run takes the honest REPLANS_EXHAUSTED terminal (maxReplans 0). The scripted
 * fixture is $0 and deterministic; the weakened ladder is the plan's own case 1.
 *
 * PHASE_18 NOTE: this pins the CN-05 loop wiring just landed (Stage 18.C); the doc 04
 * §9.4 item it maps to is 9.4 (the immutable goal contract) itself.
 */
class Phase18ContractReplanTest {

    /** Throws once the script is spent — an unscripted propose is a loud failure. */
    /**
     * Pops the chat script; when only ONE response remains it REPEATS for every further
     * call (the R2 fixture's deterministic-repeat semantics — a step's propose always
     * gets a parseable proposal). The weakened ladder is the re-plan's response: the
     * contract refuses its install and the run ends before the queue matters again.
     */
    private class ExhaustingTransport(chatResponses: List<String>) : WireTransport {
        private val chat = ArrayDeque(chatResponses)
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            if (!request.url.contains("chat/completions")) {
                throw WireTransportException("no Jev script in this test")
            }
            val body = if (chat.size > 1) chat.removeFirst() else chat.first()
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
        override fun tap(x: Int, y: Int): Boolean { taps.add(x to y); return true }
        override fun clickNode(x: Int, y: Int): Boolean = true
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean = true
        override fun back(): Boolean = true
        override fun submit(): Boolean = true
        override fun enterText(x: Int, y: Int, text: String): Boolean = true
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

    private val searchNode = ObservedNode(
        id = "n1", text = "Search", desc = "", clickable = true, editable = false,
        scrollable = false, left = 100, top = 400, right = 300, bottom = 500,
        className = "android.widget.Button", sourceOrder = 0,
    )

    /** The INITIAL ladder: binds the stated quantity (two cases) and the named site. */
    private val initialPlan = """
        {"summary":"add two cases of the drink to the cart on cart.test","taskClass":"action","subtasks":[
          {"instruction":"add 2 cases of the drink to the cart on cart.test","done":{"urlContains":"cart.test/cart"}}]}
    """.trimIndent()

    /** The WEAKENED re-plan: one item — the contract must refuse the install. */
    private val weakenedReplan = """
        {"summary":"add one item of the drink to the cart","taskClass":"action","subtasks":[
          {"instruction":"add 1 item of the drink to the cart","done":{"urlContains":"cart.test/cart"}}]}
    """.trimIndent()

    private val tapProposal = """
        {"proposals":[{"description":"the 'Search' button","action":"tap",
          "expectedEffect":{"expect":"TargetStateChanged"},
          "box":{"top":0.20,"bottom":0.25,"left":0.10,"right":0.30},"confidence":0.9}]}
    """.trimIndent()

    @Test
    fun `a re-plan that weakens the stated quantity is refused by the goal contract`() = runBlocking {
        val base = Files.createTempDirectory("p18-contract").toFile()
        val evidence = VisionEvidence(base, "contract-replan")
        var iterations = 0
        val loop = VisionLoop(
            observer = RepeatingObserver(
                StepObservation(
                    url = "https://capture-fail.test/page", title = "Page",
                    lines = listOf("search page"), nodes = listOf(searchNode),
                    windowPackage = "com.android.chrome",
                ),
            ),
            executor = RecordingExecutor(),
            capture = FakeCapture(
                VisionCapture.ScreenShot(
                    base64 = "QUJD", width = 1000, height = 2000,
                    screenWidth = 1000, screenHeight = 2000, sha256 = "contract-fixture",
                    jpegBytes = byteArrayOf(1, 2, 3),
                ),
            ),
            haiku = VisionHaiku(
                ExhaustingTransport(listOf(chatContent(initialPlan), chatContent(tapProposal), chatContent(tapProposal), chatContent(weakenedReplan))),
                "fixture-key", backoffBaseMs = 1, retryDelay = {},
            ),
            jev = VisionJev(
                ExhaustingTransport(emptyList()),
                "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                backoffBaseMs = 1, retryDelay = {},
            ),
            budget = ExpBudget(),
            evidence = evidence,
            config = VisionLoop.Config(settleMs = 0, maxReplans = 1, stepFailuresBeforeReplan = 1),
        )
        val result = loop.run("on cart.test add two cases of the drink to the cart", "contract-replan") {
            ++iterations > 10
        }

        // CN-05: the weakened re-plan is refused by the contract — the original goal
        // stands, and the run ends with the honest exhausted terminal.
        println("DIAG terminal=${result.terminal} steps=${result.stepsUsed} replans=${result.replans}")
        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(
            result.subtaskOutcomes.any { it.contains("refused by the goal contract") },
            result.subtaskOutcomes.toString(),
        )
        assertTrue(
            result.subtaskOutcomes.any { it.contains("2 cases") },
            "the refusal names the weakened parameter: ${result.subtaskOutcomes}",
        )
        val log = java.io.File(base, "contract-replan/events.log").readText()
        assertTrue(
            "the original goal stands" in log,
            "the contract refusal rides the evidence: ${log.take(2000)}",
        )
    }
}
