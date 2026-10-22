package navigator.app.loop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.Intents
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.Option
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass
import navigator.core.ladder.ReplanContext
import navigator.core.ladder.Subtask
import navigator.core.log.EngineIdentity

/**
 * PHASE_17 Gate 17.0.1 — the a11y side of the completed-prefix audit. The loop completes a
 * `urlHost` subtask, the page then leaves that host (the R2 Google drift), and three
 * failures on the active subtask must roll the pointer back instead of re-planning again.
 */
class PrefixRollbackLoopTest {

    private class ScriptedEngine(
        private val actions: List<String>,
        private val nouls: List<Double>,
    ) : DecisionEngine {
        private val actionQueue = ArrayDeque(actions)
        private val noulQueue = ArrayDeque(nouls)
        private var currentPick: String? = null

        private fun choice(name: String, chosen: String, options: List<Option>) = ChoiceResult(
            chosenId = chosen,
            probabilities = options.associate { it.id to (if (it.id == chosen) 0.95 else 0.02) },
            confidence = 0.95,
        )

        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
            if (question.name == "recovery") {
                return choice(question.name, "scroll", question.options)
            }
            val pick = actionQueue.removeFirstOrNull()
                ?: throw IllegalStateException("no scripted action answer")
            currentPick = pick
            val intent = if (question.name == Intents.INTENT_QUESTION_NAME) {
                Intents.ACTIVATE_CONTROL
            } else {
                pick
            }
            return choice(question.name, intent, question.options)
        }

        override suspend fun scoreBatch(
            state: String,
            questions: Map<String, ScoreQuestion>,
        ): Map<String, ScoreResult> {
            val gold = currentPick
            currentPick = null
            return questions.mapValues { (name, question) ->
                val level = if (gold != null && name == "cand-$gold") 5 else 1
                val probabilities = question.levels.mapIndexed { index, _ ->
                    (index + 1) to if (index + 1 == level) 1.0 else 0.0
                }.toMap()
                ScoreResult(
                    expectedLevel = navigator.core.decision.scoreExpectedLevel(probabilities),
                    levelProbabilities = probabilities,
                )
            }
        }

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult =
            NoulResult(noulQueue.removeFirstOrNull() ?: error("no scripted noul answer"))

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            throw UnsupportedOperationException("score is not exercised")
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

    private fun node(text: String) = N(text, clickable = true)

    private val home = obs(
        "http://m0.test/page",
        node("Search"),
        node("Account"),
        node("Menu"),
        node("Help"),
        N("A readable body line", clickable = false),
    )

    private val foreign = obs(
        "http://google.test/search",
        node("Search"),
        node("Sign in"),
        node("Settings"),
        node("Menu"),
        N("A readable body line", clickable = false),
    )

    private val plan = Ladder(
        summary = "open the site, find the channel, play the video",
        subtasks = listOf(
            Subtask("open m0.test", DoneCondition.UrlHost("m0.test")),
            Subtask("find the channel", DoneCondition.MarkerText("WWE")),
            Subtask("play the video", DoneCondition.ElementPresent("video player")),
        ),
        taskClass = LadderTaskClass.ACTION,
    )

    @Test
    fun `the lost-host prefix rolls the pointer back before the third failure card`() {
        var iterations = 0
        val result = runBlocking {
            NavigationLoop(
                engine = ScriptedEngine(
                    actions = List(20) { "n0" },
                    nouls = listOf(0.95),
                ),
                observer = FakeObserver(listOf(home, foreign)),
                executor = FakeExecutor(Journal()),
                gatePresenter = AutoDenyGatePresenter,
                logSink = CollectingLogSink(),
                config = NavigationLoop.Config(settleMs = 0),
                ladderSource = ScriptedLadderSource(plan),
                escalationCardPresenter = StopEscalationPresenter,
            ).run(
                goal = "find the channel on m0.test and play the latest video",
                goalId = "prefix",
                engineIdentity = EngineIdentity("stub", "stub"),
                isCancelled = { ++iterations > 12 },
            )
        }

        assertTrue(
            result.stepLog.any { it.contains("PREFIX ROLLBACK: subtask 1/3") },
            result.stepLog.joinToString("\n"),
        )
        assertTrue(
            result.stepLog.any { it.contains("IS-DONE verified") },
            "the completed URL subtask must have been verified first",
        )
    }
}
