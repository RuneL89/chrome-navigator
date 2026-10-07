package navigator.app.loop

import kotlinx.coroutines.runBlocking
import navigator.core.log.TriageClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Gate 8D.3 — per-class BudgetProfiles (vision 04 §5): the exact table numbers
 * (ART-5, integer micro-dollars), profile selection by triage class, per-class clean
 * stops at each dimension in the REAL loop (steps / wall clock / cloud spend), and the
 * three original classes' defaults byte-identical to the pre-08D constants.
 */
class BudgetProfileTest {

    private val identity = navigator.core.log.EngineIdentity("stub", "scripted-harness-v1")

    // -- the table (exact numbers, ART-5) ----------------------------------------------

    @Test
    fun `the profile table matches vision 04 section 5 exactly`() {
        assertEquals(
            BudgetProfile(
                "standard", 40, 60, 5L * 60_000L, 10L * 60_000L, 200_000L, 250_000L,
            ),
            BudgetProfiles.STANDARD,
        )
        assertEquals(
            BudgetProfile(
                "action", 60, 100, 10L * 60_000L, 15L * 60_000L, 200_000L, 250_000L,
            ),
            BudgetProfiles.ACTION,
        )
        assertEquals(
            BudgetProfile(
                "multi", 150, 300, 15L * 60_000L, 30L * 60_000L, 500_000L, 750_000L,
            ),
            BudgetProfiles.MULTI,
        )
    }

    @Test
    fun `profile selection is by triage class`() {
        assertEquals(BudgetProfiles.STANDARD, BudgetProfiles.forClass(TriageClass.LOOKUP))
        assertEquals(BudgetProfiles.STANDARD, BudgetProfiles.forClass(TriageClass.RESEARCH_MULTI_STEP))
        assertEquals(BudgetProfiles.STANDARD, BudgetProfiles.forClass(TriageClass.FORM_FILL))
        assertEquals(BudgetProfiles.ACTION, BudgetProfiles.forClass(TriageClass.ACTION))
        assertEquals(BudgetProfiles.MULTI, BudgetProfiles.forClass(TriageClass.ACTION_MULTI))
    }

    @Test
    fun `the three original classes' defaults are byte-identical to the pre-08D constants`() {
        // The TaskBudget companion constants ARE the Standard profile's numbers (the
        // profile is BUILT from them) and the no-argument TaskBudget still uses them.
        assertEquals(BudgetProfiles.STANDARD.stepsSoft, TaskBudget.STEPS_SOFT)
        assertEquals(BudgetProfiles.STANDARD.stepsHard, TaskBudget.STEPS_HARD)
        assertEquals(BudgetProfiles.STANDARD.timeSoftMs, TaskBudget.TIME_SOFT_MS)
        assertEquals(BudgetProfiles.STANDARD.timeHardMs, TaskBudget.TIME_HARD_MS)
        assertEquals(BudgetProfiles.STANDARD.spendSoftMicroUsd, TaskBudget.SPEND_SOFT_MICRO_USD)
        assertEquals(BudgetProfiles.STANDARD.spendHardMicroUsd, TaskBudget.SPEND_HARD_MICRO_USD)
        assertEquals(200_000L, TaskBudget.SPEND_SOFT_MICRO_USD) // $0.20
        assertEquals(250_000L, TaskBudget.SPEND_HARD_MICRO_USD) // $0.25
    }

    // -- per-class clean stops: STEPS ---------------------------------------------------

    @Test
    fun `action tasks hard-stop at the Action profile's 100 steps`() = runBlocking {
        val result = stepsRun("action", hardSteps = 100)
        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertEquals("action", result.budgetProfile)
        assertEquals(100, result.steps)
        assertTrue(result.softWarnings.contains("steps"))
        assertTrue(result.stepLog.any { it.contains("SOFT warning - steps") })
    }

    @Test
    fun `action-multi tasks hard-stop at the Multi profile's 300 steps`() = runBlocking {
        val result = stepsRun("action-multi", hardSteps = 300)
        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertEquals("multi", result.budgetProfile)
        assertEquals(300, result.steps)
        assertTrue(result.softWarnings.contains("steps"))
    }

    @Test
    fun `lookup tasks keep the Standard 60-step hard stop`() = runBlocking {
        val result = stepsRun("lookup", hardSteps = 60)
        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertEquals("standard", result.budgetProfile)
        assertEquals(60, result.steps)
    }

    private suspend fun stepsRun(triageOption: String, hardSteps: Int): RunResult {
        // each step consumes TWO observations (step OBSERVE + post-step observation)
        val pages = (0..2 * hardSteps + 1).map { i -> cyclingPage(i) }
        val script = buildList {
            add(choose("triage", triageOption))
            repeat(hardSteps) { j -> add(chooseByLabel("action", "Next page (${(2 * j) % 5})")) }
        }
        val fixture = LoopFixture(
            script = script,
            observations = pages,
            presenter = ScriptedGatePresenter(),
            // NO budget overrides: the profile numbers govern (vision 04 §5).
            config = NavigationLoop.Config(settleMs = 0),
        )
        return fixture.loop.run("the $triageOption goal", "steps-$triageOption", identity, fixture.clock)
    }

    /**
     * Five page shapes cycle, each carrying [shape] filler nodes before its clickable,
     * so the tapped candidate's id (n0..n4) — the stuck detector's action key — cycles
     * through 5 distinct values within any 8-step window and every consecutive screen
     * differs. Long budget runs therefore never trip the stuck detector (any 3 of:
     * same-action-3x, hash-unchanged-3x, zero-text-diff-3x, high-repetition).
     */
    private fun cyclingPage(step: Int): StepObservation {
        val shape = step % 5
        val nodes = buildList {
            repeat(shape) { add(N("Filler $step-$it")) }
            add(N("Next page ($shape)", clickable = true))
            add(N("Tail $step-a"))
            add(N("Tail $step-b"))
            add(N("Tail $step-c"))
            add(N("Tail $step-d"))
        }
        return obs("http://m0.test/p$step", *nodes.toTypedArray())
    }

    // -- per-class clean stops: WALL CLOCK ----------------------------------------------

    @Test
    fun `each class hard-stops at its profile's wall-clock limit`() = runBlocking {
        // 50_000ms of wall clock per observation = 100_000ms per step (two observes):
        // the hard stop lands on the step whose number equals hardMs / 100_000
        // (standard 600k -> 6, action 900k -> 9, multi 1_800k -> 18).
        assertEquals(6, timedRun("lookup").steps)
        assertEquals(9, timedRun("action").steps)
        assertEquals(18, timedRun("action-multi").steps)
    }

    private suspend fun timedRun(triageOption: String): RunResult {
        val pages = (0..2 * 30 + 1).map { i -> cyclingPage(i) }
        val script = buildList {
            add(choose("triage", triageOption))
            repeat(30) { j -> add(chooseByLabel("action", "Next page (${(2 * j) % 5})")) }
        }
        val fixture = LoopFixture(
            script = script,
            observations = pages,
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0),
            clockAdvancePerObserve = 50_000L,
        )
        val result = fixture.loop.run(
            "the $triageOption goal",
            "time-$triageOption",
            identity,
            fixture.clock,
        )
        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertTrue(result.terminalDetail.contains("wall clock"))
        return result
    }

    // -- per-class clean stops: CLOUD SPEND ----------------------------------------------

    @Test
    fun `the Multi profile tolerates spend the Standard profile would hard-stop`() = runBlocking {
        // ~700_000 micro-USD ($0.70): over the Multi soft cap ($0.50), under its hard cap
        // ($0.75) - a multi task WARNS and runs its step; a lookup task hard-stops at
        // step 0 (its $0.25 hard cap is far exceeded).
        val multi = spendRun("action-multi", 700_000L)
        assertEquals(1, multi.steps) // step 0 was NOT stopped by spend
        assertTrue(multi.softWarnings.contains("cloud spend"))

        val lookup = spendRun("lookup", 700_000L)
        assertEquals(TerminalState.BUDGET_EXHAUSTED, lookup.terminal)
        assertTrue(lookup.terminalDetail.contains("cloud spend"))
        assertEquals(0, lookup.steps)
    }

    @Test
    fun `each class hard-stops at its own spend cap`() = runBlocking {
        // Standard/Action hard cap $0.25: 750_000 micro-USD stops both instantly...
        assertEquals(0, spendRun("lookup", 750_000L).steps)
        assertEquals(0, spendRun("action", 750_000L).steps)
        // ...but the Multi hard cap is $0.75, so just under it a multi task only WARNS.
        val multi = spendRun("action-multi", 749_000L) // books ~748_998 micro-USD
        assertEquals(1, multi.steps)
        assertTrue(multi.softWarnings.contains("cloud spend"))
        // and at the $0.75 cap itself the multi task hard-stops at step 0.
        assertEquals(0, spendRun("action-multi", 750_000L).steps)
    }

    /** A run with [microUsd] pre-booked into the meter; steps capped at 1 so the run ends. */
    private suspend fun spendRun(triageOption: String, microUsd: Long): RunResult {
        val page = obs(
            "http://m0.test/a",
            N("Header"),
            N("Body"),
            N("Nav"),
            N("Next", clickable = true),
            N("Footer"),
        )
        val meter = SpendMeter()
        // SpendPrices: 3_000 micro-USD per 1K input tokens (integer arithmetic, ART-5).
        meter.record(inputTokens = (microUsd / 3).toInt(), outputTokens = 0)
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", triageOption),
                chooseByLabel("action", "Next"),
            ),
            observations = listOf(page),
            presenter = ScriptedGatePresenter(),
            spendMeter = meter,
            // Only the steps dimension is overridden - spend stays at the profile numbers.
            config = NavigationLoop.Config(
                settleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 1),
            ),
        )
        return fixture.loop.run(
            "the $triageOption goal",
            "spend-$triageOption",
            identity,
            fixture.clock,
        )
    }
}
