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
 * PHASE_17 Stage 0.7 — the a11y recovery bound (the R4-tree defect): a `back` that leaves
 * Chrome on a surface with no usable tree (the home screen, an empty tab) used to earn
 * the FULL unusable-read budget (~20 fresh reads, ~30 s of emulator time) and then refuse
 * the WHOLE task.
 *
 * The law now: after a successful back an unusable page gets a SHORT confirm bound
 * ([NavigationLoop.Config.refuseAfterBackUnusableReads] = 3); if it stays unreadable the
 * SUBTASK fails into the existing failure/re-plan path — the ladder can recover (its
 * re-plan re-opens the site) — and the task is never refused for a recoverable surface.
 */
class RecoveryScrollTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    private val usablePage = obs(
        "http://m0.test/a",
        N("Header"),
        N("Body one"),
        N("Body two"),
        N("A link", clickable = true),
        N("Spacer", scrollable = true),
    )

    /** No nodes, no lines: the loop's `usable` bar (minUsableNodes) is not met. */
    private val deadPage = obs("chrome://newtab")

    /** A one-subtask ladder so the failure path has an ACTIVE subtask to fail. */
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

    private val ladder = Ladder(
        summary = "find the target marker on m0.test",
        subtasks = listOf(
            Subtask("find the target marker on m0.test", DoneCondition.MarkerText("target marker")),
        ),
        taskClass = LadderTaskClass.ACTION,
    )

    /** Repeats the last observation forever, counting every read in the fixture's journal. */
    private fun LoopFixture.repeatingObserver(pages: List<StepObservation>): LoopObserver =
        object : LoopObserver {
            private var index = 0
            override suspend fun observe(): StepObservation {
                journal.add("observe")
                return if (index < pages.size) pages[index++] else pages.last()
            }
        }

    @Test
    fun `a back into an unusable page fails the subtask after 3 reads - not the whole task after 20`() =
        runBlocking {
            val fixture = LoopFixture(
                script = scriptOf(
                    choose("triage", "lookup"),
                    *intentStep("go-back"),
                    *intentStep("go-back"),
                    noul(0.9),
                ),
                observations = listOf(usablePage, deadPage),
                presenter = ScriptedGatePresenter(),
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0),
            )
            val loop = NavigationLoop(
                engine = fixture.engine,
                observer = fixture.repeatingObserver(listOf(usablePage, deadPage)),
                executor = fixture.executor,
                gatePresenter = ScriptedGatePresenter(),
                logSink = fixture.sink,
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0),
                ladderSource = ScriptedLadderSource(ladder),
            )

            // 1 initial read + 1 post-back read + the 3-read bounded confirm = 5; the
            // NEXT iteration's cancel lands after the subtask failure was recorded.
            val result = loop.run(
                goal = "open m0.test and check it",
                goalId = "recovery-back",
                engineIdentity = identity,
                isCancelled = { fixture.journal.countOf("observe") >= 5 },
            )

            assertTrue(
                result.stepLog.any { it.contains("the back left an unusable page") },
                "the bounded recovery must name the cause: ${result.stepLog}",
            )
            assertTrue(
                result.stepLog.any {
                    it.contains("ladder: subtask 1/1 FAILED (the back left an unusable page")
                },
                "the SUBTASK fails into the re-plan path: ${result.stepLog}",
            )
            assertTrue(
                result.stepLog.none { it.contains("tree still unusable - graceful refusal") },
                "the task must not be refused for a recoverable back: ${result.stepLog}",
            )
            assertEquals(
                5,
                fixture.journal.countOf("observe"),
                "the dead page is read 3 times (bound 3), never the full 20: " +
                    fixture.journal.entries,
            )
        }

    @Test
    fun `a dead page that was never left by a back keeps the full read bound and refuses`() =
        runBlocking {
            // The Stage 0.7 change is scoped to a BACK-CAUSED surface: a page that was
            // dead from the start keeps the task-level behavior (the full bound, then the
            // graceful refusal).
            val fixture = LoopFixture(
                script = scriptOf(choose("triage", "lookup")),
                observations = listOf(deadPage),
                presenter = ScriptedGatePresenter(),
                config = NavigationLoop.Config(settleMs = 0, unusableRetryDelayMs = 0),
            )
            val result = fixture.loop.run("open m0.test and check it", "recovery-dead", identity, fixture.clock)

            assertEquals(TerminalState.REFUSED, result.terminal)
            assertTrue(
                result.stepLog.any { it.contains("tree still unusable - graceful refusal") },
                "no back happened: the task refusal stands: ${result.stepLog}",
            )
            assertTrue(
                fixture.journal.countOf("observe") >= 20,
                "the full read bound ran: ${fixture.journal.entries}",
            )
        }
}
