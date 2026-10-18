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
    fun `proposals carry boxes and confidence when a screenshot rode along`() {
        val content = """
            {"proposals":[{"description":"the 'Search' field at the top","action":"type","text":"cat",
              "box":{"top":0.1,"bottom":0.2,"left":0.3,"right":0.9},"confidence":0.8}]}
        """.trimIndent()
        val p = VisionPrompts.parseProposals(content, "t").single()
        assertEquals(0.8, p.confidence)
        assertEquals(0.1, p.box?.top)
        assertEquals(0.9, p.box?.right)
        assertFailsWith<VisionHaiku.VisionHaikuException> {
            VisionPrompts.parseProposals(
                """{"proposals":[{"description":"a","box":{"top":0.9,"bottom":0.2,"left":0.3,"right":0.9}}]}""",
                "t",
            )
        }
    }

    @Test
    fun `located labels parse back to the request`() {
        val content = """
            {"boxes":[{"label":"Search","box":{"top":0.05,"bottom":0.1,"left":0.8,"right":0.95},"confidence":0.9}]}
        """.trimIndent()
        val located = VisionPrompts.parseLocated(content, listOf("Search"), "t").single()
        assertEquals("Search", located.label)
        assertEquals(0.9, located.confidence)
        assertEquals(0.8, located.box.left)
        val numbered = """
            {"boxes":[{"label":"0. Search","box":{"top":0.05,"bottom":0.1,"left":0.8,"right":0.95},"confidence":0.9}]}
        """.trimIndent()
        assertEquals("Search", VisionPrompts.parseLocated(numbered, listOf("Search"), "t").single().label)
        assertEquals(0, VisionPrompts.parseLocated("""{"boxes":[]}""", listOf("Search"), "t").size)
        assertFailsWith<VisionHaiku.VisionHaikuException> {
            VisionPrompts.parseLocated("""{"results":[]}""", listOf("Search"), "t")
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

    // -- the skill-routed manifest + verification battery ---------------------------------

    @Test
    fun `the planner system prompt carries the distilled tool manifest`() {
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains(VisionPrompts.JEV_TOOL_MANIFEST))
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("0.5 means UNSURE"))
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("verify battery"))
    }

    @Test
    fun `verify batteries parse per subtask with loud boundaries`() {
        val content = """
            {"summary":"s","taskClass":"read","subtasks":[
              {"instruction":"a","done":{"markerText":"x"},
               "verify":[{"question":"is the result visible?","badIsTrue":false},
                         {"question":"is the page an error page?","badIsTrue":true}]},
              {"instruction":"b","done":{"markerText":"y"}}]}
        """.trimIndent()
        val battery = VisionPrompts.parseVerifyBlocks(content, "t")
        assertEquals(2, battery[0]?.size)
        assertEquals(true, battery[0]?.get(1)?.badIsTrue)
        assertEquals(false, battery[0]?.get(0)?.badIsTrue)
        assertEquals(emptyList(), battery[1].orEmpty())
        assertTrue(VisionPrompts.parseVerifyBlocks("""{"summary":"s"}""", "t").isEmpty())
        assertFailsWith<VisionHaiku.VisionHaikuException> {
            VisionPrompts.parseVerifyBlocks(
                """{"subtasks":[{"instruction":"a","verify":[{"question":"  "}]}]}""", "t",
            )
        }
        assertFailsWith<VisionHaiku.VisionHaikuException> {
            VisionPrompts.parseVerifyBlocks(
                """{"subtasks":[{"instruction":"a","verify":[{"question":"1"},{"question":"2"},{"question":"3"},{"question":"4"}]}]}""",
                "t",
            )
        }
    }

    @Test
    fun `battery answers map back with numbering tolerance and gaps`() {
        val content = """
            {"answers":[{"question":"1. is the result visible?","answer":true}]}
        """.trimIndent()
        val requested = listOf("is the result visible?", "is the page an error page?")
        val answers = VisionPrompts.parseBatteryAnswers(content, requested, "t")
        assertEquals(1, answers.size)
        assertEquals("is the result visible?", answers[0].question)
        assertEquals(true, answers[0].answer)
        assertFailsWith<VisionHaiku.VisionHaikuException> {
            VisionPrompts.parseBatteryAnswers("""{"responses":[]}""", requested, "t")
        }
    }

    @Test
    fun `battery gate is max-style - one red flag escalates, all-clean achieves`() {
        fun check(
            question: String, badIsTrue: Boolean = false,
            textNoul: Double? = null, imageConfirm: Boolean? = null,
        ) = VisionLoop.CheckResult(question, badIsTrue, textNoul, imageConfirm)
        val red = 0.70
        val agree = 0.70

        // one confident red flag escalates even when an outcome check also achieved
        val escalated = VisionLoop.batteryGate(
            listOf(
                check("result visible", textNoul = 0.9),
                check("error page", badIsTrue = true, textNoul = 0.85),
            ),
            red, agree,
        )
        assertTrue(escalated is VisionLoop.BatteryGate.RedFlags)
        assertEquals(listOf("error page"), (escalated as VisionLoop.BatteryGate.RedFlags).flags)

        // image-confirm alone (text channel abstained) flags
        val imageFlag = VisionLoop.batteryGate(
            listOf(check("error page", badIsTrue = true, imageConfirm = true, textNoul = null)),
            red, agree,
        )
        assertTrue(imageFlag is VisionLoop.BatteryGate.RedFlags)

        // image says bad but the text channel CONFIDENTLY refutes -> proceed (text is the calibrated judge)
        val refutedByText = VisionLoop.batteryGate(
            listOf(check("error page", badIsTrue = true, imageConfirm = true, textNoul = 0.4)),
            red, agree,
        )
        assertTrue(refutedByText is VisionLoop.BatteryGate.Proceed)

        // every outcome check achieved, no red flags -> outcome achieved
        assertEquals(
            VisionLoop.BatteryGate.OutcomeAchieved,
            VisionLoop.batteryGate(
                listOf(check("result visible", textNoul = 0.9), check("marker seen", imageConfirm = true)),
                red, agree,
            ),
        )

        // mixed/weak -> proceed (the loop acts)
        assertEquals(
            VisionLoop.BatteryGate.Proceed,
            VisionLoop.batteryGate(
                listOf(check("result visible", textNoul = 0.4, imageConfirm = false)),
                red, agree,
            ),
        )
        assertEquals(
            VisionLoop.BatteryGate.Proceed,
            VisionLoop.batteryGate(emptyList(), red, agree),
        )
    }

    @Test
    fun `noul 0_5 is unsure - the gate treats sub-threshold text as non-refuting`() {
        // image says RED, text is exactly at the unsure point -> still a red flag
        // (the image channel is a witness; text below redFlag but >= 0.5 does not refute)
        val atUnsure = VisionLoop.batteryGate(
            listOf(VisionLoop.CheckResult("error page", badIsTrue = true, textNoul = 0.5, imageConfirm = true)),
            0.70, 0.70,
        )
        assertTrue(atUnsure is VisionLoop.BatteryGate.RedFlags)
        // text REFUTES the image ( confidently not-bad) -> no red flag -> proceed
        val refuted = VisionLoop.batteryGate(
            listOf(VisionLoop.CheckResult("error page", badIsTrue = true, textNoul = 0.05, imageConfirm = true)),
            0.70, 0.70,
        )
        assertTrue(refuted is VisionLoop.BatteryGate.Proceed)
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
