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
 * PHASE_VEXP8 WI2 — the product-page CTA / SCROLL-CHURN law ($0: real loop, scripted
 * chat transport, text-only capture, temp-dir evidence — no device, no live call).
 *
 * Run-7 evidence (`task-1792427054326`): the Celsius product page stalled on
 * scroll_down/scroll_up for steps 11–23 while the sticky add-to-cart CTA was visible the
 * whole time. Code now counts CONSECUTIVE ACTED scroll proposals (both directions; reset
 * on any acted non-scroll, a URL change, a subtask advance and a new plan install), adds
 * a soft `<scroll-churn>` block to the proposer message from the hint threshold, and
 * refuses to scroll further at the hard threshold when a visible purchase control exists
 * (the existing informed re-plan path runs; NEVER a forced click). Without a detected
 * control only the soft hint fires.
 *
 * Every observation carries DISTINCT lines (distinct screen hashes), so the
 * 3-identical-hash stuck probe never interferes with the churn assertions.
 */
class VisionLoopScrollChurnTest {

    // -- scripted edges -------------------------------------------------------------------

    /**
     * Chat-completions pops [chatResponses]; every other URL is a GROUND call and pops
     * [jevResponses] (one pick per scroll/submit proposal).
     */
    private class ScriptedVisionTransport(
        private val chatResponses: ArrayDeque<String>,
        private val jevResponses: ArrayDeque<String> = ArrayDeque(),
    ) : WireTransport {
        var chatCalls = 0
            private set
        var jevCalls = 0
            private set
        val chatBodies = mutableListOf<String>()

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
        val scrolls = mutableListOf<Pair<Int, Int>>()
        var submits = 0
        override fun tap(x: Int, y: Int): Boolean = true
        override fun clickNode(x: Int, y: Int): Boolean = true
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean {
            scrolls.add(x to y)
            return true
        }
        override fun back(): Boolean = true
        override fun submit(): Boolean { submits += 1; return true }
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

    private fun node(
        id: String,
        text: String,
        left: Int = 0,
        top: Int = 0,
        right: Int = 1000,
        bottom: Int = 100,
    ) = ObservedNode(
        id = id, text = text, desc = "", clickable = true, editable = false,
        scrollable = false, left = left, top = top, right = right, bottom = bottom,
        className = "android.widget.Button", sourceOrder = 0,
    )

    /** A product page observation; [cta] adds the sticky add-to-cart control. */
    private fun page(
        url: String = "https://shop.test/product/celsius",
        lines: List<String>,
        cta: Boolean = false,
    ) = StepObservation(
        url = url,
        title = "Celsius",
        lines = lines,
        nodes = buildList {
            add(node("n1", "Search"))
            if (cta) {
                add(
                    node(
                        "n2", "Lisää ostoskoriin",
                        left = 0, top = 2200, right = 1000, bottom = 2300,
                    ),
                )
            }
        },
    )

    private fun newEvidence(runId: String): Pair<VisionEvidence, File> {
        val base = Files.createTempDirectory("vexp8-churn").toFile()
        return VisionEvidence(base, runId) to File(base, "$runId/events.log")
    }

    /** A fabricated capture: a scroll proposal needs a frame (the no-image degrade refuses). */
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

    /** The pick-only GROUND answer (a scroll/submit proposal carries no box question). */
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

    private fun loop(
        transport: ScriptedVisionTransport,
        observations: List<StepObservation>,
        evidence: VisionEvidence,
        executor: RecordingExecutor,
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

    /** One subtask with a STRONG (urlHost) condition, so the final propose-empty is suppressed cleanly. */
    private val plan = """
        {"summary":"buy the product","taskClass":"action","subtasks":[
          {"instruction":"add the celsius product to the cart","done":{"urlHost":"done.test"}}]}
    """.trimIndent()

    private val scrollDown = """{"proposals":[{"description":"scroll down the product page","action":"scroll_down"}]}"""
    private val proposeEmpty = """{"proposals":[]}"""

    private fun baseConfig(maxReplans: Int = 0) =
        VisionLoop.Config(settleMs = 0, maxReplans = maxReplans, stepFailuresBeforeReplan = 1)

    /**
     * The USER-message block, not the system prompt's `<scroll-churn>` mention: the raw
     * request body escapes the newline as `\n`, so the block's first two lines are the
     * discriminator.
     */
    private fun carriesChurnBlock(body: String): Boolean =
        body.contains("<scroll-churn>\\nYou have made")

    // -- the five cases -------------------------------------------------------------------

    @Test
    fun `three consecutive scrolls carry the churn block on the next proposer call - once`() =
        runBlocking {
            // WI2-1: 3 acted scrolls -> the 4th proposer request body carries the
            // code-generated <scroll-churn> block naming the count; the evidence marker
            // fires once. (The condition is STRONG/unmet, so the final empty proposal
            // ends the run through the existing REPLANS_EXHAUSTED path.)
            val transport = ScriptedVisionTransport(
                ArrayDeque(listOf(chatContent(plan), chatContent(scrollDown), chatContent(scrollDown),
                    chatContent(scrollDown), chatContent(proposeEmpty))),
                ArrayDeque(List(3) { jevPick() }),
            )
            val executor = RecordingExecutor()
            val (evidence, eventsFile) = newEvidence("churn-hint")

            val result = loop(
                transport,
                listOf(
                    page(lines = listOf("product start")),
                    page(lines = listOf("scroll position 1"), cta = true),
                    page(lines = listOf("scroll position 2"), cta = true),
                    page(lines = listOf("scroll position 3"), cta = true),
                    page(lines = listOf("scroll position 4"), cta = true),
                ),
                evidence,
                executor,
                baseConfig(),
            ).run("add the celsius product to the cart", "churn-hint") { false }

            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
            assertEquals(3, executor.scrolls.size, "three scrolls act")
            assertEquals(5, transport.chatCalls, "plan + 4 proposer calls")
            assertFalse(
                carriesChurnBlock(transport.chatBodies[3]),
                "the 3rd proposer call (2 prior scrolls) carries no churn block",
            )
            assertTrue(
                carriesChurnBlock(transport.chatBodies[4]),
                "the 4th proposer call carries the block",
            )
            assertTrue(
                transport.chatBodies[4].contains("You have made 3 consecutive scrolls on this page without progress."),
                "the block names the count",
            )
            assertTrue(
                transport.chatBodies[4].contains("A visible purchase control is present"),
                "the block names the detected control",
            )
            assertEquals(
                1,
                result.subtaskOutcomes.count { it == "scroll churn: 3 consecutive scrolls on this page with a visible purchase control" },
                "the evidence marker fires ONCE: ${result.subtaskOutcomes}",
            )
            assertTrue(
                "scroll churn: 3 consecutive scrolls" in eventsFile.readText(),
                "the marker rides events.log",
            )
        }

    @Test
    fun `four scrolls with a visible purchase control force a re-plan - never a fifth scroll`() =
        runBlocking {
            // WI2-2: 4 acted scrolls + a visible CTA -> the next step's attempt is
            // SKIPPED, the exact SCROLL CHURN line is recorded, and the existing informed
            // re-plan runs (budget 1) with the churn carried in its evidence block.
            val transport = ScriptedVisionTransport(
                ArrayDeque(listOf(
                    chatContent(plan),
                    chatContent(scrollDown),
                    chatContent(scrollDown),
                    chatContent(scrollDown),
                    chatContent(scrollDown),
                    chatContent(plan), // the forced re-plan
                    chatContent(proposeEmpty),
                )),
                ArrayDeque(List(4) { jevPick() }),
            )
            val executor = RecordingExecutor()
            val (evidence, eventsFile) = newEvidence("churn-stop")

            val result = loop(
                transport,
                listOf(
                    page(lines = listOf("product start")),
                    page(lines = listOf("scroll position 1"), cta = true),
                    page(lines = listOf("scroll position 2"), cta = true),
                    page(lines = listOf("scroll position 3"), cta = true),
                    page(lines = listOf("scroll position 4"), cta = true),
                    page(lines = listOf("scroll position 5"), cta = true), // the churn-stop step
                    page(lines = listOf("scroll position 6"), cta = true), // post-re-plan propose
                ),
                evidence,
                executor,
                baseConfig(maxReplans = 1),
            ).run("add the celsius product to the cart", "churn-stop") { false }

            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
            assertEquals(4, executor.scrolls.size, "NO fifth scroll may dispatch")
            assertEquals(
                "step 4: SCROLL CHURN - 4 consecutive scrolls with a visible purchase control - forcing a re-plan",
                result.subtaskOutcomes.single { it.contains("SCROLL CHURN") },
            )
            assertTrue(
                result.subtaskOutcomes.any { it.startsWith("REPLAN #1:") },
                "the existing re-plan path ran: ${result.subtaskOutcomes}",
            )
            val replanBody = transport.chatBodies[5]
            assertTrue(
                replanBody.contains("scroll churn: 4 consecutive scrolls on this page with no progress"),
                "the re-plan request carries the churn count",
            )
            assertTrue(
                replanBody.contains(
                    "visible purchase control: YES (accessibility tree) - TAP it; do not scroll again",
                ),
                "the re-plan request carries the visible-control line",
            )
            val events = eventsFile.readText()
            assertTrue("SCROLL CHURN - 4 consecutive scrolls" in events, events)
            // The re-plan call's raw request is the durable evidence record of the churn.
            val replanReq = eventsFile.parentFile.listFiles().orEmpty()
                .single { it.name.endsWith("-replan.req.json") }
            assertTrue(
                "scroll churn: 4 consecutive scrolls on this page with no progress" in replanReq.readText(),
                "the evidence body carries the churn count",
            )
            assertTrue(
                "visible purchase control: YES (accessibility tree) - TAP it; do not scroll again" in
                    replanReq.readText(),
                "the evidence body carries the visible-control line",
            )
        }

    @Test
    fun `an acted non-scroll resets the counter - a later two-scroll run never hints`() =
        runBlocking {
            // WI2-3: scroll, scroll, SUBMIT (an acted non-scroll), scroll, scroll -> no
            // churn block and no evidence marker anywhere: the counter restarted.
            val submit = """{"proposals":[{"description":"the keyboard search key","action":"submit"}]}"""
            val transport = ScriptedVisionTransport(
                ArrayDeque(listOf(
                    chatContent(plan),
                    chatContent(scrollDown),
                    chatContent(scrollDown),
                    chatContent(submit),
                    chatContent(scrollDown),
                    chatContent(scrollDown),
                    chatContent(proposeEmpty),
                )),
                ArrayDeque(List(5) { jevPick() }), // 4 scrolls + the submit
            )
            val executor = RecordingExecutor()
            val (evidence, _) = newEvidence("churn-reset-action")

            val result = loop(
                transport,
                listOf(
                    page(lines = listOf("product start")),
                    page(lines = listOf("scroll position 1"), cta = true),
                    page(lines = listOf("scroll position 2"), cta = true),
                    page(lines = listOf("submit page"), cta = true),
                    page(lines = listOf("scroll position 3"), cta = true),
                    page(lines = listOf("scroll position 4"), cta = true),
                    page(lines = listOf("scroll position 5"), cta = true),
                ),
                evidence,
                executor,
                baseConfig(),
            ).run("add the celsius product to the cart", "churn-reset-action") { false }

            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
            assertEquals(4, executor.scrolls.size)
            assertEquals(1, executor.submits)
            assertTrue(
                transport.chatBodies.none(::carriesChurnBlock),
                "a non-scroll action restarts the count: no block in any proposer body",
            )
            assertTrue(
                result.subtaskOutcomes.none { it.contains("scroll churn:") },
                "no hint marker: ${result.subtaskOutcomes}",
            )
        }

    @Test
    fun `a URL change resets the counter - two scrolls per page never hint`() =
        runBlocking {
            // WI2-4: A,A then B,B -> each page's run stays below the hint threshold.
            val a = "https://shop.test/product/celsius"
            val b = "https://shop.test/product/celsius/reviews"
            val transport = ScriptedVisionTransport(
                ArrayDeque(listOf(
                    chatContent(plan),
                    chatContent(scrollDown),
                    chatContent(scrollDown),
                    chatContent(scrollDown),
                    chatContent(scrollDown),
                    chatContent(proposeEmpty),
                )),
                ArrayDeque(List(4) { jevPick() }),
            )
            val executor = RecordingExecutor()
            val (evidence, _) = newEvidence("churn-reset-url")

            val result = loop(
                transport,
                listOf(
                    page(url = a, lines = listOf("product start")),
                    page(url = a, lines = listOf("scroll position 1"), cta = true),
                    page(url = a, lines = listOf("scroll position 2"), cta = true),
                    page(url = b, lines = listOf("reviews page 1"), cta = true),
                    page(url = b, lines = listOf("reviews page 2"), cta = true),
                    page(url = b, lines = listOf("reviews page 3"), cta = true),
                ),
                evidence,
                executor,
                baseConfig(),
            ).run("add the celsius product to the cart", "churn-reset-url") { false }

            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
            assertEquals(4, executor.scrolls.size)
            assertTrue(
                transport.chatBodies.none(::carriesChurnBlock),
                "the URL change restarted the count: no block in any proposer body",
            )
            assertTrue(result.subtaskOutcomes.none { it.contains("scroll churn:") })
        }

    @Test
    fun `without a detected purchase control only the soft hint fires - never a forced re-plan`() =
        runBlocking {
            // WI2-5: 4 scrolls with NO CTA -> the soft hint still appears, but the loop
            // keeps proposing (long result pages may legitimately scroll) and the churn
            // hard stop never runs.
            val transport = ScriptedVisionTransport(
                ArrayDeque(listOf(
                    chatContent(plan),
                    chatContent(scrollDown),
                    chatContent(scrollDown),
                    chatContent(scrollDown),
                    chatContent(scrollDown),
                    chatContent(proposeEmpty),
                )),
                ArrayDeque(List(4) { jevPick() }),
            )
            val executor = RecordingExecutor()
            val (evidence, _) = newEvidence("churn-no-cta")

            val result = loop(
                transport,
                listOf(
                    page(lines = listOf("results start")),
                    page(lines = listOf("results page 1"), cta = false),
                    page(lines = listOf("results page 2"), cta = false),
                    page(lines = listOf("results page 3"), cta = false),
                    page(lines = listOf("results page 4"), cta = false),
                    page(lines = listOf("results page 5"), cta = false),
                ),
                evidence,
                executor,
                baseConfig(),
            ).run("browse the celsius products", "churn-no-cta") { false }

            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
            assertEquals(4, executor.scrolls.size, "the soft hint never blocks the scrolls")
            assertTrue(
                result.subtaskOutcomes.none { it.contains("SCROLL CHURN") },
                "no hard stop without a detected control: ${result.subtaskOutcomes}",
            )
            assertTrue(
                carriesChurnBlock(transport.chatBodies[4]),
                "the 4th proposer body carries the hint",
            )
            assertTrue(
                carriesChurnBlock(transport.chatBodies[5]),
                "the 5th proposer body carries the hint too (no hard stop)",
            )
            assertFalse(
                transport.chatBodies.any { it.contains("A visible purchase control is present") },
                "no control detected -> the block never claims one",
            )
            assertEquals(
                1,
                result.subtaskOutcomes.count { it.contains("scroll churn:") },
                "the hint marker fires once per episode: ${result.subtaskOutcomes}",
            )
        }
}
