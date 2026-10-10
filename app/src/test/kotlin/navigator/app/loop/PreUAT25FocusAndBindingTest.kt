package navigator.app.loop

import kotlinx.coroutines.runBlocking
import navigator.app.observe.BrowserPackages
import navigator.core.gate.ActionKind
import navigator.core.log.EngineIdentity
import navigator.core.log.Outcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 2026-10-08 pre-UAT2 RC1+RC2 JVM pins that are not classifier-matrix rows:
 *
 *  - **RC2 secret binding** — an `ApproveWithSecret` approval is bound to the candidate
 *    id its card was built for; the execute site verifies the acting candidate matches
 *    BEFORE any injection. A mismatch (or a missing binding) fails loudly and injects
 *    nothing (the wrong-TextSource precedent). The loop's single-pass wiring cannot
 *    diverge the bound and acting candidates naturally, so the pins go through the
 *    loop's own binding check ([NavigationLoop.requireSecretCandidateBinding]) — the
 *    same seam the happy-path credential tests exercise silently.
 *  - **RC1 failure-cause vocabulary** — a failed text entry renders its terminal cause
 *    on the execute step line: `-> FAILED (WRONG_WINDOW)`; a failure without a reported
 *    cause stays plain `-> FAILED` (byte-identical to the pre-RC1 line).
 *  - **RC1 executor window decision** — the pure package-gate function beside
 *    [BrowserPackages]: a browser root proceeds (null), anything else (the harness
 *    fronted after a no-overlay card, the launcher, no window) is WRONG_WINDOW. The
 *    bounded active-browser retry is executor-internal Android mechanics; its decision
 *    and its bounds are pinned here and documented on [GestureExecutor].
 */
class PreUAT25FocusAndBindingTest {

    // -- RC2: the secret is bound to the approved candidate --------------------------------

    @Test
    fun `secret bound to candidate A must not inject on candidate B - loud failure`() {
        val error = assertThrows<IllegalStateException> {
            NavigationLoop.requireSecretCandidateBinding(boundId = "n2", actingId = "n3")
        }
        assertTrue(
            error.message!!.contains("bound to candidate [n2]") &&
                error.message!!.contains("acting candidate is [n3]"),
            "the mismatch must name both sides: ${error.message}",
        )
    }

    @Test
    fun `a secret with no binding at all fails loudly - never an unbound injection`() {
        val error = assertThrows<IllegalStateException> {
            NavigationLoop.requireSecretCandidateBinding(boundId = null, actingId = "n2")
        }
        assertTrue(
            error.message!!.contains("unbound target"),
            "error.message=${error.message}",
        )
    }

    @Test
    fun `the bound candidate itself passes the binding check`() {
        NavigationLoop.requireSecretCandidateBinding(boundId = "n3", actingId = "n3")
    }

    // -- RC1: the execute line's failure-cause vocabulary -----------------------------------

    @Test
    fun `execute line renders the terminal cause on a failed text entry`() {
        assertEquals(
            "execute: type \"note\" at (50,150) -> FAILED (WRONG_WINDOW)",
            NavigationLoop.executeLine(
                kind = ActionKind.TYPE,
                label = "note",
                cx = 50,
                cy = 150,
                dispatched = false,
                typedMarker = "",
                failureCause = TextEntryFailure.WRONG_WINDOW,
            ),
        )
        assertEquals(
            "execute: type \"note\" at (50,150) -> FAILED (NO_FIELD_AT_POINT)",
            NavigationLoop.executeLine(ActionKind.TYPE, "note", 50, 150, false, "", failureCause = TextEntryFailure.NO_FIELD_AT_POINT),
        )
        // every vocabulary entry renders
        for (cause in TextEntryFailure.entries) {
            val line = NavigationLoop.executeLine(ActionKind.TYPE, "f", 1, 2, false, "", failureCause = cause)
            assertTrue(line.endsWith("FAILED (${cause.name})"), "line=$line")
        }
    }

    @Test
    fun `execute line stays byte-identical without a cause - dispatched and plain FAILED`() {
        assertEquals(
            "execute: tap \"plain label\" at (1,2) -> FAILED",
            NavigationLoop.executeLine(ActionKind.TAP, "plain label", 1, 2, dispatched = false, typedMarker = ""),
        )
        assertEquals(
            "execute: tap \"plain label\" at (1,2) -> dispatched",
            NavigationLoop.executeLine(ActionKind.TAP, "plain label", 1, 2, dispatched = true, typedMarker = ""),
        )
    }

    @Test
    fun `a failed injection logs its cause end-to-end in the real loop`() = runBlocking {
        // A plain (ungated) typing step whose executor reports WRONG_WINDOW — the
        // live-proven shape: the harness was still the active window after a no-overlay
        // card, so no editable existed at the page-field coordinates.
        val page = obs(
            "http://m0.test/note",
            N("Note page header"),
            N("Body text one"),
            N("Body text two"),
            N("Note", editable = true),
            N("Footer"),
        )
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "form-fill"),
                *intentStep("fill-field", target = "Note"),
            ),
            phraseSource = navigator.app.stub.StubPhraseSource,
            observations = listOf(page),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 1)),
        )
        fixture.executor.failNextTextWith(TextEntryFailure.WRONG_WINDOW)
        val result = fixture.loop.run("Type a note.", "preuat25-cause", identity, fixture.clock)

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        val executeLine = result.stepLog.single { it.contains("execute: type") }
        assertTrue(
            executeLine.contains("-> FAILED (WRONG_WINDOW)"),
            "the cause must ride the execute line: $executeLine",
        )
        // the failed step's row: outcome ERROR, and the tracker/guard machinery saw a
        // failed dispatch (the typed flag stays false)
        assertEquals(Outcome.ERROR, fixture.sink.rows[0].outcome)
        assertFalse(fixture.sink.rows[0].typedSecret)
    }

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    // -- RC2 end-to-end in the real loop -----------------------------------------------------

    /**
     * THE live defect shape, end-to-end under the cascade: the OMNIBOX (a
     * browser-chrome editable whose label echoes the login URL) on a login page carrying
     * an identifier+flagged-secret pair. The RC2 law now has TWO layers in the loop:
     * (1) STRUCTURAL — a browser-chrome candidate is never a stage-2 target (the slate
     * builder excludes it), so a scripted gold on the omnibox fails the stage-2 loudly;
     * (2) the GATE law itself stays pinned at the classifier level (:core's
     * RiskGateTargetScopingTest). Typing into the identifier member of the live pair
     * still gates H4 (auto-deny denies it — no secret can be injected).
     */
    @Test
    fun `the omnibox is never a cascade target and the pair's identifier typing still gates H4`() = runBlocking {
        val loginUrl = "http://127.0.0.1:8099/login.html"
        val page = obs(
            loginUrl,
            N(loginUrl.removePrefix("http://"), editable = true, browserChrome = true), // the omnibox
            N("Email or username", editable = true),
            N("correct-horse-battery-9", editable = true, password = true), // the flagged secret
            N("Submit sign in", clickable = true, button = true),
            N("Fixture Bank Dummy Login (TEST ONLY)"),
            N("No real credentials are accepted here."),
            pageNodes = 8,
        )
        // (1) the structural exclusion: the omnibox (n0) is not on ANY stage-2 slate —
        // a scripted gold on it fails the batch loudly, never taps or types
        val exclusion = LoopFixture(
            script = scriptOf(
                choose("triage", "form-fill"),
                *intentStep("fill-field", target = "n0"), // the omnibox: NOT a lawful target
            ),
            observations = listOf(page),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 1)),
        )
        val error = assertThrows<IllegalStateException> {
            exclusion.loop.run("Sign in to the fixture bank demo account.", "preuat25-omnibox", identity, exclusion.clock)
        }
        assertTrue(
            error.message?.contains("n0") == true && error.message?.contains("no candidate matches") == true,
            "the omnibox must not be a stage-2 target: ${error.message}",
        )
        assertTrue(exclusion.executor.calls.isEmpty(), "nothing was typed or tapped at the omnibox")

        // (2) typing into the identifier member of the live pair still gates H4
        // (auto-deny denies it — the credential card path is intact for real fields)
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "form-fill"),
                *intentStep("fill-field", target = "Email or username"),
                *intentStep("verify-done"),
                noul(0.95),
            ),
            observations = listOf(page),
            presenter = ScriptedGatePresenter(GateResolution.DENIED), // the H4 card
            config = NavigationLoop.Config(
                settleMs = 0,
                unusableRetryDelayMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 2),
            ),
        )
        val result = fixture.loop.run(
            "Sign in to the fixture bank demo account.",
            "preuat25-omnibox",
            identity,
            fixture.clock,
        )
        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        val emailRow = fixture.sink.rows[0]
        assertEquals(navigator.core.log.LoggedHClass.H4, emailRow.gateEvent?.hclass)
        assertEquals(navigator.core.log.LoggedGateDecision.DENIED, emailRow.gateEvent?.decision)
        assertFalse(emailRow.typedSecret)
        assertTrue(
            fixture.executor.calls.none { it.contains("fixture-secret-DO-NOT-USE") },
            "no secret may be injected: ${fixture.executor.calls}",
        )
    }

    // -- RC1: the pure executor window decision ----------------------------------------------

    @Test
    fun `browser root proceeds - harness or absent root is WRONG_WINDOW`() {
        assertNull(textEntryWindowCause("com.android.chrome"))
        assertNull(textEntryWindowCause("org.chromium.webview_shell"))
        for (notABrowser in listOf("navigator.app", "com.android.launcher", "", null, "android")) {
            assertEquals(
                TextEntryFailure.WRONG_WINDOW,
                textEntryWindowCause(notABrowser),
                "package '$notABrowser' must gate text entry with WRONG_WINDOW",
            )
        }
    }

    @Test
    fun `the browser package allowlist is pinned`() {
        assertEquals(setOf("com.android.chrome", "org.chromium.webview_shell"), BrowserPackages.ALLOWLIST)
        assertTrue(BrowserPackages.isBrowser("com.android.chrome"))
        assertTrue(BrowserPackages.isBrowser("org.chromium.webview_shell"))
        assertEquals(BrowserPackages.isBrowser("navigator.app"), false)
        assertEquals(BrowserPackages.isBrowser(null), false)
    }
}
