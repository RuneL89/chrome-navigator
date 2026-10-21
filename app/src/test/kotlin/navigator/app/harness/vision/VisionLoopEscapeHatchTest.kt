package navigator.app.harness.vision

import java.io.File
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
 * PHASE_VEXP8 WI1 — the `MODELS_CONFIRM_DONE` FINALITY LAW ($0: real loop, scripted
 * transports, text-only capture, temp-dir evidence — no device, no live model call).
 *
 * Run-11 evidence (`task-1792429124399`): a 4-subtask Verkkokauppa plan was falsely
 * completed at the FIRST subtask — noul 0.96, confirm true, ~47 s — and the store was
 * never opened. The hatch now ends the task only when ALL hold: the active subtask is the
 * ladder's FINAL one, its done-condition is CODE-UNVERIFIABLE (WEAK), no report answer is
 * pending, the existing Jev-noul + Haiku-confirm pair agrees, and ONE goal-level Jev noul
 * agrees. Checks 1-3 run BEFORE any model call (a shut hatch costs zero calls); a
 * non-final exhausted subtask takes the existing informed re-plan path and never
 * terminates or advances the pointer — REPLANS_EXHAUSTED when that budget is spent.
 */
class VisionLoopEscapeHatchTest {

    // -- scripted edges -------------------------------------------------------------------

    /**
     * Routes by URL: chat-completions pops [chatResponses], everything else
     * [jevResponses]. [failJevFrom] makes every Jev call at/after that 1-based index throw
     * a transport failure (the refused-goal-noul arm; run the Jev client with
     * `maxRetries = 0` so one clean failure happens).
     */
    private class ScriptedVisionTransport(
        private val chatResponses: ArrayDeque<String>,
        private val jevResponses: ArrayDeque<String>,
        private val failJevFrom: Int = Int.MAX_VALUE,
    ) : WireTransport {
        var chatCalls = 0
            private set
        var jevCalls = 0
            private set
        val chatBodies = mutableListOf<String>()
        val jevBodies = mutableListOf<String>()

        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            val isChat = request.url.contains("chat/completions")
            if (isChat) {
                chatCalls += 1
                chatBodies.add(request.body)
                val body = chatResponses.removeFirstOrNull()
                    ?: throw WireTransportException("chat script exhausted (#$chatCalls)")
                return WireHttpResponse(200, body)
            }
            jevCalls += 1
            jevBodies.add(request.body)
            if (jevCalls >= failJevFrom) {
                throw WireTransportException("scripted Jev transport failure (#$jevCalls)")
            }
            val body = jevResponses.removeFirstOrNull()
                ?: throw WireTransportException("jev script exhausted (#$jevCalls)")
            return WireHttpResponse(200, body)
        }
    }

    private class ScriptedObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull() ?: error("observer script exhausted")
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

    private fun jevNoul(name: String, pTrue: Double): String = buildJsonObject {
        put("answers", buildJsonObject {
            put(name, buildJsonObject {
                put("type", JsonPrimitive("noul"))
                put("noul", JsonPrimitive(pTrue))
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    private fun node(id: String, text: String) = ObservedNode(
        id = id, text = text, desc = "", clickable = true, editable = false,
        scrollable = false, left = 100, top = 200, right = 300, bottom = 260,
        className = "android.widget.Button", sourceOrder = 0,
    )

    private fun observation(vararg lines: String) = StepObservation(
        url = "https://verkkokauppa.test/",
        title = "Verkkokauppa",
        lines = lines.toList(),
        nodes = listOf(node("n1", "Search")),
    )

    private fun newEvidence(runId: String): Pair<VisionEvidence, File> {
        val base = Files.createTempDirectory("vexp8-hatch").toFile()
        return VisionEvidence(base, runId) to File(base, "$runId/events.log")
    }

    private fun loop(
        transport: ScriptedVisionTransport,
        observations: List<StepObservation>,
        evidence: VisionEvidence,
        config: VisionLoop.Config,
        jevRetries: Int = 20,
    ): VisionLoop = VisionLoop(
        observer = ScriptedObserver(*observations.toTypedArray()),
        executor = RecordingExecutor(),
        capture = VisionCapture(null), // text-only: no screenshot, no grounding call
        haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
        jev = VisionJev(
            transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
            backoffBaseMs = 1, retryDelay = {}, maxRetries = jevRetries,
        ),
        budget = ExpBudget(),
        evidence = evidence,
        config = config,
    )

    private val proposeEmpty = """{"proposals":[]}"""
    private val confirmTrue = """{"complete":true,"reason":"the outcome is visible"}"""

    /** The run-11 shape: 4 subtasks, the FIRST one's WEAK condition already holds at start. */
    private val run11Plan = """
        {"summary":"open verkkokauppa and buy celsius","taskClass":"action","subtasks":[
          {"instruction":"open verkkokauppa.com","done":{"markerText":"VERKKOKAUPPA"}},
          {"instruction":"search for celsius lemon lime","done":{"urlHost":"verkkokauppa.com"}},
          {"instruction":"open the celsius product page","done":{"markerText":"CELSIUS"}},
          {"instruction":"add two cases to the shopping cart","done":{"markerText":"OSTOSKORI"}}]}
    """.trimIndent()

    /** A final WEAK (code-unverifiable) condition that never holds in these scripts. */
    private val weakFinalPlan = """
        {"summary":"add the product to the cart","taskClass":"action","subtasks":[
          {"instruction":"add the product to the cart","done":{"markerText":"CART-CONFIRMED"}}]}
    """.trimIndent()

    // -- the five cases -------------------------------------------------------------------

    @Test
    fun `the run-11 4-subtask regression - a non-final subtask can never hatch and costs zero calls`() =
        runBlocking {
            // WI1-1: the false completion's exact shape. The first subtask's WEAK marker
            // already holds at the start (weak-at-start blocks the accept), the proposer
            // returns empty, and confirm true + noul 0.96 are scripted — but the hatch is
            // shut at check 1 (not final), BEFORE recordedOpinions: zero confirm/noul
            // calls. maxReplans = 0 -> the existing re-plan budget spends out.
            val transport = ScriptedVisionTransport(
                chatResponses = ArrayDeque(listOf(chatContent(run11Plan), chatContent(proposeEmpty))),
                jevResponses = ArrayDeque(listOf(jevNoul("done", 0.96), jevNoul("goal", 0.96))),
            )
            val (evidence, eventsFile) = newEvidence("hatch-not-final")

            val result = loop(
                transport,
                listOf(
                    observation("VERKKOKAUPPA etusivu"),        // run() start observe (plan digest)
                    observation("VERKKOKAUPPA etusivu", "search box"),
                ),
                evidence,
                VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1),
            ).run("open verkkokauppa.com and add two cases of lemon lime celsius to the cart", "hatch-not-final") { false }

            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
            val suppression = "MODELS_CONFIRM_DONE suppressed: subtask 1/4 is not final - " +
                "the hatch never terminates a non-final subtask"
            assertTrue(
                result.subtaskOutcomes.any { it == suppression },
                "the not-final suppression is the evidence: ${result.subtaskOutcomes}",
            )
            assertTrue(
                result.subtaskOutcomes.none { it.contains("MODELS_CONFIRM_DONE (code") },
                "no hatch may fire: ${result.subtaskOutcomes}",
            )
            assertEquals(2, transport.chatCalls, "chat = the plan + ONE propose — no confirm call")
            assertEquals(0, transport.jevCalls, "ZERO Jev calls: no battery, no noul, no goal noul")
            assertTrue(
                suppression in eventsFile.readText(),
                "the suppression rides events.log: ${eventsFile.readText()}",
            )
            assertEquals(0, result.replans)
        }

    @Test
    fun `final weak plus the pair plus the goal noul agree - the hatch fires with goal=AGREE`() =
        runBlocking {
            // WI1-2: the lawful hatch — final subtask, WEAK condition, no report, the
            // existing pair agrees, and ONE goal-level noul agrees.
            val transport = ScriptedVisionTransport(
                chatResponses = ArrayDeque(
                    listOf(chatContent(weakFinalPlan), chatContent(proposeEmpty), chatContent(confirmTrue)),
                ),
                jevResponses = ArrayDeque(listOf(jevNoul("done", 0.96), jevNoul("goal", 0.92))),
            )
            val (evidence, eventsFile) = newEvidence("hatch-agree")

            val result = loop(
                transport,
                listOf(
                    observation("product page"),
                    observation("product page", "still browsing"),
                ),
                evidence,
                VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1),
            ).run("add two cases of celsius to the cart", "hatch-agree") { false }

            assertEquals(VisionLoop.Terminal.MODELS_CONFIRM_DONE, result.terminal)
            val hatchLine = result.subtaskOutcomes.single { it.contains("MODELS_CONFIRM_DONE (code") }
            assertTrue(
                hatchLine.contains("[AGREE(0.96) confirm=true] goal=AGREE(0.92)"),
                "the render carries the pair AND the goal verdict: $hatchLine",
            )
            assertEquals(3, transport.chatCalls, "plan + propose + ONE confirm")
            assertEquals(2, transport.jevCalls, "the subtask noul + ONE goal noul")
            assertEquals(
                1,
                transport.jevBodies.count { it.contains("\"goal\"") },
                "the goal noul is asked exactly once: ${transport.jevBodies}",
            )
            val events = eventsFile.readText()
            assertTrue("goalNoul=0.92" in events, "the evidence carries the goal noul: $events")
            assertTrue("MODELS_CONFIRM_DONE: noul=0.96 confirm=true" in events, events)
            assertTrue("terminal=MODELS_CONFIRM_DONE" in events, events)
        }

    @Test
    fun `a disagreeing goal noul suppresses the hatch - REPLANS_EXHAUSTED`() = runBlocking {
        // WI1-3: the pair agrees but the goal-level noul does not (0.40 < 0.70). The hatch
        // is shut; the existing re-plan budget (0) ends the run.
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(chatContent(weakFinalPlan), chatContent(proposeEmpty), chatContent(confirmTrue)),
            ),
            jevResponses = ArrayDeque(listOf(jevNoul("done", 0.96), jevNoul("goal", 0.40))),
        )
        val (evidence, eventsFile) = newEvidence("hatch-goal-disagree")

        val result = loop(
            transport,
            listOf(observation("product page"), observation("product page", "still browsing")),
            evidence,
            VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1),
        ).run("add two cases of celsius to the cart", "hatch-goal-disagree") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(
            result.subtaskOutcomes.any {
                it == "MODELS_CONFIRM_DONE suppressed: the goal-level noul did not agree (goalNoul=0.4)"
            },
            "the goal-disagree suppression is exact: ${result.subtaskOutcomes}",
        )
        assertTrue(result.subtaskOutcomes.none { it.contains("MODELS_CONFIRM_DONE (code") })
        assertEquals(3, transport.chatCalls)
        assertEquals(2, transport.jevCalls, "the pair noul + the goal noul")
        assertTrue("goalNoul=0.4" in eventsFile.readText(), "the refused agreement is recorded")
    }

    @Test
    fun `a STRONG final condition the checker calls false is authoritative - zero confirm calls`() =
        runBlocking {
            // WI1-4: urlHost is code-verifiable; the checker says it is unmet, so models
            // never override it. The suppression runs BEFORE recordedOpinions -> the chat
            // script only ever sees the plan and the propose; NO confirm, NO noul.
            val strongPlan = """
                {"summary":"reach the done page","taskClass":"action","subtasks":[
                  {"instruction":"navigate to the done page","done":{"urlHost":"done.test"}}]}
            """.trimIndent()
            val transport = ScriptedVisionTransport(
                chatResponses = ArrayDeque(listOf(chatContent(strongPlan), chatContent(proposeEmpty))),
                jevResponses = ArrayDeque(listOf(jevNoul("done", 0.99), jevNoul("goal", 0.99))),
            )
            val (evidence, eventsFile) = newEvidence("hatch-strong")

            val result = loop(
                transport,
                listOf(observation("product page"), observation("product page", "still browsing")),
                evidence,
                VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1),
            ).run("navigate to the done page", "hatch-strong") { false }

            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
            assertTrue(
                result.subtaskOutcomes.any {
                    it == "MODELS_CONFIRM_DONE suppressed: the final condition is code-verifiable " +
                        "(STRONG) and unmet - the code checker is authoritative"
                },
                "the STRONG suppression is exact: ${result.subtaskOutcomes}",
            )
            assertTrue(result.subtaskOutcomes.none { it.contains("MODELS_CONFIRM_DONE (code") })
            assertEquals(2, transport.chatCalls, "plan + propose only — the confirm is never asked")
            assertEquals(0, transport.jevCalls, "no battery, no noul, no goal noul")
            assertTrue("code-verifiable (STRONG) and unmet" in eventsFile.readText())
        }

    @Test
    fun `a refused goal noul never passes - the hatch stays shut`() = runBlocking {
        // WI1-5: the pair agrees, but the goal-level Jev call is REFUSED (transport). Null
        // never passes: the goal-disagree suppression names goalNoul=null and the existing
        // re-plan budget ends the run.
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(chatContent(weakFinalPlan), chatContent(proposeEmpty), chatContent(confirmTrue)),
            ),
            jevResponses = ArrayDeque(listOf(jevNoul("done", 0.96))),
            failJevFrom = 2, // the goal noul (the second Jev call) fails
        )
        val (evidence, eventsFile) = newEvidence("hatch-goal-refused")

        val result = loop(
            transport,
            listOf(observation("product page"), observation("product page", "still browsing")),
            evidence,
            VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1),
            jevRetries = 0,
        ).run("add two cases of celsius to the cart", "hatch-goal-refused") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(
            result.subtaskOutcomes.any {
                it == "MODELS_CONFIRM_DONE suppressed: the goal-level noul did not agree (goalNoul=null)"
            },
            "a refused goal noul is a suppression with goalNoul=null: ${result.subtaskOutcomes}",
        )
        assertTrue(result.subtaskOutcomes.none { it.contains("MODELS_CONFIRM_DONE (code") })
        assertEquals(3, transport.chatCalls)
        assertEquals(2, transport.jevCalls, "the pair noul + the ONE failed goal call (no retry)")
        val events = eventsFile.readText()
        assertTrue("goal noul refused" in events, "the refusal is recorded loudly: $events")
    }

    @Test
    fun `the R3b empty-checkout shape can never hatch - the goal's end state must be evidenced`() =
        runBlocking {
            // PHASE_16 sixth guard: the R3b run (task-1792584388905) reported
            // MODELS_CONFIRM_DONE on an empty CDON checkout. The plan passes the build-time
            // goal-fidelity check (the final condition names the order confirmation), but the
            // PAGE shows only a basket — the hatch must shut before any pair call.
            val purchasePlan = """
                {"summary":"place the order","taskClass":"action","subtasks":[
                  {"instruction":"Place the order for SoundMagic E11C to koivuviita 7 B 5 within the 60 euro budget",
                   "done":{"markerText":"Order confirmed"}}]}
            """.trimIndent()
            val transport = ScriptedVisionTransport(
                chatResponses = ArrayDeque(listOf(chatContent(purchasePlan), chatContent(proposeEmpty))),
                jevResponses = ArrayDeque(listOf(jevNoul("done", 0.96), jevNoul("goal", 0.92))),
            )
            val (evidence, eventsFile) = newEvidence("hatch-r3b")

            val result = loop(
                transport,
                listOf(
                    observation("Shopping basket", "Celsius Lemon Lime 24-pack", "2 products, total 42,00"),
                    observation("Shopping basket", "1 tuote", "Yhteytestosi", "Postinumero"),
                ),
                evidence,
                VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1),
            ).run(
                "buy SoundMagic E11C with delivery to koivuviita 7 B 5. " +
                    "Total budget including delivery: 60 euros",
                "hatch-r3b",
            ) { false }

            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
            assertTrue(
                result.subtaskOutcomes.any {
                    it.contains("the goal's end state is not evidenced on this page")
                },
                "the sixth guard suppresses the hatch: ${result.subtaskOutcomes}",
            )
            assertTrue(
                result.subtaskOutcomes.none { it.contains("MODELS_CONFIRM_DONE (code") },
                "no hatch may fire on an empty checkout: ${result.subtaskOutcomes}",
            )
            assertEquals(2, transport.chatCalls, "plan + propose only — the guard runs before the pair")
            assertEquals(0, transport.jevCalls, "no battery, no noul, no goal noul")
            assertTrue("the goal's end state is not evidenced" in eventsFile.readText())
        }

    @Test
    fun `the R4 unverified-account shape can never hatch - the account must be evidenced`() =
        runBlocking {
            // PHASE_16 sixth guard: the R4 run (task-1792586283606) completed with the
            // MrAwesome switch unverified. The page shows the subscriptions/list, not the
            // switched account — the hatch must shut.
            val playPlan = """
                {"summary":"play the latest Food Wishes video","taskClass":"action","subtasks":[
                  {"instruction":"From the MrAwesome channel's subscriptions, play the latest Food Wishes video",
                   "done":{"elementPresent":"the video player is playing"}}]}
            """.trimIndent()
            val transport = ScriptedVisionTransport(
                chatResponses = ArrayDeque(listOf(chatContent(playPlan), chatContent(proposeEmpty))),
                jevResponses = ArrayDeque(listOf(jevNoul("done", 0.96), jevNoul("goal", 0.92))),
            )
            val (evidence, eventsFile) = newEvidence("hatch-r4")

            val result = loop(
                transport,
                listOf(
                    observation("YouTube", "Subscriptions", "Food Wishes"),
                    observation("YouTube", "Subscriptions", "Food Wishes", "Videos"),
                ),
                evidence,
                VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1),
            ).run(
                "open youtube.com and switch the active user channel to MrAwesome. Then find the " +
                    "Food Wishes channel from my subscriptions and play the latest posted video",
                "hatch-r4",
            ) { false }

            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
            assertTrue(
                result.subtaskOutcomes.any {
                    it.contains("the goal's end state is not evidenced on this page")
                },
                "the account precondition suppresses the hatch: ${result.subtaskOutcomes}",
            )
            assertTrue(result.subtaskOutcomes.none { it.contains("MODELS_CONFIRM_DONE (code") })
            assertEquals(2, transport.chatCalls)
            assertEquals(0, transport.jevCalls)
            assertTrue("the goal's end state is not evidenced" in eventsFile.readText())
        }
}
