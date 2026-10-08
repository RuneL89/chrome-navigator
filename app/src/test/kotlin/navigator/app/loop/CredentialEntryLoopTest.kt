package navigator.app.loop

import kotlinx.coroutines.runBlocking
import navigator.core.gate.GateCard
import navigator.core.log.ActionDisposition
import navigator.core.log.DecisionLogRow
import navigator.core.log.DECISION_LOG_JSON
import navigator.core.log.EngineIdentity
import navigator.core.log.ExitCheckRow
import navigator.core.log.LoggedGateDecision
import navigator.core.log.LoggedHClass
import navigator.core.log.TriageClass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gates 8F.1–8F.3 — the credential-entry law wired into the loop (vision 06 §2a, doc 05
 * §2 `typedSecret`, Phase 08F):
 *  - **8F.1** source selection + injection: a credential-typing action (H3/H4/H8 on a
 *    TYPE action) takes its text from the card approval ([TextSource.GateSuppliedSecret])
 *    and injects it straight into the target field — the stub phrase never executes
 *    there; wrong-source in EITHER direction (a plain approve where a secret is owed, a
 *    secret where a phrase is expected) fails loudly, never falls through;
 *  - **8F.2** SECRET-ABSENCE: after a scripted credential run, the fixture secret and
 *    user strings appear NOWHERE in the step log, the decision rows, the exit-check
 *    rows, or the report strings — asserted here by exhaustive grep — while
 *    `[credential-redacted]` and `typedSecret=true` appear exactly where the law says;
 *    the state-text builder's loud rejection is proven end-to-end (a page-side echo of
 *    the typed value kills the run with a loud error);
 *  - **8F.3** card flow: approve-with-secret / deny / timeout all resolve through the
 *    TaskGate's legal transitions (double-refusal abort intact); the masked-input card
 *    is presented for every credential-typing action (trust cannot supply a secret);
 *    non-credential cards are presented without it.
 *  - **2026-10-08 pre-UAT2 H8-gap follower**: the reader-captured `isPassword` flag —
 *    live Chrome (154) exposes secret fields as plain EditText carrying the typed
 *    content with no label association, so the wording/mask heuristics alone left H8/H3
 *    dark (the stub phrase was typed five times, no card). A reader-FLAGGED wording-free
 *    field fires the H8 credential card; the live login shape gates the H4 pair.
 *
 * $0 by construction: the engine is the scripted stub; the secrets are obviously-dummy
 * fixture strings.
 */
class CredentialEntryLoopTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    /** Obviously-dummy fixture credentials — never real-looking. */
    private val fixtureUser = "fixture-user-DO-NOT-USE"
    private val fixtureSecret = "fixture-secret-DO-NOT-USE"

    /** The login-page shape (mirrors assets/harness/pages/login.html). */
    private fun loginPage() = obs(
        "http://127.0.0.1:8099/login.html",
        N("Fixture Bank Dummy Login (TEST ONLY)"),
        N("TEST FIXTURE. No real credentials are accepted here."),
        N("Email or username", editable = true),
        N("Password", editable = true),
        N("Submit sign in", clickable = true, button = true),
        N("If you were signed in you would see the result page."),
        pageNodes = 8,
        title = "Fixture Bank Dummy Login (TEST ONLY)",
    )

    /** The same page with the typed username echoed back into the field's a11y text. */
    private fun loginPageEchoingUser() = obs(
        "http://127.0.0.1:8099/login.html",
        N("Fixture Bank Dummy Login (TEST ONLY)"),
        N("TEST FIXTURE. No real credentials are accepted here."),
        N(fixtureUser, editable = true),
        N("Password", editable = true),
        N("Submit sign in", clickable = true, button = true),
        N("If you were signed in you would see the result page."),
        pageNodes = 8,
        title = "Fixture Bank Dummy Login (TEST ONLY)",
    )

    private suspend fun LoopFixture.run(): RunResult =
        loop.run("Sign in to the fixture bank demo account.", "goal-8f", identity, clock)

    /**
     * The 2026-10-08 pre-UAT2 LIVE-DEFECT shape (S25+, Chrome 154): the login page's
     * password field is a plain EditText whose a11y text is the TYPED CONTENT (not the
     * label — the `<label>` never associates with the field) — pre-fix this page typed
     * the stub phrase five times with no gate. Post-fix the reader-captured
     * `isPassword` flag is what the gate sees.
     */
    private fun liveChromeLoginPage() = obs(
        "http://127.0.0.1:8099/login.html",
        N("Fixture Bank Dummy Login (TEST ONLY)"),
        N("TEST FIXTURE. No real credentials are accepted here."),
        N("Email or username", editable = true),
        N("correct-horse-battery-9", editable = true, password = true),
        N("Submit sign in", clickable = true, button = true),
        N("If you were signed in you would see the result page."),
        pageNodes = 7,
        title = "Fixture Bank Dummy Login (TEST ONLY)",
    )

    /** The same shape with EVERY field wording-free — only the flag marks the secret. */
    private fun wordingFreeFlaggedPage() = obs(
        "http://127.0.0.1:8099/flagged.html",
        N("Flagged fields (TEST ONLY)"),
        N("TEST FIXTURE. Two fields, one reader-flagged."),
        N("Field one", editable = true),
        N("Field two", editable = true, password = true),
        N("Go", clickable = true, button = true),
        N("End of the fixture page."),
        pageNodes = 7,
        title = "Flagged fields (TEST ONLY)",
    )

    /** The scripted form-fill transcript through username -> password -> submit -> finish. */
    private fun formFillScript(vararg actionAnswers: navigator.app.stub.ScriptedAnswer) = scriptOf(
        choose("triage", "form-fill"),
        *actionAnswers,
        choose("action", "finish"),
        noul(0.95),
    )

    /** Label selector for the action question (the scripted form steps). */
    private fun type(label: String) = chooseByLabel("action", label)

    // -- Gate 8F.1: source selection + injection ---------------------------------------

    @Test
    fun `credential typing injects the card secret and never the stub phrase`() = runBlocking {
        val cards = mutableListOf<GateCard>()
        val presenter = GatePresenter { card ->
            cards.add(card)
            if (card.target.contains("Email")) {
                GateResolution.ApproveWithSecret(fixtureUser)
            } else {
                GateResolution.ApproveWithSecret(fixtureSecret)
            }
        }
        val fixture = LoopFixture(
            script = formFillScript(
                type("Email or username"),
                type("Password"),
                type("Submit sign in"),
            ),
            observations = listOf(loginPage()),
            presenter = presenter,
        )
        val result = fixture.run()

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        // Both typing actions executed, with the CARD values injected directly.
        val typed = fixture.executor.calls.filter { it.startsWith("text(") }
        assertEquals(2, typed.size, "typed=$typed")
        assertTrue(typed.any { it.contains(fixtureUser) }, "typed=$typed")
        assertTrue(typed.any { it.contains(fixtureSecret) }, "typed=$typed")
        // The stub phrase (the goal text the pre-08F path would have typed) NEVER runs.
        val stubPhrase = phraseStub("Sign in to the fixture bank demo account.", "Email or username")
        assertTrue(
            typed.none { it.contains(stubPhrase) },
            "stub phrase executed on the credential path: $typed",
        )
        // The masked-input variant rode BOTH credential cards, never the submit card.
        assertTrue(cards.size == 2, "only the two typing cards are presented (submit rides trust): $cards")
        assertTrue(cards[0].credentialTyping, "username card must carry the credential variant")
        assertTrue(cards[1].credentialTyping, "password card must carry the credential variant")
        // H4 gated every step (two typings + the submission); trust followed the first approval.
        assertEquals(LoggedHClass.H4, fixture.sink.rows[0].gateEvent?.hclass)
        assertEquals(LoggedHClass.H4, fixture.sink.rows[1].gateEvent?.hclass)
        assertEquals(LoggedHClass.H4, fixture.sink.rows[2].gateEvent?.hclass)
        assertEquals(false, fixture.sink.rows[0].gateEvent?.trustedFromTask)
        assertEquals(true, fixture.sink.rows[1].gateEvent?.trustedFromTask)
        assertEquals(true, fixture.sink.rows[2].gateEvent?.trustedFromTask)
        assertEquals(3, result.gateEvents)
    }

    @Test
    fun `credential card approved WITHOUT a secret fails loudly - never falls through`() = runBlocking {
        val fixture = LoopFixture(
            script = formFillScript(type("Email or username")),
            observations = listOf(loginPage()),
            presenter = ScriptedGatePresenter(GateResolution.APPROVED),
        )
        val error = assertThrows<IllegalStateException> { fixture.run() }
        assertTrue(
            error.message!!.contains("WITHOUT the card-supplied secret"),
            "wrong-source must name the failure: ${error.message}",
        )
        assertTrue(fixture.executor.calls.none { it.startsWith("text(") }, "nothing may execute")
    }

    // -- the 2026-10-08 pre-UAT2 H8-gap fix: the reader flag gates where wording cannot --

    @Test
    fun `typing into a reader-FLAGGED wording-free field fires the H8 credential card and the happy path completes`() =
        runBlocking {
            val fixture = LoopFixture(
                script = formFillScript(
                    type("Field one"), // unflagged, no identifier wording -> ordinary typing
                    type("Field two"), // flagged -> H8 credential typing
                    type("Go"), // lone secret form submission -> H3
                ),
                observations = listOf(wordingFreeFlaggedPage()),
                presenter = ScriptedGatePresenter(
                    GateResolution.ApproveWithSecret(fixtureSecret),
                    GateResolution.APPROVED,
                ),
            )
            val result = fixture.run()

            assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
            // Precision: the unflagged field typed the stub phrase UNGATED (no card).
            val stubPhrase = phraseStub("Sign in to the fixture bank demo account.", "Field one")
            assertEquals(null, fixture.sink.rows[0].gateEvent, "no gate on the unflagged field")
            // The flagged field: H8, the masked-input card, the CARD secret injected —
            // never the stub phrase — and the state text marks the field `edit password`.
            assertTrue(fixture.sink.rows[1].gateEvent!!.hclass == LoggedHClass.H8)
            assertTrue(fixture.sink.rows[1].typedSecret)
            assertTrue("[n3] \"Field two\" edit password" in fixture.sink.rows[1].stateText)
            // Exactly two typing calls: the phrase into the unflagged field, the card
            // secret into the flagged one (a fall-through to the phrase is a loud
            // failure the loop enforces; the call journal pins it here too).
            val typed = fixture.executor.calls.filter { it.startsWith("text(") }
            assertEquals(2, typed.size, "typed=$typed")
            assertTrue(typed.any { it.contains(stubPhrase) }, "typed=$typed")
            assertTrue(typed.any { it.contains(fixtureSecret) }, "typed=$typed")
            // The submission over a lone flagged secret gates H3 on the ordinary card.
            assertTrue(fixture.sink.rows[2].gateEvent!!.hclass == LoggedHClass.H3)
            assertFalse(fixture.sink.rows[2].typedSecret)
        }

    @Test
    fun `the live Chrome login shape - the flagged wording-free password field gates the pair H4`() =
        runBlocking {
            val cards = mutableListOf<GateCard>()
            val presenter = GatePresenter { card ->
                cards.add(card)
                if (card.target.contains("Email")) {
                    GateResolution.ApproveWithSecret(fixtureUser)
                } else {
                    GateResolution.ApproveWithSecret(fixtureSecret)
                }
            }
            val fixture = LoopFixture(
                script = formFillScript(
                    type("Email or username"),
                    type("correct-horse-battery-9"), // the flagged field, label = typed content
                    type("Submit sign in"),
                ),
                observations = listOf(liveChromeLoginPage()),
                presenter = presenter,
            )
            val result = fixture.run()

            assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
            // The flag (not wording) made the pair: H4 gates entry AND submission.
            assertEquals(LoggedHClass.H4, fixture.sink.rows[0].gateEvent?.hclass)
            assertEquals(LoggedHClass.H4, fixture.sink.rows[1].gateEvent?.hclass)
            assertEquals(LoggedHClass.H4, fixture.sink.rows[2].gateEvent?.hclass)
            // Both typing cards are credential cards; the secret is injected.
            assertEquals(2, cards.size, "only the two typing cards: $cards")
            assertTrue(cards[0].credentialTyping)
            assertTrue(cards[1].credentialTyping)
            assertTrue(fixture.sink.rows[1].typedSecret)
            assertTrue(fixture.executor.calls.any { it.contains(fixtureSecret) })
            // The flagged field's state-text line carries the app-authored marker.
            assertTrue(
                "[n3] \"correct-horse-battery-9\" edit password" in fixture.sink.rows[1].stateText,
                fixture.sink.rows[1].stateText,
            )
        }

    @Test
    fun `a secret on a non-credential card fails loudly - never falls through`() = runBlocking {
        // The Buy-now page classifies H2 (purchase) on a TAP — a phrase action, no secret.
        val page = obs(
            "http://m0.test/buy",
            N("An item page"),
            N("Some filler text"),
            N("More filler text"),
            N("Buy now", clickable = true, button = true),
            N("About", clickable = true),
            N("Footer"),
        )
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                chooseByLabel("action", "Buy now"),
            ),
            observations = listOf(page),
            presenter = ScriptedGatePresenter(GateResolution.ApproveWithSecret(fixtureSecret)),
        )
        val error = assertThrows<IllegalStateException> { fixture.run() }
        assertTrue(
            error.message!!.contains("secret was offered where the TextSource expects a phrase"),
            "wrong-source must name the failure: ${error.message}",
        )
        assertTrue(fixture.executor.calls.isEmpty(), "nothing may execute")
    }

    // -- Gate 8F.2: SECRET-ABSENCE (the core gate) --------------------------------------

    @Test
    fun `after a credential run the fixture secrets appear NOWHERE in logs rows or reports`() = runBlocking {
        val presenter = GatePresenter { card ->
            if (card.target.contains("Email")) {
                GateResolution.ApproveWithSecret(fixtureUser)
            } else {
                GateResolution.ApproveWithSecret(fixtureSecret)
            }
        }
        val fixture = LoopFixture(
            script = formFillScript(
                type("Email or username"),
                type("Password"),
                type("Submit sign in"),
            ),
            observations = listOf(loginPage()),
            presenter = presenter,
        )
        val result = fixture.run()
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)

        // The exhaustive corpus: the step log, every decision row (full JSON — the
        // persisted shape) plus its state text, every exit-check row plus its state
        // text, and the run-report strings (the report's stepLog/partialResult/
        // terminalDetail fields are exactly these values).
        val corpus = buildString {
            append(result.stepLog.joinToString("\n")).append('\n')
            append(result.partialResult).append('\n')
            append(result.terminalDetail).append('\n')
            fixture.sink.rows.forEach { row ->
                append(DECISION_LOG_JSON.encodeToString(DecisionLogRow.serializer(), row)).append('\n')
                append(row.stateText).append('\n')
            }
            fixture.sink.exitChecks.forEach { row ->
                append(DECISION_LOG_JSON.encodeToString(ExitCheckRow.serializer(), row)).append('\n')
                append(row.stateText).append('\n')
            }
        }
        for (needle in listOf(fixtureSecret, fixtureUser)) {
            assertFalse(needle in corpus, "fixture value '$needle' LEAKED into the log/row/report corpus")
        }
        // The corpus was not accidentally tiny: the scenario logged the full flow
        // (shadow companion rows cannot exist here — no shadow was wired — and every
        // row type that could carry a secret string is in the corpus above).
        assertTrue(fixture.sink.rows.size >= 4, "the scenario must log the full flow")
        assertTrue(fixture.sink.exitChecks.isNotEmpty(), "the accepting finish check must log its row")

        // The law's POSITIVE marks: the redaction renders where the value would be —
        // on both execute lines (and the two card-approval gate lines).
        val executeRedactions =
            result.stepLog.count { it.contains("execute: type") && it.contains(TextSource.REDACTED) }
        assertEquals(2, executeRedactions, "both typing steps render [credential-redacted]: ${result.stepLog}")
        assertTrue(
            result.stepLog.count { it.contains("gate: owner APPROVED") && it.contains(TextSource.REDACTED) } == 2,
            "both credential approvals log the redacted marker",
        )
        assertTrue(fixture.sink.rows[0].typedSecret, "the username step typed a card credential")
        assertTrue(fixture.sink.rows[1].typedSecret, "the password step typed a card credential")
        assertFalse(fixture.sink.rows[2].typedSecret, "the submit step typed nothing from a card")
        assertEquals(TriageClass.FORM_FILL, fixture.sink.rows[0].triageClass)
    }

    @Test
    fun `a page-side echo of the typed credential is a LOUD state-text failure`() = runBlocking {
        val presenter = GatePresenter { _ -> GateResolution.ApproveWithSecret(fixtureUser) }
        val fixture = LoopFixture(
            script = formFillScript(
                type("Email or username"),
                type("Email or username"),
            ),
            observations = listOf(loginPage(), loginPageEchoingUser()),
            presenter = presenter,
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 5)),
        )
        // After the username is typed, the page echoes it back into the field's a11y
        // text — the next state build must fail LOUDLY (never truncate, never mask).
        val error = assertThrows<IllegalStateException> { fixture.run() }
        assertTrue(
            error.message!!.contains("credential-secret value"),
            "the builder must name the secret law: ${error.message}",
        )
        // And the failure stays loud: no logged row's state text carries the value.
        fixture.sink.rows.forEach { row ->
            assertFalse(fixtureUser in row.stateText, "a row's state text must never carry the value")
        }
    }

    // -- Gate 8F.3: card flow (approve-with-secret / deny / timeout / trust) -------------

    @Test
    fun `deny skips the credential action and a later card still supplies the secret`() = runBlocking {
        val fixture = LoopFixture(
            script = formFillScript(
                type("Email or username"),
                type("Email or username"),
                type("Password"),
                type("Submit sign in"),
            ),
            observations = listOf(loginPage()),
            presenter = ScriptedGatePresenter(
                GateResolution.DENIED,
                GateResolution.ApproveWithSecret(fixtureUser),
                GateResolution.ApproveWithSecret(fixtureSecret),
            ),
        )
        val result = fixture.run()

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        val first = fixture.sink.rows[0]
        assertEquals(ActionDisposition.REFUSED_BY_OWNER, first.executedAction.disposition)
        assertEquals(LoggedGateDecision.DENIED, first.gateEvent?.decision)
        assertEquals(LoggedHClass.H4, first.gateEvent?.hclass)
        assertFalse(first.typedSecret, "a denied credential step typed nothing")
        // The retry executed with the card secret; the submit rode the granted trust.
        assertEquals(2, fixture.executor.calls.count { it.startsWith("text(") })
        assertTrue(fixture.sink.rows[1].typedSecret)
        assertTrue(fixture.sink.rows[2].typedSecret)
        assertEquals(true, fixture.sink.rows[3].gateEvent?.trustedFromTask)
    }

    @Test
    fun `timeout skips the credential action like a deny`() = runBlocking {
        val fixture = LoopFixture(
            script = formFillScript(
                type("Email or username"),
                type("Email or username"),
                type("Password"),
                type("Submit sign in"),
            ),
            observations = listOf(loginPage()),
            presenter = ScriptedGatePresenter(
                GateResolution.TIMEOUT,
                GateResolution.ApproveWithSecret(fixtureUser),
                GateResolution.ApproveWithSecret(fixtureSecret),
            ),
        )
        val result = fixture.run()

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        val first = fixture.sink.rows[0]
        assertEquals(ActionDisposition.GATE_TIMEOUT, first.executedAction.disposition)
        assertEquals(LoggedGateDecision.TIMEOUT, first.gateEvent?.decision)
        assertFalse(first.typedSecret)
        assertEquals(2, fixture.executor.calls.count { it.startsWith("text(") })
    }

    @Test
    fun `double denial of the credential card still aborts the task`() = runBlocking {
        val fixture = LoopFixture(
            script = formFillScript(
                type("Email or username"),
                type("Email or username"),
            ),
            observations = listOf(loginPage()),
            presenter = ScriptedGatePresenter(GateResolution.DENIED, GateResolution.DENIED),
        )
        val result = fixture.run()

        assertEquals(TerminalState.GUARD_ABORT, result.terminal)
        assertTrue(result.terminalDetail.contains("refused twice"))
        assertEquals(2, fixture.sink.rows.size)
        assertTrue(fixture.executor.calls.none { it.startsWith("text(") })
        assertEquals(ActionDisposition.TASK_ABORTED, fixture.sink.rows[1].executedAction.disposition)
    }

    @Test
    fun `a trusted credential action still asks for the secret - declining skips it every time`() = runBlocking {
        val fixture = LoopFixture(
            script = formFillScript(
                type("Email or username"),
                type("Password"),
                type("Password"),
                type("Password"),
                type("Password"),
            ),
            observations = listOf(loginPage()),
            presenter = ScriptedGatePresenter(
                GateResolution.ApproveWithSecret(fixtureUser),
                GateResolution.DENIED,
                GateResolution.DENIED,
                GateResolution.DENIED,
                GateResolution.DENIED,
            ),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 5)),
        )
        val result = fixture.run()

        // Trust cannot supply a secret: every credential typing re-asks. The owner's
        // decline on a trusted card skips the action (no lawful TextSource), logs the
        // refusal, and never accumulates a double-refusal abort (nothing was pending).
        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertEquals(1, fixture.executor.calls.count { it.startsWith("text(") })
        assertEquals(5, fixture.sink.rows.size, "one typed step + four declined retries")
        val first = fixture.sink.rows[0]
        assertEquals(LoggedGateDecision.APPROVED, first.gateEvent?.decision)
        assertEquals(false, first.gateEvent?.trustedFromTask)
        assertTrue(first.typedSecret)
        fixture.sink.rows.drop(1).forEach { row ->
            assertEquals(LoggedGateDecision.DENIED, row.gateEvent?.decision)
            assertEquals(false, row.gateEvent?.trustedFromTask)
            assertEquals(ActionDisposition.REFUSED_BY_OWNER, row.executedAction.disposition)
            assertFalse(row.typedSecret)
        }
    }
}
