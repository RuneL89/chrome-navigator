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
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.Option
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.log.DecisionLogRow
import navigator.core.log.EngineIdentity
import navigator.core.log.TriageClass
import navigator.core.log.serialName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Gate 8.3 (PHASE_08 §3): router semantics — stub primary + REAL Laya (the shipped int8
 * graph over the desktop JVM ONNX Runtime):
 *
 * - primary latency is unaffected (p95 overhead <= +50 ms) and the router's synchronous
 *   answer is ALWAYS the primary's;
 * - an artificial slow shadow makes every sampled row freeze as a shadowMiss
 *   (`shadowSampled=true`, `shadow=null`) inside the REAL loop — and the executor
 *   receives ONLY the primary's choices;
 * - every-3rd-step sampling produces exactly the expected shadowSampled row pattern;
 * - the thermal/battery toggle switches the sampling pattern (vision 02 §4.3);
 * - a completed shadow enriches the row with both engines' answers + agreement fields.
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
        override suspend fun chooseTwoStage(
            state: String,
            question: ChoiceQuestion,
        ): LayaEngine.TwoStageAnswer {
            delay(delayMs)
            return inner.chooseTwoStage(state, question)
        }

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult =
            inner.judge(state, question)

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            inner.score(state, question)
    }

    /** The loop's action question stand-in: tagged options over two buckets + finish. */
    private fun question(): ChoiceQuestion = ChoiceQuestion(
        name = "action",
        options = listOf(
            Option("n0", "Apple Pies", "bucket=nav-links (270,300)"),
            Option("n1", "Cakes", "bucket=nav-links (270,360)"),
            Option("n2", "Start baking", "bucket=buttons (540,900)"),
            Option("finish", "finish", "bucket=finish (0,0)"),
        ),
    )

    /** A minimal recording primary: instant, fixed answer, call journal. */
    private class RecordingPrimary(private val chosen: String) : DecisionEngine {
        val calls = mutableListOf<Long>()

        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
            calls.add(System.nanoTime())
            return ChoiceResult(
                chosenId = chosen,
                probabilities = question.options.associate {
                    if (it.id == chosen) it.id to 0.95 else it.id to 0.05 / (question.options.size - 1)
                },
                confidence = 0.95,
            )
        }

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult = NoulResult(0.9)

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            ScoreResult(1.0, mapOf(1 to 1.0))
    }

    private fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0
        return sorted[((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)]
    }

    // -- primary latency isolation ------------------------------------------------------

    @Test
    fun `primary latency is unaffected by the async shadow (p95 overhead at most 50ms)`() = runBlocking {
        val primary = RecordingPrimary("n1")
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
                primary.choose("STATE", question())
                direct.add(System.nanoTime() - t0)
                val t1 = System.nanoTime()
                routedChoices.add(router.choose("STATE-$it", question()).chosenId)
                routed.add(System.nanoTime() - t1)
            }
            assertEquals(30, router.snapshot().sampled, "every bucketable decision samples the shadow")
            // the synchronous answer is the primary's, always
            assertTrue(routedChoices.all { it == "n1" })
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
                        chooseByLabel("action", "Apple Pies"),
                        chooseByLabel("action", "Cakes"),
                        chooseByLabel("action", "Start baking"),
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
            }

            // the executor received ONLY the primary's choices, in the primary's order
            // (candidate ids carry the observation index: the clickable nodes are n3/n4/n5)
            assertEquals(
                listOf("tap(50,320)", "tap(50,420)", "tap(50,520)"),
                executor.calls,
                "executor calls must be exactly the stub primary's scripted picks",
            )
            val primaryChoices = sink.rows.map { it.teacherAnswer.chosen }
            assertEquals(listOf("n3", "n4", "n5"), primaryChoices)
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
                        chooseByLabel("action", "Apple Pies"),
                        chooseByLabel("action", "Cakes"),
                        chooseByLabel("action", "Start baking"),
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
            // decision numbering: 1 = triage (no bucketed form, never shadowed),
            // 2,3,4 = the three steps -> sampled exactly at decision 3 (step index 1)
            assertEquals(
                listOf(false, true, false),
                sink.rows.sortedBy { it.stepIndex }.map { it.shadowSampled },
            )
            val sampledRow = sink.rows.first { it.shadowSampled }
            assertEquals(null, sampledRow.shadow, "a 2.5s shadow cannot beat a zero-settle row write")
            assertTrue(router.awaitIdle(60_000))
            assertEquals(1, router.snapshot().sampled, "exactly one decision ran the shadow")
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
            val primary = RecordingPrimary("n1")
            router = ShadowRouter(primary, laya)
            val router = requireNotNull(router)
            val state = "GOAL: pick a cake page"
            router.choose(state, question())
            assertTrue(router.awaitIdle(60_000))
            val row = DecisionLogRow(
                rowId = "task-step-0",
                taskId = "task",
                goal = "goal",
                stepIndex = 0,
                timestamp = 1,
                stateText = state,
                stateFormatVersion = navigator.core.STATE_FORMAT_VERSION,
                triageClass = TriageClass.LOOKUP,
                questionPrimary = question(),
                questionShadow = navigator.app.laya.BucketedForms.fromPrimaryQuestion(question())!!,
                teacher = identity,
                teacherAnswer = navigator.core.log.TeacherAnswer("n1", question().options.associate {
                    if (it.id == "n1") it.id to 0.95 else it.id to 0.05 / 3
                }, 0.95),
                executedAction = navigator.core.log.ExecutedAction(
                    "n1",
                    navigator.core.log.LoggedActionKind.TAP,
                    navigator.core.log.ActionDisposition.EXECUTED,
                ),
                outcome = navigator.core.log.Outcome.PROGRESSED,
                shadowSampled = false,
            )
            router.sink(sink).write(row)
            val written = sink.rows.single()
            assertNotNull(written.shadow)
            assertEquals("laya", written.shadow!!.checkpoint)
            assertEquals(navigator.core.log.ShadowPrecision.INT8, written.shadow!!.precision)
            val shadowAnswer = assertNotNull(written.shadowAnswer)
            // the two stages agree with each other: the element lives in the chosen bucket
            val chosenBucketOptions = questionShadowOptions(written, shadowAnswer.bucket.chosen)
            assertTrue(shadowAnswer.element.chosen in chosenBucketOptions)
            assertEquals(true, written.agreementExactMatch == (written.teacherAnswer.chosen == shadowAnswer.element.chosen))
            assertEquals(
                written.teacherAnswer.chosen == shadowAnswer.element.chosen,
                written.agreementExactMatch,
            )
            assertNotNull(written.agreementKL)
            assertTrue(written.agreementKL!! >= 0.0)
            assertEquals(true, written.shadowSampled)
            assertTrue(router.snapshot().latenciesMs.isNotEmpty(), "latencies must be recorded")
        } finally {
            router?.awaitIdle(60_000)
            laya.close()
        }
    }

    /** The option ids of one bucket of the row's recorded shadow question. */
    private fun questionShadowOptions(row: DecisionLogRow, bucketSerial: String): List<String> =
        row.questionShadow.buckets.entries
            .first { it.key.serialName == bucketSerial }.value.map { it.id }
}
