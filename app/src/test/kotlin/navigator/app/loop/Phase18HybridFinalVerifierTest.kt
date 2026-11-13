package navigator.app.loop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
 * PHASE_18 Stage 18.E part 1 — GATE 18.E3 (doc 04 §9.6 via §8; the 18.C deferral closed):
 * the hybrid host's OWN completion terminals pass through the ONE shared final verifier
 * the vision loop uses (`FinalGoalVerifier` over `GoalCompletionEvidence`).
 *
 * Pinned here (scripted, $0, no model call):
 *
 * 1. the vision tool's FINAL-subtask accept — the §8 boundary's success — invokes the
 *    shared verifier EXACTLY ONCE on a FRESH observation and, all evidence in place,
 *    completes GOAL_ACHIEVED (the verdict recorded in the SHARED evidence record);
 * 2. a DEFICIENT verification at that accept returns to the controller with the
 *    deficiency (the existing failure/re-plan path) — never a false GOAL_ACHIEVED, and
 *    the hook still counts exactly one verifier call;
 * 3. the host's own a11y completion (the code-triggered IS-DONE accept of the FINAL
 *    subtask) invokes the shared verifier exactly once before its success terminal;
 * 4. a deficient host completion (the R4 account-evidence law) refuses the success —
 *    the run keeps working and ends honestly.
 *
 * The three code-owned escalation signals are untouched: the thin/empty-tree signal
 * still flips the subtask to the tool with its exact cause lines (the existing
 * `ModalityTest`/`HybridTerminalEvidenceTest` pins stay green UNMODIFIED).
 *
 * Vision law: Project Vision/04 §8 (the tree-first loop) + §9.6 (the single final
 * verifier) + §9.5 (the runtime prefix the verifier reads).
 */
class Phase18HybridFinalVerifierTest {

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

    /** The tool the TREE-path scenarios wire: any escalation is a test failure. */
    private class NeverCalledTool : VisionToolStep {
        override suspend fun runSubtask(
            goal: String,
            ladder: Ladder,
            index: Int,
            isCancelled: () -> Boolean,
        ): SubtaskOutcome = throw IllegalStateException("the tree path must not escalate")
    }

    /** The ladder source with generation 1 ONLY (every re-plan call refused). */
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

    /** The thin/empty tree (the code-owned escalation signal): fewer than 5 meaningful nodes. */
    private val deadPage = obs("chrome://newtab")

    /** A usable page on the m0.test host (the final condition's evidence). */
    private val m0Page = obs(
        "http://m0.test/page",
        N("Header"),
        N("Body one"),
        N("Body two"),
        N("A link", clickable = true),
        N("Spacer", scrollable = true),
    )

    /** A usable page on ANOTHER host (the deficient-completion shape). */
    private val foreignPage = obs(
        "http://other.test/x",
        N("Header"),
        N("Body one"),
        N("Body two"),
        N("A link", clickable = true),
        N("Spacer", scrollable = true),
    )

    private val ladder = Ladder(
        summary = "find the header on m0.test then land on the m0.test page",
        subtasks = listOf(
            // The marker IS on the m0.test page the tool completes on — the kept proof
            // revalidates on the gate's fresh read (a coherent scenario).
            Subtask("find the Header text", DoneCondition.MarkerText("Header")),
            Subtask("land on the m0.test page", DoneCondition.UrlHost("m0.test")),
        ),
        taskClass = LadderTaskClass.ACTION,
    )

    private fun tempEvidence(runId: String): Pair<VisionEvidence, File> {
        val base = Files.createTempDirectory("p18-hybrid-verifier").toFile()
        return VisionEvidence(base, runId) to File(base, "$runId/events.log")
    }

    @Test
    fun `case 1 - the tool's final accept passes the shared verifier exactly once and completes`() =
        runBlocking {
            val tool = FakeVisionTool(SubtaskOutcome.Done(steps = 3), SubtaskOutcome.Done(steps = 2))
            val fixture = LoopFixture(
                script = scriptOf(),
                observations = listOf(deadPage),
                presenter = ScriptedGatePresenter(),
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
            )
            val (evidence, eventsFile) = tempEvidence("hybrid-verify-accept")
            val loop = NavigationLoop(
                engine = fixture.engine,
                observer = object : LoopObserver {
                    private var index = 0
                    override suspend fun observe(): StepObservation {
                        // The read sequence: two dead reads (escalation 1), the tool
                        // accept's FRESH proof read (the m0 page it completed on), two
                        // dead reads (escalation 2), then the gate's FRESH read.
                        index += 1
                        return if (index == 3 || index >= 6) m0Page else deadPage
                    }
                },
                executor = fixture.executor,
                gatePresenter = ScriptedGatePresenter(),
                logSink = fixture.sink,
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
                ladderSource = ScriptedLadderSource(ladder),
                visionTool = tool,
                runEvidence = evidence,
            )

            val result = loop.run(
                goal = "open m0.test and land on its page",
                goalId = "hybrid-verify-accept",
                engineIdentity = identity,
            )

            assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, result.stepLog.joinToString("\n"))
            assertEquals(listOf(0, 1), tool.calls, "two escalations ran subtask 1 then the FINAL subtask")
            assertEquals(
                1,
                loop.finalVerifierInvocations,
                "exactly ONE shared-verifier call at the §8 boundary's success: ${result.stepLog}",
            )
            assertTrue(
                result.stepLog.any { it.contains("the final verifier ACCEPTS the completion") },
                result.stepLog.joinToString("\n"),
            )
            val lines = eventsFile.readText().lines().filter { it.isNotBlank() }
            assertTrue(
                lines.any { "final-verifier verdict=ACCEPT" in it },
                "the verdict rides the SHARED record: $lines",
            )
            val terminal = lines.single { "terminal=" in it }
            assertTrue("terminal=GOAL_ACHIEVED" in terminal && "modality=HYBRID" in terminal, terminal)
            assertTrue("escalations=2" in terminal, "the run's actual flip count: $terminal")
        }

    @Test
    fun `case 2 - a deficient verification at the tool's final accept returns to the controller`() =
        runBlocking {
            val tool = FakeVisionTool(SubtaskOutcome.Done(steps = 3), SubtaskOutcome.Done(steps = 2))
            val fixture = LoopFixture(
                script = scriptOf(),
                observations = listOf(deadPage),
                presenter = ScriptedGatePresenter(),
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
            )
            val (evidence, eventsFile) = tempEvidence("hybrid-verify-deficient")
            var observes = 0
            val loop = NavigationLoop(
                engine = fixture.engine,
                observer = object : LoopObserver {
                    private var index = 0
                    override suspend fun observe(): StepObservation {
                        observes += 1
                        // The read sequence as in case 1, but the gate's FRESH read (the
                        // 6th) lands on the WRONG host — the deficiency's evidence.
                        index += 1
                        return when {
                            index == 3 -> m0Page
                            index >= 6 -> foreignPage
                            else -> deadPage
                        }
                    }
                },
                executor = fixture.executor,
                gatePresenter = ScriptedGatePresenter(),
                logSink = fixture.sink,
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0, refuseAfterUnusableReads = 2),
                ladderSource = ScriptedLadderSource(ladder), // gen >= 2 refuses: the failure path runs
                visionTool = tool,
                runEvidence = evidence,
            )

            val result = loop.run(
                goal = "open m0.test and land on its page",
                goalId = "hybrid-verify-deficient",
                engineIdentity = identity,
                isCancelled = { observes >= 6 },
            )

            assertNotEquals(
                TerminalState.GOAL_ACHIEVED,
                result.terminal,
                "a deficient verification must never yield GOAL_ACHIEVED: ${result.stepLog.joinToString("\n")}",
            )
            assertEquals(TerminalState.USER_CANCEL, result.terminal, result.stepLog.joinToString("\n"))
            assertEquals(
                1,
                loop.finalVerifierInvocations,
                "exactly ONE shared-verifier call - the refusal does not re-verify: ${result.stepLog}",
            )
            assertTrue(
                result.stepLog.any { it.contains("ACCEPT REFUSED") && it.contains("the code checker is authoritative") },
                "the deficiency is loud and named: ${result.stepLog.joinToString("\n")}",
            )
            assertTrue(
                result.stepLog.any { it.contains("ladder: subtask 2/2 FAILED (final verification:") },
                "the deficiency returned to the controller (the failure path owns it): ${result.stepLog.joinToString("\n")}",
            )
            val lines = eventsFile.readText().lines().filter { it.isNotBlank() }
            assertTrue(
                lines.any { "final-verifier verdict=DEFICIENT" in it },
                "the refusal rides the SHARED record: $lines",
            )
        }

    @Test
    fun `case 3 - the host's own a11y completion passes the shared verifier exactly once`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(noul(0.9)), // the code-triggered check's anchored judge
            observations = listOf(m0Page),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(settleMs = 0),
        )
        val (evidence, eventsFile) = tempEvidence("hybrid-verify-tree")
        val loop = NavigationLoop(
            engine = fixture.engine,
            observer = FakeObserver(listOf(m0Page)),
            executor = fixture.executor,
            gatePresenter = ScriptedGatePresenter(),
            logSink = fixture.sink,
            config = NavigationLoop.Config(settleMs = 0),
            ladderSource = ScriptedLadderSource(
                Ladder(
                    summary = "land on the m0.test page",
                    subtasks = listOf(
                        Subtask("land on the m0.test page", DoneCondition.UrlHost("m0.test")),
                    ),
                    taskClass = LadderTaskClass.ACTION,
                ),
            ),
            visionTool = NeverCalledTool(), // the hybrid is WIRED; the tree completes without it
            runEvidence = evidence,
        )

        val result = loop.run(
            goal = "open m0.test and land on its page",
            goalId = "hybrid-verify-tree",
            engineIdentity = identity,
        )

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, result.stepLog.joinToString("\n"))
        assertEquals(
            1,
            loop.finalVerifierInvocations,
            "the host's OWN completion terminal is gated by the SAME verifier, exactly once: ${result.stepLog}",
        )
        assertTrue(
            result.stepLog.any { it.contains("the final verifier ACCEPTS the completion") },
            result.stepLog.joinToString("\n"),
        )
        assertTrue(
            eventsFile.readText().contains("final-verifier verdict=ACCEPT"),
            "the verdict rides the SHARED record",
        )
    }

    @Test
    fun `case 4 - a deficient host completion (the R4 account-evidence law) refuses the success`() =
        runBlocking {
            val fixture = LoopFixture(
                script = scriptOf(noul(0.9)),
                observations = listOf(m0Page),
                presenter = ScriptedGatePresenter(),
                config = NavigationLoop.Config(settleMs = 0),
            )
            var observes = 0
            val loop = NavigationLoop(
                engine = fixture.engine,
                observer = object : LoopObserver {
                    override suspend fun observe(): StepObservation {
                        observes += 1
                        return m0Page
                    }
                },
                executor = fixture.executor,
                gatePresenter = ScriptedGatePresenter(),
                logSink = fixture.sink,
                config = NavigationLoop.Config(settleMs = 0),
                ladderSource = ScriptedLadderSource(
                    Ladder(
                        summary = "change the profile then land on m0.test",
                        subtasks = listOf(
                            // The account token rides the instruction (the contract's
                            // binding check passes); the PAGE carries no account evidence.
                            Subtask("change the active profile to ViewerTwo", DoneCondition.UrlHost("m0.test")),
                        ),
                        taskClass = LadderTaskClass.ACTION,
                    ),
                ),
                visionTool = NeverCalledTool(),
            )

            val result = loop.run(
                goal = "open m0.test and change the active profile to ViewerTwo",
                goalId = "hybrid-verify-account",
                engineIdentity = identity,
                // The refusal keeps the run working; cancel at the next iteration's top
                // (one observation was made inside the refused completion step).
                isCancelled = { observes >= 1 },
            )

            assertNotEquals(
                TerminalState.GOAL_ACHIEVED,
                result.terminal,
                "no account evidence - no success terminal (doc 04 §9.6, the R4 law): " +
                    result.stepLog.joinToString("\n"),
            )
            assertEquals(
                1,
                loop.finalVerifierInvocations,
                result.stepLog.joinToString("\n"),
            )
            assertTrue(
                result.stepLog.any { it.contains("ACCEPT REFUSED") && it.contains("account end state") },
                "the class-evidence deficiency is named: ${result.stepLog.joinToString("\n")}",
            )
        }
}
