package navigator.app.log

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.Option
import navigator.core.enumerate.Bucket
import navigator.core.log.ActionDisposition
import navigator.core.log.Agreement
import navigator.core.log.BucketedQuestion
import navigator.core.log.CompressedBy
import navigator.core.log.EngineIdentity
import navigator.core.log.ExecutedAction
import navigator.core.log.LoggedActionKind
import navigator.core.log.LoggedGateDecision
import navigator.core.log.LoggedGateEvent
import navigator.core.log.LoggedHClass
import navigator.core.log.Outcome
import navigator.core.log.ShadowAnswer
import navigator.core.log.ShadowPrecision
import navigator.core.log.ShadowRef
import navigator.core.log.ShadowStageAnswer
import navigator.core.log.TeacherAnswer
import navigator.core.log.TriageClass
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Gate 5.1 (Room half) — model → Room → model round-trip on every field, all gateEvent
 * variants, null shadow (shadowMiss) and every outcome value; plus the insert-only law
 * (duplicate rowId fails loudly; the DAO surface has no update/delete) and the query
 * APIs (by task, by time window, agreement aggregation). Runs the real Room database on
 * the JVM under Robolectric (test-only dependency).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DecisionLogDbTest {

    private lateinit var db: DecisionLogDb
    private lateinit var logger: DecisionLogger

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, DecisionLogDb::class.java)
            .allowMainThreadQueries()
            .build()
        logger = DecisionLogger(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- minimal row fixtures (mirrors the :core gate fixtures; :app tests cannot see them) ----

    private val primaryQuestion = ChoiceQuestion(
        name = "next-action",
        options = listOf(
            Option("type-query", "Type in search bar", "Enter the query text"),
            Option("open-first-result", "Open first result", "Tap the top hit"),
            Option("scroll-results", "Scroll results", "Reveal more hits"),
        ),
    )

    private val shadowQuestion = BucketedQuestion(
        name = "next-action-bucketed",
        buckets = mapOf(
            Bucket.FORM_FIELDS to listOf(Option("type-query", "Type in search bar", "Enter the query text")),
            Bucket.NAV_LINKS to listOf(
                Option("open-first-result", "Open first result", "Tap the top hit"),
                Option("open-second-result", "Open second result", "Tap the runner-up hit"),
            ),
            Bucket.BUTTONS to listOf(Option("scroll-results", "Scroll results", "Reveal more hits")),
        ),
    )

    private val teacherAnswer = TeacherAnswer(
        chosen = "open-first-result",
        probabilities = mapOf("type-query" to 0.10, "open-first-result" to 0.80, "scroll-results" to 0.10),
        confidence = 0.90,
    )

    private val agreeingShadow = ShadowAnswer(
        bucket = ShadowStageAnswer(
            chosen = "nav-links",
            probabilities = mapOf("form-fields" to 0.20, "nav-links" to 0.60, "buttons" to 0.20),
        ),
        element = ShadowStageAnswer(
            chosen = "open-first-result",
            probabilities = mapOf("open-first-result" to 0.75, "open-second-result" to 0.25),
        ),
    )

    private fun disagreeingShadow() = ShadowAnswer(
        bucket = ShadowStageAnswer(
            chosen = "buttons",
            probabilities = mapOf("form-fields" to 0.10, "nav-links" to 0.30, "buttons" to 0.60),
        ),
        element = ShadowStageAnswer(
            chosen = "scroll-results",
            probabilities = mapOf("scroll-results" to 1.0),
        ),
    )

    private fun row(
        rowId: String = "row-0001",
        taskId: String = "task-apple-pie",
        stepIndex: Int = 3,
        timestamp: Long = 1_760_200_000_000L + stepIndex,
        teacherModel: String = "typesafe/jev-1.13-20260917",
        teacherConfigId: String = "primary",
        shadowAnswer: ShadowAnswer? = agreeingShadow,
        gateEvent: LoggedGateEvent? = LoggedGateEvent(
            hclass = LoggedHClass.H4,
            decision = LoggedGateDecision.APPROVED,
            trustedFromTask = false,
        ),
        disposition: ActionDisposition = ActionDisposition.EXECUTED,
        outcome: Outcome = Outcome.PROGRESSED,
        compressedBy: CompressedBy? = CompressedBy.CODE,
        shadowSampled: Boolean = true,
    ): navigator.core.log.DecisionLogRow {
        val shadow = shadowAnswer?.let { ShadowRef("laya", ShadowPrecision.INT8) }
        return navigator.core.log.DecisionLogRow(
            rowId = rowId,
            taskId = taskId,
            goal = "find an apple pie recipe",
            stepIndex = stepIndex,
            timestamp = timestamp,
            stateText = "GOAL find an apple pie recipe\nSTEP $stepIndex\n" +
                "URL https://www.google.com/search?q=apple+pie\n" +
                "LINKS [12] \"Apple pie recipe - Allrecipes\" (540,320) [nN]",
            stateFormatVersion = 1,
            triageClass = TriageClass.RESEARCH_MULTI_STEP,
            questionPrimary = primaryQuestion,
            questionShadow = shadowQuestion,
            teacher = EngineIdentity(teacherConfigId, teacherModel),
            teacherAnswer = teacherAnswer,
            shadow = shadow,
            shadowAnswer = shadowAnswer,
            agreementExactMatch = shadowAnswer?.let { Agreement.exactMatch(teacherAnswer, it) },
            agreementKL = shadowAnswer?.let { Agreement.klDivergence(teacherAnswer, it) },
            executedAction = ExecutedAction("n12", LoggedActionKind.TAP, disposition),
            gateEvent = gateEvent,
            outcome = outcome,
            compressedBy = compressedBy,
            shadowSampled = shadowSampled,
        )
    }

    // ---- Gate 5.1: model → Room → model ----

    @Test
    fun `complete row round-trips through Room with no field loss`(): Unit = runBlocking {
        val row = row()
        logger.insert(row)
        val decoded = logger.byTask(row.taskId).single()
        assertEquals(row, decoded)
        assertEquals("primary", decoded.teacher.configId)
        assertEquals("typesafe/jev-1.13-20260917", decoded.teacher.model)
        val shadowAnswer = requireNotNull(decoded.shadowAnswer) { "shadowAnswer must survive" }
        assertEquals("nav-links", shadowAnswer.bucket.chosen)
        assertEquals("open-first-result", shadowAnswer.element.chosen)
        assertEquals(ShadowPrecision.INT8, requireNotNull(decoded.shadow) { "shadow must survive" }.precision)
        val gateEvent = requireNotNull(decoded.gateEvent) { "gateEvent must survive" }
        assertEquals(LoggedGateDecision.APPROVED, gateEvent.decision)
        assertEquals(false, gateEvent.trustedFromTask)
        assertEquals(CompressedBy.CODE, decoded.compressedBy)
        assertTrue(decoded.agreementExactMatch!!)
        assertEquals(Agreement.klDivergence(row.teacherAnswer, requireNotNull(row.shadowAnswer)), decoded.agreementKL)
    }

    @Test
    fun `every gateEvent variant round-trips through Room`(): Unit = runBlocking {
        val rows = listOf(
            row(
                rowId = "row-approved",
                gateEvent = LoggedGateEvent(LoggedHClass.H4, LoggedGateDecision.APPROVED, trustedFromTask = false),
            ),
            row(
                rowId = "row-approved-trusted",
                gateEvent = LoggedGateEvent(LoggedHClass.H4, LoggedGateDecision.APPROVED, trustedFromTask = true),
            ),
            row(
                rowId = "row-denied",
                gateEvent = LoggedGateEvent(LoggedHClass.H1, LoggedGateDecision.DENIED),
                disposition = ActionDisposition.REFUSED_BY_OWNER,
            ),
            row(
                rowId = "row-timeout",
                gateEvent = LoggedGateEvent(LoggedHClass.H7, LoggedGateDecision.TIMEOUT),
                disposition = ActionDisposition.GATE_TIMEOUT,
            ),
            row(rowId = "row-no-gate", gateEvent = null),
        )
        logger.insertAll(rows)
        val decoded = logger.byTask("task-apple-pie")
        assertEquals(rows, decoded)
        assertEquals(true, requireNotNull(decoded[1].gateEvent).trustedFromTask)
        assertEquals(LoggedGateDecision.DENIED, requireNotNull(decoded[2].gateEvent).decision)
        assertEquals(LoggedGateDecision.TIMEOUT, requireNotNull(decoded[3].gateEvent).decision)
        assertNull(decoded[4].gateEvent)
    }

    @Test
    fun `shadowMiss row round-trips with null shadow columns`(): Unit = runBlocking {
        val miss = row(
            rowId = "row-miss",
            shadowAnswer = null,
            compressedBy = null,
            outcome = Outcome.NO_CHANGE,
        )
        logger.insert(miss)
        val decoded = logger.byTask(miss.taskId).single()
        assertEquals(miss, decoded)
        assertNull(decoded.shadow)
        assertNull(decoded.shadowAnswer)
        assertNull(decoded.agreementExactMatch)
        assertNull(decoded.agreementKL)
    }

    @Test
    fun `every outcome value round-trips through Room`(): Unit = runBlocking {
        val rows = Outcome.entries.mapIndexed { index, outcome ->
            row(rowId = "row-outcome-$index", outcome = outcome)
        }
        logger.insertAll(rows)
        assertEquals(rows, logger.byTask("task-apple-pie"))
    }

    // ---- insert-only law ----

    @Test
    fun `duplicate rowId insert fails loudly - rows are immutable history`(): Unit = runBlocking {
        logger.insert(row())
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { logger.insert(row()) }
        }
    }

    // ---- query APIs ----

    @Test
    fun `byTask returns only that task in step order`(): Unit = runBlocking {
        logger.insertAll(
            listOf(
                row(rowId = "a-2", taskId = "task-a", stepIndex = 2),
                row(rowId = "b-1", taskId = "task-b", stepIndex = 1),
                row(rowId = "a-1", taskId = "task-a", stepIndex = 1),
            ),
        )
        assertEquals(listOf("a-1", "a-2"), logger.byTask("task-a").map { it.rowId })
        assertEquals(listOf("b-1"), logger.byTask("task-b").map { it.rowId })
    }

    @Test
    fun `byTimeWindow is inclusive on both ends`(): Unit = runBlocking {
        logger.insertAll(
            listOf(
                row(rowId = "early", stepIndex = 1, timestamp = 1_000),
                row(rowId = "in", stepIndex = 2, timestamp = 2_000),
                row(rowId = "late", stepIndex = 3, timestamp = 3_000),
            ),
        )
        assertEquals(
            listOf("early", "in"),
            logger.byTimeWindow(1_000, 2_000).map { it.rowId },
        )
    }

    @Test
    fun `agreement aggregation counts only both-answer rows`(): Unit = runBlocking {
        logger.insertAll(
            listOf(
                row(rowId = "agree", shadowAnswer = agreeingShadow),
                row(rowId = "disagree", shadowAnswer = disagreeingShadow()),
                row(rowId = "miss", shadowAnswer = null, shadowSampled = true),
            ),
        )
        assertEquals(listOf("agree", "disagree"), logger.rowsWithBothAnswers().map { it.rowId })
        val aggregate = logger.agreementAggregate()
        assertEquals(2, aggregate.rowsConsidered)
        assertEquals(0.5, aggregate.exactMatchFraction!!, 1e-9)
    }

    @Test
    fun `empty log aggregates to zero rows and null fraction`(): Unit = runBlocking {
        val aggregate = logger.agreementAggregate()
        assertEquals(0, aggregate.rowsConsidered)
        assertNull(aggregate.exactMatchFraction)
    }
}
