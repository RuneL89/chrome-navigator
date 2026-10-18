package navigator.app.harness.vision

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import navigator.core.enumerate.ObservedNode
import navigator.core.wire.DefaultEngineConfigs
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The EXPERIMENT's pure-JVM gates (branch-only): the grounding math, the two wire-body
 * builders + parsers, the prompt JSON contract, the 10x ledger, and — PHASE_VEXP1 — the
 * evidence-block assembly, the REPLAN_SKILL surface, the dual key resolution, and the
 * vendor-route request shape. No network, no live keys (the CapturingTransport below is
 * a pure fake; the key strings in this file are obviously-fake test fixtures).
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

    // -- PHASE_VEXP1 WI1: the evidence block + REPLAN_SKILL --------------------------------

    @Test
    fun `the evidence block renders battery code-verdict escalation grounding and route`() {
        val block = VisionPrompts.jevEvidenceBlock(
            VisionPrompts.JevEvidence(
                battery = listOf(
                    VisionPrompts.EvidenceCheck(
                        "a search result listing the requested product is visible",
                        badIsTrue = false, textNoul = 0.98, imageConfirm = true,
                    ),
                    VisionPrompts.EvidenceCheck(
                        "the page is an error page",
                        badIsTrue = true, textNoul = 0.02, imageConfirm = false,
                    ),
                ),
                conditionRender = "elementPresent add-to-cart control on the product page",
                conditionSatisfied = false,
                escalation = VisionPrompts.EscalationKind.OUTCOME_ACHIEVED_WHILE_CONDITION_UNMET,
                grounding = VisionPrompts.EvidenceGrounding(
                    "Jev box", "the Celsius Lemon Lime 24-pack result card", 529, 1170, 0.76, "n9",
                ),
                actions = listOf("type celsius lemon lime", "submit", "type celsius lemon lime"),
                routeRepeated = 2,
            ),
        )
        assertTrue(block.startsWith("<jev-evidence>"))
        assertTrue(block.endsWith("</jev-evidence>"))
        assertTrue(block.contains("battery (Jev noul over text+image, Haiku over image):"))
        assertTrue(
            block.contains(
                "[outcome] \"a search result listing the requested product is visible\" → text 0.98 / image TRUE",
            ),
        )
        assertTrue(
            block.contains("[badIsTrue] \"the page is an error page\" → text 0.02 / image FALSE"),
        )
        assertTrue(
            block.contains(
                "condition verdict: done-condition " +
                    "'elementPresent add-to-cart control on the product page' NOT satisfied (code checker)",
            ),
        )
        assertTrue(block.contains("escalation kind: OUTCOME-ACHIEVED-WHILE-CONDITION-UNMET"))
        assertTrue(
            block.contains(
                "grounding: Jev box located 'the Celsius Lemon Lime 24-pack result card' → " +
                    "screen (529,1170), IoU 0.76 vs a11y node n9",
            ),
        )
        assertTrue(
            block.contains("last actions: type celsius lemon lime → submit → type celsius lemon lime"),
        )
        assertFalse(block.contains("ROUTE-REPEATED"), "below the 3 streak the tag must not render")
    }

    @Test
    fun `the evidence block renders all escalation kinds and omits absent pieces`() {
        val bare = VisionPrompts.jevEvidenceBlock(
            VisionPrompts.JevEvidence(
                battery = emptyList(),
                conditionRender = "urlHost verkkokauppa.com",
                conditionSatisfied = false,
                escalation = VisionPrompts.EscalationKind.EXHAUSTED,
                grounding = null,
                actions = emptyList(),
            ),
        )
        assertTrue(bare.contains("escalation kind: EXHAUSTED"))
        assertFalse(bare.contains("battery ("), "no battery ran — no battery lines")
        assertFalse(bare.contains("grounding:"), "no grounding this step — no line")
        assertFalse(bare.contains("last actions:"), "no actions yet — no line")

        val red = VisionPrompts.jevEvidenceBlock(
            VisionPrompts.JevEvidence(
                battery = listOf(
                    VisionPrompts.EvidenceCheck("q", badIsTrue = true, textNoul = null, imageConfirm = null),
                ),
                conditionRender = "c",
                conditionSatisfied = false,
                escalation = VisionPrompts.EscalationKind.RED_FLAGS,
                grounding = null,
                actions = listOf("tap x", "tap x", "tap x"),
                routeRepeated = 3,
            ),
        )
        assertTrue(red.contains("escalation kind: RED-FLAGS"))
        assertTrue(red.contains("text n/a / image n/a"), "a refused channel renders n/a, never 0.5")
        assertTrue(red.contains("last actions: tap x → tap x → tap x (ROUTE-REPEATED x3)"))
    }

    @Test
    fun `route streak counts the trailing same-verb run`() {
        assertEquals(3, VisionPrompts.routeStreak(listOf("type", "submit", "type", "type", "type")))
        assertEquals(2, VisionPrompts.routeStreak(listOf("type", "submit", "type", "type")))
        assertEquals(1, VisionPrompts.routeStreak(listOf("tap", "type")))
        assertEquals(0, VisionPrompts.routeStreak(emptyList()))
    }

    @Test
    fun `REPLAN_SKILL rides only the replan system prompt beside the manifest`() {
        assertEquals(VisionPrompts.PLAN_SYSTEM, VisionPrompts.planSystem(replan = false))
        val replan = VisionPrompts.planSystem(replan = true)
        assertTrue(replan.startsWith(VisionPrompts.PLAN_SYSTEM))
        assertTrue(replan.contains(VisionPrompts.REPLAN_SKILL))
        assertTrue(VisionPrompts.REPLAN_SKILL.contains("accessibility"), "the evidence-universe law")
        assertTrue(VisionPrompts.REPLAN_SKILL.contains("never a retry"), "the outcome-achieved law")
        assertTrue(VisionPrompts.REPLAN_SKILL.contains("OUTCOME-ACHIEVED-WHILE-CONDITION-UNMET"))
        assertTrue(VisionPrompts.REPLAN_SKILL.contains("tap the located"), "the grounding law")
        assertTrue(VisionPrompts.REPLAN_SKILL.contains("ROUTE-REPEATED"), "the route-variation law")
        assertTrue(VisionPrompts.REPLAN_SKILL.contains("bad-is-true"), "the battery-authoring law")
        assertTrue(VisionPrompts.REPLAN_SKILL.contains("Jev (cheap)"), "the cascade framing")
    }

    @Test
    fun `the prompt version is vision-exp-v6`() {
        assertEquals("vision-exp-v6", VisionPrompts.VISION_PROMPT_VERSION)
    }

    // -- PHASE_VEXP1 WI3: the vendor route --------------------------------------------------

    @Test
    fun `the vision defaults point at the typesafe vendor route`() {
        assertEquals("https://api.typesafe.ai/v1/systemone", VisionJev.DEFAULT_VISION_BASE_URL)
        assertEquals("jev-1.14.0", VisionJev.DEFAULT_VISION_MODEL)
    }

    /** Pure fake transport: captures the request, answers 200 with the canned body. */
    private class CapturingTransport(private val responseBody: String) : WireTransport {
        var lastRequest: WireHttpRequest? = null
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            lastRequest = request
            return WireHttpResponse(200, responseBody)
        }
    }

    @Test
    fun `ask rides the vendor route - url model danger header assets and the batched box+noul`() = runBlocking {
        val response = """
            {"answers":{
              "b0":{"type":"box","box":{"top":0.43,"bottom":0.57,"left":0.05,"right":0.93},"confidence":0.46},
              "v0":{"type":"noul","noul":0.98}},
             "usage":{"input_tokens":6188,"output_tokens":9}}
        """.trimIndent()
        val transport = CapturingTransport(response)
        val jev = VisionJev(transport, VisionJev.DEFAULT_VISION_BASE_URL, VisionJev.DEFAULT_VISION_MODEL, "fixture-key")
        val answers = jev.ask(
            VisionLoop.stateWithImage("the page text", "ref0"),
            listOf(
                VisionJev.Question.Box("b0", "the result card"),
                VisionJev.Question.Noul("v0", "the result card is visible"),
            ),
            "QUJD", "ref0", "test",
        )
        val req = transport.lastRequest ?: throw AssertionError("no request captured")
        assertEquals("https://api.typesafe.ai/v1/systemone", req.url)
        assertEquals("Bearer fixture-key", req.headers["Authorization"])
        assertEquals("true", req.headers[VisionJev.DANGER_ZONE_IMAGES_HEADER])
        val body = Json.parseToJsonElement(req.body).jsonObject
        assertEquals("jev-1.14.0", body["model"]?.jsonPrimitive?.content)
        assertEquals(
            "the page text\n<screen>\nthe page screenshot is `ref0`\n</screen>",
            body["state"]?.jsonPrimitive?.content,
            "the image reference rides IN STATE",
        )
        assertNotNull(body["assets"]?.jsonObject?.get("ref0"), "the asset map carries the ref")
        val questions = body["questions"]?.jsonObject ?: throw AssertionError("questions missing")
        assertEquals(2, questions.size, "box + noul ride ONE batched request")
        assertEquals("box", questions["b0"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("noul", questions["v0"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(0.98, (answers["v0"] as VisionJev.Answer.Noul).pTrue)
        assertEquals(0.46, (answers["b0"] as VisionJev.Answer.Box).confidence)
    }

    @Test
    fun `usage without a provider cost falls back to the conservative production table`() {
        val (answers, usage) = VisionJev.parseResponse(
            """{"answers":{"done":{"type":"noul","noul":0.9}},"usage":{"input_tokens":1000,"output_tokens":10}}""",
            listOf(VisionJev.Question.Noul("done", "x")),
            "t",
        )
        assertEquals(0.9, (answers["done"] as VisionJev.Answer.Noul).pTrue)
        assertEquals(VisionJev.jevCost(1000, 10), usage.costMicroUsd)
        assertTrue(usage.costMicroUsd > 0, "the vendor's missing cost must never read as free")
    }

    // -- PHASE_VEXP1 WI4: the battery's image-in-state shape --------------------------------

    @Test
    fun `the battery state embeds the image reference and degrades to text without one`() {
        val withImage = VisionLoop.stateWithImage("TITLE: t", "ref1")
        assertTrue(withImage.contains("`ref1`"), "the reference rides backticked in state")
        assertEquals("TITLE: t", VisionLoop.stateWithImage("TITLE: t", null))
    }

    // -- PHASE_VEXP1 WI2: the dual key resolution -------------------------------------------

    /** A path that never exists: resolveKey must NOT read the developer machine's real keys file. */
    private val noKeysFile: Path = Path.of("build", "no-such-keys-file")

    @Test
    fun `dual key resolution prefers intent then store then env per channel`() {
        val env = mapOf(
            DefaultEngineConfigs.OPENROUTER_KEY_ENV to "env-openrouter",
            VisionKeys.TYPESAFE_KEY_ENV to "env-typesafe",
        )
        // the intent extra wins on both channels (trimmed)
        assertEquals("intent-or", VisionKeys.openRouterKey(" intent-or ", "stored-or", env, noKeysFile))
        assertEquals("intent-ts", VisionKeys.typesafeKey(" intent-ts ", "stored-ts", env, noKeysFile))
        // then the Setup store
        assertEquals("stored-or", VisionKeys.openRouterKey(null, "stored-or", env, noKeysFile))
        assertEquals("stored-ts", VisionKeys.typesafeKey(null, "stored-ts", env, noKeysFile))
        // then the environment
        assertEquals("env-openrouter", VisionKeys.openRouterKey(null, null, env, noKeysFile))
        assertEquals("env-typesafe", VisionKeys.typesafeKey(null, null, env, noKeysFile))
        // then nothing (blank is not a key)
        assertNull(VisionKeys.openRouterKey(null, null, emptyMap(), noKeysFile))
        assertNull(VisionKeys.typesafeKey(null, null, emptyMap(), noKeysFile))
        assertNull(VisionKeys.openRouterKey("   ", null, emptyMap(), noKeysFile))
    }

    @Test
    fun `the refusal names the missing channel and its delivery options`() {
        assertNull(VisionKeys.refusal("or", "ts"))
        val noTypesafe = VisionKeys.refusal("or", null) ?: throw AssertionError("refusal expected")
        assertTrue(noTypesafe.contains("TypeSafe"))
        assertTrue(noTypesafe.contains("--es typesafeKey"))
        assertTrue(noTypesafe.contains(VisionKeys.TYPESAFE_KEY_ENV))
        val noOpenRouter = VisionKeys.refusal(null, "ts") ?: throw AssertionError("refusal expected")
        assertTrue(noOpenRouter.contains("OpenRouter"))
        assertTrue(noOpenRouter.contains(DefaultEngineConfigs.OPENROUTER_KEY_ENV))
        val none = VisionKeys.refusal(null, null) ?: throw AssertionError("refusal expected")
        assertTrue(none.contains("BOTH missing"))
    }
}
