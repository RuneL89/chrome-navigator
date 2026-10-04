package navigator.app.loop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
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
import navigator.core.log.ActionDisposition
import navigator.core.log.DecisionLogRow
import navigator.core.log.EngineIdentity
import navigator.core.log.ExecutedAction
import navigator.core.log.JoinedAgreement
import navigator.core.log.JoinedDecisionRow
import navigator.core.log.LoggedActionKind
import navigator.core.log.Outcome
import navigator.core.log.ShadowResultRow
import navigator.core.log.TeacherAnswer
import navigator.core.log.TriageClass
import navigator.app.laya.LayaEngine
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 8B.2 (PHASE_08B §3) — the router's late-write semantics (vision 02 §4.4, vision
 * 05 §2 amendment 2026-10-04), over a scripted shadow so every interleaving is
 * deterministic (no model, no timing races):
 *
 * - SLOW shadow → the row freezes as `shadowMiss` and the late answer arrives as
 *   EXACTLY ONE companion row with the full answer; agreement computes on the join;
 * - FAST shadow → the single decision row carries everything and NO companion exists;
 * - a repeated row write after the freeze neither re-freezes nor duplicates (exactly
 *   once, rule a);
 * - after [ShadowRouter.close], late completions are dropped AND counted (rule c);
 * - a rejecting companion sink is counted + surfaced, never propagated (vision 02 §4.3);
 * - inside the REAL loop the executor still receives ONLY the primary's choices.
 */
class ShadowRouterCompanionTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

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

    /** Instant, fixed primary (its choice is the ONLY value the loop ever sees). */
    private class RecordingPrimary(private val chosen: String) : DecisionEngine {
        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult = ChoiceResult(
            chosenId = chosen,
            probabilities = question.options.associate {
                if (it.id == chosen) it.id to 0.9 else it.id to 0.1 / (question.options.size - 1)
            },
            confidence = 0.9,
        )

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult = NoulResult(0.9)

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            ScoreResult(1.0, mapOf(1 to 1.0))
    }

    /**
     * A scripted shadow whose chooseTwoStage blocks until [release] fires, then answers
     * with [elementChosen] — making the freeze-before-completion interleaving
     * deterministic instead of a timing bet. Distributions mirror the REAL two-stage
     * engine: the element stage only distributes over the chosen bucket's options.
     */
    private class GatedShadow(
        private val elementChosen: String,
        private val release: CompletableDeferred<Unit>,
    ) : navigator.app.laya.ShadowDecisions {
        val calls = AtomicInteger(0)

        private fun bucketSerialOf(option: Option): String =
            option.description.substringBefore(" ").removePrefix("bucket=")

        override suspend fun chooseTwoStage(
            state: String,
            question: ChoiceQuestion,
        ): LayaEngine.TwoStageAnswer {
            release.await()
            calls.incrementAndGet()
            val chosen = question.options.first { it.id == elementChosen }
            val bucketSerial = bucketSerialOf(chosen)
            val bucketOptions = question.options.filter { bucketSerialOf(it) == bucketSerial }
            val element = if (bucketOptions.size == 1) {
                mapOf(bucketOptions.single().id to 1.0) // a 1-option bucket: certain choice
            } else {
                bucketOptions.associate {
                    if (it.id == elementChosen) it.id to 0.9 else it.id to 0.1 / (bucketOptions.size - 1)
                }
            }
            val serials = question.options.map { bucketSerialOf(it) }.distinct()
            val bucketDist = serials.associate {
                if (it == bucketSerial) it to 0.6 else it to 0.4 / (serials.size - 1)
            }
            return LayaEngine.TwoStageAnswer(
                bucketedQuestion = null,
                result = ChoiceResult(elementChosen, element, 0.9),
                bucket = LayaEngine.StageAnswer(bucketSerial, bucketDist),
                element = LayaEngine.StageAnswer(elementChosen, element),
                forwardMs = 1,
                totalMs = 5,
            )
        }

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult = NoulResult(0.9)

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
            ScoreResult(1.0, mapOf(1 to 1.0))
    }

    /** Collects companion rows; a duplicate rowId fails loudly (models the DB ABORT). */
    private class CollectingCompanionSink : CompanionSink {
        val rows = mutableListOf<ShadowResultRow>()
        var failNext = false

        override suspend fun write(row: ShadowResultRow) {
            if (failNext) throw IllegalStateException("guard: injected rejection")
            require(rows.none { it.rowId == row.rowId }) { "duplicate companion ${row.rowId}" }
            rows.add(row)
        }
    }

    private fun decisionRow(
        taskId: String,
        stepIndex: Int,
        state: String,
        teacherChosen: String = "n0",
    ): DecisionLogRow {
        val teacher = TeacherAnswer(
            chosen = teacherChosen,
            probabilities = question().options.associate {
                if (it.id == teacherChosen) it.id to 0.9 else it.id to 0.1 / 3
            },
            confidence = 0.9,
        )
        return DecisionLogRow(
            rowId = "$taskId-step$stepIndex",
            taskId = taskId,
            goal = "goal",
            stepIndex = stepIndex,
            timestamp = 1_760_600_000_000L + stepIndex,
            stateText = state,
            stateFormatVersion = navigator.core.STATE_FORMAT_VERSION,
            triageClass = TriageClass.LOOKUP,
            questionPrimary = question(),
            questionShadow = navigator.app.laya.BucketedForms.fromPrimaryQuestion(question())!!,
            teacher = identity,
            teacherAnswer = teacher,
            executedAction = ExecutedAction(teacherChosen, LoggedActionKind.TAP, ActionDisposition.EXECUTED),
            gateEvent = null,
            outcome = Outcome.PROGRESSED,
            shadowSampled = false,
        )
    }

    // -- slow shadow: freeze → exactly one companion with the full answer ------------------

    @Test
    fun `a shadow completing after the row freeze lands as exactly one companion with the full answer`() = runBlocking {
        val sink = CollectingLogSink()
        val companions = CollectingCompanionSink()
        val release = CompletableDeferred<Unit>()
        val router = ShadowRouter(
            RecordingPrimary("n0"),
            GatedShadow("n0", release),
            companionSink = companions,
        )
        try {
            val state = "GOAL: pick a pie page"
            router.choose(state, question()) // the shadow is now gated mid-flight
            router.sink(sink).write(decisionRow("task-a", 0, state)) // row freezes first

            release.complete(Unit) // the answer lands AFTER the freeze
            assertTrue(router.awaitIdle(10_000))

            // the decision row stayed a shadowMiss (its shape is frozen history)
            val written = sink.rows.single()
            assertEquals(true, written.shadowSampled)
            assertNull(written.shadow)
            assertNull(written.shadowAnswer)

            // exactly one companion, full answer, correct key + measured latency
            assertEquals(1, companions.rows.size)
            val companion = companions.rows.single()
            assertEquals("task-a", companion.taskId)
            assertEquals(0, companion.stepIndex)
            assertEquals("laya", companion.shadow.checkpoint)
            assertEquals("n0", companion.shadowAnswer.element.chosen)
            assertEquals(0.9, companion.shadowAnswer.element.probabilities["n0"]!!, 1e-12)
            assertTrue(companion.latencyMs >= 0)
            assertEquals(1, router.snapshot().companionRows)

            // agreement fields compute ON THE JOIN (rule d)
            val metrics = JoinedAgreement.metrics(listOf(JoinedDecisionRow(written, companion)))
            assertEquals(1, metrics.joinedRows)
            assertEquals(1, metrics.exactMatches)
            assertEquals(1.0, metrics.exactMatchFraction!!, 1e-12)
            assertTrue(metrics.meanKl!! > 0.0, "top-1 agrees but the full distributions differ")
            val metric = JoinedAgreement.uiMetric(metrics)
            assertEquals(100.0, metric.percentage!!, 1e-9)
            assertEquals(1.0, metric.coverage, 1e-12)
        } finally {
            release.complete(Unit)
            router.close()
        }
    }

    // -- fast shadow: single row, NO companion ---------------------------------------------

    @Test
    fun `a shadow completing before the row write enriches the row and inserts NO companion`() = runBlocking {
        val sink = CollectingLogSink()
        val companions = CollectingCompanionSink()
        val release = CompletableDeferred<Unit>()
        val router = ShadowRouter(
            RecordingPrimary("n0"),
            GatedShadow("n0", release),
            companionSink = companions,
        )
        try {
            val state = "GOAL: pick a pie page"
            router.choose(state, question())
            release.complete(Unit) // the answer lands BEFORE the row write
            assertTrue(router.awaitIdle(10_000))

            router.sink(sink).write(decisionRow("task-b", 0, state))
            val written = sink.rows.single()
            assertNotNull(written.shadow, "the decision row itself carries the shadow (rule a path)")
            assertEquals("n0", assertNotNull(written.shadowAnswer).element.chosen)
            assertEquals(true, written.agreementExactMatch)
            assertTrue(written.agreementKL!! >= 0.0)

            // the joined view must stay a SINGLE answer for this key: no companion exists
            assertTrue(companions.rows.isEmpty(), "rule a: no companion beside an answered row")
            assertEquals(0, router.snapshot().companionRows)
            assertEquals(0, router.snapshot().misses)
        } finally {
            release.complete(Unit)
            router.close()
        }
    }

    // -- exactly-once under repeated writes -------------------------------------------------

    @Test
    fun `a repeated row write after the freeze neither re-freezes nor duplicates the companion`() = runBlocking {
        val sink = CollectingLogSink()
        val companions = CollectingCompanionSink()
        val release = CompletableDeferred<Unit>()
        val router = ShadowRouter(
            RecordingPrimary("n0"),
            GatedShadow("n0", release),
            companionSink = companions,
        )
        try {
            val state = "GOAL: pick a pie page"
            router.choose(state, question())
            val row = decisionRow("task-c", 0, state)
            router.sink(sink).write(row) // freeze #1 (the loop's single write)
            // a duplicate delivery (same decision, fresh rowId): must be inert
            router.sink(sink).write(row.copy(rowId = "task-c-step0-dup"))

            release.complete(Unit)
            assertTrue(router.awaitIdle(10_000))

            assertEquals(2, sink.rows.size, "both writes pass through as frozen misses")
            assertTrue(sink.rows.all { it.shadowSampled && it.shadow == null })
            assertEquals(1, companions.rows.size, "exactly ONE companion (rule a)")
            assertEquals(1, router.snapshot().misses, "the miss is counted once")
            assertEquals(1, router.snapshot().companionRows)

            // a write AFTER completion must not enrich either — the companion owns the answer
            router.sink(sink).write(row.copy(rowId = "task-c-step0-late"))
            assertEquals(3, sink.rows.size)
            assertNull(sink.rows.last().shadow, "never enrich a row whose companion exists (rule a)")
            assertEquals(1, companions.rows.size)
        } finally {
            release.complete(Unit)
            router.close()
        }
    }

    // -- post-close drop counted (rule c) ----------------------------------------------------

    @Test
    fun `after close a late completion is dropped and counted`() = runBlocking {
        val sink = CollectingLogSink()
        val companions = CollectingCompanionSink()
        val release = CompletableDeferred<Unit>()
        val router = ShadowRouter(
            RecordingPrimary("n0"),
            GatedShadow("n0", release),
            companionSink = companions,
        )
        try {
            val state = "GOAL: pick a pie page"
            router.choose(state, question())
            router.sink(sink).write(decisionRow("task-d", 0, state)) // freeze
            router.close() // the logger closed before the answer landed
            release.complete(Unit)
            assertTrue(router.awaitIdle(10_000))

            assertTrue(companions.rows.isEmpty(), "nothing may be inserted after close (rule c)")
            val snapshot = router.snapshot()
            assertEquals(1, snapshot.droppedAfterClose, "the drop is counted")
            assertEquals(0, snapshot.companionRows)
            assertTrue(snapshot.closed)
        } finally {
            release.complete(Unit)
            router.close()
        }
    }

    // -- a rejecting sink is loud in the snapshot, never in the loop --------------------------

    @Test
    fun `a rejecting companion sink is counted and surfaced - never propagated`() = runBlocking {
        val sink = CollectingLogSink()
        val companions = CollectingCompanionSink()
        val release = CompletableDeferred<Unit>()
        val router = ShadowRouter(
            RecordingPrimary("n0"),
            GatedShadow("n0", release),
            companionSink = companions,
        )
        try {
            val state = "GOAL: pick a pie page"
            router.choose(state, question())
            router.sink(sink).write(decisionRow("task-e", 0, state))
            companions.failNext = true // the persistence guard rejects (programming error)
            release.complete(Unit)
            assertTrue(router.awaitIdle(10_000))

            val snapshot = router.snapshot()
            assertEquals(0, snapshot.companionRows)
            assertEquals(1, snapshot.companionRejected, "the rejection is counted")
            assertNotNull(snapshot.lastFailure)
            assertTrue("companion insert rejected" in snapshot.lastFailure!!)
        } finally {
            release.complete(Unit)
            router.close()
        }
    }

    // -- no sink wired: Phase-8 behavior is untouched -----------------------------------------

    @Test
    fun `without a companion sink the router keeps the inert phase-8 behavior`() = runBlocking {
        val sink = CollectingLogSink()
        val release = CompletableDeferred<Unit>()
        val router = ShadowRouter(RecordingPrimary("n0"), GatedShadow("n0", release))
        try {
            val state = "GOAL: pick a pie page"
            router.choose(state, question())
            router.sink(sink).write(decisionRow("task-f", 0, state))
            release.complete(Unit)
            assertTrue(router.awaitIdle(10_000))
            val written = sink.rows.single()
            assertEquals(true, written.shadowSampled)
            assertNull(written.shadow)
            assertEquals(0, router.snapshot().companionRows)
            assertEquals(0, router.snapshot().companionRejected)
            assertEquals(1, router.snapshot().misses)
        } finally {
            release.complete(Unit)
            router.close()
        }
    }

    // -- the real loop: executor sees only primary choices; companions arrive after ------------

    @Test
    fun `inside the real loop a slow shadow freezes rows and the executor sees only primary choices`() = runBlocking {
        val page = obs(
            "http://m0.test/page",
            N("Header text"),
            N("Some filler text"),
            N("More filler text here"),
            N("Apple Pies", clickable = true),
            N("Cakes", clickable = true),
            N("Start baking", clickable = true, button = true),
        )
        val sink = CollectingLogSink()
        val companions = CollectingCompanionSink()
        val release = CompletableDeferred<Unit>()
        val executor = FakeExecutor(Journal())
        val primary = StubEngine(
            StubScript(
                scriptOf(
                    choose("triage", "lookup"),
                    chooseByLabel("action", "Apple Pies"),
                    chooseByLabel("action", "Cakes"),
                ),
            ),
        )
        val router = ShadowRouter(
            primary,
            GatedShadow("n5", release), // never completes during the loop
            companionSink = companions,
        )
        try {
            val loop = NavigationLoop(
                engine = router,
                observer = FakeObserver(listOf(page, page, page)),
                executor = executor,
                gatePresenter = AutoDenyGatePresenter,
                logSink = router.sink(sink),
                config = NavigationLoop.Config(settleMs = 0, budget = NavigationLoop.BudgetLimits(stepsHard = 2)),
            )
            val result = loop.run("pie to cake", "goal-8b-companion", identity, FakeClock())
            assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
            assertEquals(2, sink.rows.size)
            assertEquals(0, result.logGaps)

            // the executor received ONLY the primary's scripted picks, in order
            // (clickable nodes carry observation ids n3/n4: bounds y=3*100 / y=4*100)
            assertEquals(listOf("tap(50,320)", "tap(50,420)"), executor.calls)
            assertEquals(listOf("n3", "n4"), sink.rows.map { it.teacherAnswer.chosen })

            // both rows froze as shadowMiss during the loop (the shadow is still gated)
            for (row in sink.rows) {
                assertEquals(true, row.shadowSampled, "row ${row.stepIndex} must record shadowSampled")
                assertNull(row.shadow)
            }
            assertTrue(companions.rows.isEmpty(), "nothing lands before the shadow completes")

            release.complete(Unit)
            assertTrue(router.awaitIdle(60_000))

            // each frozen miss gained exactly one companion with the full answer
            assertEquals(2, companions.rows.size)
            assertEquals(listOf(0, 1), companions.rows.map { it.stepIndex })
            for (row in sink.rows) {
                val companion = companions.rows.single { it.stepIndex == row.stepIndex }
                assertEquals(row.taskId, companion.taskId)
                assertEquals("n5", companion.shadowAnswer.element.chosen)
                assertEquals("laya", companion.shadow.checkpoint)
            }

            // agreement over the join: both companions pick n5, both teachers picked n3/n4
            val view = sink.rows.sortedBy { it.stepIndex }.map { row ->
                JoinedDecisionRow(row, companions.rows.single { it.stepIndex == row.stepIndex })
            }
            val metrics = JoinedAgreement.metrics(view)
            assertEquals(2, metrics.sampledRows)
            assertEquals(2, metrics.joinedRows)
            assertEquals(0, metrics.exactMatches, "the scripted shadow disagrees with the scripted teacher")
            assertEquals(0.0, metrics.exactMatchFraction!!, 1e-12)
            assertEquals(1.0, metrics.coverage, 1e-12)
            assertTrue(metrics.meanKl!! > 0.0)
        } finally {
            release.complete(Unit)
            router.close()
        }
    }
}
