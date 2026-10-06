package navigator.app.loop

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.FallbackEngine
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.log.EngineIdentity
import navigator.core.log.ExecutedAction
import navigator.core.log.FallbackInfo
import navigator.core.log.LoggedActionKind
import navigator.core.log.TriageClass
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 8C.4 (loop half, PHASE_08C §3) — the loop's escalation wiring: when the engine is
 * a [FallbackEngine] and a step's choose escalates (primary band != HIGH), the row
 * carries `escalated=true` + the fallback provenance (doc 05 v1.2.0), the teacherAnswer
 * stays the PRIMARY's own answer, and the EXECUTED action is the FALLBACK's choice
 * (vision 02 §7: its answer is the one executed). Non-escalated steps are byte-identical
 * to the single-engine shape. The loop holds one engine throughout (ART-2 untouched).
 */
class NavigationLoopFallbackTest {

    /** Scripts triage + a queue of action answers; noul always confirms. */
    private class ScriptedEngine(
        private val triage: ChoiceResult,
        private val actions: ArrayDeque<ChoiceResult>,
        private val noulPTrue: Double = 0.95,
    ) : DecisionEngine {

        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult =
            if (question.name == Triage.QUESTION_NAME) {
                triage
            } else {
                actions.removeFirst()
            }

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult =
            NoulResult(noulPTrue)

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            throw UnsupportedOperationException("score is not exercised by the loop")
    }

    private fun choice(chosen: String, ids: List<String>, confidence: Double): ChoiceResult {
        val even = 1.0 / ids.size
        val p = ids.associateWith { if (it == chosen) confidence else even }
        return ChoiceResult(chosenId = chosen, probabilities = p, confidence = confidence)
    }

    private val page = obs(
        "http://m0.test/a",
        N("Header"),
        N("Body"),
        N("Nav"),
        N("Details", clickable = true),
        N("Next", clickable = true),
        N("Footer"),
    )

    /** The post-tap page (a DIFFERENT contentful URL — the lookup completion guard). */
    private val pageB = obs(
        "http://m0.test/b",
        N("Result headline"),
        N("Result body line one"),
        N("Result body line two"),
        N("Result footer line"),
        N("Another link", clickable = true),
    )
    private val identity = EngineIdentity("laya", "ichenney/laya-browser-v32b@161d54d6")
    private val fallbackIdentity = EngineIdentity("jev", "typesafe/jev-1.13-20260917")

    @Test
    fun `an escalated step logs the fallback answer as executed with the primary kept as teacher`() =
        runBlocking {
            // Step 0: the PRIMARY is unsure (0.40, not HIGH) between two picks -> the
            // FALLBACK's "n4" is executed. Step 1: the primary confidently finishes.
            val primary = ScriptedEngine(
                triage = choice("lookup", listOf("lookup", "research-multi-step", "form-fill", "impossible"), 0.90),
                actions = ArrayDeque(
                    listOf(
                        choice("n3", listOf("n3", "n4", "finish"), 0.40),
                        choice("finish", listOf("n4", "back", "finish"), 0.90),
                    ),
                ),
            )
            val fallback = ScriptedEngine(
                triage = choice("lookup", listOf("lookup", "research-multi-step", "form-fill", "impossible"), 0.90),
                actions = ArrayDeque(
                    listOf(
                        choice("n4", listOf("n3", "n4", "finish"), 0.80),
                        choice("finish", listOf("n4", "back", "finish"), 0.80),
                    ),
                ),
            )
            val composed = FallbackEngine(primary, fallback)
            val journal = Journal()
            val sink = CollectingLogSink()
            val executor = FakeExecutor(journal)
            val loop = NavigationLoop(
                engine = composed,
                observer = FakeObserver(listOf(page, pageB)),
                executor = executor,
                gatePresenter = AutoDenyGatePresenter,
                logSink = sink,
                config = NavigationLoop.Config(settleMs = 0),
            )

            val result = loop.run(
                goal = "open the next page",
                goalId = "g",
                engineIdentity = identity,
                fallbackIdentity = fallbackIdentity,
            )

            assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
            assertEquals(2, sink.rows.size, "one row per navigation decision")

            // Step 0: escalated — the fallback's choice was executed, the primary's kept.
            val escalatedRow = sink.rows[0]
            assertTrue(escalatedRow.escalated)
            val fb = requireNotNull(escalatedRow.fallback)
            assertEquals(FallbackInfo("jev", "typesafe/jev-1.13-20260917", fb.answer), fb)
            assertEquals("n3", escalatedRow.teacherAnswer.chosen, "teacherAnswer = the PRIMARY's answer")
            assertEquals("n4", (fb.answer as navigator.core.decision.ChoiceResult).chosenId)
            assertEquals("n4", escalatedRow.executedAction.candidateId, "the FALLBACK's answer executed")
            assertEquals(LoggedActionKind.TAP, escalatedRow.executedAction.kind)
            assertEquals(TriageClass.LOOKUP, escalatedRow.triageClass)

            // Step 1: not escalated — the single-engine row shape, byte-identical.
            val plainRow = sink.rows[1]
            assertFalse(plainRow.escalated)
            assertNull(plainRow.fallback)
            assertEquals("finish", plainRow.teacherAnswer.chosen)

            // The executor tapped the FALLBACK's node, never the primary's pick.
            assertTrue(executor.calls.contains("tap(50,420)"), "n4's center: ${executor.calls}")
            assertFalse(executor.calls.contains("tap(50,320)"), "n3 was never executed: ${executor.calls}")

            // The escalation stats feed the run report.
            val stats = composed.stats()
            assertEquals(4, stats.decisions, "triage + two action chooses + the finish noul")
            assertEquals(1, stats.escalations)
        }

    @Test
    fun `a primary hard failure inside the loop escalates and still executes`() = runBlocking {
        val flaky = object : DecisionEngine {
            var actionCalls = 0

            override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult =
                if (question.name == Triage.QUESTION_NAME) {
                    choice("lookup", listOf("lookup", "research-multi-step", "form-fill", "impossible"), 0.90)
                } else {
                    actionCalls += 1
                    if (actionCalls == 1) throw IllegalStateException("laya inference exploded")
                    choice("finish", listOf("n4", "back", "finish"), 0.90)
                }

            override suspend fun judge(state: String, question: NoulQuestion): NoulResult = NoulResult(0.95)

            override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
                throw UnsupportedOperationException("score is not exercised by the loop")
        }
        val fallback = ScriptedEngine(
            triage = choice("lookup", listOf("lookup", "research-multi-step", "form-fill", "impossible"), 0.90),
            actions = ArrayDeque(listOf(choice("n4", listOf("n3", "n4", "finish"), 0.80))),
        )
        val composed = FallbackEngine(flaky, fallback)
        val sink = CollectingLogSink()
        val loop = NavigationLoop(
            engine = composed,
            observer = FakeObserver(listOf(page, pageB)),
            executor = FakeExecutor(Journal()),
            gatePresenter = AutoDenyGatePresenter,
            logSink = sink,
            config = NavigationLoop.Config(settleMs = 0),
        )

        val result = loop.run(
            goal = "open the next page",
            goalId = "g",
            engineIdentity = identity,
            fallbackIdentity = fallbackIdentity,
        )

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal, "graceful degradation, never a dead task")
        val escalatedRow = sink.rows[0]
        assertTrue(escalatedRow.escalated)
        val fb = requireNotNull(escalatedRow.fallback)
        assertEquals("n4", (fb.answer as navigator.core.decision.ChoiceResult).chosenId)
        assertEquals(
            "n4", escalatedRow.executedAction.candidateId,
            "the fallback's answer executed even on a primary hard failure",
        )
        assertEquals("n4", escalatedRow.teacherAnswer.chosen, "no primary answer exists - the executed one stands in")
        assertEquals(
            ExecutedAction(candidateId = "n4", kind = LoggedActionKind.TAP, disposition = navigator.core.log.ActionDisposition.EXECUTED),
            escalatedRow.executedAction,
        )
    }
}
