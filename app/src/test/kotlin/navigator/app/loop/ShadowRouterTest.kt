package navigator.app.loop

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import navigator.app.laya.LayaEngine
import navigator.app.laya.LayaOnnxSession
import navigator.app.laya.ShadowDecisions
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
import navigator.core.decision.TargetRubric
import navigator.core.enumerate.CandidateList
import navigator.core.log.DecisionLogRow
import navigator.core.log.EngineIdentity
import navigator.core.log.TriageClass
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Gate 8.3 (PHASE_08 §3, re-pinned Phase 10B — the cascade router): router semantics —
 * stub primary + REAL Laya (the shipped int8 graph over the desktop JVM ONNX Runtime):
 *
 * - primary latency is unaffected (p95 overhead <= +50 ms) and the router's synchronous
 *   answer is ALWAYS the primary's;
 * - the structured handoff: the router fires on the FIXED intent form (recognized
 *   structurally, never by parsing descriptions) and mirrors BOTH cascade stages — the
 *   stage-2 batch attaches through [ShadowRouter.scoreBatch];
 * - a NON-cascade choose question (triage, recovery) is refused LOUDLY: a counted
 *   refusal + a log line + no sampling — never silent darkness;
 * - an artificial slow shadow makes every sampled row freeze as a shadowMiss
 *   (`shadowSampled=true`, `shadow=null`) inside the REAL loop — and the executor
 *   receives ONLY the primary's choices;
 * - every-3rd-step sampling produces exactly the expected shadowSampled row pattern;
 * - a completed shadow enriches the row with both engines' answers + agreement fields
 *   (the headline top-1 is the stage-1 INTENT; targetAgreement rides the stage-2).
 */
class ShadowRouterTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    private fun modelPath(): String =
        requireNotNull(System.getProperty("navigator.project.root")) {
            "navigator.project.root must be set (the :app test task sets it)"
        } + "/app/src/main/assets-local/laya/laya-int8.onnx"

    private fun realLaya(): LayaEngine = LayaEngine(LayaOnnxSession.open(modelPath()))

    /** Wraps the REAL laya with an artificial delay (the slow-shadow scenario). */
    private class SlowShadow(private val inner: LayaEngine, private val delayMs: Long) : ShadowDecisions {
        override suspend fun chooseCascade(
            intentState: String,
            intentQuestion: ChoiceQuestion,
            targetState: String?,
            stage2: Map<String, ScoreQuestion>?,
        ): LayaEngine.CascadeAnswer {
            delay(delayMs)
            return inner.chooseCascade(intentState, intentQuestion, targetState, stage2)
        }

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult =
            inner.judge(state, question)

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            inner.score(state, question)
    }

    /** A minimal recording primary over the intent form: instant, fixed, call journal. */
    private class RecordingPrimary(private val chosen: String) : DecisionEngine {
        val calls = mutableListOf<Long>()
        var batchCalls = 0

        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
            calls.add(System.nanoTime())
            val ids = question.options.map { it.id }
            return ChoiceResult(
                chosenId = if (chosen in ids) chosen else ids.first(),
                probabilities = ids.associate { id ->
                    id to if (id == chosen) 0.95 else 0.05 / (ids.size - 1).coerceAtLeast(1)
                },
                confidence = 0.95,
            )
        }

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult = NoulResult(0.9)

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            ScoreResult(1.0, mapOf(1 to 1.0))

        override suspend fun scoreBatch(
            state: String,
            questions: Map<String, ScoreQuestion>,
        ): Map<String, ScoreResult> {
            batchCalls += 1
            return questions.mapValues { ScoreResult(5.0, mapOf(1 to 0.0, 2 to 0.0, 3 to 0.0, 4 to 0.0, 5 to 1.0)) }
        }
    }

    /** A minimal lawful one-bucket shadow question (the row's shadow-question record). */
    private fun companionShadowQuestion() = navigator.core.log.BucketedQuestion(
        name = "shadow-action",
        buckets = mapOf(
            navigator.core.enumerate.Bucket.BUTTONS to listOf(
                navigator.core.decision.Option("n4", "Unsubscribe", "bucket=buttons (540,320)"),
            ),
        ),
    )

    private fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0
        return sorted[((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)]
    }

    // -- primary latency isolation ------------------------------------------------------

    @Test
    fun `primary latency is unaffected by the async shadow (p95 overhead at most 50ms)`() = runBlocking {
        val primary = RecordingPrimary(Intents.FOLLOW_LINK)
        val laya = realLaya()
        var router: ShadowRouter? = null
        try {
            router = ShadowRouter(primary, laya)
            val router = requireNotNull(router)
            val direct = mutableListOf<Long>()
            val routed = mutableListOf<Long>()
            val routedChoices = mutableListOf<String>()
            repeat(30) {
                val t0 = System.nanoTime()
                primary.choose("STATE", Intents.QUESTION)
                direct.add(System.nanoTime() - t0)
                val t1 = System.nanoTime()
                routedChoices.add(router.choose("STATE-$it", Intents.QUESTION).chosenId)
                routed.add(System.nanoTime() - t1)
            }
            assertEquals(30, router.snapshot().sampled, "every cascade decision samples the shadow")
            // the synchronous answer is the primary's, always
            assertTrue(routedChoices.all { it == Intents.FOLLOW_LINK })
            val p95Direct = percentile(direct.sorted(), 95)
            val p95Routed = percentile(routed.sorted(), 95)
            val overheadMs = (p95Routed - p95Direct) / 1_000_000
            assertTrue(
                overheadMs <= 50,
                "p95 overhead ${overheadMs}ms exceeds 50ms (direct=$p95Direct routed=$p95Routed)",
            )
            println("gate8.3: p95 direct=${p95Direct / 1_000_000}ms routed=${p95Routed / 1_000_000}ms overhead=${overheadMs}ms")
            assertTrue(router.awaitIdle(120_000), "shadow queue must settle after the loop")
        } finally {
            // settle BEFORE close: OrtSession must never be closed under an in-flight run
            router?.awaitIdle(120_000)
            laya.close()
        }
    }

    // -- the loud refusal of non-cascade choose questions --------------------------------

    @Test
    fun `a non-cascade choose question is refused loudly - counted, never sampled`() = runBlocking {
        val laya = realLaya()
        var router: ShadowRouter? = null
        try {
            val primary = RecordingPrimary("back")
            router = ShadowRouter(primary, laya)
            val router = requireNotNull(router)
            val recovery = ChoiceQuestion(
                name = "recovery",
                options = listOf(navigator.core.decision.Option("back", "back", "recovery action")),
            )
            val answered = router.choose("STATE", recovery)
            assertEquals("back", answered.chosenId, "the PRIMARY's answer still escapes")
            assertEquals(0, router.snapshot().sampled, "no sampling on a refused question")
            assertEquals(1, router.snapshot().nonCascadeRefusals, "the refusal is counted loudly")
            assertTrue(
                router.snapshot().lastFailure!!.contains("recovery"),
                "the last-failure record names the refused question",
            )
            // a score batch outside a tracked cascade decision is refused the same way
            val batch = TargetRubric.stage2Questions(listOf("n1" to "Alpha"), "subtask text")
            router.scoreBatch("STATE", batch)
            assertEquals(2, router.snapshot().nonCascadeRefusals)
            assertTrue(router.awaitIdle(60_000))
        } finally {
            router?.awaitIdle(60_000)
            laya.close()
        }
    }

    // -- slow shadow inside the real loop: shadowMiss + executor isolation ---------------

    @Test
    fun `slow shadow freezes every sampled row as a shadowMiss and the executor sees only primary choices`() = runBlocking {
        val page = obs(
            "http://m0.test/page",
            N("Header text"),
            N("Some filler text"),
            N("More filler text here"),
            N("Apple Pies", clickable = true),
            N("Cakes", clickable = true),
            N("Start baking", clickable = true, button = true),
        )
        val laya = realLaya()
        val sink = CollectingLogSink()
        var router: ShadowRouter? = null
        try {
            val primary = StubEngine(
                StubScript(
                    scriptOf(
                        choose("triage", "lookup"),
                        *intentStep("follow-link", target = "Apple Pies"),
                        *intentStep("follow-link", target = "Cakes"),
                        *intentStep("activate-control", target = "Start baking"),
                    ),
                ),
            )
            router = ShadowRouter(primary, SlowShadow(laya, delayMs = 2_500))
            val router = requireNotNull(router)
            val executor = FakeExecutor(Journal())
            val loop = NavigationLoop(
                engine = router,
                observer = FakeObserver(listOf(page, page, page)),
                executor = executor,
                gatePresenter = AutoDenyGatePresenter,
                logSink = router.sink(sink),
                config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 3)),
            )
            val result = loop.run("compare pie and cake pages", "goal-shadow-miss", identity, FakeClock())
            assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
            assertEquals(3, sink.rows.size)
            assertEquals(0, result.logGaps)

            // every sampled row froze as a shadowMiss (vision 02 §4.2 via the row schema)
            for (row in sink.rows) {
                assertEquals(true, row.shadowSampled, "row ${row.stepIndex} must record shadowSampled")
                assertEquals(null, row.shadow, "row ${row.stepIndex} must carry the shadowMiss null shadow")
                assertEquals(null, row.shadowAnswer)
                assertEquals(null, row.agreementExactMatch)
                assertEquals(null, row.agreementKL)
                assertEquals("intent", row.questionPrimary.name, "cascade rows carry the intent question")
            }

            // the executor received ONLY the primary's choices, in the primary's order
            // (candidate ids carry the observation index: the clickable nodes are n3/n4/n5)
            assertEquals(
                listOf("tap(50,320)", "tap(50,420)", "tap(50,520)"),
                executor.calls,
                "executor calls must be exactly the stub primary's scripted picks",
            )
            val primaryChoices = sink.rows.map { it.teacherAnswer.chosen }
            assertEquals(listOf("follow-link", "follow-link", "activate-control"), primaryChoices)
            for (row in sink.rows) {
                assertEquals(TriageClass.LOOKUP, row.triageClass)
            }
            assertTrue(router.awaitIdle(60_000))
            val snapshot = router.snapshot()
            assertEquals(
                0, snapshot.failures,
                "no shadow decision may fail (lastFailure=${snapshot.lastFailure})",
            )
            assertEquals(3, snapshot.misses, "the three rows must be recorded misses")
            assertEquals(3, snapshot.completions, "the shadow still completes into the void (log-only)")
        } finally {
            router?.awaitIdle(60_000)
            laya.close()
        }
    }

    // -- every-Nth sampling pattern ------------------------------------------------------

    @Test
    fun `every-3rd-step sampling produces exactly the expected row pattern`() = runBlocking {
        val page = obs(
            "http://m0.test/page",
            N("Header text"),
            N("Some filler text"),
            N("More filler text here"),
            N("Apple Pies", clickable = true),
            N("Cakes", clickable = true),
            N("Start baking", clickable = true, button = true),
        )
        val laya = realLaya()
        val sink = CollectingLogSink()
        var router: ShadowRouter? = null
        try {
            val primary = StubEngine(
                StubScript(
                    scriptOf(
                        choose("triage", "lookup"),
                        *intentStep("follow-link", target = "Apple Pies"),
                        *intentStep("follow-link", target = "Cakes"),
                        *intentStep("activate-control", target = "Start baking"),
                    ),
                ),
            )
            router = ShadowRouter(
                primary,
                SlowShadow(laya, delayMs = 2_500),
                samplingPolicy = ShadowRouter.SamplingPolicy.everyNth(3),
            )
            val router = requireNotNull(router)
            val loop = NavigationLoop(
                engine = router,
                observer = FakeObserver(listOf(page, page, page)),
                executor = FakeExecutor(Journal()),
                gatePresenter = AutoDenyGatePresenter,
                logSink = router.sink(sink),
                config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 3)),
            )
            loop.run("pie to cake to buttons", "goal-sampling", identity, FakeClock())
            // decision numbering: 1 = triage (refused, never sampled), 2,3,4 = the three
            // cascade steps -> sampled exactly at decision 3 (step index 1)
            assertEquals(
                listOf(false, true, false),
                sink.rows.sortedBy { it.stepIndex }.map { it.shadowSampled },
            )
            val sampledRow = sink.rows.first { it.shadowSampled }
            assertEquals(null, sampledRow.shadow, "a 2.5s shadow cannot beat a zero-settle row write")
            assertTrue(router.awaitIdle(60_000))
            assertEquals(1, router.snapshot().sampled, "exactly one decision ran the shadow")
            assertEquals(1, router.snapshot().nonCascadeRefusals, "the triage ask was refused loudly")
        } finally {
            router?.awaitIdle(60_000)
            laya.close()
        }
    }

    @Test
    fun `the pressure toggle switches the sampling pattern (vision 02 section 4-3)`() {
        val calm = ShadowRouter.SamplingPolicy.withPressureToggle(pressureSignals = { false })
        val underPressure = ShadowRouter.SamplingPolicy.withPressureToggle(pressureSignals = { true })
        val calmPattern = (1L..6L).map { calm.shouldSample(it) }
        val pressurePattern = (1L..6L).map { underPressure.shouldSample(it) }
        assertEquals(listOf(true, true, true, true, true, true), calmPattern)
        assertEquals(listOf(false, false, true, false, false, true), pressurePattern)
    }

    // -- a completed shadow enriches the row (both engines + agreement) -------------------

    @Test
    fun `a completed shadow answer enriches the row with both engines and agreement`() = runBlocking {
        val laya = realLaya()
        val sink = CollectingLogSink()
        var router: ShadowRouter? = null
        try {
            val primary = RecordingPrimary(Intents.ACTIVATE_CONTROL)
            router = ShadowRouter(primary, laya)
            val router = requireNotNull(router)
            val intentState = "SUBTASK: unsubscribe from the first mailing list"
            router.choose(intentState, Intents.QUESTION)
            assertTrue(router.awaitIdle(60_000))
            val row = DecisionLogRow(
                rowId = "task-step-0",
                taskId = "task",
                goal = "goal",
                stepIndex = 0,
                timestamp = 1,
                stateText = intentState,
                stateFormatVersion = navigator.core.STATE_FORMAT_VERSION,
                triageClass = TriageClass.ACTION,
                questionPrimary = Intents.QUESTION,
                questionShadow = companionShadowQuestion(),
                teacher = identity,
                teacherAnswer = navigator.core.log.TeacherAnswer(
                    Intents.ACTIVATE_CONTROL,
                    Intents.IDS.associate { if (it == Intents.ACTIVATE_CONTROL) it to 0.95 else it to 0.05 / 8 },
                    0.95,
                ),
                executedAction = navigator.core.log.ExecutedAction(
                    null,
                    navigator.core.log.LoggedActionKind.FINISH,
                    navigator.core.log.ActionDisposition.EXECUTED,
                ),
                outcome = navigator.core.log.Outcome.NO_CHANGE,
                shadowSampled = false,
            )
            router.sink(sink).write(row)
            val written = sink.rows.single()
            assertNotNull(written.shadow)
            assertEquals("laya", written.shadow!!.checkpoint)
            assertEquals(navigator.core.log.ShadowPrecision.INT8, written.shadow!!.precision)
            val shadowAnswer = assertNotNull(written.shadowAnswer)
            // the bucket stage IS the shadow's stage-1 INTENT (the cascade migration);
            // the element mirrors the intent (this step ran no stage-2)
            assertTrue(shadowAnswer.bucket.chosen in Intents.IDS)
            assertEquals(shadowAnswer.bucket.chosen, shadowAnswer.element.chosen)
            // the headline top-1 is the stage-1 INTENT vs the shadow's intent
            assertEquals(
                written.teacherAnswer.chosen == shadowAnswer.bucket.chosen,
                written.agreementExactMatch,
            )
            assertNotNull(written.agreementKL)
            assertTrue(written.agreementKL!! >= 0.0)
            assertEquals(null, written.targetAgreement, "no stage-2 ran - no target agreement")
            assertEquals(true, written.shadowSampled)
            assertTrue(router.snapshot().latenciesMs.isNotEmpty(), "latencies must be recorded")
        } finally {
            router?.awaitIdle(60_000)
            laya.close()
        }
    }

    // -- the stage-2 handoff: the batch attaches and the target agreement rides ----------

    @Test
    fun `the stage-2 batch attaches to the tracked decision and the row carries targetAgreement`() = runBlocking {
        val laya = realLaya()
        val sink = CollectingLogSink()
        var router: ShadowRouter? = null
        try {
            val primary = RecordingPrimary(Intents.ACTIVATE_CONTROL)
            router = ShadowRouter(primary, laya)
            val router = requireNotNull(router)
            val intentState = "SUBTASK: unsubscribe from the first mailing list"
            router.choose(intentState, Intents.QUESTION)
            val slate = listOf("n4" to "Unsubscribe from Cooking Classics", "n7" to "Unsubscribe from Star Log")
            val batch = TargetRubric.stage2Questions(slate, "unsubscribe from the first mailing list")
            val results = router.scoreBatch(
                "SUBTASK: unsubscribe from the first mailing list\n[n4] \"Unsubscribe\" tap (540,320)",
                batch,
            )
            assertEquals(2, results.size)
            assertEquals(1, primary.batchCalls, "the primary answered ONE batched request")
            assertTrue(router.awaitIdle(120_000))
            val row = DecisionLogRow(
                rowId = "task-step-1",
                taskId = "task",
                goal = "goal",
                stepIndex = 1,
                timestamp = 1,
                stateText = intentState,
                stateFormatVersion = navigator.core.STATE_FORMAT_VERSION,
                triageClass = TriageClass.ACTION,
                questionPrimary = Intents.QUESTION,
                questionShadow = companionShadowQuestion(),
                teacher = identity,
                teacherAnswer = navigator.core.log.TeacherAnswer(
                    Intents.ACTIVATE_CONTROL,
                    Intents.IDS.associate { if (it == Intents.ACTIVATE_CONTROL) it to 0.95 else it to 0.05 / 8 },
                    0.95,
                ),
                executedAction = navigator.core.log.ExecutedAction(
                    "n4",
                    navigator.core.log.LoggedActionKind.TAP,
                    navigator.core.log.ActionDisposition.EXECUTED,
                ),
                outcome = navigator.core.log.Outcome.PROGRESSED,
                shadowSampled = false,
                targetScores = results.mapValues { it.value.expectedLevel },
                targetStateText = "SUBTASK: unsubscribe from the first mailing list\n[n4] \"Unsubscribe\" tap (540,320)",
            )
            router.sink(sink).write(row)
            val written = sink.rows.single()
            val shadowAnswer = assertNotNull(written.shadowAnswer)
            // the element is the shadow's stage-2 ARGMAX TARGET; the target agreement
            // rides the additive field (the teacher executed n4)
            assertTrue(
                shadowAnswer.element.chosen in setOf("n4", "n7"),
                "the element is the REAL shadow's stage-2 argmax on the slate: ${shadowAnswer.element.chosen}",
            )
            assertEquals(
                shadowAnswer.element.chosen == "n4",
                written.targetAgreement,
                "the target agreement compares the executed target vs the shadow's argmax",
            )
            assertEquals(
                written.teacherAnswer.chosen == written.shadowAnswer!!.bucket.chosen,
                written.agreementExactMatch,
                "the headline is the stage-1 intent vs the shadow",
            )
        } finally {
            router?.awaitIdle(120_000)
            laya.close()
        }
    }
}
