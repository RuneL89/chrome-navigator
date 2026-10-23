package navigator.app.loop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import navigator.app.harness.hybrid.SubtaskOutcome
import navigator.app.harness.hybrid.VisionToolStep
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass
import navigator.core.ladder.ReplanContext
import navigator.core.ladder.Subtask
import navigator.core.log.EngineIdentity

/**
 * PHASE_17 Gate 17.1b — the HYBRID HOST's modality law (the plan's Stage 1): the tree loop
 * owns the ladder/pointer/accept/terminal; the vision TOOL runs ONE subtask on a
 * code-owned escalation and reports back; the next subtask starts on the tree again.
 *
 * Pinned here (scripted tool, $0, no model call):
 *
 * - the THIN/EMPTY TREE signal flips the modality and calls the tool with the ACTIVE
 *   subtask's index;
 * - the tool's Done advances the pointer through the SAME verified-subtask path (the
 *   resets run) and the modality returns to TREE;
 * - the tool's Failed returns to the tree and enters the existing failure path with the
 *   tool's cause (the host never re-plans inside vision);
 * - without a tool the tree-only loop is byte-identical (the whole existing suite).
 */
class ModalityTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    /** Scripted vision tool: records the calls and returns the queued outcomes. */
    private class FakeVisionTool(vararg outcomes: SubtaskOutcome) : VisionToolStep {
        private val queue = ArrayDeque(outcomes.toList())
        val calls = mutableListOf<Int>()

        override suspend fun runSubtask(
            goal: String,
            ladder: Ladder,
            index: Int,
            isCancelled: () -> Boolean,
        ): SubtaskOutcome {
            calls.add(index)
            return queue.removeFirstOrNull() ?: error("vision tool script exhausted")
        }
    }

    private class ScriptedLadderSource(private val ladder: Ladder) : LadderSource {
        override suspend fun restructure(
            goal: String,
            generation: Int,
            failure: ReplanContext?,
        ): RestructuredLadder = RestructuredLadder(ladder, ladder.versionTag("test", generation), 0)

        override suspend fun repairCondition(
            subtaskInstruction: String,
            current: DoneCondition,
            pageUrl: String,
            pageTitle: String,
        ): RepairedCondition? = null
    }

    private val deadPage = obs("chrome://newtab")
    private val usablePage = obs(
        "http://m0.test/a",
        N("Header"),
        N("Body one"),
        N("Body two"),
        N("A link", clickable = true),
        N("Spacer", scrollable = true),
    )

    private val ladder = Ladder(
        summary = "open m0.test then find the marker",
        subtasks = listOf(
            Subtask("open m0.test", DoneCondition.UrlHost("m0.test")),
            Subtask("find the target marker", DoneCondition.MarkerText("target marker")),
        ),
        taskClass = LadderTaskClass.ACTION,
    )

    @Test
    fun `a thin tree escalates to the vision tool - Done advances and the modality resets to TREE`() =
        runBlocking {
            val tool = FakeVisionTool(SubtaskOutcome.Done(steps = 3))
            val fixture = LoopFixture(
                script = scriptOf(choose("triage", "lookup"), *intentStep("go-back"), noul(0.9)),
                observations = listOf(deadPage, usablePage),
                presenter = ScriptedGatePresenter(),
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
            )
            var observes = 0
            val loop = NavigationLoop(
                engine = fixture.engine,
                observer = object : LoopObserver {
                    private var index = 0
                    override suspend fun observe(): StepObservation {
                        fixture.journal.add("observe")
                        observes += 1
                        return if (index < 2) { index++; deadPage } else usablePage
                    }
                },
                executor = fixture.executor,
                gatePresenter = ScriptedGatePresenter(),
                logSink = fixture.sink,
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
                ladderSource = ScriptedLadderSource(ladder),
                visionTool = tool,
            )

            val result = loop.run(
                goal = "open m0.test and find the target marker",
                goalId = "modality-thin",
                engineIdentity = identity,
                isCancelled = { observes >= 3 },
            )

            assertEquals(
                listOf(0),
                tool.calls,
                "the tool runs ONCE for the ACTIVE subtask (index 0); terminal=${result.terminal}; " +
                    result.stepLog.joinToString("\n"),
            )
            assertTrue(
                result.stepLog.any { it.contains("modality: VISION (escalated: thin/empty tree") },
                "the flip names the code-owned cause: ${result.stepLog}",
            )
            assertTrue(
                result.stepLog.any { it.contains("IS-DONE verified") } &&
                    result.stepLog.any { it.contains("modality: TREE") },
                "the accept advances through the shared verified path and the modality resets: " +
                    result.stepLog,
            )
            assertTrue(
                result.stepLog.any { it.contains("pointer advanced to 2/2") },
                "the pointer advanced one verified subtask: ${result.stepLog}",
            )
            assertTrue(
                result.stepLog.none { it.contains("tree still unusable - graceful refusal") },
                "the thin tree is the vision cue, not a refusal: ${result.stepLog}",
            )
        }

    @Test
    fun `a vision-subtask failure returns to the tree and enters the failure path with its cause`() =
        runBlocking {
            val tool = FakeVisionTool(SubtaskOutcome.Failed("the battery red-flagged the page", 2))
            val fixture = LoopFixture(
                script = scriptOf(choose("triage", "lookup"), *intentStep("go-back"), noul(0.9)),
                observations = listOf(deadPage, usablePage),
                presenter = ScriptedGatePresenter(),
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
            )
            var observes = 0
            val loop = NavigationLoop(
                engine = fixture.engine,
                observer = object : LoopObserver {
                    private var index = 0
                    override suspend fun observe(): StepObservation {
                        observes += 1
                        return if (index < 2) { index++; deadPage } else usablePage
                    }
                },
                executor = fixture.executor,
                gatePresenter = ScriptedGatePresenter(),
                logSink = fixture.sink,
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
                ladderSource = ScriptedLadderSource(ladder),
                visionTool = tool,
            )

            val result = loop.run(
                goal = "open m0.test and find the target marker",
                goalId = "modality-fail",
                engineIdentity = identity,
                isCancelled = { observes >= 3 },
            )

            assertEquals(listOf(0), tool.calls)
            assertTrue(
                result.stepLog.any {
                    it.contains("vision subtask 1/2 FAILED (the battery red-flagged the page)") &&
                        it.contains("modality: TREE")
                },
                "the failure names the tool's cause and the restored modality: ${result.stepLog}",
            )
            assertTrue(
                result.stepLog.any { it.contains("ladder: subtask 1/2 FAILED (vision tool:") },
                "the host's existing failure path owns the recovery: ${result.stepLog}",
            )
        }

    @Test
    fun `without a vision tool the loop never flips - the tree-only path is unchanged`() =
        runBlocking {
            val fixture = LoopFixture(
                script = scriptOf(choose("triage", "lookup")),
                observations = listOf(deadPage),
                presenter = ScriptedGatePresenter(),
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
            )
            val result = fixture.loop.run(
                "open m0.test and find the target marker", "modality-none", identity, fixture.clock,
            )

            assertEquals(TerminalState.REFUSED, result.terminal)
            assertTrue(
                result.stepLog.none { it.contains("modality: VISION") },
                "no tool, no modality: ${result.stepLog}",
            )
        }
}
