package navigator.app.loop

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
import navigator.app.harness.hybrid.SubtaskOutcome
import navigator.app.harness.hybrid.VisionToolStep
import navigator.app.harness.vision.ExpBudget
import navigator.app.harness.vision.VisionCapture
import navigator.app.harness.vision.VisionEvidence
import navigator.app.harness.vision.VisionHaiku
import navigator.app.harness.vision.VisionJev
import navigator.app.harness.vision.VisionLoop
import navigator.app.harness.vision.VisionLoopTool
import navigator.app.harness.vision.VisionMath
import navigator.app.harness.vision.VisionPrompts
import navigator.core.enumerate.ObservedNode
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass
import navigator.core.ladder.ReplanContext
import navigator.core.ladder.Subtask
import navigator.core.log.EngineIdentity
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport
import navigator.core.wire.WireTransportException

/**
 * PHASE_18 Stage 18.E fix-loop — THE HYBRID'S BATTERY CHANNEL (live defect 2026-11-13,
 * task-1794584800835 / task-1794585142981, emulator corpus).
 *
 * The live shape: the hybrid host's writer authored a 2-subtask ladder whose subtask 1
 * carried a WEAK content done-condition already TRUE on the loaded page. The tool's code
 * check cannot accept it (the WEAK-at-start bypass: a mention is not arrival), and the
 * writer's verify battery — the ONE evidence that lets the pure loop accept such a case
 * via the runtime condition repair — was parsed by `VisionLadderSource` and then DROPPED
 * at the host boundary: `tool.runSubtask` was called with NO checks, the battery block
 * never ran (battery=0 calls in the record), propose found nothing to do, and the subtask
 * could never complete (2 failures -> stuck card -> timeout -> STUCK_KILL).
 *
 * Pinned here (scripted, $0, no model call), the fix's halves:
 *
 * 1. TOOL: `runSubtask` with the writer's battery runs the verify-battery + battery
 *    calls, takes the runtime condition repair when the battery says the outcome is
 *    visible, and reports `Done(repairedCondition=...)`;
 * 2. TOOL: a REFUSING battery never accepts (no repair, honest failure);
 * 3. HOST: the host hands the subtask's battery to the tool (the channel law).
 *
 * Vision law: Project Vision/04 §8 (the hybrid: the tool verifies with the writer's
 * battery) + §9.3 (verified effects only) + §9.5/§9.6 (the kept condition; the single
 * verifier downstream).
 */
class Phase18HybridBatteryRepairTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    // -- scripted edges -------------------------------------------------------------------

    /** Routes by URL: chat-completions pops [chat], everything else pops [jev]. */
    private class ScriptedTransport(
        private val chat: ArrayDeque<String>,
        private val jev: ArrayDeque<String>,
    ) : WireTransport {
        var chatCalls = 0
            private set
        var jevCalls = 0
            private set

        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            if (request.url.contains("chat/completions")) {
                chatCalls += 1
                val body = chat.removeFirstOrNull()
                    ?: throw WireTransportException("chat script exhausted (#$chatCalls)")
                return WireHttpResponse(200, body)
            }
            jevCalls += 1
            val body = jev.removeFirstOrNull()
                ?: throw WireTransportException("jev script exhausted (#$jevCalls)")
            return WireHttpResponse(200, body)
        }
    }

    private class ScriptedObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        private var last = observations.last()
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull()?.also { last = it } ?: last
    }

    private class NoopExecutor : ActionExecutor {
        override fun tap(x: Int, y: Int): Boolean = true
        override fun clickNode(x: Int, y: Int): Boolean = true
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean = true
        override fun back(): Boolean = true
        override fun submit(): Boolean = true
        override fun enterText(x: Int, y: Int, text: String): Boolean = true
    }

    private class ScriptedLadderSource(
        private val ladder: Ladder,
        private val batteries: Map<Int, List<VisionPrompts.VerifyCheck>> = emptyMap(),
    ) : LadderSource {
        override suspend fun restructure(
            goal: String,
            generation: Int,
            failure: ReplanContext?,
        ): RestructuredLadder? =
            if (generation == 1) {
                RestructuredLadder(ladder, ladder.versionTag("test", generation), 0, batteries)
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

    /** Scripted vision tool: records the CHECKS it received and returns queued outcomes. */
    private class RecordingTool(vararg outcomes: SubtaskOutcome) : VisionToolStep {
        private val queue = ArrayDeque(outcomes.toList())
        val checkCounts = mutableListOf<Int>()

        override suspend fun runSubtask(
            goal: String,
            ladder: Ladder,
            index: Int,
            isCancelled: () -> Boolean,
        ): SubtaskOutcome = throw IllegalStateException("the host must call the checks overload")

        override suspend fun runSubtask(
            goal: String,
            ladder: Ladder,
            index: Int,
            checks: List<VisionPrompts.VerifyCheck>,
            isCancelled: () -> Boolean,
        ): SubtaskOutcome {
            checkCounts.add(checks.size)
            return queue.removeFirstOrNull() ?: error("vision tool script exhausted")
        }
    }

    private class FakeCapture(private val shot: VisionCapture.ScreenShot) : VisionCapture(null) {
        override suspend fun capture(exclusion: VisionMath.RectPx?): VisionCapture.ScreenShot = shot
    }

    private fun shot() = VisionCapture.ScreenShot(
        base64 = "QUJD", width = 1000, height = 2000,
        screenWidth = 1000, screenHeight = 2000, sha256 = "p18-battery",
        jpegBytes = byteArrayOf(1, 2, 3),
    )

    private fun chatContent(innerJson: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5) })
    }.toString()

    /** The Jev battery answer for one outcome question (the v0 key). */
    private fun jevBattery(pTrue: Double): String = buildJsonObject {
        put("answers", buildJsonObject {
            put("v0", buildJsonObject {
                put("type", JsonPrimitive("noul"))
                put("noul", JsonPrimitive(pTrue))
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    /** The Haiku battery's image-channel answer (the same question, answered). */
    private fun haikuBattery(question: String, answer: Boolean): String = buildJsonObject {
        put("answers", buildJsonArray {
            add(buildJsonObject {
                put("question", JsonPrimitive(question))
                put("answer", JsonPrimitive(answer))
            })
        })
    }.toString()

    private fun repairContent(conditionJson: String): String = buildJsonObject {
        // The repair response's contract (parseRepairedCondition): the condition rides a
        // 'done' object — {"done":{"markerText":"..."}}.
        put("done", kotlinx.serialization.json.Json.parseToJsonElement(conditionJson))
    }.toString()

    private fun node(id: String, text: String) = ObservedNode(
        id = id, text = text, desc = "", clickable = true, editable = false,
        scrollable = false, left = 100, top = 200, right = 900, bottom = 260,
        className = "android.widget.Button", sourceOrder = 0,
    )

    /** The loaded fixture page (the tool's observation — the marker IS in the text). */
    private val loadedPage = StepObservation(
        url = "http://10.0.2.2:8099/r2-site-a-home.html",
        title = "WebView Browser Tester",
        lines = listOf(
            "WebView Browser Tester 133.0.6943.137",
            "http://10.0.2.2:8099/r2-site-a-home.html",
            "Site A",
            "A generic fixture home page. The archive holds the items the run is asked for.",
            "Open the archive",
        ),
        nodes = listOf(
            node("n1", "Load URL"),
            node("n2", "About WebView"),
            node("n3", "Open the archive"),
            ObservedNode(
                id = "n4", text = "Site A", desc = "", clickable = false, editable = false,
                scrollable = false, left = 100, top = 400, right = 900, bottom = 460,
                className = "android.widget.TextView", sourceOrder = 0,
            ),
            ObservedNode(
                id = "n5", text = "A generic fixture home page", desc = "", clickable = false,
                editable = false, scrollable = false, left = 100, top = 500, right = 900,
                bottom = 560, className = "android.widget.TextView", sourceOrder = 0,
            ),
        ),
        windowPackage = "org.chromium.webview_shell",
    )

    /** The thin/empty tree the host escalates on. */
    private val deadPage = StepObservation(
        url = "chrome://newtab", title = "New tab", lines = emptyList(),
        nodes = emptyList(), windowPackage = "com.android.chrome",
    )

    private val ladder = Ladder(
        summary = "open the fixture home page and read its headline",
        subtasks = listOf(
            // The live shape: a WEAK content condition already TRUE on the loaded page.
            Subtask("open the fixture home page", DoneCondition.MarkerText("A generic fixture home page")),
            Subtask("read the headline", DoneCondition.UrlHost("10.0.2.2")),
        ),
        taskClass = LadderTaskClass.READ,
    )

    private val question = "Is the fixture home page loaded and showing its content?"

    private fun newEvidence(runId: String): Pair<VisionEvidence, File> {
        val base = Files.createTempDirectory("p18-hybrid-battery").toFile()
        return VisionEvidence(base, runId) to File(base, "$runId/events.log")
    }

    private fun realTool(transport: ScriptedTransport, evidence: VisionEvidence): VisionLoopTool {
        val loop = VisionLoop(
            observer = ScriptedObserver(loadedPage),
            executor = NoopExecutor(),
            capture = FakeCapture(shot()),
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(
                transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                backoffBaseMs = 1, retryDelay = {}, maxRetries = 0,
            ),
            budget = ExpBudget(),
            evidence = evidence,
            config = VisionLoop.Config(settleMs = 0, maxReplans = 0),
        )
        return VisionLoopTool(loop)
    }

    // -- case 1: the battery + runtime repair accept the WEAK-at-start subtask -----------

    @Test
    fun `case 1 - the battery runs and the runtime repair accepts the WEAK-at-start subtask`() =
        runBlocking {
            val transport = ScriptedTransport(
                chat = ArrayDeque(
                    listOf(
                        chatContent(haikuBattery(question, true)), // the battery's image channel
                        chatContent(repairContent("""{"markerText":"A generic fixture home page"}""")),
                    ),
                ),
                jev = ArrayDeque(listOf(jevBattery(0.95))),
            )
            val (evidence, eventsFile) = newEvidence("hybrid-battery-repair")
            val outcome = realTool(transport, evidence).runSubtask(
                goal = "open the fixture home page and read its headline",
                ladder = ladder,
                index = 0,
                checks = listOf(VisionPrompts.VerifyCheck(question, badIsTrue = false)),
                isCancelled = { false },
            )

            val done = outcome as? SubtaskOutcome.Done
            assertTrue(
                done != null,
                "the battery channel must complete the live shape: $outcome\n" +
                    eventsFile.readText().take(4000),
            )
            assertEquals(
                DoneCondition.MarkerText("A generic fixture home page"),
                done.repairedCondition,
                "the Done carries the repaired condition for the host's kept proof",
            )
            assertEquals(1, transport.jevCalls, "ONE verify-battery call (the outcome question)")
            val events = eventsFile.readText()
            assertTrue(
                "CONDITION REPAIRED" in events,
                "the runtime repair accepted through the tool: ${events.take(3000)}",
            )
            assertTrue(
                "call verify-battery" in events && "call battery" in events,
                "the battery's calls ride the record: ${events.take(3000)}",
            )
        }

    // -- case 2: a refusing battery never accepts ---------------------------------------

    @Test
    fun `case 2 - a refusing battery never accepts the WEAK-at-start subtask`() = runBlocking {
        val transport = ScriptedTransport(
            chat = ArrayDeque(listOf(chatContent(haikuBattery(question, false)))),
            jev = ArrayDeque(listOf(jevBattery(0.10))),
        )
        val (evidence, eventsFile) = newEvidence("hybrid-battery-refuse")
        val outcome = realTool(transport, evidence).runSubtask(
            goal = "open the fixture home page and read its headline",
            ladder = ladder,
            index = 0,
            checks = listOf(VisionPrompts.VerifyCheck(question, badIsTrue = false)),
            isCancelled = { false },
        )

        assertNotEquals(
            SubtaskOutcome.Done::class,
            outcome::class,
            "a refusing battery must never yield Done: $outcome",
        )
        val events = eventsFile.readText()
        assertTrue(
            "CONDITION REPAIRED" !in events,
            "no repair rides a refusing battery: ${events.take(3000)}",
        )
    }

    // -- case 3: the host hands the battery to the tool ---------------------------------

    @Test
    fun `case 3 - the host hands the active subtask's battery to the tool`() = runBlocking {
        val tool = RecordingTool(
            SubtaskOutcome.Done(
                steps = 2,
                noul = 0.9,
                repairedCondition = DoneCondition.MarkerText("A generic fixture home page"),
            ),
        )
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"), *intentStep("go-back"), *intentStep("go-back"),
                *intentStep("go-back"), *intentStep("go-back"), noul(0.9), noul(0.9), noul(0.9),
                noul(0.9), noul(0.9), noul(0.9), noul(0.9), noul(0.9), noul(0.9),
                noul(0.9), noul(0.9),
            ),
            observations = listOf(deadPage),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
        )
        val (evidence, _) = newEvidence("hybrid-repaired-host")
        var observes = 0
        val loop = NavigationLoop(
            engine = fixture.engine,
            observer = object : LoopObserver {
                override suspend fun observe(): StepObservation {
                    observes += 1
                    return if (observes <= 2) deadPage else loadedPage
                }
            },
            executor = fixture.executor,
            gatePresenter = ScriptedGatePresenter(),
            logSink = fixture.sink,
            config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
            ladderSource = ScriptedLadderSource(
                ladder,
                mapOf(0 to listOf(VisionPrompts.VerifyCheck(question, badIsTrue = false))),
            ),
            visionTool = tool,
            runEvidence = evidence,
        )

        loop.run(
            goal = "open the fixture home page and read its headline",
            goalId = "hybrid-repaired-host",
            engineIdentity = identity,
            isCancelled = { observes >= 8 },
        )

        assertTrue(
            tool.checkCounts.isNotEmpty(),
            "the tool was escalated to",
        )
        assertEquals(
            listOf(1),
            tool.checkCounts,
            "the host handed the ACTIVE subtask's battery to the tool (the channel law)",
        )
    }
}
