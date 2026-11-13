package navigator.app.harness.vision

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import navigator.app.harness.hybrid.SubtaskOutcome
import navigator.app.harness.hybrid.VisionToolStep
import navigator.app.loop.ActionExecutor
import navigator.app.loop.AutoDenyGatePresenter
import navigator.app.loop.FakeObserver
import navigator.app.loop.LadderSource
import navigator.app.loop.LoopObserver
import navigator.app.loop.NavigationLoop
import navigator.app.loop.NoOpLogSink
import navigator.app.loop.RepairedCondition
import navigator.app.loop.StepObservation
import navigator.app.loop.TerminalState
import navigator.app.loop.obs
import navigator.app.setup.EnginePolicy
import navigator.app.stub.StubEngine
import navigator.app.stub.StubScript
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass
import navigator.core.ladder.ReplanContext
import navigator.core.ladder.Subtask
import navigator.core.log.EngineIdentity
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport

/**
 * PHASE_18 Stage 18.E part 1 — GATE 18.E2 (the route law): all THREE advertised engine
 * policies (`setup/EnginePolicy`) map to REAL implementations consuming the SHARED
 * contracts — no policy resolves to a stub, a fake, or a silent fallback.
 *
 * Pinned here (JVM-pure, $0, scripted transport — the pure seams the product service
 * consults, `ProductRunRoute.decide` + `EnginePolicy.resolve` + the
 * `NavigatorVisionBridge` factories; the Android service layer feeds them):
 *
 * 1. the policy resolution: a stored policy WINS; the legacy toggle migrates
 *    ON→ALWAYS_VISION / OFF→TREE_ONLY; nothing stored stays ALWAYS_VISION (the product
 *    default does not move — vision 01 §1a);
 * 2. the routes: ALWAYS_VISION→Vision, TREE_FIRST→Hybrid, TREE_ONLY→Legacy (and it
 *    never even reads the second key store); a missing channel refuses LOUDLY naming
 *    it — never a silent engine switch;
 * 3. ALWAYS_VISION builds the REAL vision loop (a [VisionLoop], the 18.B–18.D hardened
 *    engine, over its own evidence record);
 * 4. TREE_FIRST's parts are the REAL hybrid wiring on the SHARED contracts: the tool
 *    loop records into the ONE [VisionEvidence] the parts carry (`evidenceRecord ===
 *    evidence`), the host's writer surfaces are the record-consuming Vision* classes,
 *    and a host writer call records its row into the shared record AND meters the ONE
 *    [ExpBudget] ledger (`parts.budget` — the 18.C residual closed);
 * 5. the host (`NavigationLoop`) consumes the shared record: a hybrid run's mandatory
 *    terminal line lands in the PARTS' evidence instance (the fix-loop law); the host's
 *    own completion passing the shared final verifier is `Phase18HybridFinalVerifierTest`.
 *
 * Vision law: Project Vision/01 §1a (the three-policy status law) + Project Vision/04 §8.
 */
class Phase18SharedContractRouteTest {

    // -- 1. the policy resolution law -------------------------------------------------------

    @Test
    fun `the stored policy wins and the legacy boolean migrates - the default stays ALWAYS_VISION`() {
        assertEquals(
            EnginePolicy.TREE_FIRST,
            EnginePolicy.resolve(legacyOn = true, stored = "tree-first"),
            "a parseable stored policy always wins",
        )
        assertEquals(EnginePolicy.ALWAYS_VISION, EnginePolicy.resolve(legacyOn = true, stored = null))
        assertEquals(EnginePolicy.TREE_ONLY, EnginePolicy.resolve(legacyOn = false, stored = null))
        assertEquals(
            EnginePolicy.ALWAYS_VISION,
            EnginePolicy.resolve(legacyOn = null, stored = null),
            "the product default does not move on missing state (vision 01 §1a)",
        )
        assertEquals(EnginePolicy.ALWAYS_VISION, EnginePolicy.DEFAULT)
    }

    // -- 2. the route law ---------------------------------------------------------------------

    @Test
    fun `the three policies route to their real engines - a missing key refuses loudly`() {
        fun keys(route: ProductRunRoute): Pair<String, String> = when (route) {
            is ProductRunRoute.Vision -> route.openRouterKey to route.typesafeKey
            is ProductRunRoute.Hybrid -> route.openRouterKey to route.typesafeKey
            else -> error("unexpected route $route")
        }
        // ALWAYS_VISION -> the vision loop route.
        val vision = ProductRunRoute.decide(EnginePolicy.ALWAYS_VISION, "or-key", "ts-key")
        assertIs<ProductRunRoute.Vision>(vision)
        assertEquals("or-key" to "ts-key", keys(vision))
        // TREE_FIRST -> the hybrid route (NEVER Legacy, never a stub).
        val hybrid = ProductRunRoute.decide(EnginePolicy.TREE_FIRST, "or-key", "ts-key")
        assertIs<ProductRunRoute.Hybrid>(hybrid)
        assertEquals("or-key" to "ts-key", keys(hybrid))
        // TREE_ONLY -> the retained legacy loop, without reading the second store.
        assertEquals(ProductRunRoute.Legacy, ProductRunRoute.decide(EnginePolicy.TREE_ONLY, "or-key", null))
        // The refusals name the missing channel + the recovery (never a silent fallback).
        assertEquals(
            ProductRunRoute.REFUSAL_BOTH,
            (ProductRunRoute.decide(EnginePolicy.ALWAYS_VISION, null, null) as ProductRunRoute.Refused).detail,
        )
        assertTrue(
            (ProductRunRoute.decide(EnginePolicy.TREE_FIRST, null, "ts-key") as ProductRunRoute.Refused)
                .detail.contains("OpenRouter"),
            "the OpenRouter refusal names the writer channel",
        )
        assertTrue(
            (ProductRunRoute.decide(EnginePolicy.TREE_FIRST, "or-key", null) as ProductRunRoute.Refused)
                .detail.contains("TypeSafe"),
            "the TypeSafe refusal names the image channel",
        )
    }

    // -- the scripted transport (a plan response for the ONE-ledger proof) -------------------

    private class ScriptedTransport(private val responses: ArrayDeque<String>) : WireTransport {
        val bodies = mutableListOf<String>()
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            bodies.add(request.body)
            val body = responses.removeFirstOrNull()
                ?: throw navigator.core.wire.WireTransportException("script exhausted")
            return WireHttpResponse(200, body)
        }
    }

    private fun chatContent(innerJson: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 100); put("completion_tokens", 50) })
    }.toString()

    private val planJson = """
        {"summary":"land on the page","taskClass":"action","subtasks":[
          {"instruction":"land on shop.test","done":{"urlHost":"shop.test"}}]}
    """.trimIndent()

    private fun observer(): LoopObserver = object : LoopObserver {
        override suspend fun observe(): StepObservation = StepObservation(
            url = "http://shop.test/",
            title = "t",
            lines = List(6) { "[n$it] \"node $it\" (0,0)" },
            nodes = emptyList(),
        )
    }

    private fun executor(): ActionExecutor = object : ActionExecutor {
        override fun tap(x: Int, y: Int) = true
        override fun clickNode(x: Int, y: Int) = true
        override fun scroll(x: Int, y: Int, down: Boolean) = true
        override fun back() = true
        override fun submit() = true
        override fun enterText(x: Int, y: Int, text: String) = true
    }

    private fun newParts(transport: WireTransport): NavigatorVisionBridge.HybridParts =
        NavigatorVisionBridge.newHybridParts(
            observer = observer(),
            executor = executor(),
            capture = VisionCapture(null),
            evidenceDir = null, // memory-only: the events stay in the record's in-memory log
            runId = "route-check",
            openRouterKey = "or-key",
            typesafeKey = "ts-key",
            log = {},
            transport = transport,
        )

    // -- 3. ALWAYS_VISION builds the REAL hardened vision loop --------------------------------

    @Test
    fun `ALWAYS_VISION's factory builds the real vision loop over its own evidence record`() {
        val loop = NavigatorVisionBridge.newVisionLoop(
            observer = observer(),
            executor = executor(),
            capture = VisionCapture(null),
            evidenceDir = null,
            runId = "vision-route",
            openRouterKey = "or-key",
            typesafeKey = "ts-key",
            log = {},
            transport = ScriptedTransport(ArrayDeque(emptyList())),
        )
        assertIs<VisionLoop>(loop)
        // The JVM-test seam (capture without a bound a11y service) keeps the loop's
        // construction real; the product wiring derives the CN-02/CN-03 seams from the
        // service the capture holds (NavigatorVisionBridge.newVisionLoop).
        assertEquals(0, loop.evidenceRecord.totalCalls(), "a fresh record starts at zero calls")
    }

    // -- 4. TREE_FIRST's parts: the REAL hybrid wiring on the SHARED contracts -----------------

    @Test
    fun `TREE_FIRST's parts wire the tool, the writer surfaces and the ONE record + ONE ledger`() =
        runBlocking {
            val transport = ScriptedTransport(ArrayDeque(listOf(chatContent(planJson))))
            val parts = newParts(transport)

            // The REAL classes — no stub, no fake, no silent fallback.
            assertIs<VisionLoop>(parts.loop)
            assertIs<VisionToolStep>(parts.tool)
            assertIs<VisionLadderSource>(parts.ladderSource)
            assertIs<VisionStuckEvent>(parts.stuckSource)
            assertIs<VisionBoundaryJudge>(parts.boundaryJudge)

            // THE SHARED RECORD: the tool's loop records into the ONE instance the parts
            // carry (a second instance would report a zero ledger — the truthfulness law).
            assertTrue(
                parts.loop.evidenceRecord === parts.evidence,
                "the tool loop and the parts share ONE evidence record",
            )

            // THE SHARED LEDGER: a HOST writer call (the hybrid's ladder source) records
            // its row into the shared record AND meters the ONE budget the tool loop uses
            // (the 18.C residual: the second client pair rode a throwaway budget and
            // recorded into no evidence).
            val result = parts.ladderSource.restructure("land on shop.test", generation = 1)
            assertTrue(result != null && result.ladder.subtasks.isNotEmpty(), "the scripted plan installed")
            assertTrue(
                parts.evidence.callCounts().containsKey("plan"),
                "the host's writer call recorded into the SHARED record: ${parts.evidence.callCounts()}",
            )
            assertEquals(
                1,
                parts.evidence.channelCalls(VisionEvidence.Channel.HAIKU),
                "the call row carries the HAIKU channel",
            )
            assertTrue(
                parts.budget.writerSpendMicroUsd > 0,
                "the host's writer spend meters the ONE ledger: ${parts.budget.writerSpendMicroUsd}",
            )
            assertTrue(
                parts.budget.spendMicroUsd >= parts.budget.writerSpendMicroUsd,
                "the writer ledger rides the task ledger",
            )
        }

    // -- 5. the host consumes the shared record (the fix-loop law, on the PARTS' instance) -----

    @Test
    fun `the hybrid host writes its mandatory terminal line into the PARTS' shared record`() =
        runBlocking {
            val parts = newParts(ScriptedTransport(ArrayDeque(emptyList())))
            val ladder = Ladder(
                summary = "an impossible marker",
                subtasks = listOf(
                    Subtask("an impossible marker", DoneCondition.MarkerText("Never On This Page")),
                ),
                taskClass = LadderTaskClass.ACTION,
            )
            val scriptedSource = object : LadderSource {
                override suspend fun restructure(
                    goal: String,
                    generation: Int,
                    failure: ReplanContext?,
                ): navigator.app.loop.RestructuredLadder? =
                    if (generation == 1) {
                        navigator.app.loop.RestructuredLadder(ladder, ladder.versionTag("test", 1), 0)
                    } else {
                        null
                    }

                override suspend fun repairCondition(
                    subtaskInstruction: String,
                    current: DoneCondition,
                    pageUrl: String,
                    pageTitle: String,
                ): RepairedCondition? = null
            }
            // A scripted tool whose every subtask fails (the failure path, fast + $0).
            val failingTool = object : VisionToolStep {
                override suspend fun runSubtask(
                    goal: String,
                    ladder: Ladder,
                    index: Int,
                    isCancelled: () -> Boolean,
                ): SubtaskOutcome = SubtaskOutcome.Failed("transport dead", 1)
            }
            val loop = NavigationLoop(
                engine = StubEngine(StubScript(emptyList())),
                observer = FakeObserver(listOf(obs("chrome://newtab"))),
                executor = executor(),
                gatePresenter = AutoDenyGatePresenter,
                logSink = NoOpLogSink,
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
                ladderSource = scriptedSource,
                visionTool = failingTool,
                runEvidence = parts.evidence, // THE product wiring's shape: the parts' instance
            )

            val result = loop.run(
                goal = "find the impossible marker",
                goalId = "route-shared-record",
                engineIdentity = EngineIdentity("stub", "stub"),
            )

            // Three thin-tree escalations drive the failure path to the owner card's
            // fail-safe stop (the HybridTerminalEvidenceTest shape, on the PARTS' record).
            assertEquals(
                TerminalState.STUCK_KILL,
                result.terminal,
                result.stepLog.joinToString("\n"),
            )
            assertTrue(
                result.terminalDetail.contains("the owner said stop (escalation card)"),
                "the scripted tool failures drove the owner-card stop: ${result.terminalDetail}",
            )
            val events = parts.evidence.summaryJson()["events"].toString()
            assertTrue(
                "terminal=STUCK_KILL" in events && "modality=HYBRID" in events,
                "the host's terminal line landed in the PARTS' shared record: $events",
            )
            assertTrue(
                "escalations=3" in events,
                "the run's actual flip count rides the line: $events",
            )
        }
}
