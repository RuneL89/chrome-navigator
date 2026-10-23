package navigator.app.harness.vision

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import navigator.app.loop.ActionExecutor
import navigator.app.loop.LoopObserver
import navigator.app.loop.StepObservation
import navigator.core.enumerate.ObservedNode
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PHASE_17 Stage 0.6 — the SPEND/EVIDENCE gate ($0, scripted transport).
 *
 * The VEXP9 corpus could not measure the vision engine: the spend was unrecorded and
 * the call counts did not exist, so "vision is more reliable but slower" had no cost
 * side. This gate pins the three facts the measurement needs:
 *
 * 1. the PROVIDER-REPORTED usage of each channel (Jev, Haiku) reaches ONE ledger
 *    ([ExpBudget]) through the EXACT wiring the product route builds
 *    ([NavigatorVisionBridge.newClients], not a re-typed copy);
 * 2. the same usage rides the run's evidence as a real `in=/out=/cost=` line with the
 *    channel and the image count — never a 0 µ$ placeholder;
 * 3. a finished run writes ONE terminal record carrying the modality and the call
 *    ledger (total / jev / haiku / images / battery), the record the corpus reads.
 */
class SpendRecordingTest {

    // -- scripted edges -------------------------------------------------------------------

    /**
     * Routes by URL: chat-completions pops [chatResponses], everything else
     * [jevResponses]. Exhaustion throws a NON-RETRYABLE error — a script mismatch must
     * fail the gate loudly, never ride a client's exponential retry backoff.
     */
    private class ScriptedVisionTransport(
        private val chatResponses: ArrayDeque<String>,
        private val jevResponses: ArrayDeque<String>,
    ) : WireTransport {
        val routes = mutableListOf<String>()
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            val isChat = request.url.contains("chat/completions")
            routes.add(if (isChat) "chat" else "jev")
            val body = (
                if (isChat) chatResponses.removeFirstOrNull() else jevResponses.removeFirstOrNull()
                ) ?: error("script exhausted for ${request.url} after $routes")
            return WireHttpResponse(200, body)
        }
    }

    private class ScriptedObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull() ?: error("observer script exhausted")
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

    private fun shot() = VisionCapture.ScreenShot(
        base64 = "QUJD", width = 1000, height = 2000,
        screenWidth = 1000, screenHeight = 2000, sha256 = "fixture",
        jpegBytes = byteArrayOf(1, 2, 3),
    )

    /** The Jev route's usage object WITHOUT `cost` — the token table must price it. */
    private fun jevNoul(pTrue: Double, inputTokens: Long = 10, outputTokens: Long = 1): String =
        buildJsonObject {
            put("answers", buildJsonObject {
                put("done", buildJsonObject {
                    put("type", JsonPrimitive("noul"))
                    put("noul", JsonPrimitive(pTrue))
                })
            })
            put("usage", buildJsonObject {
                put("input_tokens", inputTokens)
                put("output_tokens", outputTokens)
            })
        }.toString()

    /** The GROUND answer shape the batched request asks for: one Box + the pick Choice. */
    private fun jevGround(inputTokens: Long = 10, outputTokens: Long = 1): String =
        buildJsonObject {
            put("answers", buildJsonObject {
                put("b0", buildJsonObject {
                    put("type", JsonPrimitive("box"))
                    put("box", buildJsonObject {
                        put("top", JsonPrimitive(0.15))
                        put("bottom", JsonPrimitive(0.18))
                        put("left", JsonPrimitive(0.1))
                        put("right", JsonPrimitive(0.3))
                    })
                    put("confidence", JsonPrimitive(0.9))
                })
                put("pick", buildJsonObject {
                    put("type", JsonPrimitive("choice"))
                    put("choice", JsonPrimitive("p0"))
                    put("probabilities", buildJsonObject {
                        put("p0", JsonPrimitive(0.9))
                        put("none", JsonPrimitive(0.1))
                    })
                })
            })
            put("usage", buildJsonObject {
                put("input_tokens", inputTokens)
                put("output_tokens", outputTokens)
            })
        }.toString()

    private fun chatContent(innerJson: String, promptTokens: Long = 1000, completionTokens: Long = 500): String =
        buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
                })
            })
            put("usage", buildJsonObject {
                put("prompt_tokens", promptTokens)
                put("completion_tokens", completionTokens)
            })
        }.toString()

    private fun tempEvidence(runId: String): Pair<VisionEvidence, File> {
        val base = Files.createTempDirectory("vexp17-spend").toFile()
        return VisionEvidence(base, runId) to File(base, "$runId/events.log")
    }

    // -- the ledger ---------------------------------------------------------------------

    @Test
    fun `a scripted jev call meters the task ledger through the product wiring`() = runBlocking {
        // 10 in x $3/M + 1 out x $15/M = 45 micro-USD (the decision-engine table).
        val budget = ExpBudget()
        val transport = ScriptedVisionTransport(ArrayDeque(), ArrayDeque(listOf(jevNoul(0.9))))
        val (jev, _) = NavigatorVisionBridge.newClients(
            transport = transport,
            openRouterKey = "or-key",
            typesafeKey = "ts-key",
            budget = budget,
        )

        jev.ask(
            "the page state", listOf(VisionJev.Question.Noul("done", "is it done")),
            null, VisionJev.newImageRef(), "verify-noul",
        )

        assertEquals(45L, budget.spendMicroUsd, "the provider usage priced into ONE ledger")
        assertEquals(0L, budget.writerSpendMicroUsd, "a Jev call never meters the writer sub-ledger")
        assertEquals(45L, jev.lastUsage?.costMicroUsd)
    }

    @Test
    fun `a scripted haiku call meters the task and the writer ledgers`() = runBlocking {
        // 1000 in x $0.80/M + 500 out x $4/M = 800 + 2000 = 2800 micro-USD.
        val budget = ExpBudget()
        val transport = ScriptedVisionTransport(
            ArrayDeque(listOf(chatContent("""{"proposals":[]}"""))), ArrayDeque(),
        )
        val (_, haiku) = NavigatorVisionBridge.newClients(
            transport = transport,
            openRouterKey = "or-key",
            typesafeKey = "ts-key",
            budget = budget,
        )

        haiku.chat("system", "user", null, "propose")

        assertEquals(2_800L, budget.spendMicroUsd)
        assertEquals(2_800L, budget.writerSpendMicroUsd, "a writer call meters BOTH ledgers")
        assertTrue(budget.hasWriterHardRoom(), "the 100x writer cap still has room after one call")
    }

    // -- the evidence line --------------------------------------------------------------

    @Test
    fun `the evidence call line carries the real in out cost channel and image count`() = runBlocking {
        val budget = ExpBudget()
        val transport = ScriptedVisionTransport(ArrayDeque(), ArrayDeque(listOf(jevNoul(0.9))))
        val (jev, _) = NavigatorVisionBridge.newClients(
            transport = transport,
            openRouterKey = "or-key",
            typesafeKey = "ts-key",
            budget = budget,
        )
        val (evidence, eventsFile) = tempEvidence("spend-line")

        var rawReq = ""
        var rawRes = ""
        jev.ask(
            "the page state", listOf(VisionJev.Question.Noul("done", "is it done")),
            null, VisionJev.newImageRef(), "verify-battery",
            onRaw = { req, res -> rawReq = req; rawRes = res },
        )
        evidence.call(
            "verify-battery", rawReq, rawRes, 0, jev.lastUsage,
            channel = VisionEvidence.Channel.JEV, images = 1,
        )

        val line = eventsFile.readText().lines().single { "call verify-battery" in it }
        assertTrue("in=10 out=1 cost=45µ\$" in line, "the REAL usage rides the line: $line")
        assertTrue("channel=JEV images=1" in line, "the channel and image count ride it: $line")
        assertEquals(1, evidence.channelCalls(VisionEvidence.Channel.JEV))
        assertEquals(0, evidence.channelCalls(VisionEvidence.Channel.HAIKU))
        assertEquals(1, evidence.imagesSent())
        assertEquals(1, evidence.batteryCalls())
        assertEquals(
            "1",
            evidence.callSummaryJson()["jevCalls"]!!.jsonPrimitive.content,
            "the summary's Jev count",
        )
    }

    @Test
    fun `a text-only call contributes no image and is not a battery call`() = runBlocking {
        val (evidence, _) = tempEvidence("spend-text")
        evidence.call(
            "plan", "req", "res", 0, VisionJev.VisionUsage(5, 5, 90),
            channel = VisionEvidence.Channel.HAIKU,
        )
        assertEquals(0, evidence.imagesSent())
        assertEquals(0, evidence.batteryCalls())
        assertEquals(1, evidence.channelCalls(VisionEvidence.Channel.HAIKU))
    }

    // -- the run record -----------------------------------------------------------------

    @Test
    fun `a finished run's terminal line carries the modality and the call ledger`() = runBlocking {
        val plan = """
            {"summary":"open the example page","taskClass":"read","subtasks":[
              {"instruction":"open the example page","done":{"urlHost":"next.test"}}]}
        """.trimIndent()
        val propose = """{"proposals":[{"description":"the 'Go' button","action":"tap"}]}"""
        val confirm = """{"complete":true,"reason":"the example page is visible"}"""
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(plan), chatContent(propose), chatContent(confirm))),
            jevResponses = ArrayDeque(listOf(jevGround(), jevNoul(0.9))),
        )
        val (evidence, eventsFile) = tempEvidence("spend-run")
        val budget = ExpBudget()
        // The PRODUCT construction: the ledger the corpus reads is metered by the same
        // wiring the service builds, not a test-local copy.
        val (jev, haiku) = NavigatorVisionBridge.newClients(
            transport = transport,
            openRouterKey = "fixture-key",
            typesafeKey = "fixture-key",
            budget = budget,
        )

        val result = VisionLoop(
            observer = ScriptedObserver(
                observationAt("https://old.test/", node("n1", 100, 300, 300, 360)),
                observationAt("https://old.test/", node("n1", 100, 300, 300, 360)),
                observationAt("https://next.test/page"),
            ),
            executor = RecordingExecutor(),
            capture = FakeCapture(shot()),
            haiku = haiku,
            jev = jev,
            budget = budget,
            evidence = evidence,
            config = VisionLoop.Config(
                settleMs = 0, maxReplans = 0, planAttempts = 1, stepFailuresBeforeReplan = 1,
            ),
        ).run("open the example page on next.test", "spend-run") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        val terminal = eventsFile.readText().lines().single { "terminal=" in it }
        val calls = evidence.totalCalls()
        assertTrue(calls > 0, "the run made calls: $calls")
        assertTrue("modality=VISION" in terminal, "the run's modality rides the record: $terminal")
        assertTrue("calls=$calls" in terminal, "the ledger's total rides it: $terminal")
        assertTrue(
            "jev=${evidence.channelCalls(VisionEvidence.Channel.JEV)}" in terminal &&
                "haiku=${evidence.channelCalls(VisionEvidence.Channel.HAIKU)}" in terminal,
            "the channel split rides it: $terminal",
        )
        assertTrue(evidence.imagesSent() > 0, "the plan/propose/confirm images are counted")
        assertTrue("images=${evidence.imagesSent()}" in terminal, "the image count rides it: $terminal")
        assertTrue(
            "battery=${evidence.batteryCalls()}" in terminal && "escalations=0" in terminal,
            "the battery share and the (always-vision) escalation count ride it: $terminal",
        )
        // The ledger the corpus measures against: every call priced, none at 0 µ$.
        // 3 Haiku calls x 2800 + 2 Jev calls x 45 = 8490 micro-USD.
        assertEquals(
            8_490L, budget.spendMicroUsd,
            "3 Haiku calls + 2 Jev calls priced; events:\n" + eventsFile.readText(),
        )
    }

    private fun node(id: String, left: Int, top: Int, right: Int, bottom: Int) = ObservedNode(
        id = id, text = "Go", desc = "", clickable = true, editable = false,
        scrollable = false, left = left, top = top, right = right, bottom = bottom,
        className = "android.widget.Button", sourceOrder = 0,
    )

    private fun observationAt(url: String, vararg nodes: ObservedNode) = StepObservation(
        url = url, title = "Page", lines = listOf("some page"), nodes = nodes.toList(),
    )
}
