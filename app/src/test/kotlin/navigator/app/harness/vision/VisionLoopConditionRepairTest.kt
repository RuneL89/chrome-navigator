package navigator.app.harness.vision

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
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PHASE_VEXP7's JVM gates ($0 — fully scripted transports, fabricated capture, temp-dir
 * evidence, no device and no provider call): the RUNTIME CONDITION REPAIR.
 *
 * Run 5 evidence (`task-1792414981694`, step 22): the Booking results showed 81 hotel
 * cards with prices; the battery fired OUTCOME_ACHIEVED_WHILE_CONDITION_UNMET because the
 * plan's `elementPresent` ("a list of hotel result cards…") matched no control label, and
 * the loop only re-planned (nine times). The repair law: ONE bounded repair turn per
 * (active subtask, plan), triggered ONLY by [VisionLoop.BatteryGate.OutcomeAchieved]; the
 * repaired condition must pass the code checker on the CURRENT observation AND the
 * trigger battery must concur; acceptance mirrors the normal accept/advance/terminal
 * (no executor call, no gate); a failed/invalid repair falls through to the existing
 * battery re-plan path; the planner is called with the current screenshot; the code
 * fallback runs ONLY when the planner call fails.
 */
class VisionLoopConditionRepairTest {

    // -- scripted edges -------------------------------------------------------------------

    /**
     * Routes by URL: chat-completions pops [chatResponses], everything else
     * [jevResponses]. [failChatFrom] makes every chat call at/after that 1-based index
     * throw a transport failure (the repair-call-failure arm; the retry law means the
     * loop's Haiku client must run with `maxRetries = 0` there for one clean failure).
     */
    private class ScriptedVisionTransport(
        private val chatResponses: ArrayDeque<String>,
        private val jevResponses: ArrayDeque<String>,
        private val failChatFrom: Int = Int.MAX_VALUE,
    ) : WireTransport {
        var chatCalls = 0
            private set
        var jevCalls = 0
            private set

        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            val isChat = request.url.contains("chat/completions")
            if (isChat) {
                chatCalls += 1
                if (chatCalls >= failChatFrom) {
                    throw WireTransportException("scripted chat transport failure (#$chatCalls)")
                }
                val body = chatResponses.removeFirstOrNull()
                    ?: throw WireTransportException("chat script exhausted (#$chatCalls)")
                return WireHttpResponse(200, body)
            }
            jevCalls += 1
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
        val nodeClicks = mutableListOf<Pair<Int, Int>>()
        val entered = mutableListOf<Pair<Pair<Int, Int>, String>>()
        override fun tap(x: Int, y: Int): Boolean { taps.add(x to y); return true }
        override fun clickNode(x: Int, y: Int): Boolean { nodeClicks.add(x to y); return true }
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean = true
        override fun back(): Boolean = true
        override fun submit(): Boolean = true
        override fun enterText(x: Int, y: Int, text: String): Boolean {
            entered.add((x to y) to text)
            return true
        }
    }

    /** A fabricated capture: the repair call (and every battery) sees a frame, never a device. */
    private class FakeCapture(private val shot: VisionCapture.ScreenShot) : VisionCapture(null) {
        override suspend fun capture(exclusion: VisionMath.RectPx?): VisionCapture.ScreenShot = shot
    }

    private fun shot() = VisionCapture.ScreenShot(
        base64 = "QUJD",
        width = 1000,
        height = 2000,
        screenWidth = 1000,
        screenHeight = 2000,
        sha256 = "fixture",
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

    /** A scripted Jev battery answer: question key ("v0"/"v1") -> noul. */
    private fun jevBattery(vararg answers: Pair<String, Double>): String = buildJsonObject {
        put("answers", buildJsonObject {
            answers.forEach { (key, value) ->
                put(key, buildJsonObject {
                    put("type", JsonPrimitive("noul"))
                    put("noul", JsonPrimitive(value))
                })
            }
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    /** The recorded-opinion noul (the normal accept's Jev half). */
    /** The GROUND answer: the box for the only proposal (b0) + the pick choosing p0. */
    private fun jevGround(
        top: Double,
        bottom: Double,
        left: Double,
        right: Double,
        confidence: Double = 0.9,
    ): String = buildJsonObject {
        put("answers", buildJsonObject {
            put("b0", buildJsonObject {
                put("type", JsonPrimitive("box"))
                put("box", buildJsonObject {
                    put("top", JsonPrimitive(top))
                    put("bottom", JsonPrimitive(bottom))
                    put("left", JsonPrimitive(left))
                    put("right", JsonPrimitive(right))
                })
                put("confidence", JsonPrimitive(confidence))
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

    /** The pick-only GROUND answer (a tap proposal carries no box in these scripts). */
    private fun jevPick(): String = buildJsonObject {
        put("answers", buildJsonObject {
            put("pick", buildJsonObject {
                put("type", JsonPrimitive("choice"))
                put("choice", JsonPrimitive("p0"))
                put("probabilities", buildJsonObject {
                    put("p0", JsonPrimitive(0.9))
                    put("none", JsonPrimitive(0.1))
                })
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    /** The Haiku battery's image-channel answer, echoing the questions verbatim. */
    private fun haikuBattery(vararg answers: Pair<String, Boolean>): String = buildJsonObject {
        put("answers", buildJsonArray {
            answers.forEach { (question, answer) ->
                add(buildJsonObject {
                    put("question", JsonPrimitive(question))
                    put("answer", JsonPrimitive(answer))
                })
            }
        })
    }.toString()

    private fun node(id: String, text: String) = ObservedNode(
        id = id, text = text, desc = "", clickable = true, editable = false,
        scrollable = false, left = 100, top = 200, right = 300, bottom = 260,
        className = "android.widget.Button", sourceOrder = 0,
    )

    private fun observation(
        url: String = "https://booking.test/search",
        title: String = "Booking",
        vararg lines: String,
    ) = StepObservation(
        url = url,
        title = title,
        lines = lines.toList(),
        nodes = listOf(node("n1", "Book now")),
    )

    /** The run-5 results page: 81 named hotel cards with prices, and no matching control label. */
    private fun resultsPage() = page(
        "81 hotels found in Riga",
        "Grand Palace Hotel Riga from EUR 89",
        "Riga Old Town Hostel from EUR 24",
    )

    /** A page carrying only the given lines (the default booking.test URL). */
    private fun page(vararg lines: String) = observation(lines = *lines)

    private fun doneObservation() = observation(
        url = "https://done.test/finished",
        title = "Done",
        "the task is done",
    )

    // -- plan scripting -------------------------------------------------------------------

    private fun question(text: String, badIsTrue: Boolean): String =
        buildJsonObject {
            put("question", JsonPrimitive(text))
            put("badIsTrue", JsonPrimitive(badIsTrue))
        }.toString()

    private fun subtask(
        instruction: String,
        done: String,
        vararg verify: String,
    ): String = buildString {
        append("{\"instruction\":").append(JsonPrimitive(instruction)).append(",\"done\":").append(done)
        if (verify.isNotEmpty()) {
            append(",\"verify\":[").append(verify.joinToString(",")).append(']')
        }
        append('}')
    }

    private fun plan(vararg subtasks: String): String =
        """{"summary":"repair fixture","taskClass":"read","subtasks":[${subtasks.joinToString(",")}]}"""

    /** The run-5 vague elementPresent: a list DESCRIPTION, never a control label. */
    private val vagueResultsDone =
        """{"elementPresent":"a list of hotel result cards for Riga showing prices or ratings"}"""

    private val resultsQuestion = "is a results list with named hotel cards and prices visible?"

    private val outcomesPlan = plan(
        subtask(
            "Search Google for Riga hotels for next weekend and show the results",
            vagueResultsDone,
            question(resultsQuestion, badIsTrue = false),
        ),
    )

    private val twoStepPlan = plan(
        subtask(
            "Search Google for Riga hotels for next weekend and show the results",
            vagueResultsDone,
            question(resultsQuestion, badIsTrue = false),
        ),
        subtask("report the hotels found", """{"urlHost":"done.test"}"""),
    )

    private val reportPlan = plan(
        subtask(
            "Search Google for Riga hotels for next weekend and show the results",
            vagueResultsDone,
            question(resultsQuestion, badIsTrue = false),
        ),
        subtask("report the hotels found", """{"answerExtracted":{}}"""),
    )

    private fun repairContent(done: String): String = """{"done":$done}"""

    private fun loop(
        transport: ScriptedVisionTransport,
        observations: List<StepObservation>,
        evidence: VisionEvidence,
        executor: RecordingExecutor = RecordingExecutor(),
        config: VisionLoop.Config = VisionLoop.Config(
            settleMs = 0,
            maxReplans = 0,
            stepFailuresBeforeReplan = 1,
            batteryReplansPerSubtask = 0,
        ),
        haikuRetries: Int = 20,
    ): VisionLoop = VisionLoop(
        observer = ScriptedObserver(*observations.toTypedArray()),
        executor = executor,
        capture = FakeCapture(shot()),
        haiku = VisionHaiku(
            transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}, maxRetries = haikuRetries,
        ),
        jev = VisionJev(
            transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
            backoffBaseMs = 1, retryDelay = {},
        ),
        budget = ExpBudget(),
        evidence = evidence,
        config = config,
    )

    private fun newEvidence(runId: String): Pair<VisionEvidence, File> {
        val base = Files.createTempDirectory("vexp7-repair").toFile()
        return VisionEvidence(base, runId) to File(base, "$runId/events.log")
    }

    /** The outcomes' repair line (the phase doc's exact shape). */
    private fun repairLine(result: VisionLoop.Result): String =
        result.subtaskOutcomes.single { it.contains("CONDITION REPAIRED") }

    // -- the seven scenario gates ---------------------------------------------------------

    @Test
    fun `OutcomeAchieved plus a visible-marker repair accepts and advances to the terminal`() = runBlocking {
        // WI3-1: the run-5 shape — a vague elementPresent cannot match, the battery says
        // the outcome is already visible, and the planner returns a markerText that IS on
        // the current page. The subtask is accepted, the pointer advances, and the run
        // reaches its next subtask's terminal.
        val repair = repairContent("""{"markerText":"81 hotels found in Riga"}""")
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(twoStepPlan),                      // plan (2 subtasks)
                    chatContent(haikuBattery(resultsQuestion to true)), // battery image channel
                    chatContent(repair),                           // the repair turn
                    chatContent("""{"complete":true,"reason":"the end state is visible"}"""), // accept opinion
                ),
            ),
            jevResponses = ArrayDeque(listOf(jevBattery("v0" to 0.95), jevNoul(0.9))),
        )
        val executor = RecordingExecutor()
        val (evidence, eventsFile) = newEvidence("repair-advance")

        val result = loop(
            transport,
            listOf(resultsPage(), resultsPage(), doneObservation()),
            evidence,
            executor = executor,
        ).run("open hotels.test and find a hotel in Riga for next weekend", "repair-advance") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        val line = repairLine(result)
        assertEquals(
            "step 0: CONDITION REPAIRED - " +
                "'an element matching 'a list of hotel result cards for Riga showing prices or ratings' is present' -> " +
                "'the page contains '81 hotels found in Riga'' (the outcome was visible)",
            line,
        )
        assertTrue(
            result.subtaskOutcomes.any { it.startsWith("subtask 2/2 DONE") },
            "the pointer advanced to the next subtask: ${result.subtaskOutcomes}",
        )
        assertTrue(
            result.subtaskOutcomes.indexOf(line) <
                result.subtaskOutcomes.indexOfFirst { it.startsWith("subtask 2/2 DONE") },
            "the repair line precedes the advance",
        )
        assertTrue(executor.taps.isEmpty(), "the repair is an ACCEPT path - no executor call")
        assertTrue(executor.entered.isEmpty(), "the repair is an ACCEPT path - no typing")

        val events = eventsFile.readText()
        assertTrue(line in events, "the repair line rides the evidence: $events")
        assertTrue("call repair" in events, "the repair call is recorded: $events")
        assertTrue("condition repair: planner source" in events, "the source is recorded: $events")
        assertTrue("terminal=GOAL_ACHIEVED" in events, "the terminal is recorded: $events")
        val runDir = eventsFile.parentFile
        val repairReq = File(runDir, "call-003-repair.req.json")
        val repairRes = File(runDir, "call-003-repair.res.json")
        assertTrue(repairReq.isFile, "the repair request's raw body is recorded: $runDir")
        assertTrue(repairRes.isFile, "the repair response's raw body is recorded: $runDir")
        assertTrue(
            repairReq.readText().contains("You repair ONE subtask's done-condition"),
            "the repair-only system prompt is the call: ${repairReq.readText().take(200)}",
        )
    }

    @Test
    fun `an invalid repair shape never accepts and the existing battery re-plan path runs`() = runBlocking {
        // WI3-2a: the planner's answer is not a whitelisted shape. The page HAS a readable
        // URL host, so a fallback would accept — it must NOT run: invalid/no-accept goes
        // straight to the existing battery re-plan path (budget 1 -> one battery re-plan).
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(outcomesPlan),
                    chatContent(haikuBattery(resultsQuestion to true)),
                    chatContent(repairContent("""{"looksRight":"yes"}""")), // not a whitelist shape
                    chatContent(outcomesPlan),                              // the battery re-plan
                    chatContent("""{"proposals":[]}"""),                    // held step: no proposals
                    chatContent("""{"complete":false,"reason":"no results list"}"""),
                ),
            ),
            jevResponses = ArrayDeque(listOf(jevBattery("v0" to 0.95), jevNoul(0.1))),
        )
        val (evidence, eventsFile) = newEvidence("repair-invalid")
        val config = VisionLoop.Config(
            settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1, batteryReplansPerSubtask = 1,
        )

        val result = loop(
            transport, listOf(resultsPage(), resultsPage(), resultsPage()), evidence, config = config,
        ).run("open hotels.test and find a hotel in Riga for next weekend", "repair-invalid") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(result.subtaskOutcomes.none { it.contains("CONDITION REPAIRED") }, "no acceptance")
        assertTrue(result.subtaskOutcomes.any { it.contains("BATTERY ESCALATE") }, "the trigger still escalates")
        assertTrue(
            result.subtaskOutcomes.any { it.contains("REPLAN (battery #") },
            "the existing battery re-plan path ran: ${result.subtaskOutcomes}",
        )
        val events = eventsFile.readText()
        assertTrue(
            "the planner's answer is not a valid done-condition" in events,
            "the invalid shape is recorded loudly: $events",
        )
        assertFalse(
            "code-derived fallback" in events,
            "an invalid SHAPE takes the re-plan path, not the code fallback: $events",
        )
        assertEquals(6, transport.chatCalls, "exactly ONE repair call in the run")
    }

    @Test
    fun `a valid repair that does not hold on the current page never accepts`() = runBlocking {
        // WI3-2b: a schema-VALID condition that the current observation does not satisfy
        // (a marker from another page) fails the code check -> no acceptance -> re-plan.
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(outcomesPlan),
                    chatContent(haikuBattery(resultsQuestion to true)),
                    chatContent(repairContent("""{"markerText":"RESULTS-ONLY-ON-ANOTHER-PAGE"}""")),
                    chatContent(outcomesPlan),
                    chatContent("""{"proposals":[]}"""),
                    chatContent("""{"complete":false,"reason":"no results list"}"""),
                ),
            ),
            jevResponses = ArrayDeque(listOf(jevBattery("v0" to 0.95), jevNoul(0.1))),
        )
        val (evidence, eventsFile) = newEvidence("repair-nonmatching")
        val config = VisionLoop.Config(
            settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1, batteryReplansPerSubtask = 1,
        )

        val result = loop(
            transport, listOf(resultsPage(), resultsPage(), resultsPage()), evidence, config = config,
        ).run("open hotels.test and find a hotel in Riga for next weekend", "repair-nonmatching") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(result.subtaskOutcomes.none { it.contains("CONDITION REPAIRED") })
        assertTrue(result.subtaskOutcomes.any { it.contains("REPLAN (battery #") })
        assertTrue(
            "does not hold on the current page" in eventsFile.readText(),
            "the current-observation-only check is recorded",
        )
    }

    @Test
    fun `a failed repair call falls back to the code-derived URL host and accepts`() = runBlocking {
        // WI3-3: the planner call itself fails (transport), so the code fallback derives
        // the current readable URL host under the checker's rules and accepts. The call is
        // made ONCE (maxRetries=0) and the fallback is recorded as code-derived.
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(outcomesPlan),
                    chatContent(haikuBattery(resultsQuestion to true)),
                ),
            ),
            jevResponses = ArrayDeque(listOf(jevBattery("v0" to 0.95))),
            failChatFrom = 3, // plan=1, battery=2, repair=3 (then the fallback accepts)
        )
        val (evidence, eventsFile) = newEvidence("repair-fallback")
        val executor = RecordingExecutor()

        val result = loop(
            transport,
            listOf(resultsPage(), resultsPage()),
            evidence,
            executor = executor,
            haikuRetries = 0,
        ).run("open hotels.test and find a hotel in Riga for next weekend", "repair-fallback") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        val line = repairLine(result)
        assertTrue(
            "'the URL host is booking.test'" in line,
            "the fallback derived the current readable URL host: $line",
        )
        assertTrue(executor.taps.isEmpty(), "the fallback is an ACCEPT path - no executor call")
        val events = eventsFile.readText()
        assertTrue("planner call failed" in events, "the failed call is recorded: $events")
        assertTrue("condition repair: code-derived fallback" in events, "the fallback is recorded: $events")
        assertEquals(3, transport.chatCalls, "exactly ONE repair call attempt (no retries)")
        assertEquals(1, transport.jevCalls, "only the battery used the Jev channel")
    }

    @Test
    fun `one repair per subtask - a second OutcomeAchieved never calls the repair again`() = runBlocking {
        // WI3-4: the first trigger spends the subtask's one repair (the non-matching
        // answer falls through to the existing battery path). The second trigger on the
        // SAME subtask and plan must NOT call the repair again — the repair call count
        // stays 1 and the existing battery path (here its spent-budget forced-PROCEED arm,
        // so no new plan installs and the same-plan window is observable) owns the step.
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(outcomesPlan),
                    chatContent(haikuBattery(resultsQuestion to true)),
                    chatContent(repairContent("""{"markerText":"RESULTS-ONLY-ON-ANOTHER-PAGE"}""")),
                    chatContent("""{"proposals":[{"description":"the Book now button","action":"tap"}]}"""),
                    chatContent("""{"proposals":[]}"""),
                    chatContent("""{"complete":false,"reason":"no results list"}"""),
                ),
            ),
            // PHASE_18 CN-09 (doc 04 §9.8): the ground answer carries the box for the
            // single proposal's node (the OLD scenario relied on a blind centre tap —
            // exactly what §9.8 forbids; the deterministic jev-box grounding preserves
            // the choreography lawfully).
            jevResponses = ArrayDeque(
                listOf(
                    jevBattery("v0" to 0.95),
                    jevGround(top = 0.10, bottom = 0.13, left = 0.10, right = 0.30),
                    jevNoul(0.1),
                ),
            ),
        )
        val (evidence, eventsFile) = newEvidence("repair-once")

        val result = loop(
            transport,
            listOf(resultsPage(), resultsPage(), resultsPage()),
            evidence,
            // Battery budget 0: the fall-through never installs a new plan, so both
            // triggers are the SAME (subtask, plan) pair the one-per-subtask law bounds.
            config = VisionLoop.Config(
                settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1, batteryReplansPerSubtask = 0,
            ),
        ).run("open hotels.test and find a hotel in Riga for next weekend", "repair-once") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        val events = eventsFile.readText()
        assertEquals(
            1,
            Regex("call repair").findAll(events).count(),
            "the repair call ran exactly once: $events",
        )
        assertEquals(
            1,
            Regex("the one repair for this subtask already ran").findAll(events).count(),
            "the second trigger names the spent one-repair allowance: $events",
        )
        assertEquals(
            2,
            result.subtaskOutcomes.count { it.contains("BATTERY ESCALATE") },
            "both OutcomeAchieved triggers took the existing battery path: ${result.subtaskOutcomes}",
        )
        assertEquals(6, transport.chatCalls, "one plan, one battery, one repair, two proposes, one confirm")
    }

    @Test
    fun `a repaired results subtask advances to the final answerExtracted and ends with the answer`() = runBlocking {
        // WI3-5: the run-5 plan shape — repair the results subtask, advance, then the
        // report subtask's extraction runs and the run ends GOAL_ACHIEVED with the answer.
        val answer = "Grand Palace Hotel (EUR 89), Riga Old Town Hostel (EUR 24)"
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(reportPlan),
                    chatContent(haikuBattery(resultsQuestion to true)),
                    chatContent(repairContent("""{"markerText":"81 hotels found in Riga"}""")),
                    chatContent(answer),                                           // report extraction
                    chatContent("""{"complete":true,"reason":"the report state is visible"}"""),
                ),
            ),
            jevResponses = ArrayDeque(listOf(jevBattery("v0" to 0.95), jevNoul(0.9))),
        )
        val (evidence, eventsFile) = newEvidence("repair-report")

        val result = loop(
            transport,
            listOf(resultsPage(), resultsPage(), resultsPage()),
            evidence,
        ).run("open hotels.test and find a hotel in Riga for next weekend and report the options", "repair-report") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(answer, result.answer, "the report subtask extracted the answer")
        val events = eventsFile.readText()
        assertTrue(
            "report extraction ran (answer $answer)" in events,
            "the extraction ran over the freshest observation: $events",
        )
        assertEquals(
            1,
            Regex("report extraction ran").findAll(events).count(),
            "exactly ONE extraction over the freshest observation: $events",
        )
        assertTrue("terminal=GOAL_ACHIEVED" in events)
        assertTrue(result.subtaskOutcomes.any { it.contains("CONDITION REPAIRED") })
    }

    @Test
    fun `a red-flag escalation is never repaired - the existing re-plan path owns it`() = runBlocking {
        // WI3-6: the trigger is EXACTLY OutcomeAchieved. A confident bad-is-true flag
        // (the outcome is NOT visible) must not reach the repair turn at all.
        val redFlagPlan = plan(
            subtask(
                "search Riga hotels for next weekend",
                vagueResultsDone,
                question(resultsQuestion, badIsTrue = false),
                question("does the page show an error or no-hotels message?", badIsTrue = true),
            ),
        )
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(redFlagPlan),
                    chatContent(
                        haikuBattery(
                            resultsQuestion to false,
                            "does the page show an error or no-hotels message?" to true,
                        ),
                    ),
                    chatContent(redFlagPlan), // the battery re-plan
                    chatContent("""{"proposals":[]}"""),
                    chatContent("""{"complete":false,"reason":"no results list"}"""),
                ),
            ),
            jevResponses = ArrayDeque(
                listOf(jevBattery("v0" to 0.1, "v1" to 0.95), jevNoul(0.1)),
            ),
        )
        val (evidence, eventsFile) = newEvidence("repair-redflag")
        val config = VisionLoop.Config(
            settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1, batteryReplansPerSubtask = 1,
        )

        val result = loop(
            transport, listOf(resultsPage(), resultsPage(), resultsPage()), evidence, config = config,
        ).run("open hotels.test and find a hotel in Riga for next weekend", "repair-redflag") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(
            result.subtaskOutcomes.any {
                it.contains("BATTERY ESCALATE - verification battery red flag(s)")
            },
            "the red flag keeps the existing escalation: ${result.subtaskOutcomes}",
        )
        assertTrue(result.subtaskOutcomes.none { it.contains("CONDITION REPAIRED") })
        val events = eventsFile.readText()
        assertFalse("call repair" in events, "a red-flag escalation NEVER calls the repair: $events")
        assertTrue(result.subtaskOutcomes.any { it.contains("REPLAN (battery #") })
    }

    @Test
    fun `the repaired condition is checked on the current observation only - another page's marker fails`() = runBlocking {
        // WI3-7: the marker exists in an EARLIER observation (the run-start page the plan
        // was written over), never on the current page. The check must read the CURRENT
        // observation only, so the repair does not accept and the existing path runs.
        val marker = "RESULTS-HEADER-ONLY-ON-THE-EARLIER-PAGE"
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(outcomesPlan),
                    chatContent(haikuBattery(resultsQuestion to true)),
                    chatContent(repairContent("""{"markerText":"$marker"}""")),
                    chatContent(outcomesPlan),
                    chatContent("""{"proposals":[]}"""),
                    chatContent("""{"complete":false,"reason":"no results list"}"""),
                ),
            ),
            jevResponses = ArrayDeque(listOf(jevBattery("v0" to 0.95), jevNoul(0.1))),
        )
        val (evidence, eventsFile) = newEvidence("repair-current-only")
        val config = VisionLoop.Config(
            settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1, batteryReplansPerSubtask = 1,
        )

        val result = loop(
            transport,
            listOf(
                page(marker),   // the run-start page: the marker exists HERE only
                resultsPage(),  // the current page: the marker is absent
                resultsPage(),
                resultsPage(),
            ),
            evidence,
            config = config,
        ).run("open hotels.test and find a hotel in Riga for next weekend", "repair-current-only") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertTrue(result.subtaskOutcomes.none { it.contains("CONDITION REPAIRED") }, "no acceptance")
        assertTrue(result.subtaskOutcomes.any { it.contains("REPLAN (battery #") })
        assertTrue(
            "does not hold on the current page" in eventsFile.readText(),
            "the check used the current observation, never the earlier page",
        )
    }
}
