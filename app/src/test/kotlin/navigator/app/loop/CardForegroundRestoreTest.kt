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
 * The owner-screenshot defect-follower (same defect class): mobile Chrome OMITS the
 * scheme, so the card displayed "127.0.0.1:8099/subscriptions.html" while the restore's
 * old http(s)-prefix guard rejected that exact URL ("no VIEW intent") — the JVM tests
 * passed because they used fully-schemed URLs. Every chain candidate now normalizes
 * ([NavigationLoop.normalizePageUrl]): schemed URLs pass; scheme-less host/path text
 * gets http:// for the loopback hosts, https:// otherwise; garbage (about:blank,
 * hidden, empty) never serves. Scheme-less bar reads are also tracked as last-known.
 *
 * The S25+ readiness defect-follower #3 (live-proven on the credential-card flow): the
 * fixed settle expired before Chrome was front+interactive on Samsung OneUI (the
 * activity resume + VIEW-intent page restore take longer), so the focus-first tap
 * landed wrong and the run starved to REFUSED. After the intent the loop now WAITS for
 * observation readiness — bounded re-reads on the unusable-tree cadence until a read
 * passes the usable-tree bar ("restore: browser ready after Nms"); on budget expiry it
 * proceeds anyway ("browser not readable within budget - continuing" — the next
 * observe's starvation machinery owns a still-dark page). The wait rides BOTH card
 * paths and is wall-clock within the same step: probes consume no budget and no steps.
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
                *intentStep("activate-control", target = "Unsubscribe now"),
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
                *intentStep("activate-control", target = "Unsubscribe now"),
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
                *intentStep("activate-control", target = "Unsubscribe now"),
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
                *intentStep("activate-control", target = "Unsubscribe now"),
                *intentStep("activate-control", target = "Unsubscribe now"),
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
                *intentStep("activate-control", target = "Unsubscribe now"),
                *intentStep("activate-control", target = "Also unsubscribe"),
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
                *intentStep("fill-field", target = "Email or username"),
                *intentStep("fill-field", target = "Password"),
                *intentStep("submit", target = "Submit sign in"),
                *intentStep("verify-done"),
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
                *intentStep("activate-control", target = "Unsubscribe now"),
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
                *intentStep("follow-link", target = "More results"),
                // step 1: the H9 card fires while the URL bar reads empty
                *intentStep("activate-control", target = "Unsubscribe now"),
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
                *intentStep("activate-control", target = "Unsubscribe now"),
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

    // -- scheme-less URL-bar text (2026-10-08 owner-screenshot defect-follower) -----------
    // Mobile Chrome OMITS the scheme: the bar (and hence GateCard.url) reads
    // "127.0.0.1:8099/subscriptions.html" — the old http(s)-prefix guard rejected that
    // exact URL ("no VIEW intent") even as the card displayed it. Scheme-less text now
    // normalizes at the restore: loopback -> http, any other dotted host -> https.

    /** The mutation page with a parametrized (e.g. scheme-less) URL-bar read. */
    private fun schemelessListPage(url: String) = obs(
        url,
        N("Issue header"),
        N("Body text one"),
        N("Body text two"),
        N("Unsubscribe now", clickable = true, button = true),
        N("Also unsubscribe", clickable = true, button = true),
        N("Footer"),
    )

    /** An ordinary (never gated) link page with a parametrized URL-bar read. */
    private fun schemelessLinkPage(url: String) = obs(
        url,
        N("Issue header"),
        N("Body text one"),
        N("Body text two"),
        N("More results", clickable = true),
        N("Footer"),
    )

    @Test
    fun `a scheme-less loopback card URL normalizes to http and fires the restore`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("activate-control", target = "Unsubscribe now"),
            ),
            observations = listOf(schemelessListPage("127.0.0.1:8099/subscriptions.html")),
            gatePresenter = FrontingGatePresenter(true, GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 1),
            ),
        )
        val result = fixture.loop.run(
            "tidy the subscriptions",
            "restore-schemeless-loopback",
            identity,
            fixture.clock,
        )

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        // the owner-screenshot card URL — scheme-less — now fires, loopback -> http
        assertEquals(listOf("http://127.0.0.1:8099/subscriptions.html"), fired)
        assertTrue(
            result.stepLog.any {
                it.contains("restore: the gate card fronted the harness (no-overlay path)") &&
                    it.contains("http://127.0.0.1:8099/subscriptions.html") &&
                    it.contains("(normalized)") &&
                    it.contains("(card)")
            },
            "stepLog=${result.stepLog}",
        )
    }

    @Test
    fun `a scheme-less non-loopback card URL normalizes to https and fires the restore`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("activate-control", target = "Unsubscribe now"),
            ),
            observations = listOf(schemelessListPage("example.test/wiki/X")),
            gatePresenter = FrontingGatePresenter(true, GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 1),
            ),
        )
        val result = fixture.loop.run(
            "tidy the subscriptions",
            "restore-schemeless-https",
            identity,
            fixture.clock,
        )

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertEquals(listOf("https://example.test/wiki/X"), fired)
        assertTrue(
            result.stepLog.any {
                it.contains("VIEW intent for https://example.test/wiki/X (normalized) (card)")
            },
            "stepLog=${result.stepLog}",
        )
    }

    @Test
    fun `a scheme-less bar read is tracked as last-known and serves when the card URL is empty`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                // step 0: ordinary ungated tap on a page whose bar read is SCHEME-LESS
                *intentStep("follow-link", target = "More results"),
                // step 1: the H9 card fires while the URL bar reads empty
                *intentStep("activate-control", target = "Unsubscribe now"),
            ),
            observations = listOf(
                schemelessLinkPage("example.test/list"),
                hiddenListPage(),
            ),
            gatePresenter = FrontingGatePresenter(true, GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 2),
            ),
        )
        val result = fixture.loop.run(
            "tidy the subscriptions",
            "restore-schemeless-lastknown",
            identity,
            fixture.clock,
        )

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        // the scheme-less step-0 read is now tracked (normalized to https) and serves on
        // the card step's empty read
        assertEquals(listOf("https://example.test/list"), fired)
        assertTrue(
            result.stepLog.any {
                it.contains("VIEW intent for https://example.test/list (last-known)")
            },
            "stepLog=${result.stepLog}",
        )
    }

    @Test
    fun `garbage bar reads (about_blank, hidden, empty) still never serve the restore`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                // step 0: ordinary tap on a page whose bar read is EMPTY (never tracked)
                *intentStep("follow-link", target = "More results"),
                // step 1: the H9 card fires while the bar reads "about:blank"
                *intentStep("activate-control", target = "Unsubscribe now"),
            ),
            observations = listOf(
                schemelessLinkPage(""),
                schemelessListPage("about:blank"),
            ),
            gatePresenter = FrontingGatePresenter(true, GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 2),
            ),
        )
        val result = fixture.loop.run(
            "tidy the subscriptions",
            "restore-garbage-urls",
            identity,
            fixture.clock,
        )

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        // about:blank and empty normalize to nothing, nothing was ever tracked, and the
        // "hidden" sentinel is pinned by the earlier no-page-URL test — no restore
        assertTrue(fired.isEmpty(), "restore fired for garbage bar reads: $fired")
        assertTrue(
            result.stepLog.any { it.contains("fronted the harness but the URL bar holds no page URL") },
            "stepLog=${result.stepLog}",
        )
    }

    // -- restore readiness (2026-10-08 S25+ defect-follower #3) ---------------------------
    // The fixed settle expired before Chrome was front+interactive on Samsung OneUI, so
    // the first gesture landed wrong. After the VIEW intent the loop re-reads until a
    // probe passes the usable-tree bar (or the budget expires, then it proceeds anyway).

    /** A Chrome-still-re-fronting read: two toolbar lines, far below the usable bar. */
    private fun darkProbe() = obs("hidden", N("Chrome toolbar"), N("Tab strip"))

    @Test
    fun `after the restore intent the loop waits for a usable read before the execute gesture`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("activate-control", target = "Unsubscribe now"),
            ),
            // the step's page, then the restore's first probe lands DARK (still
            // re-fronting), then the restored page reads usable
            observations = listOf(listPage(), darkProbe(), listPage()),
            gatePresenter = FrontingGatePresenter(true, GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                unusableRetryDelayMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 1),
            ),
        )
        val result = fixture.loop.run("tidy the subscriptions", "restore-ready", identity, fixture.clock)

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertEquals(listOf(pageUrl), fired)
        // the wait probed through the dark first read and only continued once the
        // restored page read usable: exactly two observes between the VIEW intent and
        // the gesture — no execute until a usable read
        val entries = fixture.journal.entries
        val restoreIdx = entries.indexOfFirst { it == "restore:view($pageUrl)" }
        val tapIdx = entries.indexOfFirst { it.startsWith("tap(") }
        assertTrue(restoreIdx in 0 until tapIdx, "journal=$entries")
        assertEquals(
            2,
            entries.subList(restoreIdx + 1, tapIdx).count { it == "observe" },
            "journal=$entries",
        )
        assertTrue(
            result.stepLog.any { it.contains("restore: browser ready after") },
            "stepLog=${result.stepLog}",
        )
        assertTrue(
            result.stepLog.any { it.contains("execute: tap") && it.contains("-> dispatched") },
            "stepLog=${result.stepLog}",
        )
    }

    @Test
    fun `readiness timeout proceeds - the execute still happens after an unreadable restore`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("activate-control", target = "Unsubscribe now"),
            ),
            observations = listOf(listPage(), darkProbe()),
            gatePresenter = FrontingGatePresenter(true, GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                restoreReadinessMs = 0, // no retry budget: one probe, then proceed
                budget = NavigationLoop.BudgetLimits(stepsHard = 1),
            ),
        )
        val result = fixture.loop.run("tidy the subscriptions", "restore-ready-timeout", identity, fixture.clock)

        // expiry is not a failure mode: the step ran to its ordinary budget stop
        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertEquals(listOf(pageUrl), fired)
        // exactly one probe between the VIEW intent and the gesture - no hang, no retry loop
        val entries = fixture.journal.entries
        val restoreIdx = entries.indexOfFirst { it == "restore:view($pageUrl)" }
        val tapIdx = entries.indexOfFirst { it.startsWith("tap(") }
        assertTrue(restoreIdx in 0 until tapIdx, "journal=$entries")
        assertEquals(
            1,
            entries.subList(restoreIdx + 1, tapIdx).count { it == "observe" },
            "journal=$entries",
        )
        assertTrue(
            result.stepLog.any {
                it.contains("restore: browser not readable within budget - continuing")
            },
            "stepLog=${result.stepLog}",
        )
        assertTrue(
            result.stepLog.any { it.contains("execute: tap") && it.contains("-> dispatched") },
            "stepLog=${result.stepLog}",
        )
    }

    // -- restore readiness is FOCUS-gated (2026-10-08 pre-UAT2 RC1 follower) ----------------
    // The RC1 live defect: Chrome's tree stays READABLE while Chrome sits in the
    // background — the readability-only probe passed ("browser ready after 24ms") and
    // the injection 13ms later found no focused field (bare `false`). Readiness now
    // ALSO requires the observation's windowPackage (the ACTIVE window) to be an
    // allowlisted browser; the fakes pass it additively ([obs]'s windowPackage).

    /** A READABLE page while the active window is NOT the browser (harness fronted). */
    private fun backgroundedProbe() = obs(
        "http://m0.test/list",
        N("Issue header"),
        N("Body text one"),
        N("Body text two"),
        N("Unsubscribe now", clickable = true, button = true),
        N("Also unsubscribe", clickable = true, button = true),
        N("Footer"),
        windowPackage = "navigator.app",
    )

    @Test
    fun `a readable-but-wrong-package probe is NOT ready - the wait spins until the browser is active`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("activate-control", target = "Unsubscribe now"),
            ),
            // step page, then TWO readable-but-backgrounded probes, then the restored
            // browser page (right package) — readiness passes only on the third probe
            observations = listOf(listPage(), backgroundedProbe(), backgroundedProbe(), listPage()),
            gatePresenter = FrontingGatePresenter(true, GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                unusableRetryDelayMs = 0,
                restoreReadinessMs = 3, // up to 3 retries: the probes are bounded, not hung
                budget = NavigationLoop.BudgetLimits(stepsHard = 1),
            ),
        )
        val result = fixture.loop.run("tidy the subscriptions", "restore-ready-focus", identity, fixture.clock)

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertEquals(listOf(pageUrl), fired)
        val entries = fixture.journal.entries
        val restoreIdx = entries.indexOfFirst { it == "restore:view($pageUrl)" }
        val tapIdx = entries.indexOfFirst { it.startsWith("tap(") }
        assertTrue(restoreIdx in 0 until tapIdx, "journal=$entries")
        // exactly three probes between the VIEW intent and the gesture: two NOT ready
        // (readable, wrong package) and the ready one
        assertEquals(
            3,
            entries.subList(restoreIdx + 1, tapIdx).count { it == "observe" },
            "journal=$entries",
        )
        assertTrue(
            result.stepLog.any {
                it.contains("restore: browser ready after") && it.contains("(window=com.android.chrome)")
            },
            "the ready line must name the validated package: ${result.stepLog}",
        )
        assertTrue(
            result.stepLog.any { it.contains("execute: tap") && it.contains("-> dispatched") },
            "stepLog=${result.stepLog}",
        )
    }

    @Test
    fun `a never-right-package probe times out bounded and the execute still proceeds`() = runBlocking {
        val (fixture, fired) = frontingFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("activate-control", target = "Unsubscribe now"),
            ),
            // every probe is readable but the harness stays the active window
            observations = listOf(listPage(), backgroundedProbe()),
            gatePresenter = FrontingGatePresenter(true, GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                restoreSettleMs = 0,
                unusableRetryDelayMs = 0,
                restoreReadinessMs = 2, // one initial probe + 2 retries, then expiry proceeds
                budget = NavigationLoop.BudgetLimits(stepsHard = 1),
            ),
        )
        val result = fixture.loop.run("tidy the subscriptions", "restore-ready-wrongpkg", identity, fixture.clock)

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertEquals(listOf(pageUrl), fired)
        val entries = fixture.journal.entries
        val restoreIdx = entries.indexOfFirst { it == "restore:view($pageUrl)" }
        val tapIdx = entries.indexOfFirst { it.startsWith("tap(") }
        assertTrue(restoreIdx in 0 until tapIdx, "journal=$entries")
        // bounded: one probe + exactly 2 retries (restoreReadinessMs / cadence) - no hang
        assertEquals(
            3,
            entries.subList(restoreIdx + 1, tapIdx).count { it == "observe" },
            "journal=$entries",
        )
        assertTrue(
            result.stepLog.any {
                it.contains("restore: browser not readable within budget - continuing") &&
                    it.contains("(last window=navigator.app)")
            },
            "the timeout line must name the last-seen package: ${result.stepLog}",
        )
        assertTrue(
            result.stepLog.any { it.contains("execute: tap") && it.contains("-> dispatched") },
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
            *intentStep("follow-link", target = "Tarte Tatin Caramelised fruit tart"),
        )
        checks.forEachIndexed { index, noulValue ->
            answers += intent("verify-done")
            answers += noul(noulValue)
            if (index < checks.size - 1) {
                val down = index % 2 == 0
                answers += intent("scroll")
                answers += intent("scroll")
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

    @Test
    fun `the completion-card restore also waits for observation readiness before the loop continues`() = runBlocking {
        val cards = FrontingCompletionPresenter(true, CompletionResolution.KEEP_GOING)
        val (fixture, fired) = frontingFixture(
            script = seriesScript(listOf(0.60, 0.30, 0.60, 0.60, 0.60)),
            observations = pages(40),
            gatePresenter = ScriptedGatePresenter(),
            completionCardPresenter = cards,
        )
        val result = fixture.loop.run("Open Wikipedia article Tarte Tatin", "restore-comp-ready", identity, fixture.clock)

        // the keep-going streak completes exactly as without the wait
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals(LoggedExitPath.SUSTAINED, fixture.sink.rows.last().exit?.path)
        assertEquals(1, fired.size, "fired=$fired")
        // the readiness probe is the VERY next read after the restore intent (the wait
        // rides the completion-card path too), and the outcome line is logged
        val restoreIdx = fixture.journal.entries.indexOfFirst { it.startsWith("restore:view(") }
        assertEquals("observe", fixture.journal.entries[restoreIdx + 1], "journal=${fixture.journal.entries}")
        assertTrue(
            result.stepLog.any { it.contains("restore: browser ready after") },
            "stepLog=${result.stepLog}",
        )
    }
}
