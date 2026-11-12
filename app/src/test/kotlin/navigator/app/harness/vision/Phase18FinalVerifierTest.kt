package navigator.app.harness.vision

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.app.loop.ActionExecutor
import navigator.app.loop.FinalGoalVerifier
import navigator.app.loop.GoalCompletionEvidence
import navigator.app.loop.LoopObserver
import navigator.app.loop.ProofEvidenceType
import navigator.app.loop.ProvingEvidence
import navigator.app.loop.StepObservation
import navigator.app.loop.SubtaskRuntimeState
import navigator.core.enumerate.ObservedNode
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.DoneConditionChecker
import navigator.core.ladder.GoalContract
import navigator.core.ladder.Ladder
import navigator.core.ladder.Subtask
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport

/**
 * PHASE 18 STAGE 18.C Gate 18.C5 — CN-07, THE SINGLE FINAL VERIFIER (doc 04 §9.6,
 * ratified; $0 scripted replays only). The plan's six cases:
 *
 * 1. the R4-style final media page with VALID RECORDED account evidence (the VERIFIED
 *    switching subtask's kept proof, from the runtime states — never the ladder text):
 *    accepted — the account name need not be visible on an unrelated final media page;
 * 2. the same final media page WITHOUT account evidence: refused (a structured
 *    deficiency naming the missing account — never a success terminal);
 * 3. a generic "confirmation required" page: NOT order completion (the §4.2 extension
 *    — the bare generic vocabulary no longer completes a purchase);
 * 4. a checkout review with the correct products and total: NOT order completion;
 * 5. an explicit order confirmation with the required purchase parameters: accepted;
 * 6. every successful terminal path — the ordinary final accept (6a), the
 *    repaired-condition accept (6b, the VEXP7 repair), and the §4.1
 *    MODELS_CONFIRM_DONE hatch (6c) — invokes the SAME verifier EXACTLY ONCE (pinned
 *    through the loop's counting hook).
 *
 * Vision law: Project Vision/04 §9.6 (the single final verifier).
 */
class Phase18FinalVerifierTest {

    // -- the pure bundle builder -------------------------------------------------------------

    private val r4Goal =
        "open tubular.test, change the active profile to ViewerTwo, then open " +
            "the newest sample show episode page"

    private val r4Ladder = Ladder(
        summary = "change the profile and open the show page",
        subtasks = listOf(
            Subtask("change the active profile to ViewerTwo", DoneCondition.ElementPresent("the ViewerTwo profile is active")),
            Subtask("open the newest sample show episode page", DoneCondition.UrlHost("tubular.test")),
        ),
    )

    private fun verifiedSwitchState(evidenceText: String): SubtaskRuntimeState {
        val state = SubtaskRuntimeState(0, r4Ladder.subtasks[0].instruction, r4Ladder.subtasks[0].done)
        state.markVerified(
            ProvingEvidence(
                condition = r4Ladder.subtasks[0].done,
                snapshotRef = "hash=1",
                hostContext = "tubular.test",
                timestampMs = 1L,
                evidenceType = ProofEvidenceType.SNAPSHOT_TEXT,
                evidenceText = evidenceText,
            ),
        )
        return state
    }

    private fun bundle(
        goal: String,
        ladder: Ladder,
        observationUrl: String,
        observationTitle: String,
        observationLines: List<String>,
        states: List<SubtaskRuntimeState>,
        modelsConfirmedFinal: Boolean = false,
    ): GoalCompletionEvidence = GoalCompletionEvidence(
        goalText = goal,
        ladder = ladder,
        contract = GoalContract.fromGoal(goal),
        observationTitle = observationTitle,
        observationUrl = observationUrl,
        observationLines = observationLines,
        currentHost = DoneConditionChecker.normalizeUrl(observationUrl)?.substringBefore('/')?.removePrefix("www."),
        currentSnapshotRef = "hash=9",
        checkerEvidence = DoneConditionChecker.Evidence(
            url = observationUrl,
            title = observationTitle,
            pageText = observationLines,
            candidateLabels = observationLines,
        ),
        runtimeStates = states,
        answer = null,
        answerSource = null,
        modelsConfirmedFinal = modelsConfirmedFinal,
    )

    // -- case 1: recorded switching evidence accepts ------------------------------------------

    @Test
    fun `case 1 - the R4 final media page with valid recorded account evidence is accepted`() {
        val evidence = bundle(
            goal = r4Goal,
            ladder = r4Ladder,
            observationUrl = "https://tubular.test/watch?the-sample-show",
            observationTitle = "The sample show",
            observationLines = listOf("Now playing: the newest episode", "the video player is playing"),
            states = listOf(
                // The switching subtask VERIFIED — its kept proof text records the
                // switched profile (the runtime state, NOT the ladder text).
                verifiedSwitchState("tubular.test profile page ViewerTwo is now the active profile"),
            ),
        )
        val verdict = FinalGoalVerifier.verify(evidence)
        assertTrue(verdict is FinalGoalVerifier.Verdict.Accept, "recorded account evidence must accept: $verdict")
    }

    // -- case 2: no account evidence refuses ---------------------------------------------------

    @Test
    fun `case 2 - the same final media page without account evidence is refused`() {
        val evidence = bundle(
            goal = r4Goal,
            ladder = r4Ladder,
            observationUrl = "https://tubular.test/watch?the-sample-show",
            observationTitle = "The sample show",
            observationLines = listOf("Now playing: the newest episode", "the video player is playing"),
            // The switching subtask VERIFIED but its proof text never showed the
            // profile — recorded-but-not-evidencing is not account evidence (§9.6).
            states = listOf(verifiedSwitchState("tubular.test profile page opened")),
        )
        val verdict = FinalGoalVerifier.verify(evidence)
        assertTrue(verdict is FinalGoalVerifier.Verdict.Deficient, "no account evidence must refuse: $verdict")
        val message = (verdict as FinalGoalVerifier.Verdict.Deficient).deficiencies.joinToString("; ")
        assertTrue(message.contains("account end state"), message)
        assertTrue(message.contains("viewertwo"), message)
        assertEquals(2, verdict.activeSubtask, "the deficiency names the active subtask")
    }

    // -- cases 3+4+5: the purchase completion law ----------------------------------------------
    // A SINGLE-subtask purchase ladder keeps the prefix walk empty, so the purchase
    // question is exactly the FINAL page's evidence (the §9.6 order-specific law).

    private val purchaseGoal =
        "buy SoundMagic E11C with delivery to koivuviita 7 B 5. " +
            "Total budget including delivery: 60 euros"

    private fun purchaseBundle(finalMarker: String, lines: List<String>): GoalCompletionEvidence {
        val ladder = Ladder(
            summary = "place the order",
            subtasks = listOf(
                Subtask(
                    "Place the order for SoundMagic E11C to koivuviita 7 B 5 within the 60 euro budget",
                    DoneCondition.MarkerText(finalMarker),
                ),
            ),
        )
        return bundle(
            goal = purchaseGoal,
            ladder = ladder,
            observationUrl = "https://shop.test/checkout/step-3",
            observationTitle = "Checkout",
            observationLines = lines,
            states = emptyList(),
        )
    }

    @Test
    fun `case 3 - a generic confirmation-required page is NOT order completion`() {
        val evidence = purchaseBundle(
            finalMarker = "confirmation required",
            lines = listOf("confirmation required", "please confirm your checkout to continue"),
        )
        val verdict = FinalGoalVerifier.verify(evidence)
        assertTrue(verdict is FinalGoalVerifier.Verdict.Deficient, "generic confirmation must not complete: $verdict")
        val message = (verdict as FinalGoalVerifier.Verdict.Deficient).deficiencies.joinToString("; ")
        assertTrue(message.contains("order-specific"), message)
    }

    @Test
    fun `case 4 - a checkout review with correct products and total is NOT order completion`() {
        val evidence = purchaseBundle(
            finalMarker = "review your order",
            lines = listOf(
                "review your order",
                "SoundMagic E11C",
                "Total 59,90 euros",
            ),
        )
        val verdict = FinalGoalVerifier.verify(evidence)
        assertTrue(verdict is FinalGoalVerifier.Verdict.Deficient, "a review page must not complete: $verdict")
        val message = (verdict as FinalGoalVerifier.Verdict.Deficient).deficiencies.joinToString("; ")
        assertTrue(message.contains("order-specific"), message)
    }

    @Test
    fun `case 5 - an explicit order confirmation with the required purchase parameters is accepted`() {
        val evidence = purchaseBundle(
            finalMarker = "Order confirmed",
            lines = listOf(
                "Order confirmed",
                "your order number is 58291",
                "thank you for your order",
                "delivery to koivuviita 7 B 5",
            ),
        )
        val verdict = FinalGoalVerifier.verify(evidence)
        assertTrue(verdict is FinalGoalVerifier.Verdict.Accept, "order-specific evidence must accept: $verdict")
    }

    // -- case 6: every successful terminal path invokes the same verifier EXACTLY ONCE -------
    // Scripted edges (the VisionLoop fixture precedent): the LAST scripted response
    // repeats for every further call.

    private class ScriptedTransport(
        chatResponses: ArrayDeque<String>,
        jevResponses: ArrayDeque<String>,
    ) : WireTransport {
        private val chat = chatResponses
        private val jev = jevResponses
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            val queue = if (request.url.contains("chat/completions")) chat else jev
            val body = if (queue.size > 1) queue.removeFirst() else queue.firstOrNull()
                ?: throw navigator.core.wire.WireTransportException("script exhausted for ${request.url}")
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

    private fun chatContent(innerJson: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5) })
    }.toString()

    /** A keyed Jev noul answer body (the battery/hatch/goal channels share the shape). */
    private fun jevNoul(key: String, pTrue: Double): String = buildJsonObject {
        put("answers", buildJsonObject {
            put(key, buildJsonObject {
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

    private fun newLoop(
        observations: List<StepObservation>,
        chat: List<String>,
        jev: List<String>,
        runId: String,
        base: File,
    ): VisionLoop = VisionLoop(
        observer = ScriptedObserver(*observations.toTypedArray()),
        executor = RecordingExecutor(),
        capture = VisionCapture(null),
        haiku = VisionHaiku(
            ScriptedTransport(ArrayDeque(chat), ArrayDeque()), "fixture-key", backoffBaseMs = 1, retryDelay = {},
        ),
        jev = VisionJev(
            ScriptedTransport(ArrayDeque(), ArrayDeque(jev)),
            "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
            backoffBaseMs = 1, retryDelay = {},
        ),
        budget = ExpBudget(),
        evidence = VisionEvidence(base, runId),
        config = VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1),
    )

    @Test
    fun `case 6a - the ordinary final accept invokes the verifier exactly once`() = runBlocking {
        val plan = """
            {"summary":"open the sample show page","taskClass":"read","subtasks":[
              {"instruction":"open the newest sample show page","done":{"urlHost":"tubular.test"}}]}
        """.trimIndent()
        val mediaPage = StepObservation(
            url = "https://tubular.test/watch?the-sample-show", title = "The sample show",
            lines = listOf("the video player is playing"), nodes = listOf(node("n1", "Search")),
        )
        val base = Files.createTempDirectory("p18-verifier-c6a").toFile()
        val loop = newLoop(
            observations = listOf(mediaPage, mediaPage.copy()),
            chat = listOf(chatContent(plan)),
            jev = emptyList(),
            runId = "verifier-c6a",
            base = base,
        )
        val result = loop.run("open tubular.test and open the newest sample show page", "verifier-c6a") { false }
        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal, result.subtaskOutcomes.toString())
        assertEquals(1, loop.finalVerifierInvocations, "the ordinary accept ran the ONE verifier exactly once")
    }

    @Test
    fun `case 6b - the repaired-condition accept invokes the verifier exactly once`() = runBlocking {
        // The VEXP7 shape: a vague elementPresent + the battery's outcome-achieved trigger;
        // the repair returns a marker the CURRENT page carries; the accept funnels through
        // the same verifier (the scoped repair evidence rescues the WEAK final).
        val plan = """
            {"summary":"repair fixture","taskClass":"read","subtasks":[
              {"instruction":"show the riga results","done":{"elementPresent":"a list of hotel result cards for Riga"},
               "verify":[{"question":"is a results list with named hotel cards visible?","badIsTrue":false}]}]}
        """.trimIndent()
        val repair = """{"done":{"markerText":"81 hotels found in Riga"}}"""
        val resultsPage = StepObservation(
            url = "https://booking.test/search", title = "Booking",
            lines = listOf("81 hotels found in Riga", "Grand Palace Hotel"), nodes = listOf(node("n1", "Book now")),
        )
        val base = Files.createTempDirectory("p18-verifier-c6b").toFile()
        val loop = VisionLoop(
            observer = ScriptedObserver(resultsPage, resultsPage.copy()),
            executor = RecordingExecutor(),
            capture = VisionCapture(null), // TEXT-ONLY: the battery image channel never runs
            haiku = VisionHaiku(
                ScriptedTransport(
                    // plan, the repair answer; the LAST entry repeats for further calls
                    // (the fixture law), so the repair needs a tail behind it.
                    ArrayDeque(listOf(chatContent(plan), chatContent(repair), chatContent(repair))),
                    ArrayDeque(),
                ),
                "fixture-key", backoffBaseMs = 1, retryDelay = {},
            ),
            jev = VisionJev(
                ScriptedTransport(ArrayDeque(), ArrayDeque(listOf(jevNoul("v0", 0.95)))),
                "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                backoffBaseMs = 1, retryDelay = {},
            ),
            budget = ExpBudget(),
            evidence = VisionEvidence(base, "verifier-c6b"),
            config = VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 1, batteryReplansPerSubtask = 0),
        )
        val result = loop.run("open hotels.test and find a hotel in Riga for next weekend", "verifier-c6b") { false }
        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal, result.subtaskOutcomes.toString())
        assertTrue(
            result.subtaskOutcomes.any { it.contains("CONDITION REPAIRED") },
            "the repair path ran: ${result.subtaskOutcomes}",
        )
        assertEquals(1, loop.finalVerifierInvocations, "the repaired accept ran the ONE verifier exactly once")
    }

    @Test
    fun `case 6c - the MODELS_CONFIRM_DONE hatch invokes the verifier exactly once`() = runBlocking {
        val plan = """
            {"summary":"add two cases of the product to the cart","taskClass":"action","subtasks":[
              {"instruction":"add two cases of the product to the cart","done":{"markerText":"CART-CONFIRMED"}}]}
        """.trimIndent()
        val proposeEmpty = """{"proposals":[]}"""
        val confirmTrue = """{"complete":true,"reason":"the outcome is visible"}"""
        val page = StepObservation(
            url = "https://shop.test/product", title = "Product page",
            lines = listOf("product page", "still browsing"), nodes = listOf(node("n1", "Search")),
        )
        val base = Files.createTempDirectory("p18-verifier-c6c").toFile()
        val loop = newLoop(
            observations = listOf(page, page.copy(lines = listOf("product page", "still browsing"))),
            // plan, the failed propose, the hatch's Haiku confirm.
            chat = listOf(chatContent(plan), chatContent(proposeEmpty), chatContent(confirmTrue)),
            // the hatch's pair noul + the goal-level noul.
            jev = listOf(jevNoul("done", 0.96), jevNoul("goal", 0.92)),
            runId = "verifier-c6c",
            base = base,
        )
        val result = loop.run("open shop.test and add two cases of celsius to the cart", "verifier-c6c") { false }
        assertEquals(VisionLoop.Terminal.MODELS_CONFIRM_DONE, result.terminal, result.subtaskOutcomes.toString())
        assertEquals(1, loop.finalVerifierInvocations, "the hatch ran the ONE verifier exactly once")
    }
}
