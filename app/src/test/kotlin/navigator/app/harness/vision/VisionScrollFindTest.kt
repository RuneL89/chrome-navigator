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
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PHASE_17 Stage 0.7 — the LONG-LIST scroll policy (the R4 vision subscriptions defect:
 * the battery red-flagged "still the subscriptions feed" after EVERY scroll, so each
 * scroll paid a battery escalation/re-plan and the engine abandoned the list for
 * search/avatar routes; 126 steps / 20.5 min for one video).
 *
 * The code law pinned here:
 *
 * 1. while an acted-scroll sequence is in progress below
 *    [VisionLoop.Config.scrollFindMax], the pre-act battery is HELD — the next action
 *    (the next scroll) is the search, not a failed outcome;
 * 2. at the cap the sequence is declared EXHAUSTED and the existing failure/re-plan
 *    path runs — never a silent infinite scroll;
 * 3. any acted NON-scroll resets the counter (a new action shape is progress), so a
 *    second run of scrolls starts from zero.
 *
 * Every model call is scripted ($0, in-memory transport); the battery's answers depend
 * on whether the request's page text carries the TARGET marker — the honest fixture of a
 * list that only scans as "arrived" late.
 */
class VisionScrollFindTest {

    // -- scripted edges -------------------------------------------------------------------

    private class RecordingExecutor : ActionExecutor {
        val scrolls = mutableListOf<Boolean>()
        var submits = 0
        override fun tap(x: Int, y: Int): Boolean = true
        override fun clickNode(x: Int, y: Int): Boolean = true
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean {
            scrolls.add(down)
            return true
        }
        override fun back(): Boolean = true
        override fun submit(): Boolean { submits += 1; return true }
        override fun enterText(x: Int, y: Int, text: String): Boolean = true
    }

    private class ScriptedObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull() ?: error("observer script exhausted")
    }

    private class FakeCapture(private val shot: VisionCapture.ScreenShot) : VisionCapture(null) {
        override suspend fun capture(exclusion: VisionMath.RectPx?): VisionCapture.ScreenShot = shot
    }

    private fun shot() = VisionCapture.ScreenShot(
        base64 = "QUJD", width = 1000, height = 2000,
        screenWidth = 1000, screenHeight = 2000, sha256 = "fixture",
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

    private fun batteryAnswer(achieved: Boolean): String = buildJsonObject {
        put("answers", buildJsonArray {
            add(buildJsonObject {
                put("question", JsonPrimitive("is the Food Wishes channel row visible in the list?"))
                put("answer", JsonPrimitive(achieved))
            })
        })
    }.toString()

    private fun jevNoul(question: String, pTrue: Double): String = buildJsonObject {
        put("answers", buildJsonObject {
            put(question, buildJsonObject {
                put("type", JsonPrimitive("noul"))
                put("noul", JsonPrimitive(pTrue))
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

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

    private val planMarker = "You are the planner of an Android agent"
    private val proposeMarker = "You look at a mobile browser page"
    private val batteryMarker = "You are a visual verifier"

    /**
     * Routes every request by its content: plans and proposals pop their scripts; the
     * battery and Jev nouls answer from [arrived] — the test's own knowledge of which
     * list page is on screen (the battery request carries only questions and an image,
     * so the page text cannot be re-derived from its body).
     */
    private inner class RoutingTransport(
        private val plans: ArrayDeque<String>,
        private val proposals: ArrayDeque<String>,
        private val arrived: () -> Boolean,
        private val batteryAnswer: (Boolean) -> String,
        private val noul: (Boolean) -> String,
        private val pick: String,
    ) : WireTransport {
        val chatKinds = mutableListOf<String>()

        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            val body = request.body
            if (request.url.contains("chat/completions")) {
                val kind = when {
                    body.contains("You are the planner of an Android agent") -> "plan"
                    body.contains("You look at a mobile browser page") -> "propose"
                    body.contains("You are a visual verifier") -> "battery"
                    body.contains("You judge whether a mobile browser page fulfills") -> "confirm"
                    else -> "other"
                }
                chatKinds.add(kind)
                val response = when (kind) {
                    "plan" -> plans.removeFirstOrNull() ?: error("plan script exhausted")
                    "propose" -> proposals.removeFirstOrNull() ?: error("propose script exhausted")
                    "battery" -> batteryAnswer(arrived())
                    // The accept's recorded second opinion: the page is the arrival.
                    "confirm" -> """{"complete":true,"reason":"the channel row is visible"}"""
                    else -> error("unexpected chat request (kind=$kind)")
                }
                return WireHttpResponse(200, chatContent(response))
            }
            // The Jev question's NAME selects the answer shape: "v0" is the battery's
            // noul, "done" the accept's recorded opinion, "pick" the grounding choice.
            val answered = when {
                "\"v0\"" in body -> jevNoul("v0", if (arrived()) 0.9 else 0.1)
                "\"done\"" in body -> jevNoul("done", if (arrived()) 0.9 else 0.1)
                else -> pick
            }
            return WireHttpResponse(200, answered)
        }
    }

    /** Counts reads so the test (and the transport) know which list page is current. */
    private class RecordingObserver(private val pages: List<StepObservation>) : LoopObserver {
        var reads = 0
            private set

        override suspend fun observe(): StepObservation {
            val page = pages[minOf(reads, pages.lastIndex)]
            reads += 1
            return page
        }
    }

    private fun node(id: String, text: String) = ObservedNode(
        id = id, text = text, desc = "", clickable = true, editable = false,
        scrollable = false, left = 0, top = 0, right = 1000, bottom = 100,
        className = "android.view.View", sourceOrder = 0,
    )

    /** A subscriptions-list page; the LAST row carries the target when [arrived]. */
    private fun listPage(step: Int, arrived: Boolean = false): StepObservation {
        val rows = (1..4).map { node("r$step$it", "subscription row $step-$it") }
        val nodes = if (arrived) rows + node("target", "Food Wishes channel") else rows
        return StepObservation(
            url = "https://m.youtube.test/feed/subscriptions",
            title = "Subscriptions",
            lines = nodes.map { it.text },
            nodes = nodes,
        )
    }

    private fun newEvidence(runId: String): Pair<VisionEvidence, File> {
        val base = Files.createTempDirectory("vexp17-scroll").toFile()
        return VisionEvidence(base, runId) to File(base, "$runId/events.log")
    }

    private fun loop(
        transport: WireTransport,
        observer: LoopObserver,
        evidence: VisionEvidence,
        executor: RecordingExecutor,
        config: VisionLoop.Config,
    ): VisionLoop = VisionLoop(
        observer = observer,
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

    private val scrollDown = """{"proposals":[{"description":"scroll down the channel list","action":"scroll_down","expectedEffect":{"expect":"ViewportMoved"}}]}"""

    private val batteryPlan = """
        {"summary":"find the channel in the list","taskClass":"read","subtasks":[
          {"instruction":"find the Food Wishes channel in the subscriptions list",
           "done":{"markerText":"Food Wishes"},
           "verify":[{"question":"is the Food Wishes channel row visible in the list?","badIsTrue":false}]}]}
    """.trimIndent()

    private val plainPlan = """
        {"summary":"find the channel in the list","taskClass":"read","subtasks":[
          {"instruction":"find the Food Wishes channel in the subscriptions list",
           "done":{"markerText":"Food Wishes"}}]}
    """.trimIndent()

    private val goal = "find the Food Wishes channel in the subscriptions list on youtube.test"

    // -- 1. the hold -------------------------------------------------------------------------

    @Test
    fun `the battery is held while the list scrolls - it fires once at the start and once on arrival`() =
        runBlocking {
            // The loop's FIRST observe (the plan's frame) and the step-0 observe both see
            // the untouched list; the target row appears on the 5th read (page index 4).
            val observer = RecordingObserver(
                listOf(
                    listPage(0), listPage(0), listPage(1), listPage(2),
                    listPage(3, arrived = true),
                ),
            )
            val transport = RoutingTransport(
                plans = ArrayDeque(listOf(batteryPlan)),
                proposals = ArrayDeque(List(4) { scrollDown }),
                arrived = { observer.reads >= 5 },
                batteryAnswer = ::batteryAnswer,
                noul = { arrived -> jevNoul("v0", if (arrived) 0.9 else 0.1) },
                pick = jevPick(),
            )
            val executor = RecordingExecutor()
            val (evidence, eventsFile) = newEvidence("scroll-hold")

            val result = loop(
                transport,
                observer,
                evidence,
                executor,
                VisionLoop.Config(settleMs = 0, maxReplans = 0, planAttempts = 1, stepFailuresBeforeReplan = 1),
            ).run(goal, "scroll-hold") { false }

            assertEquals(
                VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal,
                result.subtaskOutcomes.toString() + "\n" + eventsFile.readText(),
            )
            assertEquals(3, executor.scrolls.size, "three scrolls reach the target row")
            // The battery's own counter: step 0 (the starting screen) + the accept-side
            // verification on the arrival screen. NOT one per scroll step.
            assertEquals(2, evidence.callCounts()["battery"], "battery held while scrolling")
            assertEquals(2, evidence.callCounts()["verify-battery"])
            val events = eventsFile.readText()
            assertEquals(
                2,
                events.lines().count { "battery held: a scroll-to-find is in progress" in it },
                "the hold names itself on each held step: $events",
            )
            assertTrue(
                "terminal=GOAL_ACHIEVED" in events,
                "the arrival accept lands: $events",
            )
        }

    // -- 2. the cap --------------------------------------------------------------------------

    @Test
    fun `at the scroll cap the list is declared exhausted and the failure path re-plans`() =
        runBlocking {
            val observer = RecordingObserver(
                listOf(
                    listPage(0), listPage(1), listPage(2), listPage(3), listPage(4),
                    listPage(5), listPage(6), listPage(7), listPage(8), listPage(9),
                ),
            )
            val transport = RoutingTransport(
                plans = ArrayDeque(listOf(plainPlan, plainPlan)),
                proposals = ArrayDeque(List(12) { scrollDown }),
                arrived = { false },
                batteryAnswer = ::batteryAnswer,
                noul = { arrived -> jevNoul("v0", if (arrived) 0.9 else 0.1) },
                pick = jevPick(),
            )
            val executor = RecordingExecutor()
            val (evidence, _) = newEvidence("scroll-cap")

            val result = loop(
                transport,
                observer,
                evidence,
                executor,
                VisionLoop.Config(
                    settleMs = 0,
                    maxReplans = 1,
                    planAttempts = 1,
                    stepFailuresBeforeReplan = 1,
                    scrollFindMax = 4,
                ),
            ).run(goal, "scroll-cap") { executor.scrolls.size >= 5 }

            // UPDATED by Stage 18.C CN-11 (doc 04 §9.7, ratified): the no-effect budget
            // (3 consecutive no-effect actions) now bounds the no-progress scroll
            // sequence BEFORE the scroll-find cap (4) can fire — the tighter bound wins,
            // which is §9.7's own intent ("unknown effects reach the no-progress limit
            // without waiting for thousands of steps"). The scroll-find cap remains as
            // the deeper backstop for sequences that DO verify viewport movement.
            assertEquals(
                1,
                result.subtaskOutcomes.count { it.contains("SCROLL-FIND EXHAUSTED") },
                "the cap declares the sequence exhausted ONCE: ${result.subtaskOutcomes}",
            )
            assertTrue(
                result.subtaskOutcomes.any { it.startsWith("REPLAN #1:") },
                "the existing failure path re-plans: ${result.subtaskOutcomes}",
            )
        }

    // -- 3. the reset ------------------------------------------------------------------------

    @Test
    fun `an acted non-scroll resets the scroll counter - the cap never fires`() =
        runBlocking {
            val submit = """{"proposals":[{"description":"the keyboard search key","action":"submit","expectedEffect":{"expect":"NavigationOccurred"}}]}"""
            val observer = RecordingObserver(
                listOf(listPage(0), listPage(1), listPage(2), listPage(3), listPage(4), listPage(5)),
            )
            val transport = RoutingTransport(
                plans = ArrayDeque(listOf(plainPlan)),
                proposals = ArrayDeque(
                    listOf(
                        scrollDown, scrollDown,
                        submit,
                        scrollDown, scrollDown,
                    ),
                ),
                arrived = { false },
                batteryAnswer = ::batteryAnswer,
                noul = { _ -> jevNoul("v0", 0.1) },
                pick = jevPick(),
            )
            val executor = RecordingExecutor()
            val (evidence, eventsFile) = newEvidence("scroll-reset")

            val result = loop(
                transport,
                observer,
                evidence,
                executor,
                VisionLoop.Config(
                    settleMs = 0,
                    maxReplans = 0,
                    planAttempts = 1,
                    stepFailuresBeforeReplan = 1,
                    scrollFindMax = 3,
                ),
            ).run(goal, "scroll-reset") { executor.scrolls.size >= 4 }

            assertEquals(4, executor.scrolls.size, "two scrolls, the submit, two scrolls — terminal=${result.terminal} outcomes=${result.subtaskOutcomes}")
            assertEquals(1, executor.submits)
            assertTrue(
                result.subtaskOutcomes.none { it.contains("SCROLL-FIND EXHAUSTED") },
                "the reset keeps every scroll run below the cap: ${result.subtaskOutcomes}",
            )
            assertTrue(
                "SCROLL-FIND EXHAUSTED" !in eventsFile.readText(),
                "the cap never reached the evidence",
            )
        }
}
