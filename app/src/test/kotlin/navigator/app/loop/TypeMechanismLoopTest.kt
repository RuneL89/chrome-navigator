package navigator.app.loop

import navigator.app.gen.GenUsage
import navigator.app.gen.PhraseResult
import navigator.app.stub.ScriptedAnswer
import navigator.app.stub.StubEngine
import navigator.app.stub.StubScript
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.Intents
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.gate.AdditiveEngineCheck
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass
import navigator.core.ladder.ReplanContext
import navigator.core.ladder.Subtask
import navigator.core.log.EngineIdentity
import navigator.core.log.LoggedActionKind
import navigator.core.log.Outcome
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The mechanism rotation in the REAL loop (doc 04 §1 v1.8.4 — the hotel-iteration run-3
 * repair; the run-3 defect: five IDENTICAL set-text+paste errors on one control). All $0,
 * JVM, Robolectric-free. Pinned here:
 * - the legs happen in order: the first fill attempt runs the executor's set-text+paste
 *   chain; the next attempt on the same candidate + unchanged page YIELDS (a real tap, no
 *   text, no text-entry failure); the next attempt EXHAUSTS loudly with the truthful
 *   mechanical cause;
 * - a yielded or exhausted fill never costs a writer call (the phrase is fetched for the
 *   typing leg only);
 * - the step log names the mechanism chain truthfully;
 * - on a LADDER run the exhaustion cause reaches the informed re-plan verbatim, classified
 *   INPUT_MECHANISM (a planning problem, not a wording problem), and the accepted re-plan
 *   RESETS the rotation memory (the new subtask's first attempt types again).
 *
 * The fake executor reproduces the real chain's cause sequence (set-text rejected, then
 * paste rejected) — the shape the run-3 device log showed five times in a row.
 */
class TypeMechanismLoopTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    /** An executor whose text entry fails like the real chain: set-text, then paste. */
    private class ChainFailExecutor(private val journal: Journal) : ActionExecutor {
        val calls = mutableListOf<String>()

        /** When true, enterText fails reporting BOTH mechanism causes in sequence. */
        var failText = true

        override fun tap(x: Int, y: Int): Boolean {
            calls.add("tap($x,$y)")
            journal.add("tap($x,$y)")
            return true
        }

        /** PHASE_VEXP5: the covered-point node activation (never exercised here). */
        override fun clickNode(x: Int, y: Int): Boolean {
            calls.add("clickNode($x,$y)")
            journal.add("clickNode($x,$y)")
            return true
        }

        override fun scroll(x: Int, y: Int, down: Boolean): Boolean {
            calls.add("scroll($x,$y,$down)")
            return true
        }

        override fun back(): Boolean {
            calls.add("back")
            return true
        }

        override fun submit(): Boolean {
            calls.add("submit")
            return true
        }

        override fun enterText(x: Int, y: Int, text: String): Boolean =
            enterText(x, y, text) { }

        override fun enterText(x: Int, y: Int, text: String, cause: (TextEntryFailure) -> Unit): Boolean {
            calls.add("text($x,$y,\"$text\")")
            if (!failText) return true
            cause(TextEntryFailure.SET_TEXT_REJECTED)
            cause(TextEntryFailure.PASTE_FAILED)
            return false
        }
    }

    /** A form page with one editable control (the fill-field target, n1 at (50,120)). */
    private fun formPage(url: String = "http://m0.test/form") = obs(
        url,
        N("A form page heading with prose."),
        N("Guest name", editable = true),
        N("Extra prose line for the floor."),
        N("More prose for the usable floor."),
        N("One more readable line so the tree clears the usable floor."),
    )

    /** Wires the real loop with the chain-failing executor and a counting phrase source. */
    private fun loop(
        script: List<ScriptedAnswer>,
        observations: List<StepObservation>,
        onPhrase: () -> Unit,
    ): Triple<NavigationLoop, ChainFailExecutor, CollectingLogSink> {
        val journal = Journal()
        val executor = ChainFailExecutor(journal)
        val sink = CollectingLogSink()
        val loop = NavigationLoop(
            engine = StubEngine(StubScript(script)),
            observer = FakeObserver(observations, journal),
            executor = executor,
            gatePresenter = ScriptedGatePresenter(),
            logSink = sink,
            config = NavigationLoop.Config(settleMs = 0),
            phraseSource = PhraseSource { _, _ ->
                onPhrase()
                PhraseResult("Ada Lovelace", GenUsage(20, 4, 48), "phrase-v1")
            },
        )
        return Triple(loop, executor, sink)
    }

    @Test
    fun `rotation - set-text legs first, then the activation yield, then the loud exhaustion`() =
        runBlocking {
            var phraseCalls = 0
            val script = scriptOf(
                choose("triage", "form-fill"),
                *intentStep("fill-field", target = "Guest name"),
                h9Low(),
                *intentStep("fill-field", target = "Guest name"),
                h9Low(),
                *intentStep("fill-field", target = "Guest name"),
                h9Low(),
            )
            val (loop, executor, sink) = loop(script, listOf(formPage())) { phraseCalls += 1 }
            val result = loop.run("fill the guest name into the form", "type-rotation", identity, FakeClock())

            // The exhaustion ends the non-ladder run with the TRUTHFUL mechanical cause.
            assertEquals(TerminalState.STUCK_KILL, result.terminal, result.stepLog.toString())
            assertTrue(
                result.terminalDetail.contains(
                    "typing into this field failed mechanically (set text, paste) - no mechanism left",
                ),
                "the terminal must carry the exhaustion cause: ${result.terminalDetail}",
            )
            // The legs happened in order: ONE typing attempt (both legs inside), then ONE
            // activation tap, then nothing (the third consult exhausted before any action).
            assertEquals(
                listOf("text(50,120,\"Ada Lovelace\")", "tap(50,120)"),
                executor.calls,
                "attempt 1 types, attempt 2 taps-and-yields, attempt 3 is refused: ${executor.calls}",
            )
            // A yielded or exhausted fill never costs a writer call.
            assertEquals(1, phraseCalls, "the phrase is fetched for the typing leg only")
            // The step log names the mechanism chain truthfully (the run-3 shape).
            val log = result.stepLog.toString()
            assertTrue(
                log.contains("fill: set-text rejected, paste rejected (paste failed) - n1"),
                "the attempt line must name the chain: $log",
            )
            assertTrue(
                log.contains("fill: activating n1 and yielding to the page (mechanism 3)"),
                "the yield line must name mechanism 3: $log",
            )
            assertTrue(
                log.contains("fill: no mechanism left for n1"),
                "the exhaustion must be loud: $log",
            )
            // The accounting: attempt 1 = an error row (today's shape), the yield = a real
            // action's NO_CHANGE row, the exhaustion = the failed-dispatch error row.
            assertEquals(Outcome.ERROR, sink.rows[0].outcome)
            assertEquals(LoggedActionKind.TYPE, sink.rows[0].executedAction.kind)
            assertEquals(LoggedActionKind.TAP, sink.rows[1].executedAction.kind)
            assertEquals(Outcome.NO_CHANGE, sink.rows[1].outcome)
            assertEquals(Outcome.ERROR, sink.rows[2].outcome)
        }

    @Test
    fun `rotation - the exhaustion cause reaches the informed re-plan as INPUT_MECHANISM`() = runBlocking {
        val ladder = Ladder(
            "Filter the target list.",
            listOf(
                Subtask(
                    "the target list is filtered by the chosen name",
                    DoneCondition.UrlContains("m0.test/never-here"),
                ),
            ),
            LadderTaskClass.READ,
        )
        val source = ScriptedLadderSource(1 to ladder, 2 to ladder)
        val executor = ChainFailExecutor(Journal())
        val sink = CollectingLogSink()
        val loop = NavigationLoop(
            engine = FillScriptEngine(
                picks = List(12) { "n1" },
                intentHints = mapOf("n1" to Intents.FILL_FIELD),
            ),
            observer = FakeObserver(listOf(formPage())),
            executor = executor,
            gatePresenter = ScriptedGatePresenter(),
            logSink = sink,
            config = NavigationLoop.Config(settleMs = 0),
            ladderSource = source,
            phraseSource = PhraseSource { _, _ ->
                PhraseResult("the chosen name", GenUsage(20, 4, 48), "phrase-v1")
            },
        )
        val result = loop.run("filter the target list by the chosen name", "type-rotation-ladder", identity, FakeClock())

        // The FIRST re-plan fired on the exhaustion, and its failure context classifies the
        // mechanical cause as a planning problem (the writer must change the ROUTE).
        val failure = assertNotNull(
            source.askedFailures.getOrNull(1),
            "the exhaustion drove an informed re-plan; steps=${result.steps} log=${result.stepLog}",
        )
        assertEquals(
            "typing into this field failed mechanically (set text, paste) - no mechanism left",
            failure.cause,
            "the cause reaches the re-plan verbatim",
        )
        assertEquals(ReplanContext.CauseClass.INPUT_MECHANISM, failure.causeClass)
        // The accepted re-plan RESETS the rotation memory: the new subtask's first attempt
        // is a typing attempt again (the second executor text call), never an instant
        // exhaustion.
        assertTrue(
            executor.calls.count { it.startsWith("text(") } >= 2,
            "the re-plan's subtask types again: ${executor.calls}",
        )
        assertTrue(
            result.stepLog.any { it.contains("input_mechanism") },
            "the re-plan's live-log line names the class truthfully: ${result.stepLog}",
        )
    }

    /** The additive-consult answer (a confident LOW leaves the plain field ungated). */
    private fun h9Low(): ScriptedAnswer = ScriptedAnswer(question = AdditiveEngineCheck.QUESTION_NAME, pTrue = 0.1)

    /** Serves the per-generation ladders (the LadderExecutionLoopTest pattern). */
    private class ScriptedLadderSource(
        private vararg val generations: Pair<Int, Ladder>,
    ) : LadderSource {
        val asked = mutableListOf<Pair<String, Int>>()
        val askedFailures = mutableListOf<ReplanContext?>()

        override suspend fun restructure(goal: String, generation: Int, failure: ReplanContext?): RestructuredLadder? {
            asked.add(goal to generation)
            askedFailures.add(failure)
            val ladder = generations.lastOrNull { it.first == generation }?.second ?: return null
            return RestructuredLadder(ladder, ladder.versionTag("test", generation), 0)
        }

        override suspend fun repairCondition(
            subtaskInstruction: String,
            current: DoneCondition,
            pageUrl: String,
            pageTitle: String,
        ): RepairedCondition? = null
    }

    /**
     * A scripted cascade engine: every stage-1 pick names a candidate id, mapped to its
     * intent by [intentHints] (the INTENT STATE VIEW carries no candidate kinds — the
     * LadderExecutionLoopTest precedent); the additive consult is answered confidently
     * LOW; nothing else is ever asked (an unexpected consult fails the test loudly).
     */
    private class FillScriptEngine(
        picks: List<String>,
        intentHints: Map<String, String> = emptyMap(),
    ) : DecisionEngine {
        private val queue = ArrayDeque(picks)
        private val hints = intentHints
        private var currentPick: String? = null

        private fun choice(question: ChoiceQuestion, chosen: String) = ChoiceResult(
            chosenId = chosen,
            probabilities = question.options.associate { it.id to (if (it.id == chosen) 0.95 else 0.02) },
            confidence = 0.95,
        )

        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
            if (question.name != Intents.INTENT_QUESTION_NAME) {
                error("unexpected choose: ${question.name}")
            }
            val pick = queue.removeFirstOrNull() ?: error("no scripted pick")
            currentPick = pick
            val intentId = hints[pick] ?: Intents.FILL_FIELD
            return choice(question, intentId)
        }

        override suspend fun scoreBatch(
            state: String,
            questions: Map<String, ScoreQuestion>,
        ): Map<String, ScoreResult> {
            val gold = currentPick
            currentPick = null
            return questions.mapValues { (name, question) ->
                val level = if (gold != null && name == "cand-$gold") 5 else 1
                val levelProbabilities = question.levels.mapIndexed { index, _ ->
                    (index + 1) to if (index + 1 == level) 1.0 else 0.0
                }.toMap()
                ScoreResult(
                    expectedLevel = navigator.core.decision.scoreExpectedLevel(levelProbabilities),
                    levelProbabilities = levelProbabilities,
                )
            }
        }

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult {
            check(question.name == AdditiveEngineCheck.QUESTION_NAME) {
                "unexpected judge: ${question.name}"
            }
            return NoulResult(0.1)
        }

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            throw UnsupportedOperationException("score is not exercised")
    }
}
