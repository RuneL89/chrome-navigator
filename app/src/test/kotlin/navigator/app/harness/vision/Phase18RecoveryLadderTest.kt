package navigator.app.harness.vision

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.app.loop.ActionExecutor
import navigator.app.loop.LoopObserver
import navigator.app.loop.RecoveryController
import navigator.app.loop.StepObservation
import navigator.core.enumerate.ObservedNode
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderValidator
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PHASE_18 Stage 18.D CN-17 — the RECOVERY LADDER gate (18.D6; the plan's six cases,
 * $0 scripted; doc 04 §9.9 is the law). The [RecoveryController] DECIDES the next
 * lawful stage; the loop EXECUTES through its existing seams (the stuck probe's bounded
 * stability wait, the grounding ladder's fresh re-ground, the scroll-find cap with its
 * list/target retention, the strategy guard, the honest terminals). Cases:
 *
 * 1. a loading page becomes stable within the bounded wait → NO re-plan call;
 * 2. a stale target succeeds after ONE fresh grounding → bounded recovery, no prefix
 *    reset;
 * 3. a long-list target is found within the scroll cap → list state retained across
 *    scrolls;
 * 4. the target is absent at list end → ONE informed recovery outcome, not unlimited
 *    scrolling;
 * 5. the same failing subtask returns with only the wording changed → rejected as the
 *    same strategy;
 * 6. repeated failures exhaust the recovery ladder → owner handoff or an honest
 *    terminal (never an infinite loop).
 */
class Phase18RecoveryLadderTest {

    // -- scripted edges -------------------------------------------------------------------

    private class ScriptedVisionTransport(
        private val chatResponses: ArrayDeque<String>,
        private val jevResponses: ArrayDeque<String>,
    ) : WireTransport {
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            kotlinx.coroutines.delay(2) // real-clock durations in the call rows (doc 05 §8)
            val isChat = request.url.contains("chat/completions")
            val body = (
                if (isChat) chatResponses.removeFirstOrNull() else jevResponses.removeFirstOrNull()
                ) ?: throw IllegalStateException("script exhausted for ${request.url}")
            return WireHttpResponse(200, body)
        }
    }

    private class ScriptedObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull() ?: error("observer script exhausted")
    }

    private class RecordingExecutor : ActionExecutor {
        val scrolls = mutableListOf<Boolean>()
        override fun tap(x: Int, y: Int): Boolean = true
        override fun clickNode(x: Int, y: Int): Boolean = true
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean {
            scrolls.add(down)
            return true
        }
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

    private fun node(id: String, left: Int, top: Int, right: Int, bottom: Int, text: String = "Go") =
        ObservedNode(
            id = id, text = text, desc = "", clickable = true, editable = false,
            scrollable = false, left = left, top = top, right = right, bottom = bottom,
            className = "android.widget.Button", sourceOrder = 0,
        )

    private fun observationAt(url: String, nodes: List<ObservedNode> = emptyList(), line: String = "some page") =
        StepObservation(url = url, title = "Page", lines = listOf(line), nodes = nodes)

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

    private fun jevGround(pickIndex: Int, edges: List<Double>, confidence: Double): String =
        buildJsonObject {
            put("answers", buildJsonObject {
                put("b0", buildJsonObject {
                    put("type", JsonPrimitive("box"))
                    put("box", buildJsonObject {
                        put("top", JsonPrimitive(edges[0]))
                        put("bottom", JsonPrimitive(edges[1]))
                        put("left", JsonPrimitive(edges[2]))
                        put("right", JsonPrimitive(edges[3]))
                    })
                    put("confidence", JsonPrimitive(confidence))
                })
                put("pick", buildJsonObject {
                    put("type", JsonPrimitive("choice"))
                    put("choice", JsonPrimitive("p$pickIndex"))
                })
            })
            put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
        }.toString()

    private fun tapProposal(description: String) =
        """{"proposals":[{"description":"$description","action":"tap","confidence":0.9,""" +
            """"expectedEffect":{"expect":"ExpectedHostReached","value":"next.test"}}]}"""

    private fun scrollProposal() =
        """{"proposals":[{"description":"scroll the list","action":"scroll_down","confidence":0.9}]}"""

    private fun emptyProposals() = """{"proposals":[]}"""

    private fun plan(vararg subtasks: String): String =
        """{"summary":"the plan","taskClass":"read","subtasks":[${subtasks.joinToString(",")}]}"""

    private fun subtask(instruction: String, done: String, verify: String? = null): String =
        """{"instruction":"$instruction","done":$done""" +
            (verify?.let { ""","verify":[$it]""" } ?: "") + "}"

    private fun urlHost(host: String) = """{"urlHost":"$host"}"""
    private fun marker(markerText: String) = """{"markerText":"$markerText"}"""
    private fun confirmAnswer(ok: Boolean) = """{"complete":$ok,"reason":"the scripted verdict"}"""
    private fun batteryAnswer(question: String, answer: Boolean) =
        """{"answers":[{"question":"$question","answer":$answer}]}"""

    /** The pick-Choice-only Jev answer (the scroll proposals' single ground question). */
    private fun jevPick(): String = buildJsonObject {
        put("answers", buildJsonObject {
            put("pick", buildJsonObject {
                put("type", JsonPrimitive("choice"))
                put("choice", JsonPrimitive("p0"))
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    private fun tempEvidence(runId: String): Pair<VisionEvidence, File> {
        val base = Files.createTempDirectory("phase18-ladder").toFile()
        return VisionEvidence(base, runId) to File(base, "$runId/events.log")
    }

    private fun payloadLines(eventsFile: File): List<String> =
        eventsFile.readLines().filter { it.length > 10 }.map { it.drop(10) }

    private fun field(row: String, name: String): String =
        row.split(" ").firstOrNull { it.startsWith("$name=") }?.removePrefix("$name=")
            ?: error("no $name= field in: $row")

    private fun runSummaryLine(lines: List<String>): String =
        lines.lastOrNull { it.startsWith("run-summary ") }
            ?: error("no run-summary row in:\n" + lines.joinToString("\n"))

    private fun summaryField(lines: List<String>, name: String): String =
        field(runSummaryLine(lines), name)

    private fun loop(
        transport: WireTransport,
        evidence: VisionEvidence,
        observations: List<StepObservation>,
        executor: RecordingExecutor = RecordingExecutor(),
        config: VisionLoop.Config,
    ): VisionLoop = VisionLoop(
        observer = ScriptedObserver(*observations.toTypedArray()),
        executor = executor,
        capture = FakeCapture(shot()),
        haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
        jev = VisionJev(
            transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
            backoffBaseMs = 1, retryDelay = {},
        ),
        budget = ExpBudget(),
        evidence = evidence,
        config = config,
    )

    // -- case 1: the loading page stabilizes within the bounded wait — NO re-plan ----------

    @Test
    fun `a loading page becomes stable within the bounded wait with no re-plan call`() = runBlocking {
        // Pure pin: a fresh STATIC_SCREEN episode's next lawful stage is the BOUNDED
        // STABILITY WAIT (doc 04 §9.9's order), never a straight re-plan.
        assertEquals(
            RecoveryController.Stage.BOUNDED_STABILITY_WAIT,
            RecoveryController.nextStage(
                RecoveryController.Cause.STATIC_SCREEN,
                RecoveryController.Episode(freshObservationTaken = true, replansRemaining = 5),
            ),
        )
        val planJson = plan(subtask("open the example page on next.test", urlHost("next.test")))
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(planJson),
                    chatContent(emptyProposals()), // the loading page offers nothing yet
                    chatContent(emptyProposals()), // nor yet on the second read
                    chatContent(confirmAnswer(true)),
                ),
            ),
            jevResponses = ArrayDeque(listOf(jevNoul("done", 0.9))),
        )
        val (evidence, eventsFile) = tempEvidence("ladder-stability")
        // The page "loads": the loop's first three reads are identical (the stuck window),
        // then the probe's fresh re-read sees the settled page. maxReplans = 0 is the
        // canary — ANY re-plan spend would refuse the run instead of achieving it.
        val result = loop(
            transport, evidence,
            listOf(
                observationAt("https://old.test/loading", line = "loading the page"),
                observationAt("https://old.test/loading", line = "loading the page"),
                observationAt("https://old.test/loading", line = "loading the page"),
                observationAt("https://old.test/loading", line = "loading the page"),
                observationAt("https://next.test/page", line = "the example page rendered"),
                observationAt("https://next.test/page", line = "the example page rendered"),
            ),
            config = VisionLoop.Config(
                settleMs = 0, maxReplans = 0, planAttempts = 1, stepFailuresBeforeReplan = 3,
            ),
        ).run("open the example page on next.test", "ladder-stability") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        val lines = payloadLines(eventsFile)
        // The controller DECIDED the stability wait at the stuck probe and the probe
        // EXECUTED it (settle + fresh re-read) — the row reconstructs the decision.
        assertTrue(
            lines.any { it.startsWith("recovery-stage cause=STATIC_SCREEN stage=BOUNDED_STABILITY_WAIT") },
            "the bounded-wait decision rides the record: $lines",
        )
        // NO re-plan call: zero replan rows, zero replan call rows, zero REPLAN lines.
        assertEquals(0, summaryField(lines, "replans").toInt(), lines.toString())
        assertTrue(lines.none { it.startsWith("call replan ") }, "no re-plan call: $lines")
        assertTrue(lines.none { it.startsWith("REPLAN") }, "no re-plan line: $lines")
    }

    // -- case 2: a stale target succeeds after ONE fresh grounding — no prefix reset -------

    @Test
    fun `a stale target succeeds after one fresh grounding with no prefix reset`() = runBlocking {
        // Pure pin: a STALE_TARGET walks the ladder to the FRESH TARGET GROUNDING stage.
        assertEquals(
            RecoveryController.Stage.FRESH_TARGET_GROUNDING,
            RecoveryController.nextStage(
                RecoveryController.Cause.STALE_TARGET,
                RecoveryController.Episode(
                    freshObservationTaken = true,
                    stabilityWaitTaken = true,
                    replansRemaining = 5,
                ),
            ),
        )
        val planJson = plan(subtask("open the example page on next.test", urlHost("next.test")))
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(planJson),
                    chatContent(tapProposal("the mystery target")), // grounds nowhere: the stale target
                    chatContent(tapProposal("the 'Go' button")), // the fresh grounding's target
                    chatContent(confirmAnswer(true)),
                ),
            ),
            jevResponses = ArrayDeque(
                listOf(
                    jevGround(0, listOf(0.5, 0.55, 0.5, 0.6), 0.9), // a confident MISS (IoU 0)
                    jevGround(0, listOf(0.15, 0.18, 0.1, 0.3), 0.9), // the fresh grounding: the node
                    jevNoul("done", 0.9),
                ),
            ),
        )
        val (evidence, eventsFile) = tempEvidence("ladder-grounding")
        val result = loop(
            transport, evidence,
            listOf(
                observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
                observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
                observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
                observationAt("https://next.test/page"),
            ),
            config = VisionLoop.Config(settleMs = 0, maxReplans = 0, planAttempts = 1),
        ).run("open the example page on next.test", "ladder-grounding") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        val lines = payloadLines(eventsFile)
        // The first ground refused (LOW_CONFIDENCE_GROUND, zero executor calls); the next
        // step's fresh grounding found the target and the run completed.
        assertTrue(
            lines.any { "LOW_CONFIDENCE_GROUND" in it },
            "the stale target's first ground refused loudly: $lines",
        )
        assertEquals(
            2,
            lines.count { it.startsWith("call ground ") },
            "exactly ONE fresh re-ground after the miss: $lines",
        )
        // NO prefix reset: the rollback never fired; the prefix state was untouched.
        assertEquals("0", summaryField(lines, "rollbacks"))
        assertTrue(lines.none { it.startsWith("PREFIX ROLLBACK") }, lines.toString())
        assertTrue(lines.none { it.startsWith("rollback ") }, lines.toString())
        assertEquals(0, summaryField(lines, "replans").toInt())
    }

    // -- case 3: a long-list target found within the scroll cap — list retained ------------

    @Test
    fun `a long-list target is found within the scroll cap with list state retained`() = runBlocking {
        val planJson = plan(
            subtask(
                "find the payload item in the list",
                marker("payload"),
                verify = """{"question":"the payload item is visible in the list","badIsTrue":false}""",
            ),
        )
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(planJson),
                    chatContent(batteryAnswer("the payload item is visible in the list", false)), // the pre-act battery (image channel)
                    chatContent(scrollProposal()), // scroll 1: the search
                    chatContent(scrollProposal()), // scroll 2: the held-battery step's search
                    chatContent(batteryAnswer("the payload item is visible in the list", true)), // accept-side battery (image channel)
                    chatContent(confirmAnswer(true)),
                ),
            ),
            jevResponses = ArrayDeque(
                listOf(
                    jevNoul("v0", 0.1), // the pre-act battery on the un-scrolled list: not there yet
                    jevPick(), // the scroll's pick
                    jevPick(), // the second scroll's pick
                    jevNoul("v0", 0.9), // the accept-side battery on the found list
                    jevNoul("done", 0.9),
                ),
            ),
        )
        val (evidence, eventsFile) = tempEvidence("ladder-scrollfind")
        val executor = RecordingExecutor()
        val result = loop(
            transport, evidence,
            listOf(
                observationAt("https://list.test/", line = "list row one"),
                observationAt("https://list.test/", line = "list row two"),
                observationAt("https://list.test/", line = "list row three"),
                observationAt("https://list.test/", line = "list row four and the payload item"),
            ),
            executor = executor,
            config = VisionLoop.Config(settleMs = 0, maxReplans = 0, planAttempts = 1, stepFailuresBeforeReplan = 3),
        ).run("open list.test and find the payload item", "ladder-scrollfind") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        val lines = payloadLines(eventsFile)
        // The scroll-to-find HOLD kept the battery from re-judging a mid-list screen (the
        // list + the active target retained across the scrolls — Stage 0.7's law).
        assertTrue(
            lines.any { it.startsWith("battery held: a scroll-to-find is in progress") },
            "the scroll-find hold rode the record: $lines",
        )
        // The search stayed inside the cap: two scrolls, no cap exhaustion, no re-plan.
        assertEquals(2, executor.scrolls.size, "the scrolls: $lines")
        assertEquals(2, executor.scrolls.count { it }, "both scrolls scrolled down")
        assertTrue(
            lines.none { it.contains("SCROLL-FIND EXHAUSTED") },
            "the cap was never reached: $lines",
        )
        assertTrue(lines.none { it.startsWith("REPLAN") }, "no re-plan mid-search: $lines")
        assertEquals("1", summaryField(lines, "verified"))
    }

    // -- case 4: the target absent at list end — ONE informed recovery, bounded scrolls ----

    @Test
    fun `the target absent at list end yields one informed recovery not unlimited scrolling`() = runBlocking {
        val verifyBlock = """{"question":"the payload item is visible in the list","badIsTrue":false}"""
        val planJson = plan(
            subtask("find the payload item in the list", marker("payload"), verify = verifyBlock),
        )
        val replanJson = plan(
            subtask("keep scrolling to find the payload item", marker("payload"), verify = verifyBlock),
        )
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(planJson),
                    chatContent(batteryAnswer("the payload item is visible in the list", false)), // battery 1 (iter1)
                    chatContent(scrollProposal()), // scroll 1
                    chatContent(scrollProposal()), // scroll 2 (the cap is 2)
                    chatContent(batteryAnswer("the payload item is visible in the list", false)), // battery at the cap step
                    chatContent(replanJson), // the ONE informed re-plan
                    chatContent(batteryAnswer("the payload item is visible in the list", false)), // the re-planned search's battery
                    chatContent(scrollProposal()), // the re-planned route's search scroll
                    chatContent(batteryAnswer("the payload item is visible in the list", true)), // the accept-side battery
                    chatContent(confirmAnswer(true)),
                ),
            ),
            jevResponses = ArrayDeque(
                listOf(
                    jevNoul("v0", 0.1), // battery 1: not there yet
                    jevPick(), // scroll 1's pick
                    jevPick(), // scroll 2's pick
                    jevNoul("v0", 0.1), // the cap step's battery: still searching
                    jevNoul("v0", 0.1), // the re-planned search's battery: still searching
                    jevPick(), // the re-planned scroll's pick
                    jevNoul("v0", 0.9), // the accept-side battery: found
                    jevNoul("done", 0.9),
                ),
            ),
        )
        val (evidence, eventsFile) = tempEvidence("ladder-exhaust")
        val executor = RecordingExecutor()
        val result = loop(
            transport, evidence,
            listOf(
                observationAt("https://list.test/", listOf(node("n1", 100, 300, 300, 360, text = "list row")), line = "list row one"),
                observationAt("https://list.test/", listOf(node("n1", 100, 300, 300, 360, text = "list row")), line = "list row two"),
                observationAt("https://list.test/", listOf(node("n1", 100, 300, 300, 360, text = "list row")), line = "list row three"),
                observationAt("https://list.test/", listOf(node("n1", 100, 300, 300, 360, text = "list row")), line = "list row four"),
                observationAt("https://list.test/", listOf(node("n1", 100, 300, 300, 360, text = "list row")), line = "list row five"),
                observationAt("https://list.test/", listOf(node("n1", 100, 300, 300, 360, text = "list row")), line = "list row six and the payload item"),
            ),
            executor = executor,
            config = VisionLoop.Config(
                settleMs = 0, maxReplans = 2, planAttempts = 1,
                stepFailuresBeforeReplan = 3, scrollFindMax = 2,
            ),
        ).run("open list.test and find the payload item", "ladder-exhaust") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        val lines = payloadLines(eventsFile)
        // The cap's verdict: ONE exhausted line, ONE informed re-plan — never unlimited
        // scrolling.
        assertEquals(
            1,
            lines.count { it.contains("SCROLL-FIND EXHAUSTED") },
            "the cap fired exactly once: $lines",
        )
        assertEquals(1, lines.count { it.startsWith("REPLAN #1:") }, "ONE informed re-plan: $lines")
        assertTrue(
            3 <= executor.scrolls.size && executor.scrolls.size <= RecoveryControllerLadderPin.SCROLL_CAP,
            "the scrolls stayed bounded (cap ${RecoveryControllerLadderPin.SCROLL_CAP}): ${executor.scrolls.size}",
        )
        // The controller's decision rode the gate record (the ladder's stage of record).
        assertTrue(
            lines.any { it.startsWith("recovery-stage cause=") && "stage=" in it },
            "the gate consulted the controller: $lines",
        )
    }

    private fun payloadSubtask(instruction: String) =
        navigator.core.ladder.Subtask(instruction, navigator.core.ladder.DoneCondition.MarkerText("payload"))

    // -- case 5: a wording-only change is the SAME strategy ---------------------------------

    @Test
    fun `the same failing subtask with only the wording changed is rejected as the same strategy`() = runBlocking {
        // Pure pins (doc 04 §9.9: a changed verb alone is not a new strategy).
        assertTrue(
            RecoveryController.isWordingOnlyChange(
                payloadSubtask("tap the payload button"),
                payloadSubtask("press the payload button"),
            ),
            "a verb swap over the same target tokens IS the same strategy",
        )
        assertTrue(
            RecoveryController.isWordingOnlyChange(
                payloadSubtask("tap the payload button"),
                payloadSubtask("tap the payload button"),
            ),
            "a byte-equal instruction is the same strategy",
        )
        assertFalse(
            RecoveryController.isWordingOnlyChange(
                payloadSubtask("tap the payload button"),
                payloadSubtask("tap the payment button"),
            ),
            "a changed target token is a genuine strategy change",
        )
        assertFalse(
            RecoveryController.isWordingOnlyChange(
                payloadSubtask("tap the payload button"),
                navigator.core.ladder.Subtask(
                    "press the payload button",
                    navigator.core.ladder.DoneCondition.MarkerText("other"),
                ),
            ),
            "a changed done-condition is a genuine strategy change",
        )
        assertFalse(
            RecoveryController.isWordingOnlyChange(
                payloadSubtask("tap the payload button"),
                payloadSubtask("press the payload button gently now"),
            ),
            "a different token run is a genuine strategy change",
        )

        // Integration: after two battery re-plans on the same failure, a verb-swapped
        // re-plan of the failing subtask is REFUSED — no install, honest terminal.
        val planJson = plan(
            subtask(
                "tap the payload button",
                marker("payload"),
                verify = """{"question":"the page shows no progress","badIsTrue":true}""",
            ),
        )
        fun ladderWith(instruction: String): String = plan(
            subtask(
                instruction,
                marker("payload"),
                verify = """{"question":"the page shows no progress","badIsTrue":true}""",
            ),
        )
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(planJson),
                    chatContent(batteryAnswer("the page shows no progress", true)), // red flag 1
                    chatContent(ladderWith("press the payload button")), // re-plan 1 (verb swap)
                    chatContent(tapProposal("the 'Go' button")), // the re-planned plan acts first
                    chatContent(batteryAnswer("the page shows no progress", true)), // red flag 2
                    chatContent(ladderWith("tap the payload button")), // re-plan 2 (swapped back)
                    chatContent(tapProposal("the 'Go' button")),
                    chatContent(batteryAnswer("the page shows no progress", true)), // red flag 3
                    chatContent(ladderWith("press the payload button")), // re-plan 3: REFUSED
                ),
            ),
            jevResponses = ArrayDeque(
                listOf(jevNoul("v0", 0.9), jevNoul("v0", 0.9), jevNoul("v0", 0.9)),
            ),
        )
        val (evidence, eventsFile) = tempEvidence("ladder-strategy")
        val result = loop(
            transport, evidence,
            listOf(
                observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
                observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
                observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
                observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
                observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
                observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
            ),
            config = VisionLoop.Config(
                settleMs = 0, maxReplans = 5, planAttempts = 1, stepFailuresBeforeReplan = 1,
                batteryReplansPerSubtask = 5,
            ),
        ).run("open site.test and tap the payload button", "ladder-strategy") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        val lines = payloadLines(eventsFile)
        assertTrue(
            lines.any {
                it.startsWith("re-plan refused") && "must change the APPROACH" in it
            },
            "the wording-only re-plan was refused as the same strategy: $lines",
        )
        // Re-plans 1 and 2 installed; the third (verb-swapped) did not.
        assertEquals(
            2,
            lines.count { it.startsWith("REPLAN (battery #") },
            "exactly the two lawful battery re-plans installed: $lines",
        )
    }

    // -- case 6: the ladder exhausted ends at an honest terminal -----------------------------

    @Test
    fun `repeated failures exhaust the ladder into an honest terminal never an infinite loop`() = runBlocking {
        // Pure pins: the ladder's END is the handoff/stop stage — a wrong window with no
        // restoration capability hands off; an exhausted episode stops; a capture outage
        // without the (Stage-18.E) perception switch stops.
        assertEquals(
            RecoveryController.Stage.CLARIFY_HANDOFF_OR_STOP,
            RecoveryController.nextStage(
                RecoveryController.Cause.WRONG_WINDOW,
                RecoveryController.Episode(browserRestoreAvailable = false),
            ),
        )
        assertEquals(
            RecoveryController.Stage.CLARIFY_HANDOFF_OR_STOP,
            RecoveryController.nextStage(
                RecoveryController.Cause.STATIC_SCREEN,
                RecoveryController.Episode(
                    freshObservationTaken = true, stabilityWaitTaken = true,
                    freshGroundingTaken = true, mechanismRetryTaken = true,
                    listExhausted = true, replansRemaining = 0,
                ),
            ),
        )
        assertEquals(
            RecoveryController.Stage.INFORMED_REPLAN,
            RecoveryController.nextStage(
                RecoveryController.Cause.INVALID_PREFIX,
                RecoveryController.Episode(rollbackAvailable = false, replansRemaining = 2),
            ),
        )
        assertEquals(
            RecoveryController.Stage.CLARIFY_HANDOFF_OR_STOP,
            RecoveryController.nextStage(
                RecoveryController.Cause.CAPTURE_UNAVAILABLE,
                RecoveryController.Episode(perceptionSwitchAvailable = false),
            ),
        )

        // Integration: with the re-plan budget at zero, repeated failures end the run at
        // the honest terminal (the run RETURNS — no infinite loop).
        val planJson = plan(subtask("open the example page on next.test", urlHost("next.test")))
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(planJson), chatContent(emptyProposals()))),
            jevResponses = ArrayDeque(),
        )
        val (evidence, eventsFile) = tempEvidence("ladder-terminal")
        val result = loop(
            transport, evidence,
            listOf(
                observationAt("https://old.test/"),
                observationAt("https://old.test/"),
            ),
            config = VisionLoop.Config(
                settleMs = 0, maxReplans = 0, planAttempts = 1, stepFailuresBeforeReplan = 1,
            ),
        ).run("open the example page on next.test", "ladder-terminal") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        val lines = payloadLines(eventsFile)
        assertTrue(
            lines.any { it.contains("REPLANS_EXHAUSTED") },
            "the honest terminal reconstructs: $lines",
        )
        assertEquals("0", summaryField(lines, "replans"))
    }
}

/** Test-scoped constants the scroll-cap assertion reads. */
private object RecoveryControllerLadderPin {
    const val SCROLL_CAP = 8
}
