package navigator.app.loop

import kotlinx.coroutines.runBlocking
import navigator.core.STATE_FORMAT_VERSION
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.log.ActionDisposition
import navigator.core.log.EngineIdentity
import navigator.core.log.LoggedActionKind
import navigator.core.log.LoggedGateDecision
import navigator.core.log.Outcome
import navigator.core.log.TriageClass
import navigator.core.log.serialNameOf
import navigator.core.wire.Usage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 6.1 — loop unit tests in isolation (stub engine, fake observation/executor/
 * presenter/log). Scenarios: strict step order (incl. GATE before EXECUTE), budget
 * clean-stops on each dimension, double-deny abort, exit requires noul-HIGH AND
 * guard-pass (ART-4 adversarial), exactly one log row per decision.
 */
class NavigationLoopTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    private suspend fun LoopFixture.run(
        goal: String = "read the thing",
        isCancelled: () -> Boolean = { false },
    ): RunResult = loop.run(goal, "goal-1", identity, clock, isCancelled)

    // -- step order ------------------------------------------------------------------

    @Test
    fun `strict step order - observe, decide, GATE, then EXECUTE`() = runBlocking {
        val start = obs(
            "http://m0.test/item",
            N("Welcome to the item page"),
            N("Free shipping on everything"),
            N("Browse the catalog"),
            N("Buy now", clickable = true, button = true),
            N("About us", clickable = true),
            N("Footer"),
        )
        val article = obs(
            "http://m0.test/article",
            N("The article"),
            N("Paragraph one of the article"),
            N("Paragraph two of the article"),
            N("Paragraph three of the article"),
            N("Paragraph four of the article"),
            N("Related links", clickable = true),
        )
        val shared = Journal()
        val presenter = GatePresenter { card ->
            shared.add("gate:present(${card.classLabel})")
            GateResolution.APPROVED
        }
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("activate-control", target = "Buy now"),
                *intentStep("verify-done"),
                noul(0.95),
            ),
            observations = listOf(start, article),
            presenter = presenter,
            journal = shared,
        )
        val result = fixture.run("read the apple pie article")

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        // GATE before EXECUTE on one shared timeline: observe ... gate:present ... tap
        val gateIdx = fixture.journal.firstIndexOf("gate:present(Purchase confirmation)")
        val tapIdx = fixture.journal.entries.indexOfFirst { it.startsWith("tap(") }
        assertTrue(gateIdx in 0 until tapIdx, "journal=${fixture.journal.entries}")
        // OBSERVE precedes the gate
        assertTrue(fixture.journal.firstIndexOf("observe") < gateIdx)
        // the engine answered exactly: triage, the cascade stages, then the done judge
        assertEquals(
            listOf(
                "choose:triage",
                "choose:intent",
                "scoreBatch:cand-1",
                "choose:intent",
                "judge:goal-achieved",
            ),
            fixture.engineCalls,
        )
        // exactly one tap executed, at the Buy-now node's center
        assertEquals(1, fixture.journal.countOf("tap(50,320)"))
    }

    // -- gate mechanics ----------------------------------------------------------------

    @Test
    fun `double deny aborts the task cleanly`() = runBlocking {
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
                *intentStep("activate-control", target = "Buy now"),
                *intentStep("activate-control", target = "Buy now"),
            ),
            observations = listOf(page),
            presenter = ScriptedGatePresenter(GateResolution.DENIED, GateResolution.DENIED),
        )
        val result = fixture.run()

        assertEquals(TerminalState.GUARD_ABORT, result.terminal)
        assertTrue(result.terminalDetail.contains("refused twice"))
        assertEquals(2, fixture.sink.rows.size)
        assertEquals(0, result.logGaps)
        val first = fixture.sink.rows[0]
        val second = fixture.sink.rows[1]
        assertEquals(ActionDisposition.REFUSED_BY_OWNER, first.executedAction.disposition)
        assertEquals(LoggedGateDecision.DENIED, first.gateEvent?.decision)
        assertEquals(ActionDisposition.TASK_ABORTED, second.executedAction.disposition)
        assertEquals(LoggedGateDecision.DENIED, second.gateEvent?.decision)
        // nothing ever executed
        assertTrue(fixture.executor.calls.isEmpty())
    }

    @Test
    fun `gate timeout is decided as a denial and aborts on the second expiry`() = runBlocking {
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
                *intentStep("activate-control", target = "Buy now"),
                *intentStep("activate-control", target = "Buy now"),
            ),
            observations = listOf(page),
            presenter = ScriptedGatePresenter(GateResolution.TIMEOUT, GateResolution.TIMEOUT),
        )
        val result = fixture.run()

        assertEquals(TerminalState.GUARD_ABORT, result.terminal)
        assertEquals(ActionDisposition.GATE_TIMEOUT, fixture.sink.rows[0].executedAction.disposition)
        assertEquals(LoggedGateDecision.TIMEOUT, fixture.sink.rows[0].gateEvent?.decision)
        assertEquals(ActionDisposition.TASK_ABORTED, fixture.sink.rows[1].executedAction.disposition)
    }

    @Test
    fun `first approval grants per-task trust for the same class and site`() = runBlocking {
        val page = obs(
            "http://m0.test/shop",
            N("Item body line one"),
            N("Item body line two"),
            N("Item body line three"),
            N("Buy now", clickable = true, button = true),
            N("Buy now later", clickable = true, button = true),
            N("Footer"),
        )
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("activate-control", target = "Buy now"),
                *intentStep("activate-control", target = "later"),
            ),
            observations = listOf(page),
            presenter = ScriptedGatePresenter(GateResolution.APPROVED),
            config = NavigationLoop.Config(
                settleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 2),
            ),
        )
        val result = fixture.run()

        // budget stop bounds the run (the page offers no verifiable completion)
        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        // the first approval executes; the second same-class action trusts through
        val first = fixture.sink.rows[0].gateEvent
        val second = fixture.sink.rows[1].gateEvent
        assertEquals(LoggedGateDecision.APPROVED, first?.decision)
        assertEquals(false, first?.trustedFromTask)
        assertEquals(LoggedGateDecision.APPROVED, second?.decision)
        assertEquals(true, second?.trustedFromTask)
        assertEquals(2, fixture.journal.entries.count { it.startsWith("tap(") })
    }

    // -- budgets -----------------------------------------------------------------------

    @Test
    fun `budget steps - soft warns once, hard stops clean`() = runBlocking {
        val pages = (0..3).map { i ->
            obs(
                "http://m0.test/page$i",
                N("Page $i header"),
                N("Page $i body text"),
                N("Page $i nav"),
                N("Next page", clickable = true),
                N("Footer $i"),
            )
        }
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Next page"),
                *intentStep("follow-link", target = "Next page"),
                *intentStep("follow-link", target = "Next page"),
            ),
            observations = pages,
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(
                settleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsSoft = 1, stepsHard = 2),
            ),
        )
        val result = fixture.run()

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertTrue(result.terminalDetail.contains("steps"))
        assertEquals(2, result.steps)
        assertEquals(2, result.rowsLogged)
        assertEquals(0, result.logGaps)
        assertTrue(result.softWarnings.contains("steps"))
        assertTrue(result.stepLog.any { it.contains("SOFT warning - steps") })
    }

    @Test
    fun `budget wall clock - hard stop on elapsed time`() = runBlocking {
        val pages = (0..3).map { i ->
            obs(
                "http://m0.test/page$i",
                N("Page $i header"),
                N("Page $i body text"),
                N("Page $i nav"),
                N("Next page", clickable = true),
                N("Footer $i"),
            )
        }
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Next page"),
                *intentStep("follow-link", target = "Next page"),
                *intentStep("follow-link", target = "Next page"),
            ),
            observations = pages,
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(
                settleMs = 0,
                budget = NavigationLoop.BudgetLimits(timeSoftMs = 150, timeHardMs = 400),
            ),
            clockAdvancePerObserve = 100,
        )
        val result = fixture.run()

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertTrue(result.terminalDetail.contains("wall clock"))
        assertTrue(result.softWarnings.contains("wall clock"))
        assertEquals(2, result.steps)
    }

    @Test
    fun `budget spend - hard stop before any step when the meter is over the cap`() = runBlocking {
        val page = obs(
            "http://m0.test/a",
            N("Header"),
            N("Body"),
            N("Nav"),
            N("Next", clickable = true),
            N("Footer"),
        )
        val meter = SpendMeter()
        // 100k input tokens x 3000 micro-USD/1k = 300_000 micro-USD >= the 250_000 hard cap
        meter.record(inputTokens = 100_000, outputTokens = 0)
        val fixture = LoopFixture(
            script = scriptOf(choose("triage", "lookup")),
            observations = listOf(page),
            presenter = ScriptedGatePresenter(),
            spendMeter = meter,
        )
        val result = fixture.run()

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertTrue(result.terminalDetail.contains("cloud spend"))
        assertEquals(0, result.steps)
        assertEquals(0, result.rowsLogged)
    }

    // -- completion guards / ART-4 ------------------------------------------------------

    @Test
    fun `adversarial ART-4 - engine claims finish without evidence, loop refuses`() = runBlocking {
        // five text lines make the page USABLE but it is the seed page itself, so the
        // lookup guard (content beyond the first page) can never be satisfied
        val blank = obs(
            "http://m0.test/blank",
            N("hello world"),
            N("line two"),
            N("line three"),
            N("line four"),
            N("line five"),
            N("Filler", scrollable = true),
        )
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("verify-done"),
                noul(0.99),
                // anti-oscillation (vision 04 §3 v1.2.0): the refusal arms the done-claim
                // window - the engine must act instead
                *intentStep("scroll"),
            ),
            observations = listOf(blank),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 2)),
        )
        val result = fixture.run()

        // the high-band self-report is NOT enough: no page beyond the first was read
        assertFalse(result.terminal == TerminalState.GOAL_ACHIEVED)
        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertEquals(2, result.rowsLogged)
        val refusal = fixture.sink.rows[0]
        assertEquals(ActionDisposition.REFUSED_BY_GUARD, refusal.executedAction.disposition)
        assertEquals(LoggedActionKind.FINISH, refusal.executedAction.kind)
        assertEquals(Outcome.NO_CHANGE, refusal.outcome)
        assertNull(refusal.exit, "a refused finish carries no exit provenance")
        assertTrue(result.stepLog.any { it.contains("verify-done REFUSED (ART-4)") })
    }

    @Test
    fun `goal-achieved needs noul HIGH and the guard - uncertain noul is refused, then it passes`() = runBlocking {
        val before = obs(
            "http://m0.test/a",
            N("a search box page"),
            N("hint one"),
            N("hint two"),
            N("hint three"),
            N("Open the result", clickable = true),
        )
        val after = obs(
            "http://m0.test/b",
            N("The answer page"),
            N("Line one of the answer"),
            N("Line two of the answer"),
            N("Line three of the answer"),
            N("Footer"),
            N("Filler", scrollable = true),
        )
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Open the result"),
                *intentStep("verify-done"),
                noul(0.5),
                // the refusal arms the anti-oscillation window: two non-done decisions
                // meter it down before the next done claim (vision 04 §3 v1.2.0)
                *intentStep("scroll"),
                *intentStep("scroll"),
                *intentStep("verify-done"),
                noul(0.95),
            ),
            observations = listOf(before, after),
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.run()

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals(5, result.rowsLogged)
        // step 1: the code guard passes (contentful page read beyond the seed) but the
        // noul is UNCERTAIN -> the self-report is refused (either half alone is not enough)
        assertEquals(ActionDisposition.REFUSED_BY_GUARD, fixture.sink.rows[1].executedAction.disposition)
        // step 4: noul HIGH + guard -> exit through Path A (classic)
        assertEquals(ActionDisposition.EXECUTED, fixture.sink.rows[4].executedAction.disposition)
        assertEquals(Outcome.EXITED, fixture.sink.rows[4].outcome)
        assertTrue(result.terminalDetail.contains("HIGH"))
        val exitRow = fixture.sink.rows[4]
        assertEquals(navigator.core.log.LoggedExitPath.HIGH, exitRow.exit?.path)
        assertEquals(navigator.core.log.LoggedGuardLevel.WEAK, exitRow.exit?.guardLevel)
        assertEquals(0.95, exitRow.exit?.noul)
        assertEquals(2, result.exit?.finishChecks, "two finish checks ran before the accept")
        assertEquals(false, result.exit?.completionCardShown)
    }

    // -- stuck detector escalation -------------------------------------------------------

    private fun loopPage(): StepObservation = obs(
        "http://m0.test/loop",
        N("Dead end"),
        N("Filler text one"),
        N("Filler text two"),
        N("Loop", clickable = true),
        N("Footer"),
    )

    @Test
    fun `stuck escalation L1 hint then L2 recovery then give-up kills cleanly`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Loop"),
                *intentStep("follow-link", target = "Loop"),
                *intentStep("follow-link", target = "Loop"),
                *intentStep("follow-link", target = "Loop"),
                choose("recovery", "give-up"),
            ),
            observations = listOf(loopPage()),
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.run()

        assertEquals(TerminalState.STUCK_KILL, result.terminal)
        assertTrue(result.stepLog.any { it.contains("L1 hint") })
        assertTrue(result.stepLog.any { it.contains("L2 - next decide comes from the recovery set") })
        // the give-up recovery decision logged one EXITED row on the recovery question
        val last = fixture.sink.rows.last()
        assertEquals(Outcome.EXITED, last.outcome)
        assertEquals("recovery", last.questionPrimary.name)
        assertEquals(LoggedActionKind.FINISH, last.executedAction.kind)
        // the L1 hint rode the step-3 state text's HISTORY block
        assertTrue(fixture.sink.rows[3].stateText.contains(StuckDetector.HINT))
    }

    @Test
    fun `rephrase recovery is a deterministic stub - no gesture, no row, loop continues`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Loop"),
                *intentStep("follow-link", target = "Loop"),
                *intentStep("follow-link", target = "Loop"),
                *intentStep("follow-link", target = "Loop"),
                choose("recovery", "rephrase"),
                *intentStep("follow-link", target = "Loop"),
            ),
            observations = listOf(loopPage()),
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.run()

        // the rephrase step is a meta-decision: no gesture, no row; the engine then
        // re-decides, gets stuck again, and L3 kills (no rescue coach until Phase 10)
        assertEquals(TerminalState.STUCK_KILL, result.terminal)
        assertEquals(1, result.metaDecisions)
        assertTrue(result.stepLog.any { it.contains("rephrase -> Phase-10 stub") })
        assertTrue(fixture.executor.calls.none { it.startsWith("text(") })
        // steps count decisions; the rephrase meta-decision is EXCLUDED from the
        // zero-log-gaps invariant (steps - rows - metaDecisions = 0)
        assertEquals(1, result.metaDecisions)
        assertEquals(0, result.logGaps)
    }

    // -- triage, refusal, cancel ------------------------------------------------------------

    @Test
    fun `triage impossible explains and aborts pre-navigation`() = runBlocking {
        val page = obs(
            "http://m0.test/a",
            N("Header"),
            N("Body"),
            N("Nav"),
            N("Next", clickable = true),
            N("Footer"),
        )
        val fixture = LoopFixture(
            script = scriptOf(choose("triage", "impossible")),
            observations = listOf(page),
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.run("buy a plane and fly to the moon")

        assertEquals(TerminalState.TRIAGE_IMPOSSIBLE, result.terminal)
        assertEquals("impossible", result.triage)
        assertEquals(0, result.steps)
        assertEquals(0, result.rowsLogged)
        assertTrue(fixture.executor.calls.isEmpty())
        assertTrue(result.stepLog.any { it.contains("outside that scope") })
    }

    @Test
    fun `unusable tree - graceful refusal with the doc 03 message`() = runBlocking {
        val empty = obs("http://m0.test/empty", N("canvas"), pageNodes = 1)
        val fixture = LoopFixture(
            script = scriptOf(choose("triage", "lookup")),
            observations = listOf(empty),
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.run("read the unreadable page")

        assertEquals(TerminalState.REFUSED, result.terminal)
        assertEquals(NavigationLoop.REFUSAL_MESSAGE, result.terminalDetail)
        assertEquals(0, result.steps)
        assertEquals(0, result.rowsLogged)
        // twenty fresh reads before refusing (reload-patient re-reads)
        assertEquals(20, fixture.journal.countOf("observe"))
    }

    @Test
    fun `one transient unusable read does not refuse the task`() = runBlocking {
        val unusable = obs("about:blank", pageNodes = 0)
        val before = obs(
            "http://m0.test/a",
            N("a search box page"),
            N("hint one"),
            N("hint two"),
            N("hint three"),
            N("Open the result", clickable = true),
        )
        val after = obs(
            "http://m0.test/b",
            N("The answer page"),
            N("Line one"),
            N("Line two"),
            N("Line three"),
            N("Footer"),
        )
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Open the result"),
                *intentStep("verify-done"),
                noul(0.95),
            ),
            observations = listOf(unusable, before, after),
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.run()

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals(2, result.rowsLogged)
    }

    @Test
    fun `user cancel ends the task between steps`() = runBlocking {
        val pages = (0..2).map { i ->
            obs(
                "http://m0.test/p$i",
                N("Page $i header"),
                N("Page $i body"),
                N("Page $i nav"),
                N("Next", clickable = true),
                N("Footer $i"),
            )
        }
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Next"),
                *intentStep("follow-link", target = "Next"),
            ),
            observations = pages,
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.run(isCancelled = { fixture.executor.calls.isNotEmpty() })

        assertEquals(TerminalState.USER_CANCEL, result.terminal)
        assertEquals(1, result.steps)
        assertEquals(1, result.rowsLogged)
    }

    // -- log law ------------------------------------------------------------------------

    @Test
    fun `exactly one log row per decision with the day-one schema fields`() = runBlocking {
        val start = obs(
            "http://m0.test/item",
            N("Welcome page body"),
            N("Some filler"),
            N("More filler"),
            N("Next article", clickable = true),
            N("Footer"),
        )
        val article = obs(
            "http://m0.test/article",
            N("The article"),
            N("Paragraph one"),
            N("Paragraph two"),
            N("Paragraph three"),
            N("Related links", clickable = true),
        )
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("follow-link", target = "Next article"),
                *intentStep("verify-done"),
                noul(0.95),
            ),
            observations = listOf(start, article),
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.run("read one article")

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals(2, result.rowsLogged)
        assertEquals(listOf(0, 1), fixture.sink.rows.map { it.stepIndex })
        val row = fixture.sink.rows[0]
        assertEquals("lookup", serialNameOf(TriageClass.serializer(), row.triageClass))
        assertEquals("intent", row.questionPrimary.name)
        assertEquals(navigator.core.decision.Intents.IDS, row.questionPrimary.options.map { it.id })
        assertEquals(identity.configId, row.teacher.configId)
        assertEquals(STATE_FORMAT_VERSION, row.stateFormatVersion)
        assertEquals(false, row.shadowSampled)
        assertEquals(null, row.shadow)
        assertEquals(null, row.shadowAnswer)
        assertEquals(null, row.gateEvent)
        assertEquals("progressed", serialNameOf(Outcome.serializer(), row.outcome))
        // row ids are task-unique and step-indexed
        assertTrue(fixture.sink.rows[0].rowId.endsWith("-step-0"))
        assertTrue(fixture.sink.rows[1].rowId.endsWith("-step-1"))
    }

    @Test
    fun `the live spend recorder books PROVIDER-REPORTED usage into the budget meter`() = runBlocking {
        val meter = SpendMeter()
        val feed = UsageFeed()
        val recorder = JevSpendRecorder(
            inner = UsageReportingEngine(
                feed,
                ArrayDeque(
                    listOf(
                        Usage(inputTokens = 1_000, outputTokens = 200, costUsd = 0.006),
                        Usage(inputTokens = 100, outputTokens = 100, costUsd = 0.0012),
                    ),
                ),
            ),
            spend = meter,
            usage = feed,
        )
        recorder.choose("a short state text", Triage.question())
        recorder.judge("another short state", NoulQuestion("noul-check", "the goal is achieved"))

        // Real token counts x pinned prices (ART-5): 1000 in + 200 out = 6000u$,
        // then 100 in + 100 out = 1800u$ — no tokenizer counting, no per-call estimate.
        assertEquals(7_800, meter.spentMicroUsd)
    }

    @Test
    fun `a completed live call with NO wire usage fails loudly, never books silent zero`() = runBlocking {
        val meter = SpendMeter()
        val feed = UsageFeed()
        val recorder = JevSpendRecorder(
            inner = UsageReportingEngine(feed, ArrayDeque()),
            spend = meter,
            usage = feed,
        )
        assertThrows<IllegalStateException> {
            recorder.choose("a short state text", Triage.question())
        }
        assertEquals(0, meter.spentMicroUsd)
    }

    @Test
    fun `a scripted presenter that runs dry fails loudly, never silently`() = runBlocking {
        val page = obs(
            "http://m0.test/a",
            N("Header"),
            N("Body"),
            N("Nav"),
            N("Next", clickable = true),
            N("Footer"),
        )
        val fixture = LoopFixture(
            script = scriptOf(choose("triage", "lookup"), *intentStep("follow-link", target = "Next")),
            observations = listOf(page),
            presenter = ScriptedGatePresenter(),
        )
        assertThrows<IllegalStateException> {
            fixture.loop.run("run the script dry", "g", identity, fixture.clock)
        }
    }

    /**
     * A stand-in live engine: reports wire usage like the [navigator.core.wire.SystemOneClient]
     * does — one [Usage] per call, popped from [usages] (an empty queue simulates a
     * miswired feed) — and answers like a stub.
     */
    private class UsageReportingEngine(
        private val feed: UsageFeed,
        private val usages: ArrayDeque<Usage>,
    ) : DecisionEngine {

        private fun offer() {
            usages.removeFirstOrNull()?.let { feed.offer(it) }
        }

        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
            offer()
            return ChoiceResult(
                question.options.first().id,
                question.options.associate { it.id to 1.0 / question.options.size },
                0.9,
            )
        }

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult {
            offer()
            return NoulResult(0.95)
        }

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            throw UnsupportedOperationException("score is not exercised by these tests")
    }
}
