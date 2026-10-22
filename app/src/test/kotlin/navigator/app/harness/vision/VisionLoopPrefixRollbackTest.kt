package navigator.app.harness.vision

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
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
 * PHASE_17 Gate 17.0.1 — the vision side of the completed-prefix audit. The R2 vision
 * run is the fixture: the engine left Rumble for Google, subtask 1's `urlHost` became
 * false, and every re-plan resumed at subtask 2. The audit must roll the pointer back
 * after the third failure since the last advance.
 */
class VisionLoopPrefixRollbackTest {

    private class ScriptedTransport(private val chatResponses: ArrayDeque<String>) : WireTransport {
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            if (!request.url.contains("chat/completions")) {
                throw WireTransportException("no Jev script in this test")
            }
            val body = chatResponses.removeFirstOrNull()
                ?: throw WireTransportException("chat script exhausted")
            return WireHttpResponse(200, body)
        }
    }

    /** Pops the script; repeats the LAST observation once the script is spent. */
    private class RepeatingObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        private var last: StepObservation = observations.last()
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull()?.also { last = it } ?: last
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

    private fun node(id: String, text: String) = ObservedNode(
        id = id, text = text, desc = "", clickable = true, editable = false,
        scrollable = false, left = 100, top = 200, right = 300, bottom = 260,
        className = "android.widget.Button", sourceOrder = 0,
    )

    private fun observation(url: String, title: String) = StepObservation(
        url = url,
        title = title,
        lines = listOf("WWE 32.3K Followers All Videos Shorts"),
        nodes = listOf(node("n1", "Search")),
    )

    /** Three subtasks; subtask 1 is the URL identity that goes false on the Google page. */
    private val plan = """
        {"summary":"find the WWE channel on Rumble and play its latest live video","taskClass":"action","subtasks":[
          {"instruction":"open rumble.com","done":{"urlHost":"rumble.com"}},
          {"instruction":"find the WWE channel","done":{"markerText":"WWE"}},
          {"instruction":"play the latest video","done":{"elementPresent":"video player"}}]}
    """.trimIndent()

    private val emptyProposals = """{"proposals":[]}"""
    private val confirmTrue = """{"complete":true,"reason":"the outcome is visible"}"""

    @Test
    fun `the R2 lost-site shape rolls the pointer back to the completed urlHost subtask`() = runBlocking {
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    chatContent(plan),           // the initial plan
                    chatContent(confirmTrue),    // subtask 1's accept-side Haiku confirm
                    chatContent(emptyProposals), // failure 1 at subtask 2 (Google)
                    chatContent(emptyProposals), // failure 2 -> the first informed re-plan
                    chatContent(plan),           // the re-plan (valid, same ladder)
                    chatContent(emptyProposals), // failure 3 -> the audit must roll back
                    chatContent(confirmTrue),    // the re-entered subtask 1's accept confirm
                ),
            ),
        )
        val base = Files.createTempDirectory("vexp17-prefix").toFile()
        val evidence = VisionEvidence(base, "prefix-test")
        var iterations = 0
        val loop = VisionLoop(
            observer = RepeatingObserver(
                // The initial plan observes once; the step loop observes again. Both must
                // be the Rumble page so subtask 1's urlHost accepts on the first step.
                observation("https://rumble.com/c/wwe", "WWE"),
                observation("https://rumble.com/c/wwe", "WWE"),
                observation("https://www.google.com/search?q=WWE", "Google"),
                observation("https://www.google.com/search?q=WWE", "Google"),
                observation("https://www.google.com/search?q=WWE", "Google"),
                observation("https://rumble.com/c/wwe", "WWE"),
            ),
            executor = RecordingExecutor(),
            capture = VisionCapture(null),
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(
                transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                backoffBaseMs = 1, retryDelay = {}, maxRetries = 0,
            ),
            budget = ExpBudget(),
            evidence = evidence,
            config = VisionLoop.Config(maxReplans = 5, prefixAuditAfter = 3),
        )

        val result = loop.run(
            "find the wwe channel on rumble.com and press the latest video in Live tab",
            "prefix-test",
            isCancelled = { ++iterations > 12 },
        )

        val log = File(base, "prefix-test/events.log").readText()
        assertTrue(log.contains("PREFIX ROLLBACK: subtask 1/3"), log)
        assertTrue(
            result.subtaskOutcomes.any { it.contains("PREFIX ROLLBACK") },
            "the rollback must be in the run's outcomes: ${result.subtaskOutcomes}",
        )
    }
}
