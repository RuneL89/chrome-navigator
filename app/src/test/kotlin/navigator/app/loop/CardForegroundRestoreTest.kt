package navigator.app.loop

import kotlinx.coroutines.runBlocking
import navigator.app.stub.ScriptedAnswer
import navigator.core.gate.GateCard
import navigator.core.log.EngineIdentity
import navigator.core.log.LoggedExitPath
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 2026-10-08 pre-UAT defect (S25+): after an in-task interactive card resolves on
 * the NO-OVERLAY path the loop never returned Chrome to the foreground — the
 * post-approval EXECUTE failed (dispatchGesture cannot land in a background app) and
 * the post-step OBSERVE read the harness window (0 lines) -> spurious REFUSED. The fix:
 * when a card presenter reports the bring-to-front path ([HarnessFrontAware]) the loop
 * re-fronts the browser BEFORE the loop continues — the VIEW intent for the card's URL
 * (gate card) or the current page URL (completion card) through the cold-start launcher
 * seam, a short settle, everything logged. The overlay path (Chrome never left),
 * never-fronting presenters (scripted/auto), and task-ending resolutions (CONFIRM_DONE,
 * the DENY-abort) restore NOTHING.
 *
 * The same-day defect-follower: on the S25+ the per-step URL-bar read is intermittently
 * empty — two live approvals produced no usable card/observation URL, so the restore
 * silently skipped and the run starved anyway. The restore now walks a preference chain
 * (card URL -> current observation URL -> the last-known page URL noted at every
 * observation) and the step-log line names which source served; the loud line stays for
 * genuinely-never-had-a-URL runs.
 *
 * $0 by construction: the engine is the scripted stub; the launcher journal is the
 * LoopTestSupport cold-start intent pattern reused for the restore intents.
 */
class CardForegroundRestoreTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    private val pageUrl = "http://m0.test/list"

    /** A mutation-page shape: two dictionary-worded controls + ordinary fillers. */
    private fun listPage() = obs(
        pageUrl,
        N("Issue header"),
        N("Body text one"),
        N("Body text two"),
        N("Unsubscribe now", clickable = true, button = true),
        N("Also unsubscribe", clickable = true, button = true),
        N("Footer"),
    )

    /** A scripted gate presenter with a FIXED fronting report per presentation. */
    private class FrontingGatePresenter(
        private val frontsHarness: Boolean,
        private vararg val resolutions: GateResolution,
    ) : GatePresenter, HarnessFrontAware {
        private var index = 0
        override fun lastCardFrontedHarness(): Boolean = frontsHarness
        override suspend fun present(card: GateCard): GateResolution = resolutions[index++]
    }

    /** A scripted completion presenter with a fixed resolution + fronting report. */
    private class FrontingCompletionPresenter(
        private val frontsHarness: Boolean,
        private val resolution: CompletionResolution,
    ) : CompletionCardPresenter, HarnessFrontAware {
        val presented = mutableListOf<CompletionCard>()
        override fun lastCardFrontedHarness(): Boolean = frontsHarness
        override suspend fun present(card: CompletionCard): CompletionResolution {
            presented.add(card)
            return resolution
        }
    }

    /** LoopFixture whose launcher journals restore VIEW intents on the shared timeline. */
    private fun frontingFixture(
        script: List<ScriptedAnswer>,
        observations: List<StepObservation>,
        gatePresenter: GatePresenter,
        completionCardPresenter: CompletionCardPresenter = AutoKeepGoingCompletionPresenter,
        config: NavigationLoop.Config = NavigationLoop.Config(settleMs = 0, restoreSettleMs = 0),
    ): Pair<LoopFixture, MutableList<String>> {
        val fired = mutableListOf<String>()
        val shared = Journal()
        val fixture = LoopFixture(
            script = script,
            observations = observations,
            presenter = gatePresenter,
            journal = shared,
            completionCardPresenter = completionCardPresenter,
            config = config,
            browserLauncher = {
                fired.add(it)
                shared.add("restore:view($it)")
            },
        )
        return fixture to fired
    }

    // -- the gate card ------------------------------------------------------------------

    @Test
    fun `restore fires after a gate-card approval on the no-overlay path - before the execute gesture`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                chooseByLabel("action", "Unsubscribe now"),
            ),
            observations = listOf(listPage()),
            gatePresenter = FrontingGatePresenter(true, GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 1),
            ),
        )
        val result = fixture.loop.run("tidy the subscriptions", "restore-approve", identity, fixture.clock)

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        // exactly one restore intent, for the card's page URL
        assertEquals(listOf(pageUrl), fired)
        // the restore ran BEFORE the execute gesture on one shared timeline
        val restoreIdx = fixture.journal.firstIndexOf("restore:view($pageUrl)")
        val tapIdx = fixture.journal.entries.indexOfFirst { it.startsWith("tap(") }
        assertTrue(restoreIdx in 0 until tapIdx, "journal=${fixture.journal.entries}")
        // and the restore is logged on the run report's step log
        assertTrue(
            result.stepLog.any {
                it.contains("restore: the gate card fronted the harness (no-overlay path)") &&
                    it.contains(pageUrl)
            },
            "stepLog=${result.stepLog}",
        )
    }

    @Test
    fun `the overlay path never restores`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                chooseByLabel("action", "Unsubscribe now"),
            ),
            observations = listOf(listPage()),
            gatePresenter = FrontingGatePresenter(false, GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 1),
            ),
        )
        fixture.loop.run("tidy the subscriptions", "restore-overlay", identity, fixture.clock)

        assertTrue(fired.isEmpty(), "restore fired on the overlay path: $fired")
    }

    @Test
    fun `a never-fronting presenter (scripted and auto modes) restores nothing - the default is byte-identical`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                chooseByLabel("action", "Unsubscribe now"),
            ),
            observations = listOf(listPage()),
            gatePresenter = ScriptedGatePresenter(GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 1),
            ),
        )
        fixture.loop.run("tidy the subscriptions", "restore-scripted", identity, fixture.clock)

        assertTrue(fired.isEmpty(), "restore fired without a fronting presenter: $fired")
    }

    @Test
    fun `a first deny keeps the restore (the loop continues) but the aborting second deny does not`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                chooseByLabel("action", "Unsubscribe now"),
                chooseByLabel("action", "Unsubscribe now"),
            ),
            observations = listOf(listPage()),
            gatePresenter = FrontingGatePresenter(true, GateResolution.DENIED, GateResolution.DENIED),
        )
        val result = fixture.loop.run("tidy the subscriptions", "restore-deny", identity, fixture.clock)

        // the second same-(class, site) refusal aborts the task (GUARD_ABORT)
        assertEquals(TerminalState.GUARD_ABORT, result.terminal)
        // exactly one restore: after the FIRST (non-task-ending) deny only
        assertEquals(listOf(pageUrl), fired)
        assertEquals(
            1,
            result.stepLog.count { it.contains("restore: the gate card fronted the harness") },
            "stepLog=${result.stepLog}",
        )
    }

    @Test
    fun `the trusted second action restores nothing - only real card presentations do`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                chooseByLabel("action", "Unsubscribe now"),
                chooseByLabel("action", "Also unsubscribe"),
            ),
            observations = listOf(listPage()),
            gatePresenter = FrontingGatePresenter(true, GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 2),
            ),
        )
        val result = fixture.loop.run("tidy the subscriptions", "restore-trust", identity, fixture.clock)

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        // the first action's card restored once; the per-task-trusted second action
        // presented no card and restored nothing
        assertEquals(listOf(pageUrl), fired)
    }

    @Test
    fun `a credential card approval restores before the secret is typed`() = runBlocking {
        val loginUrl = "http://127.0.0.1:8099/login.html"
        val loginPage = obs(
            loginUrl,
            N("Fixture Bank Dummy Login (TEST ONLY)"),
            N("TEST FIXTURE. No real credentials are accepted here."),
            N("Email or username", editable = true),
            N("Password", editable = true),
            N("Submit sign in", clickable = true, button = true),
            N("If you were signed in you would see the result page."),
            pageNodes = 8,
            title = "Fixture Bank Dummy Login (TEST ONLY)",
        )
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "form-fill"),
                chooseByLabel("action", "Email or username"),
                chooseByLabel("action", "Password"),
                chooseByLabel("action", "Submit sign in"),
                choose("action", "finish"),
                noul(0.95),
            ),
            observations = listOf(loginPage),
            gatePresenter = FrontingGatePresenter(
                true,
                GateResolution.ApproveWithSecret("fixture-user-DO-NOT-USE"),
                GateResolution.ApproveWithSecret("fixture-secret-DO-NOT-USE"),
            ),
        )
        val result = fixture.loop.run(
            "Sign in to the fixture bank demo account.",
            "restore-credential",
            identity,
            fixture.clock,
        )

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        // the card is presented for EVERY credential typing action (trust cannot supply
        // a secret) - so two restores, one per credential card; the trusted submit and
        // the finish restore nothing
        assertEquals(listOf(loginUrl, loginUrl), fired)
        // each restore preceded its injection gesture
        val restores = fixture.journal.entries.withIndex().filter { it.value.startsWith("restore:view(") }
        val typings = fixture.journal.entries.withIndex().filter { it.value.startsWith("text(") }
        assertEquals(2, restores.size)
        assertEquals(2, typings.size)
        assertTrue(restores[0].index < typings[0].index, "journal=${fixture.journal.entries}")
        assertTrue(restores[1].index < typings[1].index, "journal=${fixture.journal.entries}")
    }

    @Test
    fun `a URL bar without a page URL never fires the restore intent`() = runBlocking {
        val hiddenPage = obs(
            "hidden",
            N("Issue header"),
            N("Body text one"),
            N("Body text two"),
            N("Unsubscribe now", clickable = true, button = true),
            N("Footer"),
        )
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                chooseByLabel("action", "Unsubscribe now"),
            ),
            observations = listOf(hiddenPage),
            gatePresenter = FrontingGatePresenter(true, GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 1),
            ),
        )
        val result = fixture.loop.run("tidy the subscriptions", "restore-hidden", identity, fixture.clock)

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertTrue(fired.isEmpty(), "restore fired for a non-page URL: $fired")
        assertTrue(
            result.stepLog.any { it.contains("fronted the harness but the URL bar holds no page URL") },
            "stepLog=${result.stepLog}",
        )
    }

    // -- the URL preference chain (2026-10-08 S25+ defect-follower) -----------------------

    /** A usable page whose only control is an ordinary (never gated) link. */
    private fun linkPage() = obs(
        pageUrl,
        N("Issue header"),
        N("Body text one"),
        N("Body text two"),
        N("More results", clickable = true),
        N("Footer"),
    )

    /** The mutation page with the per-step URL-bar read EMPTY (the S25+ intermittency). */
    private fun hiddenListPage() = obs(
        "hidden",
        N("Issue header"),
        N("Body text one"),
        N("Body text two"),
        N("Unsubscribe now", clickable = true, button = true),
        N("Also unsubscribe", clickable = true, button = true),
        N("Footer"),
    )

    @Test
    fun `an empty per-step URL read still restores from the last-known page URL - source logged`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                // step 0: ordinary ungated tap on a page whose URL read fine
                chooseByLabel("action", "More results"),
                // step 1: the H9 card fires while the URL bar reads empty
                chooseByLabel("action", "Unsubscribe now"),
            ),
            observations = listOf(linkPage(), hiddenListPage()),
            gatePresenter = FrontingGatePresenter(true, GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 2),
            ),
        )
        val result = fixture.loop.run("tidy the subscriptions", "restore-last-known", identity, fixture.clock)

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        // the card's page URL read empty (the S25+ intermittency) but a PRIOR
        // observation carried the page URL — the restore fires with it
        assertEquals(listOf(pageUrl), fired)
        assertTrue(
            result.stepLog.any {
                it.contains("restore: the gate card fronted the harness (no-overlay path)") &&
                    it.contains(pageUrl) &&
                    it.contains("(last-known)")
            },
            "stepLog=${result.stepLog}",
        )
    }

    @Test
    fun `the chain prefers the card URL and the log names the serving source`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                chooseByLabel("action", "Unsubscribe now"),
            ),
            observations = listOf(listPage()),
            gatePresenter = FrontingGatePresenter(true, GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 1),
            ),
        )
        val result = fixture.loop.run("tidy the subscriptions", "restore-chain-card", identity, fixture.clock)

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        // card.url, obs.url and the last-known URL all hold the page URL here; the
        // served one must be the card's, and the log says so
        assertEquals(listOf(pageUrl), fired)
        assertTrue(
            result.stepLog.any {
                it.contains("restore: the gate card fronted the harness (no-overlay path)") &&
                    it.contains(pageUrl) &&
                    it.contains("(card)")
            },
            "stepLog=${result.stepLog}",
        )
        // the observation and last-known sources were never consulted
        assertTrue(
            result.stepLog.none { it.contains("(observation)") || it.contains("(last-known)") },
            "stepLog=${result.stepLog}",
        )
    }

    // -- the completion card --------------------------------------------------------------

    /** The exit-flow specimen pages: seed, then entity-bearing article pages. */
    private fun seedPage() = obs(
        "http://m0.test/search",
        N("search results page"),
        N("Tarte Tatin recipe"),
        N("More results"),
        N("Tarte Tatin Caramelised fruit tart", clickable = true),
        N("Footer"),
    )

    private fun articlePage(extra: String) = obs(
        "http://m0.test/tarte-tatin",
        N("Tarte Tatin"),
        N("The Tarte Tatin is a caramelised fruit tart"),
        N(extra),
        N("Related recipes", clickable = true),
        N("Footer"),
        N("Spacer", scrollable = true),
    )

    private fun pages(count: Int): List<StepObservation> =
        listOf(seedPage()) + (0 until count).map { i -> articlePage(extra = "Visit record $i") }

    /** The tap-then-finish-checks script (the ExitFlowLoopTest shape). */
    private fun seriesScript(checks: List<Double>): List<ScriptedAnswer> {
        val answers = mutableListOf<ScriptedAnswer>(
            choose("triage", "lookup"),
            chooseByLabel("action", "Tarte Tatin Caramelised fruit tart"),
        )
        checks.forEachIndexed { index, noulValue ->
            answers += choose("action", "finish")
            answers += noul(noulValue)
            if (index < checks.size - 1) {
                val down = index % 2 == 0
                answers += chooseByLabel("action", if (down) "Scroll down" else "Scroll up")
                answers += chooseByLabel("action", if (down) "Scroll up" else "Scroll down")
            }
        }
        return answers
    }

    @Test
    fun `the completion card keep-going restores the browser before the loop continues`() = runBlocking {
        val cards = FrontingCompletionPresenter(true, CompletionResolution.KEEP_GOING)
        val (fixture, fired) = frontingFixture(
            script = seriesScript(listOf(0.60, 0.30, 0.60, 0.60, 0.60)),
            observations = pages(40),
            gatePresenter = ScriptedGatePresenter(),
            completionCardPresenter = cards,
        )
        val result = fixture.loop.run("Open Wikipedia article Tarte Tatin", "restore-keepgoing", identity, fixture.clock)

        // the keep-going streak completes exactly as in ExitFlowLoopTest
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals(LoggedExitPath.SUSTAINED, fixture.sink.rows.last().exit?.path)
        assertEquals(1, cards.presented.size)
        // exactly one restore, carrying the CURRENT page URL (the article page, not the
        // seed the task started on)
        assertEquals(1, fired.size, "fired=$fired")
        assertTrue(fired[0].startsWith("http://m0.test/tarte-tatin"), "fired=$fired")
        assertTrue(
            result.stepLog.any {
                it.contains("restore: the completion card fronted the harness (no-overlay path)")
            },
            "stepLog=${result.stepLog}",
        )
    }

    @Test
    fun `the completion card timeout restores like keep going (fail-safe)`() = runBlocking {
        val cards = FrontingCompletionPresenter(true, CompletionResolution.TIMEOUT)
        val (fixture, fired) = frontingFixture(
            script = seriesScript(listOf(0.60, 0.30, 0.60, 0.60, 0.60)),
            observations = pages(40),
            gatePresenter = ScriptedGatePresenter(),
            completionCardPresenter = cards,
        )
        val result = fixture.loop.run("Open Wikipedia article Tarte Tatin", "restore-timeout", identity, fixture.clock)

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals("TIMEOUT", result.exit?.completionCardResolution)
        assertEquals(1, fired.size, "fired=$fired")
        assertTrue(fired[0].startsWith("http://m0.test/tarte-tatin"), "fired=$fired")
    }

    @Test
    fun `the completion card confirm-done never restores (the task is over)`() = runBlocking {
        val cards = FrontingCompletionPresenter(true, CompletionResolution.CONFIRM_DONE)
        val (fixture, fired) = frontingFixture(
            script = seriesScript(listOf(0.60, 0.30, 0.60)),
            observations = pages(40),
            gatePresenter = ScriptedGatePresenter(),
            completionCardPresenter = cards,
        )
        val result = fixture.loop.run("Open Wikipedia article Tarte Tatin", "restore-confirm", identity, fixture.clock)

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals("OWNER_CONFIRM", result.exit?.path)
        assertEquals(1, cards.presented.size)
        assertTrue(fired.isEmpty(), "restore fired after CONFIRM_DONE: $fired")
    }

    @Test
    fun `the completion card on the overlay path never restores`() = runBlocking {
        val cards = FrontingCompletionPresenter(false, CompletionResolution.KEEP_GOING)
        val (fixture, fired) = frontingFixture(
            script = seriesScript(listOf(0.60, 0.30, 0.60, 0.60, 0.60)),
            observations = pages(40),
            gatePresenter = ScriptedGatePresenter(),
            completionCardPresenter = cards,
        )
        val result = fixture.loop.run("Open Wikipedia article Tarte Tatin", "restore-comp-overlay", identity, fixture.clock)

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertTrue(fired.isEmpty(), "restore fired on the overlay path: $fired")
    }

    @Test
    fun `the completion card with no page URL anywhere never restores and keeps the loud line`() = runBlocking {
        // The specimen pages with the URL bar never yielding a page URL (each page a
        // distinct non-http read — the lookup guard's WEAK predicate needs navigation,
        // while the restore chain only accepts http(s)): every finish check refuses and
        // the card fires on the third guard-pass refusal — and no usable page URL was
        // ever known (both chain sources empty: observation + last-known).
        val hiddenSeed = obs(
            "hidden",
            N("search results page"),
            N("Tarte Tatin recipe"),
            N("More results"),
            N("Tarte Tatin Caramelised fruit tart", clickable = true),
            N("Footer"),
        )
        fun hiddenArticle(i: Int) = obs(
            "hidden-$i",
            N("Tarte Tatin"),
            N("The Tarte Tatin is a caramelised fruit tart"),
            N("Visit record $i"),
            N("Related recipes", clickable = true),
            N("Footer"),
            N("Spacer", scrollable = true),
        )
        val observations = listOf(hiddenSeed) + (0 until 40).map { i -> hiddenArticle(i) }
        val cards = FrontingCompletionPresenter(true, CompletionResolution.KEEP_GOING)
        val (fixture, fired) = frontingFixture(
            script = seriesScript(listOf(0.60, 0.60, 0.60, 0.60)),
            observations = observations,
            gatePresenter = ScriptedGatePresenter(),
            completionCardPresenter = cards,
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 11),
            ),
        )
        val result = fixture.loop.run("Open Wikipedia article Tarte Tatin", "restore-comp-nourl", identity, fixture.clock)

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertEquals(1, cards.presented.size)
        assertTrue(fired.isEmpty(), "restore fired with no page URL known: $fired")
        assertTrue(
            result.stepLog.any { it.contains("fronted the harness but the URL bar holds no page URL") },
            "stepLog=${result.stepLog}",
        )
    }
}
