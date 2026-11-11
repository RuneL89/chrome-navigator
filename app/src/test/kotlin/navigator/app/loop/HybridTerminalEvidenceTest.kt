package navigator.app.loop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import navigator.app.harness.hybrid.SubtaskOutcome
import navigator.app.harness.hybrid.VisionToolStep
import navigator.app.harness.vision.VisionEvidence
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass
import navigator.core.ladder.ReplanContext
import navigator.core.ladder.Subtask
import navigator.core.log.EngineIdentity
import java.io.File
import java.nio.file.Files

/**
 * PHASE_18 fix-loop (ART-3 / doc 05 §2 — THE TERMINAL LINE IS MANDATORY): the hybrid
 * host's ONE exit funnel writes the terminal line into the SHARED evidence record on
 * EVERY terminal. The 2026-11-11 UAT defect (task-1794374180813): a TREE_FIRST product
 * run printed its `task STUCK_KILL — … the owner said stop (escalation card)` corner-log
 * line, but the pulled `events.log` had NO `terminal=` line — an incomplete evidence
 * record. The always-vision arm has pinned its own line since PHASE_VEXP6
 * (`VisionTerminalEvidenceTest`); this gate pins the HYBRID host's line —
 * `terminal=<T> steps=<n> replans=<n> modality=HYBRID calls=<n> … escalations=<n>` —
 * and the byte-identical default (no record wired = no events.log line; the a11y
 * engine's evidence law stays where doc 05 §4 puts it, NOT in events.log).
 *
 * Scripted, $0: the thin-tree signal escalates the active subtask to the vision TOOL,
 * whose scripted failure drives the host's existing failure path (both re-plan calls
 * refused) to the owner escalation card's fail-safe STOP — the STUCK_KILL shape the
 * defect run died on (steps=0 is the truthful count for this script: no action ever
 * executed; replans=2 is truthful too — two re-plan CALLS happened, both refused).
 */
class HybridTerminalEvidenceTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    /** Scripted vision tool: records the calls and always fails the subtask. */
    private class FailingVisionTool(private val cause: String) : VisionToolStep {
        val calls = mutableListOf<Int>()

        override suspend fun runSubtask(
            goal: String,
            ladder: Ladder,
            index: Int,
            isCancelled: () -> Boolean,
        ): SubtaskOutcome {
            calls.add(index)
            return SubtaskOutcome.Failed(cause, steps = 2)
        }
    }

    /**
     * The ladder source with generation 1 ONLY (the LadderExecutionLoopTest precedent):
     * every re-plan call is refused, so the failure path reaches the owner card exactly
     * as the defect run's "the cascade stayed uncertain and the owner said stop" did.
     */
    private class ScriptedLadderSource(private val ladder: Ladder) : LadderSource {
        override suspend fun restructure(
            goal: String,
            generation: Int,
            failure: ReplanContext?,
        ): RestructuredLadder? =
            if (generation == 1) {
                RestructuredLadder(ladder, ladder.versionTag("test", generation), 0)
            } else {
                null
            }

        override suspend fun repairCondition(
            subtaskInstruction: String,
            current: DoneCondition,
            pageUrl: String,
            pageTitle: String,
        ): RepairedCondition? = null
    }

    private val ladder = Ladder(
        summary = "an impossible marker",
        subtasks = listOf(
            Subtask("an impossible marker", DoneCondition.MarkerText("Never On This Page")),
        ),
        taskClass = LadderTaskClass.ACTION,
    )

    /** The thin-tree page (the code-owned escalation signal): fewer than 5 meaningful nodes. */
    private val deadPage = obs("chrome://newtab")

    private fun tempEvidence(runId: String): Pair<VisionEvidence, File> {
        val base = Files.createTempDirectory("p18-hybrid-terminal").toFile()
        return VisionEvidence(base, runId) to File(base, "$runId/events.log")
    }

    @Test
    fun `a hybrid run ending STUCK_KILL writes the mandatory terminal line into the shared record`() =
        runBlocking {
            val tool = FailingVisionTool("the battery red-flagged the page")
            val fixture = LoopFixture(
                script = scriptOf(),
                observations = listOf(deadPage),
                presenter = ScriptedGatePresenter(),
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
            )
            val (evidence, eventsFile) = tempEvidence("hybrid-stuck-kill")
            val loop = NavigationLoop(
                engine = fixture.engine,
                observer = FakeObserver(listOf(deadPage)),
                executor = fixture.executor,
                gatePresenter = ScriptedGatePresenter(),
                logSink = fixture.sink,
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
                ladderSource = ScriptedLadderSource(ladder),
                visionTool = tool,
                // THE FIX: the shared record rides the host (the bridge passes the tool
                // loop's own instance in the product wiring).
                runEvidence = evidence,
            )

            val result = loop.run(
                goal = "open m0.test and find the target marker",
                goalId = "hybrid-terminal",
                engineIdentity = identity,
            )

            assertEquals(TerminalState.STUCK_KILL, result.terminal, result.stepLog.joinToString("\n"))
            assertEquals(
                listOf(0, 0, 0),
                tool.calls,
                "three thin-tree escalations drove the failure path: calls=${tool.calls} " +
                    result.stepLog.joinToString("\n"),
            )
            assertTrue(
                result.terminalDetail.contains("the owner said stop (escalation card)"),
                "the defect run's exact terminal shape: ${result.terminalDetail}",
            )
            val lines = eventsFile.readText().lines().filter { it.isNotBlank() }
            val terminalLines = lines.filter { "terminal=" in it }
            assertEquals(1, terminalLines.size, "exactly ONE terminal line: $lines")
            val terminal = terminalLines.single()
            assertTrue(
                "terminal=STUCK_KILL steps=0 replans=2 modality=HYBRID" in terminal,
                "the terminal, the counts and the record's modality ride ONE line: $terminal",
            )
            assertTrue(
                "escalations=3" in terminal,
                "the line carries the run's actual tree->vision flip count: $terminal",
            )
            assertTrue(
                "calls=" in terminal && "jev=" in terminal && "haiku=" in terminal &&
                    "images=" in terminal && "battery=" in terminal,
                "the always-vision line's record fields are mirrored: $terminal",
            )
            assertFalse(" answer=" in terminal, "no answer was extracted - none is written: $terminal")
        }

    @Test
    fun `the shared record's call ledger rides the line - the tool's calls are counted`() =
        runBlocking {
            val tool = FailingVisionTool("the battery red-flagged the page")
            val fixture = LoopFixture(
                script = scriptOf(),
                observations = listOf(deadPage),
                presenter = ScriptedGatePresenter(),
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
            )
            val (evidence, eventsFile) = tempEvidence("hybrid-shared-ledger")
            // One call the tool's loop already made, recorded into the SAME instance the
            // bridge shares between the tool loop and the host (the product wiring's
            // shape): the terminal line's ledger must report it.
            evidence.call(
                kind = "propose",
                requestBody = "{}",
                responseBody = "{}",
                latencyMs = 1,
                usage = null,
                channel = VisionEvidence.Channel.HAIKU,
            )
            val loop = NavigationLoop(
                engine = fixture.engine,
                observer = FakeObserver(listOf(deadPage)),
                executor = fixture.executor,
                gatePresenter = ScriptedGatePresenter(),
                logSink = fixture.sink,
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
                ladderSource = ScriptedLadderSource(ladder),
                visionTool = tool,
                runEvidence = evidence,
            )

            loop.run(
                goal = "open m0.test and find the target marker",
                goalId = "hybrid-ledger",
                engineIdentity = identity,
            )

            val terminal = eventsFile.readText().lines().single { "terminal=" in it }
            assertTrue(
                "calls=1" in terminal && "haiku=1" in terminal,
                "the SHARED record's ledger rides the line (the tool's calls included): $terminal",
            )
        }

    @Test
    fun `no record wired - no events log line (the tree-only evidence law is unchanged)`() =
        runBlocking {
            val fixture = LoopFixture(
                script = scriptOf(choose("triage", "lookup")),
                observations = listOf(deadPage),
                presenter = ScriptedGatePresenter(),
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
            )
            val base = Files.createTempDirectory("p18-treeonly").toFile()
            val loop = NavigationLoop(
                engine = fixture.engine,
                observer = FakeObserver(listOf(deadPage)),
                executor = fixture.executor,
                gatePresenter = ScriptedGatePresenter(),
                logSink = fixture.sink,
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
                // runEvidence defaults to null: the a11y engine's record is NOT events.log
                // (doc 05 §4) and the unwired loop must write nothing anywhere.
            )

            val result = loop.run(
                goal = "open m0.test and find the target marker",
                goalId = "tree-only",
                engineIdentity = identity,
            )

            assertEquals(TerminalState.REFUSED, result.terminal, result.stepLog.joinToString("\n"))
            assertTrue(
                base.walkTopDown().none { it.name == "events.log" },
                "an unwired loop leaves no evidence file at all",
            )
        }
}
