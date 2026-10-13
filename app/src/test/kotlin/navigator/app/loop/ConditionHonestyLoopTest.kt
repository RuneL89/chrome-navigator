package navigator.app.loop

import kotlinx.coroutines.runBlocking
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.Subtask
import navigator.core.log.LoggedExitPath
import navigator.core.log.ExitCheckVerdict
import navigator.core.log.EngineIdentity
import navigator.app.gen.Prompts
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The condition-honesty gate's law (Phase 10C, vision 04 §1 v1.7.0 — owner-ratified after
 * the 2026-10-11 on-device battery): a WEAK-shaped done-condition already satisfied where
 * its subtask starts, on a subtask that NAMES a different page, is repaired (ONE bounded
 * writer call), else replaced by a code-derived URL identity, else refused — never a
 * silently self-satisfying ladder. URL identities already true stay legitimate.
 */
class ConditionHonestyLoopTest {

    /**
     * The minimal scripted engine for this file: `intent` -> the queued pick (default
     * verify-done — harmless refusals), `triage` -> lookup, `judge` -> the queued noul,
     * `recovery` -> give-up (the honest end once the stuck machinery escalates — the
     * 2026-10-13 meter fix lets repeated refused claims reach it; before, they spun
     * withheld forever).
     */
    private class HonestyEngine(
        private val noulValues: MutableList<Double>,
        private val intentPicks: MutableList<String> = mutableListOf(),
    ) : DecisionEngine {
        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
            val pick = when (question.name) {
                "triage" -> "lookup"
                "recovery" -> "give-up"
                else -> if (intentPicks.isEmpty()) "verify-done" else intentPicks.removeAt(0)
            }
            return ChoiceResult(pick, mapOf(pick to 0.95), 0.95)
        }

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult =
            NoulResult(if (noulValues.isEmpty()) 0.9 else noulValues.removeAt(0))

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            ScoreResult(
                expectedLevel = question.levels.size.toDouble(),
                levelProbabilities = question.levels.indices.associateWith { 1.0 / question.levels.size },
            )
    }


    /** A ladder source with scripted generations AND scripted repairs. */
    private class RepairingLadderSource(
        private val ladder: Ladder,
        repairs: List<RepairedCondition?>,
    ) : LadderSource {
        private val pending = repairs.toMutableList()
        val repairCalls = CopyOnWriteArrayList<String>()
        override suspend fun restructure(
            goal: String,
            generation: Int,
            failure: navigator.core.ladder.ReplanContext?,
        ): RestructuredLadder? = RestructuredLadder(ladder, "test/gen$generation", 0)

        override suspend fun repairCondition(
            subtaskInstruction: String,
            current: DoneCondition,
            pageUrl: String,
            pageTitle: String,
        ): RepairedCondition? {
            repairCalls.add(subtaskInstruction)
            return if (pending.isEmpty()) null else pending.removeAt(0)
        }
    }

    private fun page(url: String, vararg texts: String): StepObservation = obs(
        url,
        *texts.map { N(it, clickable = false) }.toTypedArray(),
        N("A second body paragraph carrying more prose.", clickable = false),
        N("Open the fixture link", clickable = true),
        N("One more readable line so the tree clears the usable floor.", clickable = false),
    )

    private fun run(
        ladder: Ladder,
        repairs: List<RepairedCondition?>,
        observations: List<StepObservation>,
        goal: String = "open allrecipes.com and open the DINNER category",
        nouls: List<Double> = listOf(0.9, 0.9),
        actionPicks: List<String> = emptyList(),
    ): Triple<RunResult, CollectingLogSink, RepairingLadderSource> {
        val source = RepairingLadderSource(ladder, repairs.toMutableList())
        val sink = CollectingLogSink()
        val engine = HonestyEngine(nouls.toMutableList(), actionPicks.toMutableList())
        val loop = NavigationLoop(
            engine = engine,
            observer = FakeObserver(observations),
            executor = FakeExecutor(Journal()),
            gatePresenter = AutoDenyGatePresenter,
            logSink = sink,
            config = NavigationLoop.Config(settleMs = 0),
            ladderSource = source,
            escalationCardPresenter = StopEscalationPresenter,
        )
        val result = runBlocking {
            loop.run(goal, "honesty", EngineIdentity("stub", "scripted-harness-v1"))
        }
        return Triple(result, sink, source)
    }

    @Test
    fun `a self-satisfying weak condition is REPAIRED by the writer - the honest condition rides the ladder on`() {
        // The battery's test-5 shape: the homepage CONTAINS the category link's word, so
        // "the DINNER category page is showing" (markerText) is already true — and the
        // subtask names a DIFFERENT page (its instruction carries the category's URL).
        val ladder = Ladder(
            "Open the dinner category.",
            listOf(
                Subtask(
                    "open allrecipes.com/recipes/dinner",
                    DoneCondition.MarkerText("Dinner"),
                ),
            ),
        )
        val repaired = RepairedCondition(
            done = DoneCondition.UrlContains("allrecipes.com/recipes/dinner"),
            promptVersion = Prompts.CONDITION_REPAIR_PROMPT_VERSION,
            costMicroUsd = 421L,
        )
        val (result, sink, source) = run(
            ladder = ladder,
            repairs = listOf(repaired),
            observations = listOf(
                page("https://allrecipes.com", "Dinner ideas for tonight", "Browse recipes"),
                page("https://allrecipes.com/recipes/dinner", "Dinner", "A rich listing of dinner recipes"),
            ),
        )

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, result.stepLog.toString())
        assertEquals(1, source.repairCalls.size, "exactly ONE bounded repair call")
        assertTrue(
            result.stepLog.any { it.contains("honesty: subtask 1/1 condition") && it.contains("bounded writer repair") },
            result.stepLog.toString(),
        )
        assertTrue(
            result.stepLog.any { it.contains("condition replaced by writer repair") },
            result.stepLog.toString(),
        )
        // the repaired identity condition is what verified (STRONG) — the report shows it
        val exit = assertNotNull(result.exit)
        assertEquals("STRONG", exit.guardLevel)
        assertEquals("HIGH", exit.path)
        assertEquals(421L, result.writerSpendMicroUsd, "the repair meters into the writer budget")
        // Two checks: the engine's early verify-done claim on the OLD page (guard NONE ->
        // refused, its row still written) and the accepted identity check on the arrival page.
        assertEquals(2, sink.exitChecks.size, sink.exitChecks.map { it.verdict }.toString())
        assertEquals(1, sink.exitChecks.count { it.verdict == ExitCheckVerdict.ACCEPTED })
        assertEquals(1, sink.exitChecks.count { it.verdict == ExitCheckVerdict.REFUSED })
        val accepted = sink.exitChecks.last()
        assertEquals(ExitCheckVerdict.ACCEPTED, accepted.verdict)
        assertEquals(LoggedExitPath.HIGH, accepted.acceptedPath)
    }

    @Test
    fun `with no repair available the gate derives the code-side URL identity and proceeds`() {
        val ladder = Ladder(
            "Open the dinner category.",
            listOf(
                Subtask(
                    "open allrecipes.com/recipes/dinner",
                    DoneCondition.MarkerText("Dinner"),
                ),
            ),
        )
        val (result, _, source) = run(
            ladder = ladder,
            repairs = listOf(null), // the writer refused / unwired
            observations = listOf(
                page("https://allrecipes.com", "Dinner ideas for tonight", "Browse recipes"),
                page("https://allrecipes.com/recipes/dinner", "Dinner recipes", "A rich listing"),
            ),
        )

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, result.stepLog.toString())
        assertEquals(1, source.repairCalls.size, "the repair was attempted once (and refused)")
        assertTrue(
            result.stepLog.any { it.contains("code-derived urlContains") },
            result.stepLog.toString(),
        )
        val exit = assertNotNull(result.exit)
        assertEquals("STRONG", exit.guardLevel, "the derived condition is URL identity")
    }

    @Test
    fun `an already-satisfied URL identity is LEGITIMATE and never enters the gate`() {
        // The subtask-1-on-the-right-site case: urlHost already true = the outcome is
        // already achieved; the code-triggered check verifies and advances (no repair).
        val ladder = Ladder(
            "Reach the fixture site.",
            listOf(Subtask("the site is loaded", DoneCondition.UrlHost("m0.test"))),
        )
        val (result, _, source) = run(
            ladder = ladder,
            repairs = emptyList(),
            observations = listOf(
                page("http://m0.test/page", "World War II article body", "More prose"),
            ),
        )

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, result.stepLog.toString())
        assertEquals(0, source.repairCalls.size, "URL identity already true is not a proxy")
        assertTrue(
            result.stepLog.none { it.contains("honesty:") },
            "the gate never fired: " + result.stepLog,
        )
        assertEquals("STRONG", assertNotNull(result.exit).guardLevel)
    }

    @Test
    fun `a weak condition satisfied by the page the subtask NAMES is not a proxy (rerun on the arrival page)`() {
        // The legitimately-arrived rerun: the named target IS the page in front of us —
        // the ordinary exit flow decides it; the gate stays silent.
        val ladder = Ladder(
            "Read the results.",
            listOf(Subtask("find mushroom recipes on allrecipes.com", DoneCondition.MarkerText("mushroom"))),
        )
        val (result, _, source) = run(
            ladder = ladder,
            repairs = emptyList(),
            observations = listOf(
                page("https://allrecipes.com/search?q=mushroom", "mushroom recipes", "Results"),
            ),
            goal = "open allrecipes.com and find mushroom recipes",
        )

        assertEquals(0, source.repairCalls.size)
        assertTrue(
            result.stepLog.none { it.contains("honesty:") },
            "the named target IS the current page - no proxy: " + result.stepLog,
        )
    }
}
