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
import navigator.app.loop.BrowserLauncher
import navigator.app.loop.LoopObserver
import navigator.app.loop.StepObservation
import navigator.core.enumerate.ObservedNode
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport

/**
 * PHASE_17 Gate 17.0.3 — the off-page recovery (the R3 vision run's shape): the checkout
 * is reached, a tap lands on Chrome's TAB SWITCHER, and the loop re-opens the checkout URL
 * through the code-owned launcher seam — never the New tab, never a re-plan redo.
 */
class CheckoutRecoveryTest {

    private class ScriptedTransport(private val chatResponses: ArrayDeque<String>) : WireTransport {
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            if (!request.url.contains("chat/completions")) {
                throw WireTransportExceptionStub()
            }
            val body = chatResponses.removeFirstOrNull()
                ?: throw WireTransportExceptionStub()
            return WireHttpResponse(200, body)
        }
    }

    private class WireTransportExceptionStub : Exception("no Jev script in this test")

    private class RecordingObserver(private val pages: List<StepObservation>) : LoopObserver {
        private var index = 0
        override suspend fun observe(): StepObservation =
            pages.getOrElse(index) { pages.last() }.also { if (index < pages.lastIndex) index++ }
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
    private val tabSwitcher = StepObservation(
        url = "",
        title = "Tab Switcher",
        lines = listOf("Kassa | CDON", "New tab"),
        nodes = listOf(
            node("n1", "Kassa | CDON").copy(browserChrome = true),
            node("n2", "New tab").copy(browserChrome = true),
        ),
        windowPackage = "com.android.chrome",
    )

    private val plan = """
        {"summary":"buy the product","taskClass":"action","subtasks":[
          {"instruction":"complete the checkout at cdon.test to koivuviita 7 B 5 for 60 euros","done":{"markerText":"Kiitos tilauksestasi"}}]}
    """.trimIndent()
    private val emptyProposals = """{"proposals":[]}"""

    @Test
    fun `the tab switcher after a checkout is recovered by re-opening the checkout url`() = runBlocking {
        val base = Files.createTempDirectory("p17-recovery").toFile()
        val evidence = VisionEvidence(base, "recovery-test")
        val launched = mutableListOf<String>()
        var iterations = 0
        val loop = VisionLoop(
            observer = RecordingObserver(listOf(checkout, checkout, tabSwitcher, tabSwitcher, tabSwitcher, tabSwitcher)),
            executor = RecordingExecutor(),
            capture = VisionCapture(null),
            haiku = VisionHaiku(ScriptedTransport(ArrayDeque(listOf(chatContent(plan), chatContent(emptyProposals), chatContent(plan)))), "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(ScriptedTransport(ArrayDeque()), "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key", backoffBaseMs = 1, retryDelay = {}, maxRetries = 0),
            budget = ExpBudget(),
            evidence = evidence,
            config = VisionLoop.Config(settleMs = 0, maxReplans = 1, stepFailuresBeforeReplan = 1),
            browserLauncher = BrowserLauncher { launched.add(it) },
        )
        val result = loop.run(
            "open cdon.test and buy SoundMagic E11C with delivery to koivuviita 7 B 5 for 60 euros",
            "recovery-test",
        ) { ++iterations > 12 }

        val log = java.io.File(base, "recovery-test/events.log").readText()
        assertTrue(log.contains("checkout state recorded"), log)
        assertTrue(log.contains("OFF-PAGE RECOVERY #1"), log)
        assertTrue(launched.isNotEmpty(), "the recovery re-opened the checkout")
        assertTrue(launched.all { it == "https://cdon.test/kassa" }, "every recovery re-opens the checkout, never the New tab: " + launched)
        assertTrue(
            result.subtaskOutcomes.none { it.contains("OFF-PAGE RECOVERY") && it.contains("New tab") },
            "the recovery never chose the New tab: ${result.subtaskOutcomes}",
        )
    }
}
