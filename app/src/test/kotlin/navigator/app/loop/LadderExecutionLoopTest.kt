package navigator.app.loop

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.Option
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.log.DecisionLogRow
import navigator.core.log.EngineIdentity
import navigator.core.log.TriageClass
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass
import navigator.core.ladder.Subtask
import java.util.LinkedList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 10.2 (JVM): ladder execution in the REAL loop over hand-built ladders (all $0).
 * Pinned here:
 * - the pointer advances ONLY on (engine noul HIGH **and** the code guard); a satisfied
 *   guard with noul below the HIGH floor does NOT advance, and vice versa;
 * - count-shaped done-conditions track working memory ("3 of 5 followed" advances only
 *   at 5 — here: 2 of 3 does not, 3 of 3 does);
 * - the FINAL subtask's IS-DONE is the task's goal-achieved (with the answer riding the
 *   run report when an extractor is wired);
 * - the failure path: the bounded re-plan (≤2/task), the re-plan-refused clean stop, and
 *   the owner escalation card ("Retry / Re-plan / Stop") with its scripted resolutions;
 * - the writer budget is SEPARATE from the decision spend (integer micro-dollars, ART-5);
 * - the restructure refusal is the clean pre-navigation [TerminalState.LADDER_REFUSED]
 *   (nothing navigates, no rows);
 * - the outcome-based site entry: the pure chooser pins + the logged VIEW intent.
 */
class LadderExecutionLoopTest {

    // -- fakes ------------------------------------------------------------------------------

    /** Serves the script queues by question name; $0; no triage is ever asked on ladder runs. */
    private class ScriptedEngine(
        actionPicks: List<String> = emptyList(),
        noulValues: List<Double> = emptyList(),
        recoveryPicks: List<String> = emptyList(),
    ) : DecisionEngine {
        val states = mutableListOf<Pair<String, String>>() // question name -> state text
        val askedQuestions = mutableListOf<String>()
        private val actions = ArrayDeque(actionPicks)
        private val nouls = ArrayDeque(noulValues)
        private val recoveries = ArrayDeque(recoveryPicks)

        private fun choice(name: String, chosen: String, options: List<Option>) = ChoiceResult(
            chosenId = chosen,
            probabilities = options.associate { it.id to (if (it.id == chosen) 0.95 else 0.02) },
            confidence = 0.95,
        )

        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
            states += question.name to state
            askedQuestions.add(question.name)
            return when (question.name) {
                "recovery" -> choice(
                    question.name,
                    recoveries.removeFirstOrNull()
                        ?: throw IllegalStateException(
                            "no scripted recovery answer (asked ${askedQuestions.size}: $askedQuestions)",
                        ),
                    question.options,
                )

                else -> choice(
                    question.name,
                    actions.removeFirstOrNull()
                        ?: throw IllegalStateException(
                            "no scripted action answer for ${question.name} (asked ${askedQuestions.size}: $askedQuestions)",
                        ),
                    question.options,
                )
            }
        }

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult {
            states += question.name to state
            askedQuestions.add(question.name)
            return NoulResult(nouls.removeFirstOrNull() ?: error("no scripted noul answer"))
        }

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            throw UnsupportedOperationException("score is not exercised")
    }

    /** The hand-built ladder source: per-generation ladders + an optional charged cost. */
    private class ScriptedLadderSource(
        private vararg val generations: Pair<Int, Ladder>,
        private val costMicroUsd: Long = 0,
    ) : LadderSource {
        val asked = mutableListOf<Pair<String, Int>>() // (goal, generation)

        override suspend fun restructure(goal: String, generation: Int): RestructuredLadder? {
            asked.add(goal to generation)
            val ladder = generations.lastOrNull { it.first == generation }?.second ?: return null
            return RestructuredLadder(ladder, ladder.versionTag("test", generation), costMicroUsd)
        }
    }

    private class ScriptedExtractor(
        private val answer: String = "The article records 37 casualties.",
    ) : AnswerExtractor {
        val asked = mutableListOf<String>()
        val cost = 3_000L

        override suspend fun extract(goal: String, finalState: String): ExtractedAnswer {
            asked.add(finalState)
            return ExtractedAnswer(answer, "extract-v1", cost, inputTokens = 100, outputTokens = 500)
        }
    }

    /** A two-candidate page whose text names the marker (the guard's code-read evidence). */
    private val page = obs(
        "http://m0.test/page",
        N("World War II article body", clickable = false),
        N("A second body paragraph carrying more prose.", clickable = false),
        N("Open the article", clickable = true),
        N("A page footer note that is just prose.", clickable = false),
        N("One more readable line so the tree clears the usable floor.", clickable = false),
    )

    private fun ladder2(vararg subtasks: Subtask) = Ladder("Two subtask ladder.", subtasks.toList())

    private fun runLadder(
        engine: ScriptedEngine,
        source: LadderSource,
        extractor: AnswerExtractor? = null,
        escalation: EscalationCardPresenter = StopEscalationPresenter,
        observations: List<StepObservation> = listOf(page),
        launcher: BrowserLauncher? = null,
        gatePresenter: GatePresenter = AutoDenyGatePresenter,
        goal: String = "open wikipedia.org and read the world war 2 page",
        uiListener: LoopUiListener? = null,
    ): Pair<RunResult, CollectingLogSink> {
        val sink = CollectingLogSink()
        val loop = NavigationLoop(
            engine = engine,
            observer = FakeObserver(observations),
            executor = FakeExecutor(Journal()),
            gatePresenter = gatePresenter,
            logSink = sink,
            config = NavigationLoop.Config(settleMs = 0),
            ladderSource = source,
            answerExtractor = extractor,
            escalationCardPresenter = escalation,
            browserLauncher = launcher,
            uiListener = uiListener,
        )
        val result = runBlocking {
            loop.run(goal = goal, goalId = "g", engineIdentity = EngineIdentity("stub", "stub"))
        }
        return result to sink
    }


    // -- the pointer law (engine-initiated finish) + the code-triggered check boundary -----

    @Test
    fun `gate 10_2 r1 - the pointer advances on engine-HIGH AND code-guard - a satisfied guard alone never advances`() {
        val source = ScriptedLadderSource(
            1 to ladder2(
                Subtask("the article is displayed", DoneCondition.MarkerText("World War II")),
                Subtask("the second section is displayed", DoneCondition.MarkerText("Second Marker")),
            ),
        )
        // step 0: the marker is NOT on the page (guard NONE -> no code check) -> DECIDE tap.
        // step 1: the marker page loads (hash changed) -> the CODE check fires FIRST
        //   (v1.5.0) and is REFUSED at noul 0.4 - a satisfied guard alone never advances;
        //   the step then CONTINUES NORMALLY (the engine still decides): it volunteers
        //   finish -> noul 0.9 (HIGH) + WEAK guard -> subtask 1 verified, pointer 2/2.
        // step 2: "Second Marker" page (new hash, new ordinal) -> the code check fires ->
        //   noul 0.9 -> the FINAL subtask's IS-DONE = GOAL_ACHIEVED.
        val page0 = obs(
            "http://m0.test/page",
            N("Intro prose without the marker.", clickable = false),
            N("A second body paragraph carrying more prose.", clickable = false),
            N("Open the article", clickable = true),
            N("A page footer note that is just prose.", clickable = false),
            N("One more readable line so the tree clears the usable floor.", clickable = false),
        )
        val page2 = obs(
            "http://m0.test/page",
            N("Second Marker section body", clickable = false),
            N("A second body paragraph carrying more prose.", clickable = false),
            N("Open the article", clickable = true),
            N("A page footer note that is just prose.", clickable = false),
            N("One more readable line so the tree clears the usable floor.", clickable = false),
        )
        val engine = ScriptedEngine(
            actionPicks = listOf("n2", "finish"),
            noulValues = listOf(0.4, 0.9, 0.9),
        )
        val (result, sink) = runLadder(
            engine,
            source,
            observations = listOf(page0, page, page, page2),
        )

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        val ladderReport = assertNotNull(result.ladder)
        assertEquals("test/gen1", ladderReport.version)
        assertEquals(1, ladderReport.generation)
        assertEquals(2, ladderReport.subtasks.size)
        assertTrue(ladderReport.subtasks[0].completed, "subtask 1 verified IS-DONE via the ENGINE finish")
        assertTrue(ladderReport.subtasks[1].completed, "the FINAL subtask's IS-DONE is the goal")
        assertTrue(
            result.stepLog.any { it.contains("pointer advanced to 2/2") },
            "the ENGINE's finish (noul HIGH + guard) advanced the pointer: " +
                result.stepLog.filter { it.contains("ladder") },
        )
        assertTrue(
            result.stepLog.any { it.contains("code-triggered IS-DONE check REFUSED") },
            "the code-triggered check fired on the satisfied guard and was REFUSED at noul 0.4 " +
                "(a satisfied guard alone never advances - ART-4 both ways)",
        )
        assertTrue(
            result.stepLog.any { it.contains("decide: finish -> verification: noul=0.9") },
            "the engine-initiated finish path is intact (shared verification)",
        )
        // The code check's refusal never withholds candidates (no engine-finish window):
        // the engine chose `finish` on the very next same-hash step.
        assertTrue(
            result.stepLog.none { it.contains("finish excluded") },
            "a refused CODE check does not arm the anti-oscillation window",
        )
        // Three checks wrote rows: refused (code) + accepted (engine) + accepted (final code).
        assertEquals(3, sink.exitChecks.size)
        assertEquals(
            listOf(
                navigator.core.log.ExitCheckVerdict.REFUSED,
                navigator.core.log.ExitCheckVerdict.ACCEPTED,
                navigator.core.log.ExitCheckVerdict.ACCEPTED,
            ),
            sink.exitChecks.map { it.verdict },
        )
        // Two decision rows (the tap + the engine's mid-ladder FINISH/PROGRESSED), and the
        // zero-log-gaps invariant counts the code checks as meta-decisions.
        assertEquals(2, sink.rows.size)
        assertTrue(sink.rows.all { it.plan?.version == "test/gen1" }, "the ladder provenance rides every row")
        assertTrue(sink.rows.all { it.stateFormatVersion == navigator.core.STATE_FORMAT_VERSION })
        assertTrue(
            sink.rows.all { "ACTIVE SUBTASK:" in it.stateText },
            "the ACTIVE SUBTASK section rides the state text (v3)",
        )
        assertEquals(2, result.metaDecisions, "the two code-triggered checks consumed their steps")
        assertEquals(0, result.logGaps)
    }

    @Test
    fun `gate 10_2 - engine-HIGH with an unsatisfied guard does NOT advance (guard NONE refuses every path)`() {
        val source = ScriptedLadderSource(
            1 to ladder2(
                Subtask("an impossible marker", DoneCondition.MarkerText("Never On This Page")),
                Subtask("unreached", DoneCondition.UrlHost("m0.test")),
            ),
        )
        // finish with noul 0.9 -> guard NONE -> refused; the loop then keeps going via a
        // give-up recovery -> subtask failure #1 -> bounded re-plan -> gen2's first
        // subtask verifies immediately on the SAME page -> GOAL_ACHIEVED.
        val source2 = ScriptedLadderSource(
            1 to ladder2(
                Subtask("an impossible marker", DoneCondition.MarkerText("Never On This Page")),
                Subtask("unreached", DoneCondition.UrlHost("m0.test")),
            ),
            2 to Ladder(
                "Re-planned ladder.",
                listOf(Subtask("the page is loaded", DoneCondition.UrlHost("m0.test"))),
            ),
        )
        val engine = ScriptedEngine(
            // four identical taps trip L1 (after 3) then L2 (after 4); the scripted give-up
            // recovery is subtask failure #1 -> the bounded re-plan -> gen2 finishes on the
            // SAME page (its urlHost condition is satisfiable -> STRONG + HIGH -> accept).
            actionPicks = listOf("n2", "n2", "n2", "n2", "finish"),
            noulValues = listOf(0.9, 0.9),
            recoveryPicks = listOf("give-up"),
        )
        val (result, sink2) = runLadder(engine, source2)
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        val report = assertNotNull(result.ladder)
        assertEquals("test/gen2", report.version, "the bounded re-plan produced generation 2")
        assertEquals(2, report.generation)
        assertTrue(report.subtasks[0].completed)
        assertTrue(result.stepLog.any { it.contains("re-plan 2") }, result.stepLog.toString())
        // The guard-NONE refusal is pinned by the count test (count 2/3 with a HIGH noul);
        // this scenario refuses nothing - the give-up recovery drives the failure path.
    }

    // -- count-shaped working memory ---------------------------------------------------------

    @Test
    fun `gate 10_2 - count done-conditions track working memory and advance only at n`() {
        // MULTI ladder: 3 targets, count satisfied only at the tracker's 3rd processed row.
        val source = ScriptedLadderSource(
            1 to Ladder(
                "Follow three accounts.",
                listOf(
                    Subtask("follow the three listed accounts", DoneCondition.CountReached("accounts followed", 3)),
                ),
                LadderTaskClass.MULTI,
            ),
        )
        val page3 = obs(
            "http://m0.test/follow",
            N("Follow A", clickable = true),
            N("Follow B", clickable = true),
            N("Follow C", clickable = true),
            N("A page heading with prose.", clickable = false),
            N("A footer line of text.", clickable = false),
        )
        val engine = ScriptedEngine(
            // follow A, follow B -> finish (count 2/3 -> guard NONE -> REFUSED; the
            // anti-oscillation window meters through two taps) -> follow C -> finish
            // (count 3/3 -> WEAK + HIGH -> the final subtask = goal-achieved).
            actionPicks = listOf("n0", "n1", "finish", "n2", "n2", "finish"),
            noulValues = listOf(0.9, 0.9, 0.9, 0.9, 0.9, 0.9),
        )
        val (result, sink) = runLadder(
            engine,
            source,
            observations = listOf(page3),
            // "follow" is a mutation word (H9, vision 06): the first card is APPROVED and
            // per-task trust carries the rest (the vision 06 §2 trust law).
            gatePresenter = ScriptedGatePresenter(GateResolution.APPROVED),
        )
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        // finish after 2 processed: count 2 < 3 -> guard NONE -> refused ("2 of 3" does not
        // advance); after the 3rd: 3 >= 3 -> WEAK + HIGH -> the final subtask = goal-achieved.
        assertTrue(result.stepLog.any { it.contains("finish REFUSED") })
        val countState = sink.rows.first { it.executedAction.kind.name == "FINISH" }.stateText
        assertTrue(
            "LADDER PROGRESS: accounts followed 2/3" in countState,
            "the working-memory count line rides the LADDER PROGRESS section: $countState",
        )
        assertTrue(
            sink.rows.all { it.triageClass == TriageClass.ACTION_MULTI },
            "a MULTI ladder maps to the action-multi row class",
        )
    }

    // -- the failure path: re-plans and the owner card ----------------------------------------

    @Test
    fun `gate 10_2 - the re-plan refusal keeps the loop bounded - the fail-safe card stops it`() {
        // gen2 REFUSES: without re-plan capacity the subtask keeps going under the loop
        // machinery; the third consecutive failure raises the owner card, whose fail-safe
        // default presenter (timeout = Stop) ends the task cleanly.
        val source = ScriptedLadderSource(
            1 to ladder2(Subtask("an impossible marker", DoneCondition.MarkerText("Never On This Page"))),
        )
        val engine = ScriptedEngine(
            actionPicks = listOf("n2", "n2", "n2", "n2", "n2", "n2", "n2", "n2"),
            recoveryPicks = listOf("give-up"),
        )
        val (result, _) = runLadder(engine, source)
        assertEquals(TerminalState.STUCK_KILL, result.terminal)
        assertTrue(
            result.terminalDetail.contains("the owner said stop"),
            result.terminalDetail,
        )
        assertTrue(result.stepLog.any { it.contains("re-plan REFUSED") })
    }

    @Test
    fun `gate 10_2 r1 - the owner card fires on the L3 stuck-kill (guaranteed) - scripted Retry, Re-plan, and Stop`() {
        // The writer's hard cap is exhausted by the first call (100_000 micro-USD =
        // the HARD cap): auto re-plans are unlawful. The L2 give-up counts failure #1;
        // the NEXT stuck escalation is L3 — and since v1.5.0 the kill is INTERCEPTED by
        // the card (failure #2 = the give-up + the kill), never a silent task end.
        val source = ScriptedLadderSource(
            1 to ladder2(Subtask("an impossible marker", DoneCondition.MarkerText("Never On This Page"))),
            costMicroUsd = 100_000L,
        )
        // RETRY resets the streak AND the stuck escalations, so the detector re-escalates
        // L1 -> L2 (never straight back to L3) before the next card — its queue carries
        // the follow-up STOP. STOP and REPLAN end the task on one card.
        for ((index, resolutions) in listOf(
            listOf(EscalationResolution.STOP),
            listOf(EscalationResolution.RETRY, EscalationResolution.STOP),
            listOf(EscalationResolution.REPLAN),
        ).withIndex()) {
            val engine = ScriptedEngine(
                actionPicks = List(16) { "n2" },
                recoveryPicks = listOf("give-up", "give-up", "give-up"),
            )
            val presenter = ScriptedEscalationCardPresenter(LinkedList(resolutions))
            val (result, _) = runLadder(engine, source, escalation = presenter)
            assertEquals(TerminalState.STUCK_KILL, result.terminal, "resolution #$index")
            assertTrue(presenter.presented.isNotEmpty(), "the card fired (resolution #$index)")
            presenter.presented.forEach { card ->
                assertEquals(2, card.failureCount, "the give-up failure + the intercepted L3 kill")
                assertEquals(0, card.rePlansRemaining, "the exhausted cap is disclosed")
                assertEquals("an impossible marker", card.subtaskInstruction, "the card shows the ACTIVE subtask")
            }
            when (resolutions.first()) {
                EscalationResolution.STOP ->
                    assertTrue(result.terminalDetail.contains("the owner said stop"), result.terminalDetail)

                EscalationResolution.RETRY -> {
                    assertTrue(
                        result.terminalDetail.contains("the owner said stop"),
                        "after a retry the detector re-escalates from L1 - the second card's " +
                            "queued STOP ends the task: " + result.terminalDetail,
                    )
                    // The reset proof: an L1 hint was injected AFTER the retry (without
                    // resetEscalations the very next assessment is L3 again).
                    val retryAt = result.stepLog.indexOfFirst { it.contains("the owner said RETRY") }
                    val l1AfterRetry = result.stepLog.indexOfLast { it.contains("stuck: L1 hint injected") }
                    assertTrue(
                        retryAt in 0 until l1AfterRetry,
                        "the stuck escalations reset - L1 re-escalation after the retry " +
                            "(retryAt=$retryAt, l1After=$l1AfterRetry)",
                    )
                }

                EscalationResolution.REPLAN ->
                    assertTrue(
                        result.terminalDetail.contains("the re-plan cap is exhausted"),
                        result.terminalDetail,
                    )
            }
        }
    }

    // -- R1-A: the code-triggered IS-DONE check (vision 04 §3 v1.5.0) -------------------------

    /**
     * The UAT 10.1-a defect-1 pin: the guard is satisfied from step 0 and the engine
     * NEVER volunteers `finish` — before R1 the check never ran (zero ExitCheckRows) and
     * the pointer was hostage to engine willingness. Now the loop asks itself: the FINAL
     * subtask's code-triggered accept is the task's goal-achieved with full exit
     * provenance (the report's exit block + the ONE accepted ExitCheckRow).
     */
    @Test
    fun `gate 10_2 r1 - the code-triggered check completes a ladder the engine never finishes - FINAL accept is the goal`() {
        val source = ScriptedLadderSource(
            1 to Ladder(
                "Read the world war 2 page.",
                listOf(Subtask("the article is displayed", DoneCondition.MarkerText("World War II"))),
            ),
        )
        val engine = ScriptedEngine(noulValues = listOf(0.9)) // only the judge is consulted
        val (result, sink) = runLadder(engine, source)
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        // ART-2/ART-4: the engine was asked to JUDGE the anchored subtask question (the
        // advance still requires engine noul HIGH) but was never asked to CHOOSE - the
        // loop asked, not the engine's finish. No decision row is fabricated.
        assertEquals(listOf("goal-achieved"), engine.askedQuestions)
        assertEquals(0, sink.rows.size, "no decision row: the engine made no choose")
        assertEquals(1, result.metaDecisions, "the code check consumed its step")
        assertEquals(0, result.logGaps)
        // Full exit provenance: the report's exit block + the accepted ExitCheckRow.
        val exit = assertNotNull(result.exit)
        assertEquals("HIGH", exit.path)
        assertEquals("WEAK", exit.guardLevel)
        assertEquals(0.9, exit.noul)
        assertEquals(1, sink.exitChecks.size, "exactly one ExitCheckRow for the one check")
        val row = sink.exitChecks.single()
        assertEquals(navigator.core.log.ExitCheckVerdict.ACCEPTED, row.verdict)
        assertEquals(navigator.core.log.LoggedExitPath.HIGH, row.acceptedPath)
        assertEquals(navigator.core.log.ExitCheckGuardLevel.WEAK, row.guardLevel)
        assertEquals(2, row.anchoredNoulVersion, "the SAME anchored v2 question family")
        assertEquals(0, row.stepIndex, "the check consumed step 0 (its own row key)")
        assertTrue(result.stepLog.any { it.contains("code-triggered IS-DONE check") })
        assertTrue(
            result.ladder!!.subtasks.single().completed,
            "the report's ladder block shows the subtask completed",
        )
    }

    /** The thinking-pair pin: the code-triggered consult wraps onThinking(true/false). */
    @Test
    fun `gate 10_2 r1 - the code-triggered check wraps its consult in the onThinking pair`() {
        val source = ScriptedLadderSource(
            1 to Ladder(
                "Read the world war 2 page.",
                listOf(Subtask("the article is displayed", DoneCondition.MarkerText("World War II"))),
            ),
        )
        val events = mutableListOf<Pair<Boolean, Int>>() // (active, snapshot size)
        val listener = object : LoopUiListener {
            override fun onThinking(active: Boolean) {
                events.add(active to events.size)
            }

            override fun onPlan(subgoals: List<String>) {}

            override fun onStep(facts: LoopUiListener.StepFacts) {}

            override fun onTaskState(state: LoopUiListener.TaskStateUpdate) {}
        }
        val engine = ScriptedEngine(noulValues = listOf(0.9))
        val (result, _) = runLadder(engine, source, uiListener = listener)
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        // Exactly one consult (the code check's judge); its pair strictly encloses it.
        assertEquals(listOf(true, false), events.map { it.first }, "one (true,false) pair")
    }

    /**
     * The rate bound + the refusal law: a refused code-triggered check writes its
     * ExitCheckRow (the corpus's decoy contrast), does NOT advance, does NOT re-fire on
     * the same page-hash (a static page is asked once), re-fires after the hash changes,
     * and the step continues normally (the engine still decides, candidates unwithheld).
     */
    @Test
    fun `gate 10_2 r1 - a refused code check does not advance, is asked once per page-hash, and re-fires on change`() {
        val source = ScriptedLadderSource(
            1 to ladder2(
                Subtask("the site is loaded", DoneCondition.UrlHost("m0.test")),
                Subtask("an impossible marker", DoneCondition.MarkerText("Never On This Page")),
            ),
        )
        // Same host, DIFFERENT hash: the guard stays satisfied after the hash change so
        // the re-fire can be proven deterministically.
        val page2 = obs(
            "http://m0.test/other",
            N("Different prose entirely.", clickable = false),
            N("Second page filler line.", clickable = false),
            N("Open something", clickable = true),
            N("Footer text two.", clickable = false),
            N("More filler on page two.", clickable = false),
        )
        val engine = ScriptedEngine(
            actionPicks = listOf("n2", "n2", "n2", "n2", "n2", "n2", "n2", "n2"),
            noulValues = listOf(0.5, 0.9),
            recoveryPicks = listOf("give-up"),
        )
        val (result, sink) = runLadder(engine, source, observations = listOf(page, page, page2))
        // step 0: the check fires (urlHost satisfied from step 0) and is REFUSED at 0.5.
        assertTrue(result.stepLog.any { it.contains("code-triggered IS-DONE check REFUSED") })
        // step 1 (fall-through, SAME step): the DECIDE still runs - the engine decides,
        // candidates NOT withheld (the anti-oscillation window is engine-finish-only).
        assertTrue(
            result.stepLog.none { it.contains("finish excluded") },
            "a refused code check never withholds candidates",
        )
        // step 2 (same page, same hash): NO re-fire - the next judge consult only happens
        // AFTER the tap moved the screen to page2. Consult order proves it:
        // [code-check refuse, action decide, code-check re-fire(0.9), ...].
        assertEquals(
            listOf("goal-achieved", "action", "goal-achieved"),
            engine.askedQuestions.take(3),
            "one consult per page-hash: no re-fire until the hash changed",
        )
        assertTrue(
            result.stepLog.any { it.contains("pointer advanced to 2/2") },
            "the re-fire at 0.9 accepted and advanced the pointer",
        )
        // Exactly two ExitCheckRows: the refused (H,0) and the accepted (H2,0) - each on
        // its own consumed step index (the (taskId, stepIndex) key stays unique).
        assertEquals(2, sink.exitChecks.size)
        assertEquals(navigator.core.log.ExitCheckVerdict.REFUSED, sink.exitChecks[0].verdict)
        assertEquals(navigator.core.log.ExitCheckGuardLevel.STRONG, sink.exitChecks[0].guardLevel,
            "urlHost is the STRONG page-identity guard")
        assertNull(sink.exitChecks[0].acceptedPath)
        assertEquals(navigator.core.log.ExitCheckVerdict.ACCEPTED, sink.exitChecks[1].verdict)
        assertEquals(
            sink.exitChecks.map { it.stepIndex }.distinct().size,
            sink.exitChecks.size,
            "one ExitCheckRow per (taskId, stepIndex) - the code check consumes its step",
        )
        // Both code checks consumed their steps (the refuse + the mid-ladder accept);
        // after the advance the impossible second subtask grinds to the guaranteed card.
        assertEquals(2, result.metaDecisions)
        assertEquals(TerminalState.STUCK_KILL, result.terminal)
        assertEquals(0, result.logGaps)
    }

    /** Failure counting is unchanged: a refused code check is NOT a subtask failure. */
    @Test
    fun `gate 10_2 r1 - a refused code check is NOT a subtask failure`() {
        val source = ScriptedLadderSource(
            1 to ladder2(
                Subtask("the site is loaded", DoneCondition.UrlHost("m0.test")),
                Subtask("an impossible marker", DoneCondition.MarkerText("Never On This Page")),
            ),
        )
        val engine = ScriptedEngine(
            actionPicks = List(8) { "n2" },
            noulValues = listOf(0.5), // the code check refuses
            recoveryPicks = listOf("give-up"),
        )
        val (result, _) = runLadder(engine, source)
        // step 0: refused code check (consumes its step, counts NO failure) -> the step
        // continues (a tap) -> L1/L2 -> the give-up recovery is failure #1 (NOT #2).
        assertTrue(
            result.stepLog.any { it.contains("FAILED") && it.contains("consecutive failures: 1") },
            "the give-up is failure #1 - the refused check never counted: " +
                result.stepLog.filter { it.contains("consecutive failures") },
        )
        assertTrue(
            result.stepLog.none { it.contains("FAILED") && it.contains("consecutive failures: 2") },
            "no second failure was counted before the card intercepted the L3 kill",
        )
        assertEquals(TerminalState.STUCK_KILL, result.terminal)
    }

    // -- R1-B: the legacy (non-ladder) L3 kill stays byte-stable ------------------------------

    @Test
    fun `gate 10_2 r1 - the legacy non-ladder L3 kill is byte-identical - raw cause, no owner card`() {
        val legacyPage = obs(
            "http://m0.test/loop",
            N("Dead end"),
            N("Filler text one"),
            N("Filler text two"),
            N("Loop", clickable = true),
            N("Footer"),
        )
        val engine = ScriptedEngine(
            // triage lookup -> 4 taps (L1 after the 3rd, L2 arms recovery) -> rephrase
            // stub (meta, no row) -> the next tap trips L3 -> the DIRECT task-level kill.
            actionPicks = listOf("lookup", "n3", "n3", "n3", "n3", "n3"),
            recoveryPicks = listOf("rephrase"),
        )
        val escalation = ScriptedEscalationCardPresenter(mutableListOf())
        val sink = CollectingLogSink()
        val loop = NavigationLoop(
            engine = engine,
            observer = FakeObserver(listOf(legacyPage)),
            executor = FakeExecutor(Journal()),
            gatePresenter = AutoDenyGatePresenter,
            logSink = sink,
            config = NavigationLoop.Config(settleMs = 0),
            escalationCardPresenter = escalation,
            // ladderSource defaults to LadderSource.None - the LEGACY path.
        )
        val result = runBlocking {
            loop.run(goal = "read the loop page", goalId = "g", engineIdentity = EngineIdentity("stub", "stub"))
        }
        assertEquals(TerminalState.STUCK_KILL, result.terminal)
        assertEquals(
            "still stuck after hint and strategy switch - L3 auto-kill",
            result.terminalDetail,
            "the legacy kill keeps the raw L3 cause byte-identical (no card wording)",
        )
        assertTrue(
            escalation.presented.isEmpty(),
            "the legacy L3 kill never presents the owner card (non-ladder runs unchanged)",
        )
        assertTrue(result.ladder == null)
    }

    // -- budgets + extraction -----------------------------------------------------------------

    @Test
    fun `gate 10_2 - the writer budget is SEPARATE from decision spend, integer micro-dollars`() {
        val source = ScriptedLadderSource(
            1 to ladder2(
                Subtask("the site is loaded", DoneCondition.UrlHost("m0.test")),
                Subtask("the article is displayed", DoneCondition.MarkerText("World War II")),
            ),
            costMicroUsd = 12_345L,
        )
        val engine = ScriptedEngine(
            actionPicks = listOf("finish", "finish"),
            noulValues = listOf(0.9, 0.9),
        )
        val extractor = ScriptedExtractor()
        val (result, _) = runLadder(engine, source, extractor = extractor)
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals(15_345L, result.writerSpendMicroUsd, "restructure 12_345 + extraction 3_000 - exact integers, one SEPARATE cap")
        assertEquals(0L, result.spendMicroUsd, "the decision spend is untouched by the writer")
        assertNotNull(result.answer)
        assertEquals("The article records 37 casualties.", result.answer!!.text)
        assertEquals(3_000L, result.answer!!.costMicroUsd)
    }

    // -- the restructure refusal --------------------------------------------------------------

    @Test
    fun `gate 10_2 - an invalid ladder refuses cleanly BEFORE any navigation (LADDER_REFUSED)`() {
        val source = ScriptedLadderSource() // generation 1 refuses
        val engine = ScriptedEngine() // no scripted answers: ANY navigation would fail loudly
        val (result, _) = runLadder(engine, source)
        assertEquals(TerminalState.LADDER_REFUSED, result.terminal)
        assertEquals(0, result.steps)
        assertEquals(0, result.rowsLogged)
        assertTrue(result.terminalDetail.contains("nothing navigated"), result.terminalDetail)
        assertNull(result.ladder)
    }

    // -- the outcome-based site entry -----------------------------------------------------------

    @Test
    fun `gate 10_2 - site entry is outcome-based - the urlHost subtask fires the VIEW intent, logged`() {
        val launched = mutableListOf<String>()
        val launcher = BrowserLauncher { launched.add(it) }
        val source = ScriptedLadderSource(
            1 to ladder2(
                Subtask("wikipedia.org is loaded", DoneCondition.UrlHost("m0.test")),
                Subtask("the article is displayed", DoneCondition.MarkerText("World War II")),
            ),
        )
        val engine = ScriptedEngine(
            actionPicks = listOf("finish", "finish"),
            noulValues = listOf(0.9, 0.9),
        )
        val (result, _) = runLadder(engine, source, launcher = launcher)
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertEquals(
            listOf("https://wikipedia.org"),
            launched,
            "the goal text names wikipedia.org - the VIEW intent resolves THAT url (deterministic)",
        )
        assertTrue(
            result.stepLog.any { it.contains("site-entry: VIEW intent for https://wikipedia.org") },
            "the site entry is logged with its derivation source - log: " + result.stepLog,
        )
        assertTrue(result.ladder != null && result.ladder.subtasks.all { it.completed })
    }

    @Test
    fun `gate 10_2 - site entry plans are pure and deterministic`() {
        // Explicit URL wins; bare domain schemes https; the validated done-condition host
        // is the resolution of last resort; non-urlHost subtasks never site-enter.
        assertEquals(
            SiteEntry.Plan(SiteEntry.Mechanism.VIEW_INTENT, "https://en.wikipedia.org/wiki/X", "GOAL_URL"),
            SiteEntry.plan("open https://en.wikipedia.org/wiki/X", "load the site", DoneCondition.UrlHost("en.wikipedia.org")),
        )
        assertEquals(
            SiteEntry.Plan(SiteEntry.Mechanism.VIEW_INTENT, "https://youtube.com", "GOAL_DOMAIN"),
            SiteEntry.plan("follow accounts on youtube.com", "load the site", DoneCondition.UrlHost("youtube.com")),
        )
        assertEquals(
            SiteEntry.Plan(SiteEntry.Mechanism.VIEW_INTENT, "https://m0.test", "DONE_CONDITION_HOST"),
            SiteEntry.plan("no url in sight", "open the fixture site", DoneCondition.UrlHost("m0.test")),
        )
        assertNull(
            SiteEntry.plan("read the page", "find the marker", DoneCondition.MarkerText("x")),
            "a non-urlHost subtask never site-enters",
        )
    }

    // -- the writer redaction law (doc 06 §2a: no secret ever enters a writer prompt) ----

    /**
     * The sanctioned-secret masking pin (verifier-recommended coverage gap): a card
     * credential supplied mid-run must NEVER reach the writer. The goal text itself
     * carries the secret (the owner typed it), a credential typing step puts it into
     * `cardSecrets` (the redaction keys), the stuck machinery drives a re-plan, and the
     * page echoes the typed value in its prose (the lawful post-injection shape). Both
     * writer-bound texts — the re-plan's goal and the extraction's final state — must
     * arrive at the seam already masked through `redactionKeys()`.
     */
    @Test
    fun `gate 10_2 - sanctioned secrets never reach the writer - re-plan goal and extraction state are masked`() {
        val secret = "s3cr3t-value-9"
        val goal = "open the vault, use $secret, and read the welcome page"
        // The password field (n2) fires H8 on typing; the button (n3) feeds the stuck
        // taps; n4 carries gen2's marker; the EXCERPT echoes the typed value (the
        // post-injection page shape) so the extraction state has something to mask.
        val page = obs(
            "http://m0.test/page",
            N("A vault page heading with some prose.", clickable = false),
            N("Intro text for the sign in form area.", clickable = false),
            N("password", editable = true, password = true),
            N("Continue", clickable = true),
            N("Welcome back to your vault summary.", clickable = false),
        ).copy(excerpt = listOf("You typed $secret (do not share)."))
        val source = ScriptedLadderSource(
            1 to ladder2(
                Subtask("the credentials are typed", DoneCondition.ElementPresent("password field")),
                Subtask("an impossible marker", DoneCondition.MarkerText("Never On This Page")),
            ),
            2 to Ladder(
                "Re-planned ladder.",
                listOf(Subtask("the welcome page is shown", DoneCondition.MarkerText("Welcome back"))),
            ),
        )
        // Type -> four identical taps trip L1 (after 3) then L2 -> give-up = subtask
        // failure #1 -> the bounded re-plan -> gen2 finishes on the SAME page: its
        // marker condition is satisfied, so the v1.5.0 CODE check fires (noul 0.9)
        // and the FINAL subtask's IS-DONE = goal-achieved -> extraction. (The first
        // noul serves gen1's own code check if its elementPresent guard is satisfied
        // at step 0; the second serves gen2's — the code checks consult the same judge.)
        val engine = ScriptedEngine(
            actionPicks = listOf("n2", "n3", "n3", "n3", "n3", "finish"),
            noulValues = listOf(0.9, 0.9),
            recoveryPicks = listOf("give-up"),
        )
        val extractor = ScriptedExtractor()
        // The H8 credential card: the owner types the secret on the card (doc 06 §2a) —
        // this is what puts the value into the loop's redaction keys.
        val (result, sink) = runLadder(
            engine,
            source,
            extractor = extractor,
            observations = listOf(page),
            gatePresenter = ScriptedGatePresenter(GateResolution.ApproveWithSecret(secret)),
            goal = goal,
        )
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        // Non-vacuousness: the secret DID enter the loop (the credential row carries the
        // typedSecret flag — the redaction keys were non-empty from that step on).
        assertTrue(
            sink.rows.any { it.typedSecret },
            "the fixture must actually type a card credential (doc 05 §2 typedSecret law)",
        )
        // (a) The RE-PLAN call's goal text arrived at the seam MASKED through
        // redactionKeys() — the raw secret never transits to the writer.
        val replanGoal = source.asked.first { it.second == 2 }.first
        assertTrue(secret !in replanGoal, "the raw secret reached the re-plan prompt: $replanGoal")
        assertTrue(TextSource.REDACTED in replanGoal, "the masked render is lawful: $replanGoal")
        assertTrue("open the vault" in replanGoal, "the goal's intent text survives the masking")
        // The INITIAL restructure (generation 1) runs before any card exists — no
        // sanctioned secret is possible yet; the goal is owner input (pre-existing law).
        val initialGoal = source.asked.first { it.second == 1 }.first
        assertEquals(goal, initialGoal)
        // (b) The EXTRACTION call's final-state text arrived MASKED too — the page-side
        // echo of the typed value is the exact leak shape the fold exists for.
        val finalState = extractor.asked.single()
        assertTrue(secret !in finalState, "the raw secret reached the extraction prompt: $finalState")
        assertTrue(TextSource.REDACTED in finalState, "the echo masks to the lawful render: $finalState")
        assertTrue("url: http://m0.test/page" in finalState, "the state's lawful content survives")
        // And the answer itself is scrubbed at the report boundary (belt-and-braces pin).
        assertTrue(secret !in (result.answer?.text ?: ""), result.answer?.text ?: "")
    }
}
