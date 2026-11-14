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

/**
 * PHASE_17 Gate 17.0.3 — the NO-REDO-AFTER-CHECKOUT guard (the R3 vision REPLAN #4): the
 * checkout was reached; a re-plan that restarts the purchase (a fresh shop search + cart
 * ladder that drops the checkout subtasks) is refused — the run ends instead of shopping
 * again.
 */
class NoRedoAfterCheckoutTest {

    private class ScriptedTransport(private val chatResponses: ArrayDeque<String>) : WireTransport {
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            if (!request.url.contains("chat/completions")) {
                throw IllegalStateException("no Jev script in this test")
            }
            val body = chatResponses.removeFirstOrNull()
                ?: throw IllegalStateException("chat script exhausted")
            return WireHttpResponse(200, body)
        }
    }

    private class RecordingObserver(private val pages: List<StepObservation>) : LoopObserver {
        private var index = 0
        override suspend fun observe(): StepObservation =
            pages.getOrElse(index) { pages.last() }.also { if (index < pages.lastIndex) index++ }
    }

    private class RecordingExecutor : ActionExecutor {
        override fun tap(x: Int, y: Int): Boolean = true
        override fun clickNode(x: Int, y: Int): Boolean = true
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

    private fun node(id: String, text: String, editable: Boolean = false) = ObservedNode(
        id = id, text = text, desc = "", clickable = !editable, editable = editable,
        scrollable = false, left = 100, top = 200, right = 300, bottom = 260,
        className = if (editable) "android.widget.EditText" else "android.widget.Button",
        sourceOrder = 0,
    )

    private val checkout = StepObservation(
        url = "https://cdon.test/kassa",
        title = "Kassa | CDON",
        lines = listOf("Kassa", "43,92 €"),
        nodes = listOf(node("n1", "Etunimi", editable = true), node("n2", "Jatka maksamaan")),
        windowPackage = "com.android.chrome",
    )

    /** The checkout-shaped plan the first install carries. */
    private val checkoutPlan = """
        {"summary":"buy the product","taskClass":"action","subtasks":[
          {"instruction":"complete the checkout at cdon.test to koivuviita 7 B 5 for 60 euros","done":{"markerText":"Kiitos tilauksestasi"}}]}
    """.trimIndent()

    /** The REDO plan: a fresh shop search + cart — no checkout subtask at all. */
    private val redoPlan = """
        {"summary":"search and buy the product","taskClass":"action","subtasks":[
          {"instruction":"Search Google for SoundMagic E11C and open its product page","done":{"elementPresent":"a search result linking to the SoundMagic E11C product page"}},
          {"instruction":"add the product to the cart and complete the purchase to koivuviita 7 B 5 for 60 euros","done":{"markerText":"Kiitos tilauksestasi"}}]}
    """.trimIndent()

    private val emptyProposals = """{"proposals":[]}"""

    @Test
    fun `a re-plan that restarts the purchase after a checkout is refused`() = runBlocking {
        val base = Files.createTempDirectory("p17-noredo").toFile()
        val evidence = VisionEvidence(base, "noredo-test")
        var iterations = 0
        val loop = VisionLoop(
            observer = RecordingObserver(listOf(checkout, checkout, checkout, checkout)),
            executor = RecordingExecutor(),
            capture = VisionCapture(null),
            haiku = VisionHaiku(
                ScriptedTransport(ArrayDeque(listOf(chatContent(checkoutPlan), chatContent(emptyProposals), chatContent(redoPlan)))),
                "fixture-key", backoffBaseMs = 1, retryDelay = {},
            ),
            jev = VisionJev(ScriptedTransport(ArrayDeque()), "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key", backoffBaseMs = 1, retryDelay = {}, maxRetries = 0),
            budget = ExpBudget(),
            evidence = evidence,
            config = VisionLoop.Config(settleMs = 0, maxReplans = 1, stepFailuresBeforeReplan = 1),
        )
        val result = loop.run(
            "open cdon.test and buy SoundMagic E11C with delivery to koivuviita 7 B 5 for 60 euros",
            "noredo-test",
        ) { ++iterations > 12 }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(
            result.subtaskOutcomes.any { it.contains("must RESUME from the checkout, not restart the purchase") },
            "the refusal is loud: ${result.subtaskOutcomes}",
        )
        // The refused re-plan was never installed: the ladder summary stays the checkout plan.
        assertTrue(
            result.subtaskOutcomes.none { it.contains("search and buy the product") },
            "the redo ladder never installed: ${result.subtaskOutcomes}",
        )
    }
}
