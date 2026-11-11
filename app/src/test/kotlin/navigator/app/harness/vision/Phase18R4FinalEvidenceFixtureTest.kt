package navigator.app.harness.vision

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
 * PHASE 18 STAGE 18.A — SANITIZED REGRESSION FIXTURE (R4, the final-evidence gap).
 *
 * SANITIZED from UAT 17.1 R4 (the account-switch-then-media run: the final frame was a
 * media page carrying no account evidence; `.state/phase-17-uat/17.1/R4-*`). All real
 * site, channel, account, show and person names are removed: the media site is
 * `tubular.test`, the switched profile is the generic `ViewerTwo`, the show is "the
 * sample show"; the fixture page is
 * `app/src/test/resources/fixtures/phase18/r4-media-page.html`.
 *
 * DEFECT-CLAIM RE-VERIFICATION (the §3 adaptation duty): the source plan's R4 claim —
 * "the ordinary accept path does not check the class-specific end-state evidence" — is
 * PARTIALLY FIXED on this frontier: since Phase 17 Stage 0.5, the ordinary accept's
 * `GoalFidelity.finalAcceptViolation` re-checks the switched account at GOAL_ACHIEVED
 * (`AccountEndAcceptTest` pins the pure half). What these fixtures pin:
 *
 * 1. THE RECOGNIZED ACCOUNT-SWITCH SHAPE — the final media page shows the playback
 *    outcome but NO `ViewerTwo` marker: the ordinary accept REFUSES (no false success
 *    terminal). Recorded evidence is not wired yet (the loop passes null), so even a
 *    ladder that names the account cannot rescue the accept — Stage 18.C CN-07's
 *    evidence-scoped verifier changes exactly this.
 * 2. THE UNCLASSIFIED SHAPE — a goal no class recognizes ends GOAL_ACHIEVED on the
 *    same evidence-free page: generic rules, no fabricated class proof (doc 04 §9.4's
 *    law). The single final verifier (Stage 18.C CN-07) formalizes this path.
 *
 * Vision law: Project Vision/04 §9.6 (the single final verifier).
 */
class Phase18R4FinalEvidenceFixtureTest {

    /**
     * Routes by URL: chat-completions pops the chat script, everything else the Jev
     * script. The LAST response repeats when the script is spent (the accept-refusal
     * attempts are identical, so the tail stays deterministic).
     */
    private class ScriptedTransport(
        chatResponses: List<String>,
        jevResponses: List<String>,
    ) : WireTransport {
        private val chat = ArrayDeque(chatResponses)
        private val jev = ArrayDeque(jevResponses)
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            val queue = if (request.url.contains("chat/completions")) chat else jev
            // The LAST scripted response never pops: it repeats for every further call
            // (the accept-refusal attempts are identical).
            val body = if (queue.size > 1) queue.removeFirst() else queue.firstOrNull()
                ?: throw WireTransportException("script exhausted for ${request.url}")
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

    private fun jevNoul(pTrue: Double): String = buildJsonObject {
        put("answers", buildJsonObject {
            put("done", buildJsonObject {
                put("type", JsonPrimitive("noul"))
                put("noul", JsonPrimitive(pTrue))
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    /** The media page: the playback outcome is visible, the profile marker is NOT. */
    private fun mediaPage() = StepObservation(
        url = "https://tubular.test/watch?the-sample-show",
        title = "The sample show",
        lines = listOf(
            "Now playing: the newest episode of the sample show",
            "the video player is playing",
        ),
        nodes = listOf(
            ObservedNode(
                id = "n1", text = "the video player is playing", desc = "",
                clickable = true, editable = false, scrollable = false,
                left = 100, top = 400, right = 900, bottom = 700,
                className = "android.view.View", sourceOrder = 0,
            ),
        ),
        windowPackage = "com.android.chrome",
    )

    /**
     * The sanitized R4 ladder: the profile switch FIRST, the media outcome LAST. The
     * done conditions are urlHost identity checks (STRONG, no battery needed at the
     * accept) so the fixture isolates exactly one question: does the final accept
     * re-check the account evidence.
     */
    private val r4Plan = """
        {"summary":"change the profile on tubular.test and open the newest sample show page","taskClass":"action","subtasks":[
          {"instruction":"change the active profile to ViewerTwo","done":{"urlHost":"tubular.test"}},
          {"instruction":"open the newest sample show page","done":{"urlHost":"tubular.test"}}]}
    """.trimIndent()

    private val plainPlan = """
        {"summary":"open the newest sample show page on tubular.test","taskClass":"read","subtasks":[
          {"instruction":"open the newest sample show page","done":{"urlHost":"tubular.test"}}]}
    """.trimIndent()

    private val confirmTrue = """{"complete":true,"reason":"the outcome is visible"}"""

    private fun newLoop(
        runId: String,
        plan: String,
        base: java.io.File,
    ): VisionLoop = VisionLoop(
        observer = RepeatingObserver(mediaPage(), mediaPage()),
        executor = RecordingExecutor(),
        capture = FakeCapture(
            VisionCapture.ScreenShot(
                base64 = "QUJD", width = 1000, height = 2000,
                screenWidth = 1000, screenHeight = 2000, sha256 = "r4-fixture",
                jpegBytes = byteArrayOf(1, 2, 3),
            ),
        ),
        haiku = VisionHaiku(
            ScriptedTransport(listOf(chatContent(plan), chatContent(confirmTrue)), emptyList()),
            "fixture-key", backoffBaseMs = 1, retryDelay = {},
        ),
        jev = VisionJev(
            ScriptedTransport(emptyList(), listOf(jevNoul(0.9))),
            "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
            backoffBaseMs = 1, retryDelay = {},
        ),
        budget = ExpBudget(),
        evidence = VisionEvidence(base, runId),
        config = VisionLoop.Config(settleMs = 0, maxReplans = 5, prefixAuditAfter = 3),
    )

    @Test
    fun `the R4 shape - the final media page without the account marker - the ordinary accept refuses`() =
        runBlocking {
            val base = Files.createTempDirectory("p18-r4-acct").toFile()
            var iterations = 0
            val result = newLoop("r4-acct", r4Plan, base).run(
                "open tubular.test, change the active profile to ViewerTwo, then open " +
                    "the newest sample show episode page",
                "r4-acct",
            ) { ++iterations > 8 }

            // The playback outcome is visible; the switched profile is not. The CURRENT
            // vision-path accept re-checks the account (Stage 0.5) and refuses it.
            assertNotEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal, result.subtaskOutcomes.toString())
            assertNotEquals(VisionLoop.Terminal.MODELS_CONFIRM_DONE, result.terminal, result.subtaskOutcomes.toString())
            val refused = result.subtaskOutcomes.filter { it.contains("ACCEPT REFUSED") }
            assertTrue(
                refused.any { it.contains("account end state") && it.contains("viewertwo") },
                "the accept refusal must name the missing account evidence: $refused",
            )
        }

    @Test
    fun `the unclassified shape - the same evidence-free page - the ordinary accept passes`() =
        runBlocking {
            val base = Files.createTempDirectory("p18-r4-plain").toFile()
            var iterations = 0
            val result = newLoop("r4-plain", plainPlan, base).run(
                "open tubular.test and open the newest sample show page",
                "r4-plain",
            ) { ++iterations > 8 }

            // No goal class recognizes this goal, so the generic code condition rules —
            // the ordinary accept passes with NO class-specific end-state evidence on
            // the page. This is the baseline the single final verifier (Stage 18.C
            // CN-07) formalizes; doc 04 §9.4 keeps generic rules for unclassified goals.
            assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal, result.subtaskOutcomes.toString())
            assertTrue(
                result.subtaskOutcomes.none { it.contains("ACCEPT REFUSED") },
                result.subtaskOutcomes.toString(),
            )
        }
}
