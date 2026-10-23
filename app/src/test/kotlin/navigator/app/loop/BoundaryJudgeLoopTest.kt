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
 * PHASE_17 Gate 17.2 — the SUBTASK-BOUNDARY AUDIT's host law (Stage 2.3): at a VERIFIED
 * boundary the writer's verdict is ADVISORY. A "does not hold" verdict feeds the EXISTING
 * bounded re-plan (its reason becomes the failure cause); a "holds" verdict changes
 * nothing; code remains the decider of every advance.
 */
class BoundaryJudgeLoopTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    /** Counts restructure calls and the failure reasons it saw. */
    private class CountingLadderSource(private val ladder: Ladder) : LadderSource {
        var calls = 0
            private set
        val failureCauses = mutableListOf<String>()

        override suspend fun restructure(
            goal: String,
            generation: Int,
            failure: ReplanContext?,
        ): RestructuredLadder {
            calls += 1
            failure?.let { failureCauses.add(it.cause) }
            return RestructuredLadder(ladder, ladder.versionTag("test", generation), 0)
        }

        override suspend fun repairCondition(
            subtaskInstruction: String,
            current: DoneCondition,
            pageUrl: String,
            pageTitle: String,
        ): RepairedCondition? = null
    }

    private class FakeBoundaryJudge(private val verdict: BoundaryVerdict?) : BoundaryJudge {
        val calls = mutableListOf<String>()

        override suspend fun judge(
            goal: String,
            completedInstruction: String,
            completedDone: String,
            pageUrl: String,
            pageTitle: String,
            pageDigest: String,
        ): BoundaryVerdict? {
            calls.add(completedInstruction)
            return verdict
        }
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
        summary = "open the site then find the marker",
        subtasks = listOf(
            Subtask("open the site", DoneCondition.UrlHost("m0.test")),
            Subtask("find the target marker", DoneCondition.MarkerText("target marker never here")),
        ),
        taskClass = LadderTaskClass.ACTION,
    )

    /** A page whose text satisfies the FIRST subtask's marker (the boundary then fires). */
    private val satisfiedFirst = obs(
        "http://m0.test/opened",
        N("open marker"),
        N("Header"),
        N("Body one"),
        N("Body two"),
        N("A control", clickable = true),
    )

    private fun fixture(
        source: CountingLadderSource,
        judge: BoundaryJudge,
    ): Pair<NavigationLoop, LoopFixture> {
        val fixture = LoopFixture(
            script = scriptOf(choose("triage", "lookup"), noul(0.95), noul(0.95), noul(0.95), *intentStep("activate-control", target = "A control"), *intentStep("activate-control", target = "A control"), *intentStep("activate-control", target = "A control"), *intentStep("activate-control", target = "A control"), *intentStep("activate-control", target = "A control"), *intentStep("activate-control", target = "A control")),
            observations = listOf(satisfiedFirst, page(1), page(2), page(3)),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(
                settleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 4),
            ),
        )
        val loop = NavigationLoop(
            engine = fixture.engine,
            observer = object : LoopObserver {
                private var index = 0
                override suspend fun observe(): StepObservation {
                    fixture.journal.add("observe")
                    return when {
                        index == 0 -> satisfiedFirst.also { index++ }
                        else -> page(index++)
                    }
                }
            },
            executor = fixture.executor,
            gatePresenter = ScriptedGatePresenter(),
            logSink = fixture.sink,
            config = NavigationLoop.Config(
                settleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 4),
            ),
            ladderSource = source,
            boundaryJudge = judge,
        )
        return loop to fixture
    }

    @Test
    fun `a boundary verdict that the plan no longer holds feeds the bounded re-plan`() = runBlocking {
        val source = CountingLadderSource(ladder)
        val judge = FakeBoundaryJudge(
            BoundaryVerdict(
                planHolds = false,
                reason = "the page is a login wall",
                promptVersion = "vision-exp-v19",
                costMicroUsd = 0,
            ),
        )
        val (loop, fixture) = fixture(source, judge)

        val result = loop.run(
            goal = "open the site and find the target marker",
            goalId = "boundary-replan",
            engineIdentity = identity,
            isCancelled = { false },
        )

        assertEquals(listOf("open the site"), judge.calls, "the judge audits the VERIFIED subtask")
        assertTrue(
            result.stepLog.any { it.contains("boundary: the remaining plan NO LONGER holds") },
            "the verdict is loud: ${result.stepLog}",
        )
        assertEquals(2, source.calls, "the initial plan + the boundary-driven re-plan")
        assertTrue(
            source.failureCauses.any { it.contains("boundary judgment: the page is a login wall") },
            "the re-plan's failure context names the boundary verdict: ${source.failureCauses}",
        )
    }

    @Test
    fun `a boundary verdict that the plan holds changes nothing`() = runBlocking {
        val source = CountingLadderSource(ladder)
        val judge = FakeBoundaryJudge(
            BoundaryVerdict(
                planHolds = true,
                reason = "the page is the site we opened",
                promptVersion = "vision-exp-v19",
                costMicroUsd = 0,
            ),
        )
        val (loop, _) = fixture(source, judge)

        val result = loop.run(
            goal = "open the site and find the target marker",
            goalId = "boundary-holds",
            engineIdentity = identity,
            isCancelled = { false },
        )

        assertEquals(listOf("open the site"), judge.calls)
        assertTrue(
            result.stepLog.any { it.contains("boundary: the remaining plan holds") },
            "the hold verdict is recorded: ${result.stepLog}",
        )
        assertEquals(1, source.calls, "no re-plan from a holding boundary")
    }

    @Test
    fun `without a judge no boundary call happens`() = runBlocking {
        val source = CountingLadderSource(ladder)
        val fixture = LoopFixture(
            script = scriptOf(choose("triage", "lookup"), noul(0.95), noul(0.95), noul(0.95), *intentStep("activate-control", target = "A control"), *intentStep("activate-control", target = "A control"), *intentStep("activate-control", target = "A control"), *intentStep("activate-control", target = "A control"), *intentStep("activate-control", target = "A control"), *intentStep("activate-control", target = "A control")),
            observations = listOf(satisfiedFirst, page(1), page(2), page(3)),
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
                    return when {
                        index == 0 -> satisfiedFirst.also { index++ }
                        else -> page(index++)
                    }
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
            goal = "open the site and find the target marker",
            goalId = "boundary-none",
            engineIdentity = identity,
            isCancelled = { false },
        )

        assertTrue(
            result.stepLog.none { it.contains("boundary:") },
            "no judge, no boundary line: ${result.stepLog}",
        )
        assertEquals(1, source.calls)
    }
}
