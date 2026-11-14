package navigator.app.harness.vision

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.app.gen.LadderSchema
import navigator.app.loop.ActionExecutor
import navigator.app.loop.BrowserLauncher
import navigator.app.loop.LoopObserver
import navigator.app.loop.StepObservation
import navigator.core.enumerate.ObservedNode
import navigator.core.ladder.GoalContract
import navigator.core.ladder.GoalSitePolicy
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport

/**
 * PHASE_18 Stage 18.D Gate 18.D3 — the five CN-14 site-resolution cases (doc 04 §9.4 +
 * §9.9, the plan's §6 CN-14 test list). Scripted transports only ($0, no secrets):
 *
 * 1. a site-agnostic product goal → the first subtask is SEARCH, not a guessed store
 *    (the store-first ladder is refused; the error-fed retry installs the search plan);
 * 2. a site-named goal → no unnecessary search detour (the detour ladder is refused;
 *    the named-site ladder installs clean);
 * 3. a re-plan after failed site entries → the same unproductive URL is not re-opened
 *    repeatedly (the landing history persists ACROSS the re-plan);
 * 4. a correct `/videos`-style path with a weak/blank title → path + content establish
 *    the landing (never the title alone);
 * 5. landing content later changes → the bounded fresh-evidence recheck clears the old
 *    failure, recorded (and the cleared ban re-opens in the loop).
 */
class Phase18SitePolicyTest {

    // -- scripted edges -------------------------------------------------------------------

    /**
     * Routes chat by the PROMPT (the plan system says "Decompose the goal into", the
     * proposer does not): plan calls pop [planResponses]; proposal calls repeat
     * [proposeResponse] (the deterministic empty-proposal steps). Unscripted plan calls
     * fail loudly.
     */
    private class RoutingTransport(
        private val planResponses: ArrayDeque<String>,
        private val proposeResponse: String,
    ) : WireTransport {
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            if (!request.url.contains("chat/completions")) {
                throw IllegalStateException("no Jev script in this test")
            }
            val isPlan = request.body.contains("Decompose the goal into")
            if (isPlan) planRequests.add(request.body)
            val body = if (isPlan) {
                planResponses.removeFirstOrNull()
                    ?: throw IllegalStateException("plan script exhausted")
            } else {
                proposeResponse
            }
            return WireHttpResponse(200, body)
        }

        /** The plan-request bodies seen (the error-fed correction content pins). */
        val planRequests = mutableListOf<String>()
    }

    /** Repeats the LAST observation forever (the R2 fixture pattern). */
    private class RepeatingObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        private var last: StepObservation = observations.last()
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull()?.also { last = it } ?: last
    }

    /** Answers every Jev noul with a fixed pTrue (0.0 = never agrees). */
    private inner class JevBatteryTransport(private val pTrue: Double) : WireTransport {
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            if (request.url.contains("chat/completions")) {
                throw IllegalStateException("no Haiku script in the Jev transport")
            }
            val name = Regex("\"name\":\"(v\\d+)\"").find(request.body)?.groupValues?.get(1) ?: "v0"
            return WireHttpResponse(200, jevNoul(name, pTrue))
        }
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

    private fun chatContent(innerJson: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5) })
    }.toString()

    private fun jevNoul(name: String, pTrue: Double): String = buildJsonObject {
        put("answers", buildJsonObject {
            put(name, buildJsonObject {
                put("type", JsonPrimitive("noul"))
                put("noul", JsonPrimitive(pTrue))
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    // -- fixtures (sanitized shop.example; no real store, address, or person) --------------

    private fun node(id: String, text: String, editable: Boolean = false) = ObservedNode(
        id = id, text = text, desc = "", clickable = !editable, editable = editable,
        scrollable = false, left = 100, top = 200, right = 900, bottom = 280,
        className = if (editable) "android.widget.EditText" else "android.widget.Button",
        sourceOrder = 0,
    )

    private fun chromeCard(id: String, text: String) =
        node(id, text).copy(browserChrome = true)

    private val checkout = StepObservation(
        url = "https://shop.example/checkout",
        title = "Checkout — Sample Store",
        lines = listOf("Checkout", "Delivery address", "43,92 €"),
        nodes = listOf(node("n1", "First name", editable = true), node("n2", "Continue to payment")),
        windowPackage = "com.android.chrome",
    )

    private val tabSwitcher = StepObservation(
        url = "",
        title = "Tab switcher",
        lines = listOf("Checkout — Sample Store", "Home", "New tab"),
        nodes = listOf(
            chromeCard("c1", "Checkout — Sample Store"),
            chromeCard("c2", "Home"),
            chromeCard("c3", "New tab"),
        ),
        windowPackage = "com.android.chrome",
    )

    /** A DIFFERENT real page (the failed landing) — the part-1 WEB_CONTENT shape. */
    private val otherPage = StepObservation(
        url = "https://other.example/somewhere",
        title = "Somewhere else",
        lines = listOf("A different page", "nothing checkout-shaped here", "plain content"),
        nodes = listOf(node("n1", "A link"), node("n2", "Another link"), node("n3", "Text")),
        windowPackage = "com.android.chrome",
    )

    /** The checkout again — BLANK title, correct path, the identity in the CONTENT. */
    private val checkoutBlankTitle = StepObservation(
        url = "https://shop.example/checkout",
        title = "",
        lines = listOf("Checkout — Sample Store", "Delivery address", "43,92 €"),
        nodes = listOf(node("n1", "First name", editable = true), node("n2", "Continue to payment")),
        windowPackage = "com.android.chrome",
    )

    private val buyGoal =
        "open shop.example and buy SoundMagic E11C with delivery to koivuviita 7 B 5 for 60 euros"

    private val checkoutPlan = """
        {"summary":"buy the product","taskClass":"action","subtasks":[
          {"instruction":"complete the checkout at shop.example to koivuviita 7 B 5 for 60 euros",
           "done":{"markerText":"order placed"}}]}
    """.trimIndent()

    private val emptyProposals = """{"proposals":[]}"""

    // -- case 1: site-agnostic product goal -> first subtask is SEARCH, not a guessed store -

    @Test
    fun `case 1 - site-agnostic product goal - the store-first ladder is refused and search installs`() =
        runBlocking {
            // The POLICY pins: a commerce goal with no named site resolves SEARCH_FIRST,
            // and a store-first ladder (navigating straight to a store host) is a
            // violation, while a search-first ladder passes.
            val goal = "buy a kettle for 30 euros"
            val contract = GoalContract.fromGoal(goal)
            assertEquals(GoalSitePolicy.Resolution.SEARCH_FIRST, GoalSitePolicy.resolution(contract))
            val storeFirst = """
                {"summary":"buy the kettle at the store","taskClass":"action","subtasks":[
                  {"instruction":"open kettlestore.example and buy a kettle for 30 euros",
                   "done":{"urlHost":"kettlestore.example"}}]}
            """.trimIndent()
            val searchThenBuy = """
                {"summary":"search then buy the kettle","taskClass":"action","subtasks":[
                  {"instruction":"Search Google for a kettle for 30 euros and open the results",
                   "done":{"elementPresent":"a search result linking to a kettle store"}},
                  {"instruction":"buy the kettle from the results for 30 euros",
                   "done":{"markerText":"order placed"}}]}
            """.trimIndent()
            val storeFirstLadder = LadderSchema.parse(storeFirst, maxSubtasks = 8)
            val searchLadder = LadderSchema.parse(searchThenBuy, maxSubtasks = 8)
            val policyViolations = GoalSitePolicy.checkLadder(contract, storeFirstLadder)
            assertTrue(
                policyViolations.any { it.contains("START WITH SEARCH") },
                "a store-first ladder on a site-agnostic goal is refused: $policyViolations",
            )
            assertTrue(
                GoalSitePolicy.checkLadder(contract, searchLadder).isEmpty(),
                "a search-first ladder passes the site policy",
            )
            // (d) the enforcement note: the policy reads ONLY the goal + plan text — a
            // store can never be inferred from the page open at entry.
            assertTrue(
                policyViolations.single().contains("the goal names no website"),
                "the refusal names the law: $policyViolations",
            )

            // The LOOP pins: the writer's store-first attempt is refused (error-fed) and
            // the INSTALLED plan is the search-first one — the first subtask is search.
            val base = Files.createTempDirectory("p18-site-1").toFile()
            val transport = RoutingTransport(
                planResponses = ArrayDeque(listOf(chatContent(storeFirst), chatContent(searchThenBuy))),
                proposeResponse = chatContent(emptyProposals),
            )
            val loop = VisionLoop(
                observer = RepeatingObserver(
                    StepObservation(
                        url = "https://search.test/blank", title = "Blank",
                        lines = listOf("a blank page"), nodes = listOf(node("n1", "Text")),
                        windowPackage = "com.android.chrome",
                    ),
                ),
                executor = RecordingExecutor(),
                capture = VisionCapture(null),
                haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
                jev = VisionJev(
                    JevBatteryTransport(0.0), "https://jev.test/v1/systemone", "jev-1.14.0",
                    "fixture-key", backoffBaseMs = 1, retryDelay = {}, maxRetries = 0,
                ),
                budget = ExpBudget(),
                evidence = VisionEvidence(base, "p18-site-1"),
                config = VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 2),
            )
            var iterations = 0
            val result = loop.run(goal, "p18-site-1") { ++iterations > 8 }

            assertTrue(
                transport.planRequests.any { it.contains("site-first") || it.contains("START WITH") },
                "the store-first refusal rode the error-fed correction: " +
                    transport.planRequests.joinToString("|") { it.take(200) },
            )
            assertTrue(
                result.subtaskOutcomes.any { it.contains("search then buy the kettle") },
                "the INSTALLED plan is the search-first one: ${result.subtaskOutcomes}",
            )
            assertTrue(
                result.subtaskOutcomes.none { it.contains("buy the kettle at the store") },
                "the store-first ladder never installed: ${result.subtaskOutcomes}",
            )
            assertTrue(result.terminal != VisionLoop.Terminal.GOAL_ACHIEVED)
            assertTrue(result.stepsUsed > 0, "the run navigated under the search plan (before the cap)")
        }

    // -- case 2: site-named goal -> no unnecessary search detour (installs clean) ----------

    @Test
    fun `case 2 - site-named goal - a search detour is refused and the named-site ladder installs clean`() =
        runBlocking {
            // The POLICY pins: the named-site resolution; the detour (search-shaped first
            // step bound OUTSIDE the named site's family) is a violation; the named-site
            // ladder is clean THROUGH the contract's install law (the composition pin).
            val contract = GoalContract.fromGoal(buyGoal)
            assertEquals(GoalSitePolicy.Resolution.NAMED_SITE, GoalSitePolicy.resolution(contract))
            val detourPlan = """
                {"summary":"search then buy the product","taskClass":"action","subtasks":[
                  {"instruction":"Search google.com for SoundMagic E11C for 60 euros and open the results",
                   "done":{"elementPresent":"a search result linking to a store selling SoundMagic E11C"}},
                  {"instruction":"buy the product on shop.example to koivuviita 7 B 5 for 60 euros",
                   "done":{"markerText":"order placed"}}]}
            """.trimIndent()
            val namedSitePlan = """
                {"summary":"buy the product on the named site","taskClass":"action","subtasks":[
                  {"instruction":"open shop.example and search the catalog for SoundMagic E11C for 60 euros",
                   "done":{"urlHost":"shop.example"}},
                  {"instruction":"buy the product to koivuviita 7 B 5 for 60 euros",
                   "done":{"markerText":"order placed"}}]}
            """.trimIndent()
            val detourLadder = LadderSchema.parse(detourPlan, maxSubtasks = 8)
            val namedLadder = LadderSchema.parse(namedSitePlan, maxSubtasks = 8)
            val detourViolations = GoalSitePolicy.checkLadder(contract, detourLadder)
            assertTrue(
                detourViolations.any { it.contains("the goal names shop.example") },
                "the detour is refused by name: $detourViolations",
            )
            assertTrue(
                GoalSitePolicy.checkLadder(contract, namedLadder).isEmpty(),
                "the named-site ladder is clean",
            )
            // The CONTRACT composition: validateInstall carries the policy; the re-plan
            // law does NOT (the entry law vs the Phase-16 strategy law).
            assertTrue(
                contract.validateInstall(detourLadder).any { it.contains("site policy") },
                "the install law carries the site policy",
            )
            assertTrue(
                contract.validateReplan(detourLadder).none { it.contains("site policy") },
                "the re-plan law stays the parameter binding (the strategy law governs re-plans)",
            )
            assertTrue(contract.validateInstall(namedLadder).isEmpty())

            // The LOOP pins: the detour attempt is refused (error-fed) and the named-site
            // ladder installs clean.
            val base = Files.createTempDirectory("p18-site-2").toFile()
            val transport = RoutingTransport(
                planResponses = ArrayDeque(listOf(chatContent(detourPlan), chatContent(namedSitePlan))),
                proposeResponse = chatContent(emptyProposals),
            )
            val loop = VisionLoop(
                observer = RepeatingObserver(
                    StepObservation(
                        url = "https://search.test/blank", title = "Blank",
                        lines = listOf("a blank page"), nodes = listOf(node("n1", "Text")),
                        windowPackage = "com.android.chrome",
                    ),
                ),
                executor = RecordingExecutor(),
                capture = VisionCapture(null),
                haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
                jev = VisionJev(
                    JevBatteryTransport(0.0), "https://jev.test/v1/systemone", "jev-1.14.0",
                    "fixture-key", backoffBaseMs = 1, retryDelay = {}, maxRetries = 0,
                ),
                budget = ExpBudget(),
                evidence = VisionEvidence(base, "p18-site-2"),
                config = VisionLoop.Config(settleMs = 0, maxReplans = 0, stepFailuresBeforeReplan = 2),
            )
            var iterations = 0
            val result = loop.run(buyGoal, "p18-site-2") { ++iterations > 8 }

            assertTrue(
                transport.planRequests.any { it.contains("site policy") },
                "the detour refusal rode the error-fed correction: " +
                    transport.planRequests.joinToString("|") { it.take(200) },
            )
            assertTrue(
                result.subtaskOutcomes.any { it.contains("buy the product on the named site") },
                "the named-site ladder installed: ${result.subtaskOutcomes}",
            )
            assertTrue(result.terminal != VisionLoop.Terminal.GOAL_ACHIEVED)
        }

    // -- case 3: re-plan after failed site entries -> the ban persists across the re-plan --

    @Test
    fun `case 3 - the landing history persists across a re-plan - the same unproductive URL is not reopened`() =
        runBlocking {
            val launched = mutableListOf<String>()
            val executor = RecordingExecutor()
            val base = Files.createTempDirectory("p18-site-3").toFile()
            val transport = RoutingTransport(
                planResponses = ArrayDeque(
                    listOf(chatContent(checkoutPlan), chatContent(checkoutPlan), chatContent(checkoutPlan)),
                ),
                proposeResponse = chatContent(emptyProposals),
            )
            val loop = VisionLoop(
                observer = RepeatingObserver(
                    checkout, checkout, tabSwitcher, otherPage, tabSwitcher, otherPage,
                    tabSwitcher, otherPage, tabSwitcher, tabSwitcher, tabSwitcher, tabSwitcher,
                ),
                executor = executor,
                capture = VisionCapture(null),
                haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
                jev = VisionJev(
                    JevBatteryTransport(0.0), "https://jev.test/v1/systemone", "jev-1.14.0",
                    "fixture-key", backoffBaseMs = 1, retryDelay = {}, maxRetries = 0,
                ),
                budget = ExpBudget(),
                evidence = VisionEvidence(base, "p18-site-3"),
                browserLauncher = BrowserLauncher { launched.add(it) },
                config = VisionLoop.Config(
                    settleMs = 0,
                    maxReplans = 1,
                    stepFailuresBeforeReplan = 2,
                    maxOffPageRecoveries = 5, // the recovery BUDGET has room; the BAN stops it
                ),
            )
            var iterations = 0
            val result = loop.run(buyGoal, "p18-site-3") { ++iterations > 24 }

            val log = File(base, "p18-site-3/events.log").readText()
            assertTrue(
                log.contains("OFF-PAGE RECOVERY #1") && log.contains("OFF-PAGE RECOVERY #2"),
                "two recoveries ran before the ban: ${log.take(2500)}",
            )
            assertEquals(
                2,
                launched.size,
                "the ban stopped the re-opens at two, inside the recovery budget: $launched\nLOG:\n$log",
            )
            assertTrue(
                launched.all { it == "https://shop.example/checkout" },
                "every re-open targeted the EXISTING checkout: $launched",
            )
            assertTrue(
                log.contains("landing recorded: 2 failed landing(s)"),
                "two failed landings are recorded (the ban input): ${log.take(2500)}",
            )
            val replanAt = log.indexOf("REPLAN #")
            assertTrue(replanAt >= 0, "a re-plan installed: ${log.take(2500)}")
            val banLines = Regex("landing ban").findAll(log).toList().map { it.range.first }
            assertTrue(
                banLines.isNotEmpty() && banLines.last() > replanAt,
                "the ban REFUSED a re-open AFTER the re-plan (the history persists across it): " +
                    log.take(2500),
            )
            assertTrue(
                result.subtaskOutcomes.none { it.contains("New tab") },
                "the New tab is never the recovery: ${result.subtaskOutcomes}",
            )
            assertTrue(result.terminal != VisionLoop.Terminal.GOAL_ACHIEVED)
        }

    // -- case 4: a correct /videos path with a weak/blank title -> path+content establish ---

    @Test
    fun `case 4 - a correct videos path with a blank title establishes the landing by path and content`() {
        // PATH: the same /videos path establishes; a longer path under it establishes;
        // a different path does not. Never title-dependent.
        assertTrue(
            GoalSitePolicy.pathEstablishes(
                "https://tube.example/videos",
                "https://tube.example/videos",
            ),
            "the same /videos path establishes the landing",
        )
        assertTrue(
            GoalSitePolicy.pathEstablishes(
                "https://tube.example/videos/new?sort=asc",
                "https://tube.example/videos",
            ),
            "a path under the destination establishes the landing",
        )
        assertTrue(
            !GoalSitePolicy.pathEstablishes(
                "https://tube.example/other",
                "https://tube.example/videos",
            ),
            "a different path does not establish the landing",
        )
        // CONTENT: the identity tokens in the content LINES establish (a blank observed
        // title changes nothing — the evidence is the path + the content, never the title).
        assertTrue(
            GoalSitePolicy.contentEstablishes(
                listOf("Latest videos on Tube", "New uploads every week"),
                "Latest videos — Tube",
            ),
            "the content lines carry the destination identity",
        )
        assertTrue(
            !GoalSitePolicy.contentEstablishes(
                listOf("Unrelated page content"),
                "Latest videos — Tube",
            ),
            "unrelated content does not establish the landing",
        )
        // The BANNED-landing recheck: a blank-title page at the correct path with the
        // identity in the content CLEARS (path+content, title ignored).
        val history = GoalSitePolicy.LandingHistory()
        history.recordFailure("https://tube.example/videos", "first landing failed")
        history.recordFailure("https://tube.example/videos", "second landing failed")
        assertTrue(history.isBanned("https://tube.example/videos"))
        val cleared = history.recheck(
            "https://tube.example/videos",
            GoalSitePolicy.RecheckEvidence(
                pathEstablishes = GoalSitePolicy.pathEstablishes(
                    "https://tube.example/videos",
                    "https://tube.example/videos",
                ),
                contentEstablishes = GoalSitePolicy.contentEstablishes(
                    listOf("Latest videos on Tube"),
                    "", // the destination title is blank — the content carries the identity
                ) || GoalSitePolicy.contentEstablishes(
                    listOf("Latest videos on Tube"),
                    "Latest videos on Tube",
                ),
                detail = "the /videos path matched and the content carries the identity (blank title)",
            ),
        )
        assertNotEquals(null, cleared, "the path+content evidence clears the old failure")
        assertTrue(cleared!!.contains("landing recheck"), "the clearing is recorded: $cleared")
    }

    // -- case 5: landing content later changes -> bounded fresh evidence clears (recorded) --

    @Test
    fun `case 5 - changed landing content clears the old failure through the bounded recheck, recorded`() =
        runBlocking {
        // The POLICY pins: two failures ban; a fresh-evidence recheck clears and RECORDS;
        // the old failure count survives (recorded, not deleted); the recheck attempts
        // are BOUNDED.
        val history = GoalSitePolicy.LandingHistory()
        history.recordFailure("https://shop.example/checkout", "the restored surface did not match")
        history.recordFailure("https://shop.example/checkout", "the restored surface did not match")
        assertTrue(history.isBanned("https://shop.example/checkout"))
        assertEquals(2, history.failures("https://shop.example/checkout"))
        // A non-establishing recheck consumes a bounded attempt and clears nothing.
        assertEquals(
            null,
            history.recheck(
                "https://shop.example/checkout",
                GoalSitePolicy.RecheckEvidence(false, false, "the page still does not match"),
            ),
        )
        assertTrue(history.isBanned("https://shop.example/checkout"))
        // The establishing recheck clears — recorded with its evidence.
        val cleared = history.recheck(
            "https://shop.example/checkout",
            GoalSitePolicy.RecheckEvidence(
                pathEstablishes = true,
                contentEstablishes = true,
                detail = "the /checkout path matched and the content carries the checkout identity",
            ),
        )
        assertTrue(cleared != null && cleared.contains("landing recheck"))
        assertEquals(1, history.clearEvents.size, "the clearing is RECORDED")
        assertTrue(!history.isBanned("https://shop.example/checkout"))
        assertEquals(
            2,
            history.failures("https://shop.example/checkout"),
            "the old failures stay recorded (never deleted by a clear)",
        )
        // The BOUND: past maxRechecksPerUrl no further recheck runs (even valid evidence).
        val bounded = GoalSitePolicy.LandingHistory()
        bounded.recordFailure("https://shop.example/checkout", "failed")
        bounded.recordFailure("https://shop.example/checkout", "failed")
        bounded.recheck("https://shop.example/checkout", GoalSitePolicy.RecheckEvidence(false, false, "x"))
        bounded.recheck("https://shop.example/checkout", GoalSitePolicy.RecheckEvidence(false, false, "x"))
        assertEquals(
            null,
            bounded.recheck(
                "https://shop.example/checkout",
                GoalSitePolicy.RecheckEvidence(true, true, "valid evidence past the bound"),
            ),
            "the recheck attempts are bounded per URL",
        )

        // The LOOP pins: after the ban, the checkout page returns (blank title, correct
        // path, identity in the content) — the recheck clears (recorded) and the NEXT
        // tab-switcher recovery LAUNCHES again.
        val launched = mutableListOf<String>()
        val base = Files.createTempDirectory("p18-site-5").toFile()
        val transport = RoutingTransport(
            planResponses = ArrayDeque(
                listOf(chatContent(checkoutPlan), chatContent(checkoutPlan), chatContent(checkoutPlan)),
            ),
            proposeResponse = chatContent(emptyProposals),
        )
        val loop = VisionLoop(
            observer = RepeatingObserver(
                checkout, checkout, tabSwitcher, otherPage, tabSwitcher, otherPage,
                checkoutBlankTitle, tabSwitcher, tabSwitcher, tabSwitcher,
            ),
            executor = RecordingExecutor(),
            capture = VisionCapture(null),
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(
                JevBatteryTransport(0.0), "https://jev.test/v1/systemone", "jev-1.14.0",
                "fixture-key", backoffBaseMs = 1, retryDelay = {}, maxRetries = 0,
            ),
            budget = ExpBudget(),
            evidence = VisionEvidence(base, "p18-site-5"),
            browserLauncher = BrowserLauncher { launched.add(it) },
            config = VisionLoop.Config(settleMs = 0, maxReplans = 2, stepFailuresBeforeReplan = 2),
        )
        var iterations = 0
        val result = loop.run(buyGoal, "p18-site-5") { ++iterations > 24 }

        val log = File(base, "p18-site-5/events.log").readText()
        assertTrue(
            log.contains("landing recorded: 2 failed landing(s)"),
            "the ban formed after two failed landings: ${log.take(2500)}",
        )
        assertTrue(
            log.contains("landing recheck"),
            "the fresh evidence cleared the old failure (recorded): ${log.take(2500)}",
        )
        assertTrue(
            launched.size >= 3,
            "the cleared ban re-opened on the next recovery: $launched",
        )
        assertTrue(result.terminal != VisionLoop.Terminal.GOAL_ACHIEVED)
        }
}
