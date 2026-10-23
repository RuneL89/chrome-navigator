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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PHASE_17 Stage 0.4 — the FIELD-LEVEL form facts (the R3 CDON checkout: the required
 * `Etunimi` (first name) stayed empty while the engine re-tapped the saved-address
 * autofill chip; the battery judged the PAGE, so no model ever saw "the first-name field
 * is empty").
 *
 * What code owns now (pure, pinned here):
 *
 * - [FormFacts.fields] — every editable field with its label (desc → placeholder → the
 *   row label above it), its value, and whether the page marks it required;
 * - [FormFacts.factLine] — the digest's ONE `FORM:` line, empty/required-empty first,
 *   plus the chip law;
 * - [FormFacts.english] — the Finnish checkout glossary (`Etunimi` = first name);
 * - [FormFacts.landed] — the per-field read-back verdict;
 * - the end-to-end digest: a scripted propose call's REQUEST carries the FORM line.
 */
class FieldFormTest {

    // -- the R3 checkout fixture ------------------------------------------------------------

    private fun text(id: String, label: String, top: Int, desc: String = "") = ObservedNode(
        id = id, text = label, desc = desc, clickable = false, editable = false,
        scrollable = false, left = 0, top = top, right = 1000, bottom = top + 40,
        className = "android.view.View", sourceOrder = 0,
    )

    private fun field(id: String, value: String, top: Int, desc: String = "") = ObservedNode(
        id = id, text = value, desc = desc, clickable = false, editable = true,
        scrollable = false, left = 0, top = top, right = 1000, bottom = top + 40,
        className = "android.widget.EditText", sourceOrder = 0,
    )

    private val checkoutNodes = listOf(
        text("n0", "Etunimi *", 0),
        field("n1", "", 50),
        text("n2", "Katuosoite", 100),
        field("n3", "Koivuviita 7 B 5", 150),
        text("n4", "Postinumero", 200),
        field("n5", "", 250),
    )

    private fun checkoutPage(nodes: List<ObservedNode> = checkoutNodes) = StepObservation(
        url = "https://cdon.fi/kassa",
        title = "Kassa | CDON",
        lines = nodes.map { "${it.id} \"${it.text}\"" },
        nodes = nodes,
    )

    // -- the facts --------------------------------------------------------------------------

    @Test
    fun `the R3 checkout tree yields labeled fields with their empty state`() {
        val fields = FormFacts.fields(checkoutNodes)
        assertEquals(3, fields.size)
        assertEquals("Etunimi *", fields[0].label)
        assertTrue(fields[0].empty)
        assertTrue(fields[0].required, "the '*' marks the field required")
        assertEquals("Katuosoite", fields[1].label)
        assertFalse(fields[1].empty)
        assertEquals("Koivuviita 7 B 5", fields[1].value)
        assertEquals("Postinumero", fields[2].label)
        assertTrue(fields[2].empty)
        assertFalse(fields[2].required, "no required marker on this page for the postal code")
    }

    @Test
    fun `the fact line names the required empty field first - and the chip law`() {
        val line = assertNotNull(FormFacts.factLine(checkoutNodes))
        assertTrue(
            line.startsWith("FORM: required empty: Etunimi * (first name)"),
            "the required empty field leads: $line",
        )
        assertTrue(line.contains("empty: Postinumero (postal code)"), line)
        assertTrue(
            line.contains("filled: Katuosoite (street address) = \"Koivuviita 7 B 5\""),
            line,
        )
        assertTrue(line.contains("a saved-address chip fills only the focused field"), line)
    }

    @Test
    fun `a page without editable fields has no fact line`() {
        assertEquals(null, FormFacts.factLine(listOf(text("n0", "No form here", 0))))
    }

    @Test
    fun `a password field never renders its value`() {
        val secret = ObservedNode(
            id = "p0", text = "hunter2", desc = "Salasana", clickable = false, editable = true,
            scrollable = false, left = 0, top = 0, right = 100, bottom = 40,
            className = "android.widget.EditText", sourceOrder = 0, isPassword = true,
        )
        val line = assertNotNull(FormFacts.factLine(listOf(secret)))
        assertFalse(line.contains("hunter2"), "the secret never rides the facts: $line")
        assertTrue(line.contains("Salasana"), line)
    }

    @Test
    fun `the Finnish checkout glossary maps the R3 labels`() {
        assertEquals("first name", FormFacts.english("Etunimi"))
        assertEquals("first name", FormFacts.english("Etunimi *"))
        assertEquals("street address", FormFacts.english("Katuosoite"))
        assertEquals("postal code", FormFacts.english("Postinumero"))
        assertEquals("postal city", FormFacts.english("Postitoimipaikka"))
        assertEquals("last name", FormFacts.english("Sukunimi"))
        assertEquals("email address", FormFacts.english("Sähköposti"))
        assertEquals("Search", FormFacts.english("Search"), "unknown labels stay as written")
    }

    @Test
    fun `the read-back verdict reads the SAME field`() {
        assertFalse(FormFacts.landed(checkoutNodes, "n1", "Rune Lauritzen"), "still empty")
        val typed = checkoutNodes.map { if (it.id == "n1") it.copy(text = "Rune Lauritzen") else it }
        assertTrue(FormFacts.landed(typed, "n1", "Rune Lauritzen"))
        assertFalse(FormFacts.landed(checkoutNodes, "n9", "Rune"), "a field that is gone did not land")
        assertFalse(FormFacts.landed(checkoutNodes, "n1", ""), "an empty type lands nothing")
    }

    // -- the end-to-end digest ---------------------------------------------------------------

    private class ScriptedTransport(private val responses: ArrayDeque<String>) : WireTransport {
        val bodies = mutableListOf<String>()
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            bodies.add(request.body)
            val body = responses.removeFirstOrNull()
                ?: throw WireTransportException("script exhausted")
            return WireHttpResponse(200, body)
        }
    }

    private class ScriptedObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull() ?: error("observer script exhausted")
    }

    private class NoopExecutor : ActionExecutor {
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

    private fun chatContent(innerJson: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5) })
    }.toString()

    @Test
    fun `the propose request carries the FORM facts of the page`() = runBlocking {
        val plan = """
            {"summary":"fill the delivery address","taskClass":"read","subtasks":[
              {"instruction":"fill the delivery address on the checkout",
               "done":{"markerText":"Kiitos tilauksestasi"}}]}
        """.trimIndent()
        val transport = ScriptedTransport(
            ArrayDeque(listOf(chatContent(plan), chatContent("""{"proposals":[]}"""))),
        )
        val base = Files.createTempDirectory("vexp17-form").toFile()
        val evidence = VisionEvidence(base, "form-digest")

        val result = VisionLoop(
            observer = ScriptedObserver(checkoutPage(), checkoutPage()),
            executor = NoopExecutor(),
            capture = FakeCapture(
                VisionCapture.ScreenShot(
                    base64 = "QUJD", width = 1000, height = 2000,
                    screenWidth = 1000, screenHeight = 2000, sha256 = "fixture",
                    jpegBytes = byteArrayOf(1, 2, 3),
                ),
            ),
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(
                transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                backoffBaseMs = 1, retryDelay = {},
            ),
            budget = ExpBudget(),
            evidence = evidence,
            config = VisionLoop.Config(settleMs = 0, maxReplans = 0, planAttempts = 1, stepFailuresBeforeReplan = 1),
        ).run("fill the delivery address on shop.test", "form-digest") { false }

        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal, result.subtaskOutcomes.toString())
        val proposeBody = transport.bodies.drop(1).firstOrNull { "FIELD-LEVEL" in it || "PROPOSE" in it }
            ?: transport.bodies.last()
        assertTrue(
            "FORM: required empty: Etunimi * (first name)" in proposeBody,
            "the digest carries the FORM facts into the proposer: $proposeBody",
        )
        assertTrue(
            "Postinumero" in proposeBody && "Koivuviita 7 B 5" in proposeBody,
            "every field rides the facts: $proposeBody",
        )
        val log = File(base, "form-digest/events.log").readText()
        assertTrue("call propose" in log, log)
    }
}
