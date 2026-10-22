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
 * PHASE_17 Stage 0.8 — the same-action binding's L2 persistence (the R1-PT five-submit
 * loop, NB-2). The loop repeats the same `SUBMIT` on the same screen; at L2 the action
 * key is banned for the subtask, so the stage-2 slate can no longer select it.
 */
class TreeAcceptTest {

    private class ScriptedEngine(private val actions: List<String>) : DecisionEngine {
        private val actionQueue = ArrayDeque(actions)
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
                Intents.SUBMIT
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
            throw IllegalStateException("the condition never satisfies in this test")

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

    private fun node(text: String) = N(text, clickable = true, button = true)

    private val page = obs(
        "http://m0.test/page",
        node("Search"),
        node("Account"),
        node("Menu"),
        node("Help"),
        N("A readable body line", clickable = false),
    )

    private val plan = Ladder(
        summary = "submit the same control",
        subtasks = listOf(Subtask("submit the search", DoneCondition.ElementPresent("never"))),
        taskClass = LadderTaskClass.ACTION,
    )

    @Test
    fun `the L2 same-action trip bans the SUBMIT key for the subtask`() {
        var iterations = 0
        val result = runBlocking {
            NavigationLoop(
                engine = ScriptedEngine(actions = List(30) { "n0" }),
                observer = FakeObserver(listOf(page)),
                executor = FakeExecutor(Journal()),
                gatePresenter = AutoDenyGatePresenter,
                logSink = CollectingLogSink(),
                config = NavigationLoop.Config(settleMs = 0),
                ladderSource = ScriptedLadderSource(plan),
                escalationCardPresenter = StopEscalationPresenter,
            ).run(
                goal = "submit the search",
                goalId = "tree-accept",
                engineIdentity = EngineIdentity("stub", "stub"),
                isCancelled = { ++iterations > 25 },
            )
        }

        assertTrue(
            result.stepLog.any { it.contains("same action banned for this subtask: SUBMIT:") },
            result.stepLog.joinToString("\n"),
        )
    }
}
