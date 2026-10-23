package navigator.app.harness.vision

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import navigator.app.loop.ActionExecutor
import navigator.app.loop.LoopObserver
import navigator.app.loop.StepObservation
import navigator.core.enumerate.ObservedNode
import navigator.core.wire.DefaultEngineConfigs
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport
import navigator.core.wire.WireTransportException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
    fun `enterText fields parse per subtask and refuse non-strings`() {
        // The v10 planner field (fifth actual-life repair): the per-subtask text the
        // loop types when a tap on an editable field converts. Blank = absent.
        val content = """
            {"summary":"s","taskClass":"action","subtasks":[
              {"instruction":"search","enterText":"hotels in Riga next weekend","done":{"markerText":"x"}},
              {"instruction":"read","done":{"markerText":"y"}},
              {"instruction":"fill","enterText":"  ","done":{"markerText":"z"}}]}
        """.trimIndent()
        val enterTexts = VisionPrompts.parseEnterTexts(content, "t")
        assertEquals(1, enterTexts.size)
        assertEquals("hotels in Riga next weekend", enterTexts[0])
        assertTrue(VisionPrompts.parseEnterTexts("""{"summary":"s"}""", "t").isEmpty())
        assertTrue(VisionPrompts.parseEnterTexts("""{"subtasks":[]}""", "t").isEmpty())
        assertFailsWith<VisionHaiku.VisionHaikuException> {
            VisionPrompts.parseEnterTexts(
                """{"subtasks":[{"instruction":"a","enterText":42,"done":{"markerText":"x"}}]}""", "t",
            )
        }
        assertFailsWith<VisionHaiku.VisionHaikuException> {
            VisionPrompts.parseEnterTexts(
                """{"subtasks":[{"instruction":"a","enterText":{"text":"x"},"done":{"markerText":"x"}}]}""", "t",
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

    // -- ExpBudget (100x ceilings, owner directive 2026-10-19) ----------------------------

    @Test
    fun `budget warns at soft and stops at hard`() {
        var now = 0L
        val budget = ExpBudget(nowMs = { now })
        assertEquals(ExpBudget.Check.Ok, budget.stepCheck(100))
        assertEquals(ExpBudget.Check.Ok, budget.stepCheck(4_000))
        assertTrue(budget.stepCheck(4_001) is ExpBudget.Check.Soft)
        assertTrue(budget.stepCheck(6_001) is ExpBudget.Check.Hard)
        budget.recordSpend(ExpBudget.SPEND_HARD_MICRO_USD + 1, writerSurface = false)
        assertTrue(budget.stepCheck(10) is ExpBudget.Check.Hard)
        now = ExpBudget.TIME_HARD_MS + 1
        assertTrue(budget.stepCheck(1) is ExpBudget.Check.Hard)
    }

    @Test
    fun `the budgets are pinned at the 100x product ceilings`() {
        // PHASE_VEXP2: ALL budgets to 100x product values ("models are dirt cheap —
        // runs may span days", owner directive 2026-10-19).
        assertEquals(4_000, ExpBudget.STEPS_SOFT)
        assertEquals(6_000, ExpBudget.STEPS_HARD)
        assertEquals(500 * 60_000L, ExpBudget.TIME_SOFT_MS)
        assertEquals(1_000 * 60_000L, ExpBudget.TIME_HARD_MS)
        assertEquals(20_000_000L, ExpBudget.SPEND_SOFT_MICRO_USD)
        assertEquals(25_000_000L, ExpBudget.SPEND_HARD_MICRO_USD)
        assertEquals(5_000_000L, ExpBudget.WRITER_SOFT_MICRO_USD)
        assertEquals(10_000_000L, ExpBudget.WRITER_HARD_MICRO_USD)
        val config = VisionLoop.Config()
        assertEquals(20, config.maxReplans)
        assertEquals(30, config.batteryReplansPerSubtask)
        assertEquals(20, config.planAttempts)
        val transport = CapturingTransport("{}")
        assertEquals(20, VisionJev(
            transport, VisionJev.DEFAULT_VISION_BASE_URL, VisionJev.DEFAULT_VISION_MODEL, "fixture",
        ).maxRetries)
        assertEquals(20, VisionHaiku(transport, "fixture").maxRetries)
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
    fun `the prompt version is vision-exp-v19`() {
        assertEquals("vision-exp-v19", VisionPrompts.VISION_PROMPT_VERSION)
    }

    @Test
    fun `the boundary audit law pins v19`() {
        // PHASE_17 Stage 2.3 — the boundary judgment is ADVISORY: it judges the remaining
        // PLAN, never the completed condition (code already verified that), and its page
        // material is delimited untrusted.
        assertTrue(VisionPrompts.BOUNDARY_SYSTEM.contains("Judge the PLAN"))
        assertTrue(VisionPrompts.BOUNDARY_SYSTEM.contains("never the completed subtask"))
        assertTrue(VisionPrompts.BOUNDARY_SYSTEM.contains("UNTRUSTED DATA"))
        assertTrue(VisionPrompts.BOUNDARY_SYSTEM.contains("\"plan_holds\""))
        val verdict = VisionPrompts.parseBoundaryVerdict("""{"plan_holds":false,"reason":"wrong site"}""")
        assertEquals(false, verdict?.first)
        assertEquals("wrong site", verdict?.second)
        assertEquals(null, VisionPrompts.parseBoundaryVerdict("""{"reason":"x"}"""))
    }

    @Test
    fun `the field-level form law pins v18`() {
        // PHASE_17 Stage 0.4 — the R3 CDON gap: the empty first-name field was never a
        // fact any model saw. The digest carries code-read FORM facts; both writers are
        // told to fill the EMPTY field directly and never treat a chip as its filler.
        assertTrue(VisionPrompts.PROPOSE_SYSTEM.contains("FIELD-LEVEL FORMS"))
        assertTrue(VisionPrompts.PROPOSE_SYSTEM.contains("saved-address or autofill CHIP"))
        assertTrue(VisionPrompts.PROPOSE_SYSTEM.contains("Etunimi"))
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("FIELD-LEVEL FORMS"))
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("fills only the FOCUSED field"))
    }

    @Test
    fun `the goal-fidelity law pins v16`() {
        // PHASE_16 — the ladder is bound to the goal: a purchase ends on the order
        // confirmation, every explicit parameter stays, and a re-plan may not weaken it.
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("GOAL FIDELITY"))
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("order confirmation"))
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("never write \"review the"))
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("Every explicit goal parameter"))
        assertTrue(VisionPrompts.REPLAN_SKILL.contains("GOAL FIDELITY"))
    }

    @Test
    fun `the same-action binding renders its proposer block`() {
        val rendered = VisionPrompts.proposeUser(
            "do the thing", "the page shows it", "digest", null, null,
            VisionPrompts.SameAction(3, "type"),
        )
        assertTrue(rendered.contains("<same-action>"), rendered)
        assertTrue(rendered.contains("SAME ACTION x3"), rendered)
        assertTrue(rendered.contains("'type'"), rendered)
        assertFalse(
            VisionPrompts.proposeUser("do the thing", "the page shows it", "digest", null)
                .contains("<same-action>"),
            "no block without a same-action payload",
        )
    }

    @Test
    fun `the translate-first law pins v15`() {
        // PHASE_15 — a non-English page is normalized by accepting the browser's
        // translate-to-English prompt BEFORE any other work. The gate floor stays
        // English/structural; the PHASE_14 Finnish dictionary additions are withdrawn.
        // v15: the pick instruction names the Translate acceptance FIRST, and the loop
        // code-prefers the proposer's Translate proposal on the image path (run 13).
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains("Translate to English"),
            "the planner names the English translation target",
        )
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains("never plan a flow"),
            "the planner never plans around a non-English page",
        )
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains("CODE-PREFERS"),
            "the planner knows the pick-level code preference",
        )
        assertTrue(
            VisionPrompts.PROPOSE_SYSTEM.contains("TRANSLATE FIRST"),
            "the proposer's translate-first rule",
        )
        assertTrue(
            VisionPrompts.PROPOSE_SYSTEM.contains("Never propose \"Not now\""),
            "the proposer never dismisses the translate prompt",
        )
        assertTrue(
            VisionPrompts.REPLAN_SKILL.contains("ACCEPT the translation"),
            "the re-plan accepts translation first",
        )
        assertTrue(
            VisionPrompts.REPLAN_SKILL.contains("CODE-PREFERS"),
            "the re-plan knows the pick-level code preference",
        )
        assertTrue(
            VisionPrompts.pickInstructions().contains("choose that option FIRST"),
            "the Jev pick instruction prefers the translate acceptance",
        )
        assertTrue(
            VisionPrompts.pickInstructions().contains("never choose Not now"),
            "the pick instruction never dismisses the prompt",
        )
        assertEquals(
            listOf(
                "p0" to "tap the Translate control",
                "none" to "none of the proposals can advance the subtask",
            ),
            VisionPrompts.pickCriteria(
                listOf(
                    VisionPrompts.Proposal(
                        "the Translate control", VisionPrompts.ProposalAction.TAP, null,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `the product-page CTA and scroll-churn law pins its prompt surfaces`() {
        // PHASE_VEXP8 WI2 — the run-7 product-page stall: the Celsius page scrolled for
        // steps 11-23 while the sticky add-to-cart CTA was visible. The three prompt
        // surfaces carry the CTA law; code carries the churn thresholds and the block.
        assertTrue(
            VisionPrompts.PROPOSE_SYSTEM.contains("On a PRODUCT page, a VISIBLE purchase control"),
            "the proposer's purchase-control law",
        )
        assertTrue(
            VisionPrompts.PROPOSE_SYSTEM.contains("\"Add to cart\", \"Lisää ostoskoriin\", \"Buy\""),
            "the CTA vocabulary examples",
        )
        assertTrue(
            VisionPrompts.PROPOSE_SYSTEM.contains("sticky/fixed bar pinned to the bottom of the screen"),
            "the sticky bar is explicitly a purchase control",
        )
        assertTrue(
            VisionPrompts.PROPOSE_SYSTEM.contains(
                "Never scroll while the control the\n  subtask needs is already visible",
            ),
            "never scroll while the needed control is visible",
        )
        assertTrue(
            VisionPrompts.PROPOSE_SYSTEM.contains("Do not repeat the same scroll more than 3 times on one page"),
            "the scroll-repeat cap",
        )
        assertTrue(
            VisionPrompts.PROPOSE_SYSTEM.contains(
                "A <scroll-churn> block in the user message is CODE-GENERATED guidance",
            ),
            "the block is code guidance, page text stays untrusted",
        )
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains(
                "For a PRODUCT-page purchase action, author the subtask around the VISIBLE purchase control",
            ),
            "the planner's purchase-subtask law",
        )
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains("never a\nscroll to find it"),
            "never a scroll to find the control",
        )
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains("verify\nthe resulting cart state"),
            "the final verifies the cart state (item + quantity)",
        )
        assertTrue(
            VisionPrompts.REPLAN_SKILL.contains(
                "A scroll streak on a product page means the purchase control is VISIBLE — TAP it; never scroll again.",
            ),
            "the re-plan's scroll-streak law",
        )

        // proposeUser renders the block ONLY when passed, names the count, and never
        // injects a page label.
        val plain = VisionPrompts.proposeUser("instruction", "done", "TITLE: t", null)
        assertFalse(plain.contains("<scroll-churn>"), "no churn passed -> no block")
        val withCta = VisionPrompts.proposeUser(
            "instruction", "done", "TITLE: t", null, VisionPrompts.ScrollChurn(3, true),
        )
        assertTrue(withCta.contains("<scroll-churn>"))
        assertTrue(withCta.contains("You have made 3 consecutive scrolls on this page without progress."))
        assertTrue(withCta.contains("A visible purchase control is present in the accessibility tree"))
        assertTrue(withCta.contains("TAP it; do not scroll again."))
        val noCta = VisionPrompts.proposeUser(
            "instruction", "done", "TITLE: t", null, VisionPrompts.ScrollChurn(4, false),
        )
        assertTrue(noCta.contains("<scroll-churn>"))
        assertTrue(noCta.contains("Change the action shape"))
        assertFalse(noCta.contains("A visible purchase control is present"))

        // The re-plan evidence block renders the two phase-doc lines.
        val block = VisionPrompts.jevEvidenceBlock(
            VisionPrompts.JevEvidence(
                battery = emptyList(),
                conditionRender = "the URL host is done.test",
                conditionSatisfied = false,
                escalation = VisionPrompts.EscalationKind.EXHAUSTED,
                grounding = null,
                actions = listOf("scroll down", "scroll down"),
                scrollChurn = VisionPrompts.ScrollChurn(4, true),
            ),
        )
        assertTrue(block.contains("scroll churn: 4 consecutive scrolls on this page with no progress"))
        assertTrue(
            block.contains("visible purchase control: YES (accessibility tree) - TAP it; do not scroll again"),
        )
        val noCtaBlock = VisionPrompts.jevEvidenceBlock(
            VisionPrompts.JevEvidence(
                battery = emptyList(),
                conditionRender = "c",
                conditionSatisfied = false,
                escalation = VisionPrompts.EscalationKind.EXHAUSTED,
                grounding = null,
                actions = emptyList(),
                scrollChurn = VisionPrompts.ScrollChurn(3, false),
            ),
        )
        assertTrue(noCtaBlock.contains("scroll churn: 3 consecutive scrolls"))
        assertFalse(noCtaBlock.contains("visible purchase control: YES"))

        // The Config thresholds (3/4) and the ordering invariant.
        val config = VisionLoop.Config()
        assertEquals(3, config.scrollChurnHintAfter)
        assertEquals(4, config.scrollChurnReplanAfter)
        assertFailsWith<IllegalArgumentException> {
            VisionLoop.Config(scrollChurnHintAfter = 4, scrollChurnReplanAfter = 3)
        }
    }

    @Test
    fun `the repair prompt pins the outcome-only no-clock law and the whitelist`() {
        // PHASE_VEXP7 WI2 — the run-5 condition repair: the repair-only surface must carry
        // the whitelist, the OUTCOME law and the owner's no-clock/no-date law (the planner
        // has no clock; a date copied from the page proves nothing).
        val repair = VisionPrompts.REPAIR_SYSTEM
        assertTrue(repair.contains("""{"done": <exactly one shape>}"""), "the STRICT JSON contract")
        listOf("urlHost", "urlContains", "markerText", "elementPresent", "count", "answerExtracted")
            .forEach { shape ->
                assertTrue(repair.contains("\"$shape\""), "the whitelist names $shape: $repair")
            }
        assertTrue(repair.contains("Name the OUTCOME the battery saw"), "the outcome law")
        assertTrue(
            repair.contains("NEVER write a date or time condition. You have no clock"),
            "the no-clock / no-date law",
        )
        assertTrue(repair.contains("incidental mention"), "the never-a-mention law")
        assertTrue(repair.contains("UNTRUSTED DATA"), "the untrusted-page law")

        val user = VisionPrompts.repairUser(
            instruction = "search Riga hotels",
            doneText = "an element matching 'a list of hotel cards' is present",
            pageDigest = "TITLE: Booking\nURL: https://booking.test/search",
            outcomeEvidence = "<jev-evidence>\ncondition verdict: NOT satisfied\n</jev-evidence>",
        )
        assertTrue(user.contains("<subtask>\nsearch Riga hotels\n</subtask>"), "the active subtask rides")
        assertTrue(user.contains("<old-done>\nan element matching"), "the old condition rides")
        assertTrue(user.contains("<battery-outcome>\n<jev-evidence>"), "the battery outcome rides")
        assertTrue(user.contains("<untrusted-page>\nTITLE: Booking"), "the page digest rides")
    }

    @Test
    fun `the repair parser accepts exactly one whitelisted condition and is loud otherwise`() {
        assertEquals(
            navigator.core.ladder.DoneCondition.MarkerText("81 hotels found in Riga"),
            VisionPrompts.parseRepairedCondition(
                """{"done":{"markerText":"81 hotels found in Riga"}}""",
                "repair",
            ),
        )
        assertEquals(
            navigator.core.ladder.DoneCondition.UrlHost("booking.test"),
            VisionPrompts.parseRepairedCondition("""{"done":{"urlHost":"booking.test"}}""", "repair"),
        )
        assertEquals(
            navigator.core.ladder.DoneCondition.AnswerExtracted,
            VisionPrompts.parseRepairedCondition("""{"done":{"answerExtracted":{}}}""", "repair"),
        )
        // ``` fences are stripped, the same discipline every writer answer obeys.
        assertEquals(
            navigator.core.ladder.DoneCondition.MarkerText("from a fenced answer"),
            VisionPrompts.parseRepairedCondition(
                "```json\n{\"done\":{\"markerText\":\"from a fenced answer\"}}\n```",
                "repair",
            ),
        )
        // An opinion-shaped condition (no whitelisted key) is schema-REJECTED.
        assertFailsWith<VisionHaiku.VisionHaikuException> {
            VisionPrompts.parseRepairedCondition("""{"done":{"looksRight":"yes"}}""", "repair")
        }
        // A blend of two whitelisted shapes resolves through the SAME ladder schema the
        // plans obey (the first matching shape wins there; the repair adds no new rule).
        assertEquals(
            navigator.core.ladder.DoneCondition.UrlHost("booking.test"),
            VisionPrompts.parseRepairedCondition(
                """{"done":{"urlHost":"booking.test","markerText":"x"}}""",
                "repair",
            ),
        )
        // A missing `done` object is loud.
        assertFailsWith<VisionHaiku.VisionHaikuException> {
            VisionPrompts.parseRepairedCondition("""{"condition":{"urlHost":"booking.test"}}""", "repair")
        }
    }

    @Test
    fun `the search-submit law pins type-then-submit and never tapping suggestions`() {
        // PHASE_VEXP6 WI3 — the run-4 search-submit repair (task-1792411667614): the
        // loop typed into the omnibox, then tapped Chrome's suggestion rows (accepted but
        // inert) and the clear-X, never submitting. PLAN_SYSTEM now authors the
        // type-then-submit sequence, the COMPLETE query and a results-shaped condition;
        // PROPOSE_SYSTEM names submit as the next action and forbids suggestion/clear-X
        // taps.
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains("The search sequence is TYPE-THEN-SUBMIT"),
            "the plan's type-then-submit law",
        )
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains("Search/Go/Done key with the submit action"),
            "the IME submit key named",
        )
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains("COMPLETE query with every"),
            "the complete-query law",
        )
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains("constraint the goal names (dates, location, filters)"),
            "every constraint must ride the query",
        )
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains("a results-only marker"),
            "the results-shaped condition",
        )
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains(
                "never a condition the query text or the omnibox suggestion list can satisfy",
            ),
            "the suggestion-text trap named",
        )

        assertTrue(
            VisionPrompts.PROPOSE_SYSTEM.contains("the next action is `submit`"),
            "submit is the proposer's post-type action",
        )
        assertTrue(
            VisionPrompts.PROPOSE_SYSTEM.contains("Never tap an omnibox suggestion row or the clear-X"),
            "the run-4 inert controls are forbidden",
        )
        assertTrue(
            VisionPrompts.PROPOSE_SYSTEM.contains("shows the typed query, submit it"),
            "a matching suggestion is submitted, never tapped",
        )
        assertTrue(
            VisionPrompts.PROPOSE_SYSTEM.contains("When the subtask carries `enterText`, type exactly that text."),
            "the plan text is authoritative in the prompt too",
        )
    }

    @Test
    fun `the proposer composes search text instead of tapping an empty address bar`() {
        // Third actual-app live-test repair (2026-10-19, task-1792396531977): the browser
        // sat on about:blank while the plan said "enter a hotel search into the address
        // bar"; the old rule ("if the subtask does not say what to type ... else propose
        // tap instead") produced a bare tap on the empty address bar for 13 re-plans and
        // the search was never composed.
        assertTrue(VisionPrompts.PROPOSE_SYSTEM.contains("COMPOSE a concise search"))
        assertTrue(VisionPrompts.PROPOSE_SYSTEM.contains("hotels in Riga next weekend"))
        assertTrue(VisionPrompts.PROPOSE_SYSTEM.contains("address bar or search field"))
        assertTrue(
            VisionPrompts.PROPOSE_SYSTEM.contains("a bare tap on an empty address bar is never a sufficient proposal"),
        )
        assertFalse(
            VisionPrompts.PROPOSE_SYSTEM.contains("else propose tap instead"),
            "the rule that suppressed composed search text must be gone",
        )
        assertTrue(VisionPrompts.PROPOSE_SYSTEM.contains("at most 120 chars"))
        assertTrue(VisionPrompts.PROPOSE_SYSTEM.contains("never a directive"))
    }

    @Test
    fun `the planner opens a blank or error page with a named search step`() {
        // The proposer can only compose text the plan names: when the browser shows
        // about:blank or an error page, the FIRST subtask must be a search/navigate step
        // that names the query it will enter.
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains(
                "When the browser shows a blank or error page, the first subtask must be a SEARCH/NAVIGATE step",
            ),
        )
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("names\nthe query to enter"))
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("so the proposer has text to type"))
    }

    @Test
    fun `the planner authors the per-subtask enterText field`() {
        // Fifth actual-app live-test repair (2026-10-19, task-1792397809506): the
        // grounded address bar was TAPPED five times and never typed because no plan
        // field carried the text. The planner now authors it — required for a
        // search/navigate/fill whose point is entering text, with the query for a
        // blank/error-page search step.
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("\"enterText\": \"<the exact text to type>\""))
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("REQUIRED when the subtask is a search/navigate/fill"))
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("hotels in Riga next weekend"))
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains(
                "\"enterText\":\"<the exact text to type; omit when the subtask types nothing>\"",
            ),
            "the JSON example carries the field",
        )
    }

    @Test
    fun `the planner claims multi only with a count-shaped done condition`() {
        // Live defect D3 (2026-10-19): the writer repeatedly claimed taskClass "multi"
        // with no count condition; its JSON was refused and only the error-fed retry
        // recovered. The system prompt now states the law explicitly.
        assertTrue(
            VisionPrompts.PLAN_SYSTEM.contains(
                "Claim 'multi' ONLY when a count-shaped done condition exists; otherwise use read or action.",
            ),
        )
    }

    @Test
    fun `the planner law demands a final-subtask battery over the visible end state`() {
        // Round-2 false-completion law: a markerText-style condition matching the page's
        // own hero text must never be the last word — the FINAL subtask's battery checks
        // the VISIBLE end state, and the loop's accept gate enforces concurrence.
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("FINAL subtask must ALWAYS carry a verify battery"))
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("never a heading or hero text"))
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("a results list,\na confirmation screen, the cart contents"))
        assertTrue(VisionPrompts.PLAN_SYSTEM.contains("badIsTrue check\nis welcome here"))
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

    // -- PHASE_VEXP1 live-run defect fix: the escalation-without-action guard ---------------
    //
    // Emulator evidence (vision-loop-1792356890231): a battery escalation skips
    // attemptStep, so an UNCHANGED screen re-fired the same bad-is-true verdict every
    // iteration — re-plans burned with zero actions into REPLANS_EXHAUSTED. The guard:
    // a battery-escalation re-plan is followed by ONE battery-free action attempt; the
    // battery re-arms afterwards; the re-plan budget still bounds the loop.

    /** Routes by URL: chat-completions pops [chatResponses], everything else [jevResponses]. */
    private class ScriptedVisionTransport(
        private val chatResponses: ArrayDeque<String>,
        private val jevResponses: ArrayDeque<String>,
    ) : WireTransport {
        var chatCalls = 0
        var jevCalls = 0

        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            val isChat = request.url.contains("chat/completions")
            if (isChat) chatCalls++ else jevCalls++
            val body = (
                if (isChat) chatResponses.removeFirstOrNull() else jevResponses.removeFirstOrNull()
                ) ?: throw WireTransportException("script exhausted for ${request.url}")
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
        override fun tap(x: Int, y: Int): Boolean { taps.add(x to y); return true }
        override fun clickNode(x: Int, y: Int): Boolean = true
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean = true
        override fun back(): Boolean = true
        override fun submit(): Boolean = true
        override fun enterText(x: Int, y: Int, text: String): Boolean = true
    }

    /** Chat-completions envelope around the writer's inner JSON content. */
    private fun chatContent(innerJson: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5) })
    }.toString()

    private fun jevAnswer(name: String, pTrue: Double): String = jevAnswers(name to pTrue)

    /** A batched multi-check answer (the two-check battery shape: v0 outcome + v1 bad). */
    private fun jevAnswers(vararg pairs: Pair<String, Double>): String = buildJsonObject {
        put("answers", buildJsonObject {
            pairs.forEach { (name, p) ->
                put(name, buildJsonObject {
                    put("type", JsonPrimitive("noul"))
                    put("noul", JsonPrimitive(p))
                })
            }
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    private fun jevBatteryAnswer(pTrue: Double): String = jevAnswer("v0", pTrue)

    private fun observation(vararg lines: String): StepObservation =
        observationAt("https://booking.test/search", *lines)

    private fun observationAt(url: String, vararg lines: String): StepObservation = StepObservation(
        url = url,
        title = "Booking",
        lines = lines.toList(),
        nodes = listOf(
            node("n1", left = 100, top = 200, right = 300, bottom = 260, text = "Search"),
        ),
    )

    @Test
    fun `a battery-escalation replan gets its action attempt - then the per-subtask budget forces PROCEED`() = runBlocking {
        // The defect's shape: one bad-is-true check that is true BY DEFINITION while no
        // action has run since the last escalation (the old-default-dates question).
        // Round-3 update: the first escalation re-plans under the BATTERY budget; once
        // that per-subtask budget is spent (1 here), the next escalation degrades to a
        // forced PROCEED — the cascade acts — and the loop only terminates through the
        // failure budget (maxReplans), which battery escalations no longer drain.
        val plan = """
            {"summary":"search riga hotels","taskClass":"read","subtasks":[
              {"instruction":"Search Google for riga hotels for next weekend","done":{"urlContains":"google.com/search?q=riga"},
               "verify":[{"question":"does the date field still show the old default dates?","badIsTrue":true}]}]}
        """.trimIndent()
        val propose = """{"proposals":[{"description":"the Search button","action":"tap"}]}"""
        val proposeEmpty = """{"proposals":[]}"""
        val confirmFalse = """{"complete":false,"reason":"no results list is visible"}"""
        val transport = ScriptedVisionTransport(
            // plan, battery re-plan, propose(tap), propose(empty) after the forced
            // PROCEED, failure re-plan, propose(empty) at the exhausted-failure-budget
            // gate. PHASE_VEXP8: the run's condition is STRONG (urlHost), so the
            // MODELS_CONFIRM_DONE hatch is suppressed BEFORE recordedOpinions — the two
            // old confirm(false) + noul pairs are gone (zero hatch calls).
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(plan),
                    chatContent(plan),
                    chatContent(propose),
                    chatContent(proposeEmpty),
                    chatContent(plan),
                    chatContent(proposeEmpty),
                ),
            ),
            // battery (escalate), battery (escalate -> forced PROCEED): no MODELS-check
            // noul is spent (the STRONG finality guard shuts the hatch first)
            jevResponses = ArrayDeque(
                listOf(
                    jevBatteryAnswer(0.95),
                    jevBatteryAnswer(0.95),
                ),
            ),
        )
        val executor = RecordingExecutor()
        val loop = VisionLoop(
            observer = ScriptedObserver(
                observation("initial page"), // the run() start observe (planLadder's digest)
                observation("step-zero page"),
                observation("step-one page"),
                observation("step-two page"),
                observation("step-three page"),
            ),
            executor = executor,
            capture = VisionCapture(null), // null service -> every capture degrades text-only
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(
                transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                backoffBaseMs = 1, retryDelay = {},
            ),
            budget = ExpBudget(),
            evidence = VisionEvidence(null, "guard-test"),
            config = VisionLoop.Config(
                settleMs = 0,
                maxReplans = 1,
                batteryReplansPerSubtask = 1,
                stepFailuresBeforeReplan = 1,
            ),
        )

        val result = loop.run("find a hotel in riga for next weekend", "guard-test") { false }

        // The battery budget still bounds: the second escalation forced PROCEED, and the
        // loop terminates through the FAILURE budget (maxReplans) as before.
        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertEquals(1, result.replans, "only the failure re-plan spends maxReplans")
        // The held step RAN its action attempt exactly once (the defect produced zero).
        assertEquals(1, executor.taps.size)
        // The battery fired on the escalating steps only — the held step fired none, and
        // the forced-PROCEED steps reused/ran their batteries without re-planning. The
        // STRONG finality guard spends ZERO hatch calls (PHASE_VEXP8).
        assertEquals(2, transport.jevCalls)
        assertEquals(6, transport.chatCalls)
        val escalations = result.subtaskOutcomes.indices.filter {
            result.subtaskOutcomes[it].contains("BATTERY ESCALATE")
        }
        assertEquals(2, escalations.size)
        val budgetLines = result.subtaskOutcomes.count {
            it.contains("battery budget exhausted for this subtask - acting per cascade")
        }
        assertEquals(1, budgetLines, "the spent per-subtask budget degrades to a forced PROCEED")
        val actionIndex = result.subtaskOutcomes.indexOfFirst { it.contains("step 1: tap") }
        assertTrue(actionIndex > escalations[0], "the action attempt follows the first escalation")
        assertTrue(actionIndex < escalations[1], "the battery re-arms only after the action attempt")
    }

    @Test
    fun `the battery re-plan budget is per subtask - spent escalations force PROCEED and a subtask advance resets`() = runBlocking {
        // Round 3 (budget coalescing), the live defect's shape: keep-acting red-flag
        // escalations drained the task budget so the USEFUL mis-shaped-condition
        // re-plan never fired. Now: two battery re-plans per subtask (budget 2 here),
        // the third escalation forces PROCEED (acts, no re-plan), and the subtask
        // advance resets the budget — the next subtask's first escalation re-plans.
        val plan = """
            {"summary":"riga dates","taskClass":"read","subtasks":[
              {"instruction":"select the check-in date","done":{"urlHost":"next.test"},
               "verify":[{"question":"is the chosen check-in date visible in the field?","badIsTrue":false},
                         {"question":"is the date picker stuck with no selection made?","badIsTrue":true}]},
              {"instruction":"select the check-out date","done":{"markerText":"CHECKOUT-DONE-MARKER"},
               "verify":[{"question":"is the chosen check-out date visible?","badIsTrue":false}]}]}
        """.trimIndent()
        val propose = """{"proposals":[{"description":"the Search button","action":"tap"}]}"""
        val transport = ScriptedVisionTransport(
            // plan, battery re-plan #1, tap, battery re-plan #2, tap, tap (forced
            // PROCEED — NO re-plan chat here), the accept's recorded-opinion confirm
            // (tolerant), PHASE_VEXP7 the s1 OutcomeAchieved trigger's ONE repair turn
            // (a non-matching condition -> no acceptance, so the existing battery path
            // runs), battery re-plan #1 on subtask 2 (post-reset), tap, the final
            // accept's recorded-opinion confirm. Every ACCEPT also spends one tolerant
            // noul (jev) + one confirm (chat) — recordedOpinions rides the accept path.
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(plan),
                    chatContent(plan),
                    chatContent(propose),
                    chatContent(plan),
                    chatContent(propose),
                    chatContent(propose),
                    chatContent(confirmTrue),
                    chatContent("""{"done":{"markerText":"NOT-THE-CHECKOUT-PAGE"}}"""),
                    chatContent(plan),
                    chatContent(propose),
                    chatContent(confirmTrue),
                ),
            ),
            // s0 battery (red flag), s0 battery (red flag), s0 battery (red flag ->
            // budget spent), s0 ACCEPT battery (concurs) + its opinions noul, s1 battery
            // (outcome achieved -> escalates), s1 ACCEPT battery (concurs) + its noul
            jevResponses = ArrayDeque(
                listOf(
                    jevAnswers("v0" to 0.1, "v1" to 0.95),
                    jevAnswers("v0" to 0.1, "v1" to 0.95),
                    jevAnswers("v0" to 0.1, "v1" to 0.95),
                    jevAnswers("v0" to 0.9, "v1" to 0.1),
                    jevAnswer("done", 0.9),
                    jevAnswers("v0" to 0.9),
                    jevAnswers("v0" to 0.9),
                    jevAnswer("done", 0.9),
                ),
            ),
        )
        val executor = RecordingExecutor()
        val loop = VisionLoop(
            observer = ScriptedObserver(
                observationAt("https://old.test/", "hotel page"),             // run() start
                observationAt("https://old.test/", "hotel page 2"),           // iter0: escalate -> battery re-plan #1
                observationAt("https://old.test/", "hotel page 3"),           // iter1: held -> tap
                observationAt("https://old.test/", "hotel page 4"),           // iter2: escalate -> battery re-plan #2
                observationAt("https://old.test/", "hotel page 5"),           // iter3: held -> tap
                observationAt("https://old.test/", "hotel page 6"),           // iter4: escalate -> budget spent -> FORCED PROCEED -> tap
                observationAt("https://next.test/search", "dates set page"),  // iter5: accept (concurs) -> advance, budget reset
                observationAt("https://next.test/search", "checkout page"),   // iter6: s1 escalate -> battery re-plan #1 (post-reset)
                observationAt("https://next.test/search", "checkout page 2"), // iter7: held -> tap
                observationAt("https://next.test/search", "CHECKOUT-DONE-MARKER posted"), // iter8: accept -> GOAL_ACHIEVED
            ),
            executor = executor,
            capture = VisionCapture(null),
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(
                transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                backoffBaseMs = 1, retryDelay = {},
            ),
            budget = ExpBudget(),
            evidence = VisionEvidence(null, "budget-split-test"),
            config = VisionLoop.Config(settleMs = 0, maxReplans = 5, batteryReplansPerSubtask = 2),
        )

        val result = loop.run("open next.test and select the check-in and check-out dates", "budget-split-test") { false }

        // The run PROGRESSES to a true completion instead of starving: the battery
        // escalations re-planned within the per-subtask budget, the spent budget forced
        // PROCEED (act, no re-plan), and the subtask advance reset the budget so the
        // next subtask's first escalation re-planned again.
        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(0, result.replans, "battery escalations never spend the failure budget")
        assertEquals(4, executor.taps.size)
        assertEquals(
            1,
            result.subtaskOutcomes.count { it.contains("battery budget exhausted for this subtask - acting per cascade") },
        )
        assertEquals(
            3,
            result.subtaskOutcomes.count { it.contains("REPLAN (battery") },
            "two battery re-plans on subtask 1 + one on subtask 2 (the advance reset the budget)",
        )
        // The forced-PROCEED step acted: the budget line is immediately followed by a tap.
        val budgetIndex = result.subtaskOutcomes.indexOfFirst {
            it.contains("battery budget exhausted for this subtask - acting per cascade")
        }
        assertTrue(
            budgetIndex >= 0 && result.subtaskOutcomes[budgetIndex + 1].contains("tap"),
            "a spent-budget escalation must degrade to an action attempt",
        )
    }

    // -- PHASE_VEXP1 round-2 live defect: the accept-side battery gate ----------------------
    //
    // Emulator evidence (vision-loop-1792358155819): the final subtask's markerText-style
    // condition matched the page's own hero heading ("Search hotels in Riga" is printed
    // on the page) and the ACCEPT path — where the verification battery never spoke —
    // returned GOAL_ACHIEVED with the date picker open, default dates, and no results.

    /** The false-completion shape: a text condition the hero text satisfies, plus an outcome check. */
    private val rigaFinalPlan = """
        {"summary":"search riga hotels","taskClass":"read","subtasks":[
          {"instruction":"Search Google for riga hotels for next weekend","done":{"markerText":"Search hotels in Riga"},
           "verify":[{"question":"is a results list with the selected dates visible?","badIsTrue":false}]}]}
    """.trimIndent()

    private val proposeSearchTap = """{"proposals":[{"description":"the Search button","action":"tap"}]}"""
    private val proposeEmpty = """{"proposals":[]}"""
    private val confirmFalse = """{"complete":false,"reason":"no results list is visible"}"""
    private val confirmTrue = """{"complete":true,"reason":"the results list is visible"}"""

    @Test
    fun `a hero-text condition cannot accept when the battery outcome check fails - no false GOAL_ACHIEVED`() = runBlocking {
        val transport = ScriptedVisionTransport(
            // plan, propose(tap), propose(empty) after the refused accept, confirm(false)
            // at the first re-plan gate, re-plan, then one more propose-failure round
            // (the re-plan reset the failure counter) ending in confirm(false) at the
            // exhausted-budget gate
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(rigaFinalPlan),
                    chatContent(proposeSearchTap),
                    chatContent(proposeEmpty),
                    chatContent(confirmFalse),
                    chatContent(rigaFinalPlan),
                    chatContent(proposeEmpty),
                    chatContent(confirmFalse),
                    chatContent(proposeEmpty),
                    chatContent(confirmFalse),
                ),
            ),
            // pre-act battery (step 0), accept battery (refused), the first MODELS-check
            // noul, the re-planned step's battery, the second and third MODELS-check nouls
            jevResponses = ArrayDeque(
                listOf(
                    jevBatteryAnswer(0.1),
                    jevBatteryAnswer(0.1),
                    jevAnswer("done", 0.1),
                    jevBatteryAnswer(0.1),
                    jevAnswer("done", 0.1),
                    jevAnswer("done", 0.1),
                ),
            ),
        )
        val executor = RecordingExecutor()
        val loop = VisionLoop(
            observer = ScriptedObserver(
                observation("some other page"),
                observation("Search hotels in Riga", "check-in row"),
                observation("Search hotels in Riga", "date picker open"),
                observation("Search hotels in Riga", "date picker open"),
                observation("Search hotels in Riga", "date picker open"),
            ),
            executor = executor,
            capture = VisionCapture(null),
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(
                transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                backoffBaseMs = 1, retryDelay = {},
            ),
            budget = ExpBudget(),
            evidence = VisionEvidence(null, "accept-gate-false"),
            config = VisionLoop.Config(settleMs = 0, maxReplans = 1),
        )

        val result = loop.run("find a hotel in riga for next weekend", "accept-gate-false") { false }

        // The money assertion: the hero-text mention NEVER accepts.
        assertTrue(
            result.subtaskOutcomes.none { it.contains("GOAL_ACHIEVED") } &&
                result.terminal != VisionLoop.Terminal.GOAL_ACHIEVED,
            "a false completion slipped through: ${result.terminal} ${result.subtaskOutcomes}",
        )
        assertTrue(result.subtaskOutcomes.any { it.contains("ACCEPT REFUSED") }, "the refusal is loud")
        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)
        assertEquals(1, result.replans, "the refusal feeds the existing failure/re-plan budget")
        assertEquals(1, executor.taps.size)
    }

    @Test
    fun `a concurring battery lets the accept stand - GOAL_ACHIEVED on the visible end state`() = runBlocking {
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(chatContent(rigaFinalPlan), chatContent(proposeSearchTap), chatContent(confirmTrue)),
            ),
            jevResponses = ArrayDeque(
                listOf(jevBatteryAnswer(0.1), jevBatteryAnswer(0.9), jevAnswer("done", 0.9)),
            ),
        )
        val executor = RecordingExecutor()
        val loop = VisionLoop(
            observer = ScriptedObserver(
                observation("some other page"),
                observation("Search hotels in Riga", "check-in row"),
                observation("Search hotels in Riga", "results list with 15 properties"),
            ),
            executor = executor,
            capture = VisionCapture(null),
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(
                transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                backoffBaseMs = 1, retryDelay = {},
            ),
            budget = ExpBudget(),
            evidence = VisionEvidence(null, "accept-gate-true"),
            config = VisionLoop.Config(settleMs = 0, maxReplans = 1),
        )

        val result = loop.run("find a hotel in riga for next weekend", "accept-gate-true") { false }

        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)
        assertEquals(0, result.replans)
        assertEquals(1, executor.taps.size)
        assertTrue(result.subtaskOutcomes.any { it.contains("DONE (code)") })
    }

    @Test
    fun `accept-side concurrence mirrors the max-gate rules - red flags refuse missing outcomes speak`() {
        fun check(badIsTrue: Boolean, textNoul: Double?, imageConfirm: Boolean? = null) =
            VisionLoop.CheckResult("q", badIsTrue, textNoul, imageConfirm)
        // a red flag refuses even when an outcome check achieved
        assertFalse(
            VisionLoop.batteryConcurs(
                listOf(check(false, 0.9), check(true, 0.85)),
                redFlagAbove = 0.70, agreeAbove = 0.70,
            ),
        )
        // an unachieved outcome check refuses
        assertFalse(
            VisionLoop.batteryConcurs(
                listOf(check(false, 0.1)),
                redFlagAbove = 0.70, agreeAbove = 0.70,
            ),
        )
        // both channels refused on the outcome check -> cannot concur
        assertFalse(
            VisionLoop.batteryConcurs(
                listOf(check(false, null)),
                redFlagAbove = 0.70, agreeAbove = 0.70,
            ),
        )
        // image-confirmed alone concurs (the two-channel witness rule)
        assertTrue(
            VisionLoop.batteryConcurs(
                listOf(check(false, null, imageConfirm = true)),
                redFlagAbove = 0.70, agreeAbove = 0.70,
            ),
        )
        // no outcome checks authored -> the battery cannot speak -> code rules alone
        assertTrue(
            VisionLoop.batteryConcurs(
                listOf(check(true, 0.1)),
                redFlagAbove = 0.70, agreeAbove = 0.70,
            ),
        )
        assertTrue(VisionLoop.batteryConcurs(emptyList(), redFlagAbove = 0.70, agreeAbove = 0.70))
    }
}
