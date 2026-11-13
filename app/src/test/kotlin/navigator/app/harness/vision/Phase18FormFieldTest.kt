package navigator.app.harness.vision

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.app.loop.ActionExecutor
import navigator.app.loop.BrowserRecoveryController
import navigator.app.loop.FormCompletionChecker
import navigator.app.loop.LoopObserver
import navigator.app.loop.StepObservation
import navigator.app.observe.FieldRequiredness
import navigator.app.observe.FieldRole
import navigator.app.observe.FormFieldState
import navigator.app.observe.FormStateExtractor
import navigator.core.enumerate.ObservedNode
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport

/**
 * PHASE_18 Stage 18.D Gate 18.D2 — the seven CN-13 field-level form cases (the plan's
 * §6 CN-13 test list; the R3 CDON checkout's law, sanitized to `shop.example`). Scripted
 * transports only ($0, no secrets):
 *
 * 1. several empty fields with similar geometry → the field-level facts identify the
 *    INTENDED field (distinct snapshot-local keys; the checker's next-needed field);
 * 2. a required name field still empty → the form subtask cannot complete;
 * 3. an autofill chip without the intended focus → ZERO chip dispatch;
 * 4. text accepted by a11y but absent on read-back → no field progress;
 * 5. one field failing while others are populated → the recovery retains the valid
 *    fields and retries ONLY the failed one;
 * 6. a password/card field → the descriptors and evidence carry REDACTED state only;
 * 7. unknown required metadata → reported UNKNOWN, never falsely optional/required by a
 *    heuristic.
 */
class Phase18FormFieldTest {

    // -- fixtures (sanitized shop.example) --------------------------------------------------

    private fun label(id: String, text: String, top: Int) = ObservedNode(
        id = id, text = text, desc = "", clickable = false, editable = false,
        scrollable = false, left = 0, top = top, right = 1000, bottom = top + 40,
        className = "android.view.View", sourceOrder = 0,
    )

    private fun field(id: String, value: String, top: Int, isPassword: Boolean = false) = ObservedNode(
        id = id, text = value, desc = "", clickable = false, editable = true,
        scrollable = false, left = 0, top = top, right = 1000, bottom = top + 40,
        className = "android.widget.EditText", sourceOrder = 0, isPassword = isPassword,
    )

    private fun chip(id: String, text: String, top: Int) = ObservedNode(
        id = id, text = text, desc = "", clickable = true, editable = false,
        scrollable = false, left = 0, top = top, right = 1000, bottom = top + 60,
        className = "android.widget.Button", sourceOrder = 0,
    )

    /** The delivery form: a required-empty name field, a populated street, an empty email. */
    private val formNodes = listOf(
        label("n0", "First name *", 0),
        field("n1", "", 50),
        label("n2", "Street address", 100),
        field("n3", "Koivuviita 7 B 5", 150),
        label("n4", "Email address *", 200),
        field("n5", "", 250),
        chip("n6", "Saved address Koivuviita 7 B 5", 300),
    )

    private fun formPage(nodes: List<ObservedNode> = formNodes) = StepObservation(
        url = "https://shop.example/checkout",
        title = "Checkout — Sample Store",
        lines = nodes.map { "${it.id} \"${it.text}\"" },
        nodes = nodes,
        windowPackage = "com.android.chrome",
    )

    // -- case 1: similar geometry, distinct field identities --------------------------------

    @Test
    fun `case 1 - empty fields with identical geometry still identify the intended field`() {
        // Three empty fields with IDENTICAL shape (the R3 similarity trap: same width,
        // same height, adjacent rows); only the name field carries the page's own
        // required marker.
        fun sameField(id: String, top: Int) = ObservedNode(
            id = id, text = "", desc = "", clickable = false, editable = true,
            scrollable = false, left = 100, top = top, right = 900, bottom = top + 40,
            className = "android.widget.EditText", sourceOrder = 0,
        )
        val nodes = listOf(
            label("n0", "First name *", 0),
            sameField("n1", 50),
            label("n2", "Email address", 100),
            sameField("n3", 150),
            label("n4", "Phone number", 200),
            sameField("n5", 250),
        )
        val fields = FormStateExtractor.extract(nodes)
        assertEquals(3, fields.size, "every editable node becomes a descriptor")
        // The snapshot-local keys are DISTINCT even at identical geometry — the proposal
        // can address the intended field exactly.
        assertEquals(listOf("n1", "n3", "n5"), fields.map { it.key })
        assertEquals(fields[0].left, fields[1].left, "the geometry really is identical")
        assertEquals(fields[0].right, fields[2].right)
        assertEquals(fields[0].bottom - fields[0].top, fields[2].bottom - fields[2].top)
        // The intended field: the KNOWN required one, deterministically first.
        val intended = FormCompletionChecker.nextNeededField(fields)
        assertEquals("n1", intended?.key, "the required-empty field is the intended target")
        assertEquals("First name *", intended?.label)
        assertEquals(FieldRequiredness.REQUIRED, intended?.required)
        assertEquals(FieldRequiredness.UNKNOWN, fields[1].required, "unmarked fields stay UNKNOWN")
        assertEquals(FieldRequiredness.UNKNOWN, fields[2].required)
    }

    // -- case 2: a required name field still empty keeps the form open -----------------------

    @Test
    fun `case 2 - a required name field still empty cannot complete the form subtask`() {
        val fields = FormStateExtractor.extract(formNodes)
        val completion = FormCompletionChecker.completion(fields)
        val incomplete = assertIs<FormCompletionChecker.Completion.Incomplete>(completion)
        assertTrue(
            incomplete.blockers.any { it.contains("First name") && it.contains("EMPTY") },
            "the empty required name field blocks: ${incomplete.blockers}",
        )
        assertTrue(
            incomplete.blockers.any { it.contains("Email address") },
            "every empty required field blocks: ${incomplete.blockers}",
        )
        // The contrast: fill the required fields and the form completes.
        val filled = FormStateExtractor.extract(
            formNodes.map {
                when (it.id) {
                    "n1" -> it.copy(text = "Rune Lauritzen")
                    "n5" -> it.copy(text = "buyer@example.test")
                    else -> it
                }
            },
        )
        assertEquals(FormCompletionChecker.Completion.Complete, FormCompletionChecker.completion(filled))
    }

    // -- end-to-end edges --------------------------------------------------------------------

    /** Chat transport repeating the LAST scripted plan (the R3 fixture pattern). */
    private class ChatTransport(responses: List<String>) : WireTransport {
        private val queue = ArrayDeque(responses)
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            if (!request.url.contains("chat/completions")) {
                throw IllegalStateException("no Jev script in this test")
            }
            val body = if (queue.size > 1) queue.removeFirst() else queue.firstOrNull()
                ?: throw IllegalStateException("chat script exhausted")
            return WireHttpResponse(200, body)
        }
    }

    /** Grounds the FIRST tap/type proposal at the scripted fractional box. */
    private inner class JevGroundTransport(
        private val left: Double, private val right: Double,
        private val top: Double, private val bottom: Double,
    ) : WireTransport {
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            if (request.url.contains("chat/completions")) {
                throw IllegalStateException("no Haiku script in the Jev transport")
            }
            return WireHttpResponse(200, buildJsonObject {
                put("answers", buildJsonObject {
                    put("b0", buildJsonObject {
                        put("type", JsonPrimitive("box"))
                        put("box", buildJsonObject {
                            put("top", JsonPrimitive(top)); put("bottom", JsonPrimitive(bottom))
                            put("left", JsonPrimitive(left)); put("right", JsonPrimitive(right))
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
            }.toString())
        }
    }

    private class ScriptedObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        private var last: StepObservation = observations.last()
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull()?.also { last = it } ?: last
    }

    private class RecordingExecutor : ActionExecutor {
        val taps = mutableListOf<Pair<Int, Int>>()
        val entered = mutableListOf<String>()
        override fun tap(x: Int, y: Int): Boolean { taps.add(x to y); return true }
        override fun clickNode(x: Int, y: Int): Boolean = true
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean = true
        override fun back(): Boolean = true
        override fun submit(): Boolean = true
        override fun enterText(x: Int, y: Int, text: String): Boolean { entered.add(text); return true }
    }

    private class FakeCapture(frame: VisionCapture.ScreenShot) : VisionCapture(null) {
        private val theShot = frame
        override suspend fun capture(exclusion: VisionMath.RectPx?): VisionCapture.ScreenShot = theShot
    }

    private fun shot() = VisionCapture.ScreenShot(
        base64 = "QUJD", width = 1000, height = 2000,
        screenWidth = 1000, screenHeight = 2000, sha256 = "p18-form",
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

    private val formPlan = """
        {"summary":"fill the delivery form","taskClass":"action","subtasks":[
          {"instruction":"fill the delivery form at shop.example","done":{"markerText":"form saved"}}]}
    """.trimIndent()
    private val emptyProposals = """{"proposals":[]}"""

    private fun newFormLoop(
        runId: String,
        observer: LoopObserver,
        chatResponses: List<String>,
        jev: WireTransport,
        executor: RecordingExecutor,
    ): Pair<VisionLoop, File> {
        val base = Files.createTempDirectory("p18-form").toFile()
        val loop = VisionLoop(
            observer = observer,
            executor = executor,
            capture = FakeCapture(shot()),
            haiku = VisionHaiku(ChatTransport(chatResponses), "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(jev, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            budget = ExpBudget(),
            evidence = VisionEvidence(base, runId),
            config = VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 2),
        )
        return loop to File(base, "$runId/events.log")
    }

    // -- case 3: an autofill chip without the intended focus -> zero chip dispatch -----------

    @Test
    fun `case 3 - an autofill chip tap without the intended field focused dispatches nothing`() =
        runBlocking {
            val executor = RecordingExecutor()
            val chipTap = """
                {"proposals":[{"description":"Saved address Koivuviita","action":"tap",
                    "expectedEffect":{"expect":"FieldContainsExpectedValue"},
                    "box":{"top":0.15,"bottom":0.18,"left":0.0,"right":1.0},"confidence":0.9}]}
            """.trimIndent()
            val (loop, events) = newFormLoop(
                "p18-form-chip",
                ScriptedObserver(formPage(), formPage(), formPage()),
                chatResponses = listOf(chatContent(formPlan), chatContent(chipTap)),
                jev = JevGroundTransport(0.0, 1.0, 0.15, 0.18),
                executor = executor,
            )
            var iterations = 0
            val result = loop.run("fill the delivery form at shop.example", "p18-form-chip") {
                ++iterations > 12
            }

            val log = events.readText()
            assertTrue(
                log.contains("AUTOFILL CHIP law"),
                "the chip gate refused the unfocused chip fill: ${log.take(1800)}",
            )
            assertTrue(
                executor.taps.isEmpty() && executor.entered.isEmpty(),
                "ZERO chip dispatch: taps=${executor.taps} entered=${executor.entered}",
            )
            assertTrue(
                result.terminal != VisionLoop.Terminal.GOAL_ACHIEVED &&
                    result.terminal != VisionLoop.Terminal.MODELS_CONFIRM_DONE,
                "no success terminal: ${result.terminal}",
            )
        }

    // -- case 4: accepted by a11y but absent on read-back -> no field progress ---------------

    @Test
    fun `case 4 - a type the a11y accepted but the read-back refutes is no field progress`() =
        runBlocking {
            val executor = RecordingExecutor()
            // The page is STATIC: the type dispatches (the a11y accepted it) but the
            // field never carries the text on the next read.
            val emailType = """
                {"proposals":[{"description":"the 'Email address *' field","action":"type",
                    "expectedEffect":{"expect":"FieldContainsExpectedValue"},"text":"buyer@example.test",
                    "box":{"top":0.125,"bottom":0.145,"left":0.0,"right":1.0},"confidence":0.9}]}
            """.trimIndent()
            val (loop, events) = newFormLoop(
                "p18-form-readback",
                ScriptedObserver(formPage(), formPage(), formPage()),
                chatResponses = listOf(chatContent(formPlan), chatContent(emailType)),
                jev = JevGroundTransport(0.0, 1.0, 0.125, 0.145),
                executor = executor,
            )
            var iterations = 0
            val result = loop.run("fill the delivery form at shop.example", "p18-form-readback") {
                ++iterations > 12
            }

            val log = events.readText()
            assertTrue(
                log.contains("STILL EMPTY"),
                "the read-back refused the accepted-but-absent write: ${log.take(1800)}",
            )
            assertTrue(
                log.contains("FIELD RECOVERY"),
                "the loss carries the field-recovery law: ${log.take(1800)}",
            )
            assertTrue(
                executor.entered.isNotEmpty(),
                "the type DID dispatch (the a11y accepted it): ${executor.entered}",
            )
            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
            assertTrue(
                result.terminal != VisionLoop.Terminal.GOAL_ACHIEVED,
                "no progress on an unverified write: ${result.terminal}",
            )
        }

    // -- case 5: one field fails while others are populated -> retain, retry ONLY the failed --

    @Test
    fun `case 5 - the recovery retains the populated fields and retries only the failed one`() {
        val fields = FormStateExtractor.extract(formNodes)
        // n3 is populated (the street), n1/n5 empty; the FAILED write was n5's.
        val plan = FormCompletionChecker.recoveryPlan(fields, failedKeys = setOf("n5"))
        assertEquals(listOf("n3"), plan.retainedKeys, "the populated field is retained")
        assertEquals(listOf("n5"), plan.retryKeys, "ONLY the failed field is retried")
        assertTrue(plan.render.contains("never restarts the page, never switches fields"))
        // A multi-field retention: two populated fields, one failure.
        val two = FormStateExtractor.extract(
            formNodes.map { if (it.id == "n1") it.copy(text = "Rune Lauritzen") else it },
        )
        val plan2 = FormCompletionChecker.recoveryPlan(two, failedKeys = setOf("n5"))
        assertEquals(listOf("n1", "n3"), plan2.retainedKeys)
        assertEquals(listOf("n5"), plan2.retryKeys)
        // The end-to-end loss line names the retention (pinned against drift in case 4's log).
    }

    // -- case 6: a password/card field carries redacted state only ----------------------------

    @Test
    fun `case 6 - a password field descriptor and its evidence carry redacted state only`() {
        val secret = "hunter2-secret"
        val nodes = listOf(
            label("n0", "Card number *", 0),
            field("n1", secret, 50, isPassword = true),
        )
        val fields = FormStateExtractor.extract(nodes)
        assertEquals(1, fields.size)
        val descriptor = fields.single()
        assertEquals(FieldRole.PASSWORD_INPUT, descriptor.role, "the reader flag drives the role")
        assertTrue(descriptor.populated, "the descriptor knows the field is populated")
        assertTrue(descriptor.isPassword)
        // The VALUE rides nowhere: the descriptor is structurally incapable of carrying it.
        assertFalse(
            fields.toString().contains(secret),
            "the secret never rides the descriptor: $fields",
        )
        // The evidence surfaces never carry it either.
        val blockers = (FormCompletionChecker.completion(fields)
            as FormCompletionChecker.Completion.Complete)
        assertTrue(blockers == FormCompletionChecker.Completion.Complete)
        val plan = FormCompletionChecker.recoveryPlan(fields, failedKeys = setOf("n9"))
        assertFalse(plan.render.contains(secret))
        val milestone = BrowserRecoveryController.milestoneOf(
            StepObservation(
                url = "https://shop.example/pay",
                title = "Payment",
                lines = listOf("Card number", secret),
                nodes = nodes,
                windowPackage = "com.android.chrome",
            ),
        )
        assertTrue(
            milestone != BrowserRecoveryController.CheckoutMilestone.ORDER_CONFIRMATION,
            "a secret echo is never order evidence: $milestone",
        )
    }

    // -- case 7: unknown required metadata is UNKNOWN, never guessed --------------------------

    @Test
    fun `case 7 - an unmarked field reports UNKNOWN requiredness and never blocks by heuristic`() {
        // An EMPTY field whose label carries no marker: the page exposes nothing.
        val nodes = listOf(
            label("n0", "Email address", 0),
            field("n1", "", 50),
        )
        val fields = FormStateExtractor.extract(nodes)
        val descriptor = fields.single()
        assertEquals(
            FieldRequiredness.UNKNOWN,
            descriptor.required,
            "no marker = UNKNOWN — the label's MEANING is never a code heuristic",
        )
        // UNKNOWN never blocks the completion law (never falsely required):
        assertEquals(
            FormCompletionChecker.Completion.Complete,
            FormCompletionChecker.completion(fields),
        )
        // ...and there is no OPTIONAL verdict to falsely claim either.
        assertFalse(FieldRequiredness.entries.any { it.name == "OPTIONAL" })
        // The field is still ADDRESSABLE as the next-needed (proposal guidance, not a
        // required claim): the checker may target it for filling.
        assertEquals("n1", FormCompletionChecker.nextNeededField(fields)?.key)
        // The tri-state edges: an exposed marker IS honored.
        val marked = FormStateExtractor.extract(
            listOf(label("n0", "Email address *", 0), field("n1", "", 50)),
        )
        assertEquals(FieldRequiredness.REQUIRED, marked.single().required)
        assertIs<FormCompletionChecker.Completion.Incomplete>(FormCompletionChecker.completion(marked))
    }
}
