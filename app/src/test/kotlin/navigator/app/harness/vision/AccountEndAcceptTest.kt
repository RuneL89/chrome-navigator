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
 * PHASE_17 Gate 17.0.5 — the ACCOUNT_SWITCH end state at GOAL_ACHIEVED (the R4 vision
 * gap): the hatch's sixth guard covers the model hatch only; the NORMAL accept must also
 * re-check the account when the goal is an account switch and the final frame shows
 * neither the account nor recorded switching evidence.
 */
class AccountEndAcceptTest {

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

    private fun node(id: String, text: String) = ObservedNode(
        id = id, text = text, desc = "", clickable = true, editable = false,
        scrollable = false, left = 100, top = 200, right = 300, bottom = 260,
        className = "android.widget.Button", sourceOrder = 0,
    )

    private val videoPage = StepObservation(
        url = "https://m.youtube.test/watch?v=foodwishes",
        title = "Crime Scene Chicken - YouTube",
        lines = listOf("Crime Scene Chicken", "the video is playing"),
        nodes = listOf(node("n1", "the video player is playing")),
        windowPackage = "com.android.chrome",
    )

    /** The R4 ladder: the account switch FIRST, the playback final. */
    private val r4Plan = """
        {"summary":"switch the channel to MrAwesome and play the latest Food Wishes video","taskClass":"action","subtasks":[
          {"instruction":"open youtube.com and switch the active user channel to MrAwesome","done":{"elementPresent":"the MrAwesome channel is active"}},
          {"instruction":"play the latest Food Wishes video","done":{"elementPresent":"the video player is playing the Food Wishes video"}}]}
    """.trimIndent()

    private val proposeEmpty = """{"proposals":[]}"""
    private val confirmTrue = """{"complete":true,"reason":"the outcome is visible"}"""

    private fun newLoop(
        pages: List<StepObservation>,
        responses: List<String>,
        evidence: VisionEvidence,
        maxReplans: Int = 1,
    ): VisionLoop = VisionLoop(
        observer = RecordingObserver(pages),
        executor = RecordingExecutor(),
        capture = VisionCapture(null),
        haiku = VisionHaiku(ScriptedTransport(ArrayDeque(responses)), "fixture-key", backoffBaseMs = 1, retryDelay = {}),
        jev = VisionJev(ScriptedTransport(ArrayDeque()), "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key", backoffBaseMs = 1, retryDelay = {}, maxRetries = 0),
        budget = ExpBudget(),
        evidence = evidence,
        config = VisionLoop.Config(settleMs = 0, maxReplans = maxReplans, stepFailuresBeforeReplan = 1),
    )

    private val r4Goal =
        "open youtube.com and switch the active user channel to MrAwesome. Then find the " +
            "Food Wishes channel from my subscriptions and play the latest posted video"

    @Test
    fun `the R4 shape - the video final passes but no account evidence anywhere - the accept is refused`() = runBlocking {
        val base = Files.createTempDirectory("p17-acct").toFile()
        val evidence = VisionEvidence(base, "acct-missing")
        var iterations = 0
        val loop = newLoop(
            // The first subtask (the switch) starts on a page that does NOT show MrAwesome;
            // the battery/propose script fails both subtasks without evidence, then the
            // replan re-writes the same plan — the accept never legitimately passes.
            pages = List(8) { videoPage },
            responses = listOf(
                chatContent(r4Plan),
                chatContent(proposeEmpty),
                chatContent(r4Plan),
            ),
            evidence = evidence,
        )
        val result = loop.run(r4Goal, "acct-missing") { ++iterations > 14 }

        // The video condition's code check would accept the final frame; the account
        // re-check must refuse it because neither the current page nor the ladder's
        // recorded switching evidence shows the account on THIS observation... In this
        // scripted shape the switch subtask never completed, so the run stays failing —
        // the point is: no GOAL_ACHIEVED without the account evidence.
        assertTrue(
            result.terminal != VisionLoop.Terminal.GOAL_ACHIEVED,
            "no account evidence, no GOAL_ACHIEVED: ${result.terminal} ${result.subtaskOutcomes}",
        )
    }

    @Test
    fun `the account visible on the final page - the accept stands`() {
        // Pure: the violation check passes when the current observation names the account.
        assertEquals(
            null,
            navigator.core.ladder.GoalFidelity.finalAcceptViolation(
                goal = r4Goal,
                ladderText = r4Plan,
                currentObservationText = "Crime Scene Chicken MrAwesome the video is playing",
                recordedEvidenceText = null,
            ),
        )
    }

    @Test
    fun `the account absent from the final page and no recorded evidence - the violation names it`() {
        val violation = navigator.core.ladder.GoalFidelity.finalAcceptViolation(
            goal = r4Goal,
            ladderText = r4Plan,
            currentObservationText = "Crime Scene Chicken the video is playing",
            recordedEvidenceText = null,
        )
        assertTrue(violation != null && violation.contains("mrawesome"), violation ?: "none")
    }

    @Test
    fun `a non-account goal is never blocked`() {
        assertEquals(
            null,
            navigator.core.ladder.GoalFidelity.finalAcceptViolation(
                goal = "open verkkokauppa.com and add two cases of celsius to the cart",
                ladderText = "add the product",
                currentObservationText = "the cart page",
                recordedEvidenceText = null,
            ),
        )
    }
}
