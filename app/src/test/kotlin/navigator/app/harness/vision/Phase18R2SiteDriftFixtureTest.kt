package navigator.app.harness.vision

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * PHASE 18 STAGE 18.A — SANITIZED REGRESSION FIXTURE (R2, site drift).
 *
 * SANITIZED from UAT 17.1 R2-vision (the 107-step drift run: the run left the original
 * host for an unrelated one, the screen change was accepted as progress, and the
 * completed first subtask was never revalidated; `.state/phase-17-uat/17.1/R2-*`).
 * Every real site, brand and account name is removed: the completed host is
 * `sitea.test`, the drift target is `siteb.test`; the fixture pages live in
 * `app/src/test/resources/fixtures/phase18/` for the emulator leg.
 *
 * What it pins (CURRENT behavior, $0 scripted replay): subtask 1
 * (`urlHost sitea.test`) completes on Site A; the observation drifts to Site B;
 * while the scripted steps keep ACTING successfully, the pointer stays past
 * subtask 1, the run keeps acting on the wrong host, and NO prefix revalidation
 * runs — the completed-prefix audit fires only on consecutive step FAILURES
 * (`Config.prefixAuditAfter`; the rollback-on-failures half is already pinned by
 * [VisionLoopPrefixRollbackTest] and is deliberately not duplicated here).
 *
 * Changed by: Stage 18.C CN-06 (runtime prefix states VERIFIED/INVALIDATED,
 * evidence-scoped revalidation, wrong-host = WRONG_CONTEXT).
 * Vision law: Project Vision/04 §9.5 (and §9.3's wrong-context rule).
 */
class Phase18R2SiteDriftFixtureTest {

    /** Routes by URL: chat-completions pops the chat script, everything else the Jev script. */
    private class ScriptedTransport(
        chatResponses: List<String>,
        jevResponses: List<String>,
    ) : WireTransport {
        private val chat = ArrayDeque(chatResponses)
        private val jev = ArrayDeque(jevResponses)
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            val queue = if (request.url.contains("chat/completions")) chat else jev
            // The LAST scripted response never pops: it repeats for every further call,
            // so the identical drift steps are deterministic without a fixed length.
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

    /** The GROUND answer: one Box over the only tap proposal (b0) + the pick choosing p0. */
    private fun jevGround(): String = buildJsonObject {
        put("answers", buildJsonObject {
            put("b0", buildJsonObject {
                put("type", JsonPrimitive("box"))
                put("box", buildJsonObject {
                    put("top", JsonPrimitive(0.20)); put("bottom", JsonPrimitive(0.25))
                    put("left", JsonPrimitive(0.10)); put("right", JsonPrimitive(0.30))
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

    private fun jevNoul(pTrue: Double): String = buildJsonObject {
        put("answers", buildJsonObject {
            put("done", buildJsonObject {
                put("type", JsonPrimitive("noul"))
                put("noul", JsonPrimitive(pTrue))
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    private fun node(id: String, text: String) = ObservedNode(
        id = id, text = text, desc = "", clickable = true, editable = false,
        scrollable = false, left = 100, top = 400, right = 300, bottom = 500,
        className = "android.widget.Button", sourceOrder = 0,
    )

    private fun siteAHome() = StepObservation(
        url = "https://sitea.test/home",
        title = "Site A",
        lines = listOf("Site A", "Open the archive"),
        nodes = listOf(node("n1", "Open the archive")),
        windowPackage = "com.android.chrome",
    )

    private fun siteBListing(page: Int) = StepObservation(
        url = "https://siteb.test/listing?page=$page",
        title = "Site B",
        lines = listOf("Site B", "Directory entry one", "Directory entry two", "page $page"),
        nodes = listOf(node("n1", "Directory entry one")),
        windowPackage = "com.android.chrome",
    )

    /** The sanitized R2 ladder: the host identity first, then the work on that host. */
    private val plan = """
        {"summary":"open sitea.test and open the newest archive item","taskClass":"action","subtasks":[
          {"instruction":"open sitea.test","done":{"urlHost":"sitea.test"}},
          {"instruction":"open the archive list","done":{"urlContains":"sitea.test/archive"}},
          {"instruction":"open the newest archive item","done":{"elementPresent":"the archive item view is open"}}]}
    """.trimIndent()

    private val tapProposal = """
        {"proposals":[{"description":"the first directory entry","action":"tap","expectedEffect":{"expect":"TargetStateChanged"},
          "box":{"top":0.20,"bottom":0.25,"left":0.10,"right":0.30},"confidence":0.9}]}
    """.trimIndent()

    private val confirmTrue = """{"complete":true,"reason":"the outcome is visible"}"""

    @Test
    fun `the R2 drift shape - the loop keeps acting on the wrong host without prefix revalidation`() =
        runBlocking {
            val base = Files.createTempDirectory("p18-r2-drift").toFile()
            val evidence = VisionEvidence(base, "r2-drift")
            var iterations = 0
            val loop = VisionLoop(
                observer = RepeatingObserver(
                    siteAHome(),         // the plan observes once...
                    siteAHome(),         // ...and the step loop observes again: subtask 1 accepts
                    // The drift: every page is distinct (URL + hash), so acting keeps
                    // "succeeding" — no stuck probe, no same-action accumulation, no
                    // failures, hence no prefix audit within the pinned window.
                    *Array(12) { siteBListing(it + 1) },
                ),
                executor = RecordingExecutor(),
                capture = FakeCapture(
                    VisionCapture.ScreenShot(
                        base64 = "QUJD", width = 1000, height = 2000,
                        screenWidth = 1000, screenHeight = 2000, sha256 = "r2-fixture",
                        jpegBytes = byteArrayOf(1, 2, 3),
                    ),
                ),
                haiku = VisionHaiku(
                    ScriptedTransport(listOf(chatContent(plan), chatContent(confirmTrue), chatContent(tapProposal)), emptyList()),
                    "fixture-key", backoffBaseMs = 1, retryDelay = {},
                ),
                jev = VisionJev(
                    ScriptedTransport(emptyList(), listOf(jevNoul(0.9), jevGround())),
                    "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                    backoffBaseMs = 1, retryDelay = {},
                ),
                budget = ExpBudget(),
                evidence = evidence,
                config = VisionLoop.Config(settleMs = 0, maxReplans = 5, prefixAuditAfter = 3),
            )

            val result = loop.run(
                "open sitea.test and open the newest archive item",
                "r2-drift",
            ) { ++iterations > 10 }

            // UPDATED by Stage 18.C CN-04+CN-11 (doc 04 §9.3/§9.7, ratified): the drift
            // taps produce NO observed effect (the scripted Site-B pages repeat), so the
            // no-effect budget bounds the drift at 3 — the 107-step R2 shape can no
            // longer happen (the budget feeds the re-plan gate; maxReplans 0 ->
            // REPLANS_EXHAUSTED). The prefix-rollback pins below are unchanged: the
            // rollback itself is Stage 18.C CN-06's mechanism (a later part).
            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal, result.subtaskOutcomes.toString())
            assertTrue(
                result.subtaskOutcomes.any { it.contains("NO_EFFECT_ACTIONS exhausted") },
                "the no-effect budget bounded the drift: ${result.subtaskOutcomes}",
            )
            // Subtask 1 completed exactly once, BEFORE the drift.
            assertTrue(
                result.subtaskOutcomes.any { it.contains("subtask 1/3 DONE") },
                result.subtaskOutcomes.toString(),
            )
            // The loop acted on Site B — the screen changes were accepted as progress
            // (each step line carries its grounding; none is a failure).
            assertTrue(
                result.subtaskOutcomes.any { it.startsWith("step") && "jev-box" in it },
                "the drift steps acted: ${result.subtaskOutcomes}",
            )
            assertTrue(
                result.subtaskOutcomes.none { it.startsWith("step") && "FAILED" in it },
                "the drift steps keep acting successfully: ${result.subtaskOutcomes}",
            )
            assertTrue(result.stepsUsed > 2, "several steps ran past the completed prefix: ${result.stepsUsed}")
            // CURRENT behavior: NO prefix revalidation while acting succeeds — no
            // rollback, no re-check of the completed sitea.test subtask on Site B.
            assertFalse(
                result.subtaskOutcomes.any { it.contains("PREFIX ROLLBACK") },
                "no rollback may fire on a successful-acting drift: ${result.subtaskOutcomes}",
            )
            val log = Files.readAllBytes(java.io.File(base, "r2-drift/events.log").toPath()).decodeToString()
            assertFalse(log.contains("PREFIX ROLLBACK"), log.take(2000))
            assertFalse(
                result.subtaskOutcomes.any { it.contains("sitea.test") && it.contains("REVALIDAT") },
                "the completed prefix was not revalidated: ${result.subtaskOutcomes}",
            )
        }
}
