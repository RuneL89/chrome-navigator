package navigator.app.harness.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import navigator.core.enumerate.ObservedNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The EXPERIMENT's pure-JVM gates (branch-only): the grounding math, the two wire-body
 * builders + parsers, the prompt JSON contract, and the 10x ledger. No network, no
 * Android types (the capture/loop mechanics are device-verified by Phases A/B/C).
 */
class VisionExperimentTest {

    // -- VisionMath ----------------------------------------------------------------------

    private fun node(
        id: String, left: Int, top: Int, right: Int, bottom: Int,
        clickable: Boolean = true, editable: Boolean = false, text: String = "button",
        browserChrome: Boolean = false, scrollable: Boolean = false,
    ) = ObservedNode(
        id = id, text = text, desc = "", clickable = clickable, editable = editable,
        scrollable = scrollable, left = left, top = top, right = right, bottom = bottom,
        className = "android.widget.Button", sourceOrder = 0, browserChrome = browserChrome,
    )

    @Test
    fun `box fractions map through the ORIGINAL screen dims`() {
        val rect = VisionMath.boxToScreenRect(0.5, 0.25, 0.75, 0.5, screenWidth = 1440, screenHeight = 3120)
        assertEquals(720, rect.left)
        assertEquals(1080, rect.right)
        assertEquals(780, rect.top)
        assertEquals(1560, rect.bottom)
        assertEquals(900, rect.centerX)
        assertEquals(1170, rect.centerY)
    }

    @Test
    fun `box fractions clamp to the screen and order inverted edges`() {
        val rect = VisionMath.boxToScreenRect(1.2, -0.1, 0.8, 0.4, 1000, 2000)
        assertEquals(800, rect.left)
        assertEquals(1000, rect.right)
        assertEquals(0, rect.top)
        assertEquals(800, rect.bottom)
    }

    @Test
    fun `iou is 1 for identical 0 for disjoint and honest in between`() {
        val a = VisionMath.RectPx(0, 0, 100, 100)
        assertEquals(1.0, VisionMath.iou(a, VisionMath.RectPx(0, 0, 100, 100)))
        assertEquals(0.0, VisionMath.iou(a, VisionMath.RectPx(200, 200, 300, 300)))
        val half = VisionMath.iou(a, VisionMath.RectPx(0, 50, 100, 150))
        assertTrue(half in 0.30..0.35, "half-overlap iou=$half expected 1/3")
    }

    @Test
    fun `best match prefers the real control and skips chrome and inert nodes`() {
        val box = VisionMath.RectPx(400, 900, 700, 1000)
        val button = node("n1", left = 420, top = 910, right = 680, bottom = 990)
        val chrome = node("n2", left = 420, top = 910, right = 680, bottom = 990, browserChrome = true)
        val inert = node("n3", left = 420, top = 910, right = 680, bottom = 990, clickable = false)
        val match = VisionMath.bestNodeMatch(listOf(button, chrome, inert), box)
        assertEquals("n1", match?.first?.id)
        assertTrue((match?.second ?: 0.0) > 0.65, "nested-button iou=${match?.second}")
    }

    @Test
    fun `label match finds the described control or nothing`() {
        val nodes = listOf(
            node("n1", 0, 0, 10, 10, text = "Unsubscribe from Cooking Classics"),
            node("n2", 0, 20, 30, 40, text = "Subscribe", editable = true),
        )
        assertEquals(
            "n1",
            VisionMath.bestLabelMatch(nodes, "the 'Unsubscribe from Cooking Classics' button")?.id,
        )
        assertNull(VisionMath.bestLabelMatch(nodes, "the login submit arrow"))
    }

    // -- VisionJev wire shapes -----------------------------------------------------------

    @Test
    fun `text-only request carries no assets and no danger header`() {
        val body = VisionJev.buildRequestJson(
            "state text", "m", listOf(VisionJev.Question.Noul("done", "is it done?")), null, "r",
        )
        assertNull(body["assets"])
        val headers = VisionJev.buildHeaders("k", imagePresent = false)
        assertFalse(headers.containsKey(VisionJev.DANGER_ZONE_IMAGES_HEADER))
        assertEquals("state text", body["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun `image request embeds the asset and the box references it`() {
        val body = VisionJev.buildRequestJson(
            "see `r`", "m",
            listOf(VisionJev.Question.Box("b0", "the search field")),
            "QUJD", "r",
        )
        val assets = body["assets"]?.jsonObject
            ?: throw AssertionError("assets missing")
        val asset = assets["r"]?.jsonObject ?: throw AssertionError("asset 'r' missing")
        assertEquals("image", asset["type"]?.jsonPrimitive?.content)
        assertEquals("QUJD", asset["data_base64"]?.jsonPrimitive?.content)
        assertEquals("jpeg", asset["format"]?.jsonPrimitive?.content)
        val q = body["questions"]?.jsonObject?.get("b0")?.jsonObject ?: throw AssertionError("question missing")
        assertEquals("box", q["type"]?.jsonPrimitive?.content)
        assertTrue(q["instructions"]?.jsonPrimitive?.content!!.contains("the search field in `r`"))
        assertTrue(VisionJev.buildHeaders("k", imagePresent = true).containsKey(VisionJev.DANGER_ZONE_IMAGES_HEADER))
    }

    @Test
    fun `choice criteria are a record keyed by option id`() {
        val body = VisionJev.buildRequestJson(
            "s", "m",
            listOf(
                VisionJev.Question.Choice("pick", "pick one", listOf("p0" to "first", "none" to "nothing")),
            ),
            null, "r",
        )
        val criteria = body["questions"]?.jsonObject?.get("pick")?.jsonObject?.get("criteria")?.jsonObject
            ?: throw AssertionError("criteria missing")
        assertEquals("first", criteria["p0"]?.jsonPrimitive?.content)
        assertEquals("nothing", criteria["none"]?.jsonPrimitive?.content)
    }

    @Test
    fun `response parses boxes nouls and choices and fails loud on gaps`() {
        val response = """
            {"model":"m","answers":{
              "b0":{"type":"box","box":{"top":0.1,"bottom":0.2,"left":0.3,"right":0.4},"confidence":0.9},
              "done":{"type":"noul","noul":0.91},
              "pick":{"type":"choice","choice":"p0","probabilities":{"p0":0.8,"none":0.2}}},
             "usage":{"input_tokens":100,"output_tokens":5,"cost":0.0002}}
        """.trimIndent()
        val questions = listOf(
            VisionJev.Question.Box("b0", "x"),
            VisionJev.Question.Noul("done", "y"),
            VisionJev.Question.Choice("pick", "z", listOf("p0" to "first", "none" to "nothing")),
        )
        val (answers, usage) = VisionJev.parseResponse(response, questions, "test")
        val box = answers["b0"] as VisionJev.Answer.Box
        assertEquals(0.1, box.top)
        assertEquals(0.9, box.confidence)
        assertEquals(0.91, (answers["done"] as VisionJev.Answer.Noul).pTrue)
        assertEquals("p0", (answers["pick"] as VisionJev.Answer.Choice).chosen)
        assertEquals(200L, usage.costMicroUsd)
        assertEquals(100L, usage.inputTokens)

        val missing = """
            {"model":"m","answers":{
              "done":{"type":"noul","noul":0.91},
              "pick":{"type":"choice","choice":"p0","probabilities":{"p0":0.8,"none":0.2}}},
             "usage":{"input_tokens":100,"output_tokens":5,"cost":0.0002}}
        """.trimIndent()
        assertFailsWith<VisionJev.VisionJevException> { VisionJev.parseResponse(missing, questions, "test") }
        val extra = """
            {"model":"m","answers":{
              "ghost":{"type":"noul","noul":0.5},
              "b0":{"type":"box","box":{"top":0.1,"bottom":0.2,"left":0.3,"right":0.4},"confidence":0.9},
              "done":{"type":"noul","noul":0.91},
              "pick":{"type":"choice","choice":"p0","probabilities":{"p0":0.8,"none":0.2}}},
             "usage":{"input_tokens":100,"output_tokens":5,"cost":0.0002}}
        """.trimIndent()
        assertFailsWith<VisionJev.VisionJevException> { VisionJev.parseResponse(extra, questions, "test") }
    }

    // -- VisionHaiku wire shapes ---------------------------------------------------------

    @Test
    fun `chat body is string content without an image and content parts with one`() {
        val textOnly = VisionHaiku.buildChatBody("m", "sys", "user", null)
        assertFalse(textOnly.contains("image_url"))
        assertTrue(textOnly.contains("\"content\":\"user\""))
        val withImage = VisionHaiku.buildChatBody("m", "sys", "user", "QUJD")
        assertTrue(withImage.contains("\"type\":\"image_url\""))
        assertTrue(withImage.contains("data:image/jpeg;base64,QUJD"))
        assertTrue(withImage.contains("\"type\":\"text\""))
    }

    @Test
    fun `content parse is loud on a missing assistant message`() {
        val ok = VisionHaiku.parseContent(
            """{"choices":[{"message":{"content":"hi"}}],"usage":{"prompt_tokens":1,"completion_tokens":2}}""",
            "test",
        )
        assertEquals("hi", ok)
        assertFailsWith<VisionHaiku.VisionHaikuException> {
            VisionHaiku.parseContent("""{"choices":[]}""", "test")
        }
    }

    // -- VisionPrompts JSON contract -----------------------------------------------------

    @Test
    fun `proposals parse validate and refuse overflows`() {
        val content = """
            {"proposals":[
              {"description":"the 'Search' field at the top","action":"type","text":"airfryer"},
              {"description":"the magnifier icon right of the field","action":"tap"}]}
        """.trimIndent()
        val proposals = VisionPrompts.parseProposals(content, "test")
        assertEquals(2, proposals.size)
        assertEquals(VisionPrompts.ProposalAction.TYPE, proposals[0].action)
        assertEquals("airfryer", proposals[0].text)
        assertEquals(VisionPrompts.ProposalAction.TAP, proposals[1].action)
        assertFailsWith<VisionHaiku.VisionHaikuException> {
            VisionPrompts.parseProposals(
                """{"proposals":[{"description":"a"},{"description":"b"},{"description":"c"},{"description":"d"}]}""",
                "test",
            )
        }
        assertFailsWith<VisionHaiku.VisionHaikuException> {
            VisionPrompts.parseProposals("""{"proposals":[{"description":"a","action":"type"}]}""", "test")
        }
        assertFailsWith<VisionHaiku.VisionHaikuException> {
            VisionPrompts.parseProposals("""{"proposals":[{"description":"a","action":"explode"}]}""", "test")
        }
    }

    @Test
    fun `extract json handles fences and confirms parse tolerantly`() {
        val fenced = "```json\n{\"complete\":true,\"reason\":\"title matches\"}\n```"
        assertEquals(true, VisionPrompts.parseConfirm(fenced)?.first)
        assertNull(VisionPrompts.parseConfirm("no json at all"))
        val obj = VisionPrompts.extractJson("prefix {\"a\":1} suffix", "test")
        assertEquals(1, obj["a"]?.jsonPrimitive?.content?.toInt())
    }

    // -- ExpBudget (10x ceilings) --------------------------------------------------------

    @Test
    fun `budget warns at soft and stops at hard`() {
        var now = 0L
        val budget = ExpBudget(nowMs = { now })
        assertEquals(ExpBudget.Check.Ok, budget.stepCheck(100))
        assertEquals(ExpBudget.Check.Ok, budget.stepCheck(300))
        assertTrue(budget.stepCheck(500) is ExpBudget.Check.Soft)
        assertTrue(budget.stepCheck(601) is ExpBudget.Check.Hard)
        budget.recordSpend(ExpBudget.SPEND_HARD_MICRO_USD + 1, writerSurface = false)
        assertTrue(budget.stepCheck(10) is ExpBudget.Check.Hard)
        now = ExpBudget.TIME_HARD_MS + 1
        assertTrue(budget.stepCheck(1) is ExpBudget.Check.Hard)
    }

    @Test
    fun `writer ledger mirrors spends and carries its own ceilings`() {
        val budget = ExpBudget()
        assertEquals(true, budget.hasWriterHardRoom())
        budget.recordSpend(ExpBudget.WRITER_SOFT_MICRO_USD + 1, writerSurface = true)
        assertTrue(budget.writerCheck() is ExpBudget.Check.Soft)
        assertEquals(ExpBudget.WRITER_SOFT_MICRO_USD + 1, budget.writerSpendMicroUsd)
        assertEquals(ExpBudget.WRITER_SOFT_MICRO_USD + 1, budget.spendMicroUsd)
        budget.recordSpend(ExpBudget.WRITER_HARD_MICRO_USD, writerSurface = true)
        assertFalse(budget.hasWriterHardRoom())
        assertTrue(budget.writerCheck() is ExpBudget.Check.Hard)
    }
}
