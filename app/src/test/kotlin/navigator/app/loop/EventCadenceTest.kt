package navigator.app.loop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass
import navigator.core.ladder.ReplanContext
import navigator.core.ladder.Subtask
import navigator.core.log.EngineIdentity

/**
 * PHASE_17 Stage 2 — the EVENT CADENCE's tree half (the plan's §2.1): Haiku is called at
 * PLAN, RE-PLAN, the subtask boundary, stuck/failure and answer extraction — and **a tree
 * step makes ZERO writer calls**. The step loop's context is the state text + the code
 * evidence; the writer never sees a per-step page.
 *
 * The a11y route already behaves this way (its only writer surfaces are the ladder source,
 * the phrase source, the assist handshake and the extractor); this gate PINS it so the
 * hybrid work cannot reintroduce a per-step writer call unnoticed.
 */
class EventCadenceTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    /** Counts restructure calls (the only writer surface wired here). */
    private class CountingLadderSource(private val ladder: Ladder) : LadderSource {
        var calls = 0
            private set

        override suspend fun restructure(
            goal: String,
            generation: Int,
            failure: ReplanContext?,
        ): RestructuredLadder {
            calls += 1
            return RestructuredLadder(ladder, ladder.versionTag("test", generation), 0)
        }

        override suspend fun repairCondition(
            subtaskInstruction: String,
            current: DoneCondition,
            pageUrl: String,
            pageTitle: String,
        ): RepairedCondition? = null
    }

    private fun page(step: Int) = obs(
        "http://m0.test/page/$step",
        N("Header"),
        N("Body $step one"),
        N("Body $step two"),
        N("A control", clickable = true),
        N("Spacer", scrollable = true),
    )

    private val ladder = Ladder(
        summary = "find the marker",
        subtasks = listOf(Subtask("find the target marker", DoneCondition.MarkerText("target marker"))),
        taskClass = LadderTaskClass.ACTION,
    )

    @Test
    fun `three tree steps produce ONE writer call - the plan, never a step`() = runBlocking {
        val source = CountingLadderSource(ladder)
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                *intentStep("activate-control", target = "A control"),
                *intentStep("activate-control", target = "A control"),
                *intentStep("activate-control", target = "A control"),
            ),
            observations = listOf(page(0), page(1), page(2), page(3), page(4)),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(
                settleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 3),
            ),
        )
        val loop = NavigationLoop(
            engine = fixture.engine,
            observer = object : LoopObserver {
                private var index = 0
                override suspend fun observe(): StepObservation {
                    fixture.journal.add("observe")
                    return if (index < 5) page(index++) else page(5)
                }
            },
            executor = fixture.executor,
            gatePresenter = ScriptedGatePresenter(),
            logSink = fixture.sink,
            config = NavigationLoop.Config(
                settleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 3),
            ),
            ladderSource = source,
        )

        val result = loop.run(
            goal = "find the target marker on m0.test",
            goalId = "cadence-tree",
            engineIdentity = identity,
            isCancelled = { false },
        )

        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal, result.stepLog.toString())
        assertTrue(result.steps >= 2, "the tree step loop really ran: ${result.stepLog}")
        assertEquals(
            1,
            source.calls,
            "the writer is consulted at the PLAN only - never per tree step (${result.steps} steps)",
        )
    }
}
