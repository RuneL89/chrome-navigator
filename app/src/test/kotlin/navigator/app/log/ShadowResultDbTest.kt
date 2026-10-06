package navigator.app.log

import android.content.ContentValues
import android.content.Context
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
import navigator.core.log.ExecutedAction
import navigator.core.log.JoinedAgreement
import navigator.core.log.LoggedActionKind
import navigator.core.log.Outcome
import navigator.core.log.ShadowAnswer
import navigator.core.log.ShadowPrecision
import navigator.core.log.ShadowRef
import navigator.core.log.ShadowResultRow
import navigator.core.log.ShadowStageAnswer
import navigator.core.log.TeacherAnswer
import navigator.core.log.TriageClass
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Gate 8B.1 (persistence half) + Gate 8B.3 (Room level), PHASE_08B §3 — the companion
 * shadow-result store (vision 05 §2 amendment 2026-10-04): round-trip with no field
 * loss; duplicate-key ABORT (rowId AND the unique `(taskId, stepIndex)`); the loud
 * persistence-boundary guard (rule a: never beside an answered decision row, never an
 * orphan); the Room migration v1→v2 keeping every historical row (Robolectric); the
 * joined view LEFT JOIN with hand-checked agreement + coverage.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShadowResultDbTest {

    private lateinit var db: DecisionLogDb
    private lateinit var logger: DecisionLogger
    private lateinit var shadows: ShadowResultLogger

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, DecisionLogDb::class.java)
            .allowMainThreadQueries()
            .build()
        logger = DecisionLogger(db)
        shadows = ShadowResultLogger(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- row fixtures (the Gate 8B ledger; mirrors the :core fixtures, invisible here) ----

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

    private fun shadowAnswer(elementChosen: String): ShadowAnswer {
        val element = when (elementChosen) {
            "open-first-result" -> ShadowStageAnswer(
                "open-first-result",
                mapOf("open-first-result" to 0.75, "open-second-result" to 0.25),
            )
            else -> ShadowStageAnswer(elementChosen, mapOf(elementChosen to 1.0))
        }
        // the element must live inside the chosen bucket (DecisionLogRow law):
        // scroll-results sits in buttons, everything else in nav-links
        val bucket = when (elementChosen) {
            "scroll-results" -> ShadowStageAnswer(
                "buttons",
                mapOf("form-fields" to 0.10, "nav-links" to 0.30, "buttons" to 0.60),
            )
            else -> ShadowStageAnswer(
                "nav-links",
                mapOf("form-fields" to 0.20, "nav-links" to 0.60, "buttons" to 0.20),
            )
        }
        return ShadowAnswer(bucket = bucket, element = element)
    }

    private fun decisionRow(
        stepIndex: Int,
        taskId: String = "task-8b",
        sampled: Boolean = true,
        answered: Boolean = false,
        elementChosen: String = "open-first-result",
    ): navigator.core.log.DecisionLogRow {
        val answer = if (answered) shadowAnswer(elementChosen) else null
        return navigator.core.log.DecisionLogRow(
            rowId = "$taskId-step$stepIndex",
            taskId = taskId,
            goal = "find an apple pie recipe",
            stepIndex = stepIndex,
            timestamp = 1_760_500_000_000L + stepIndex,
            stateText = "GOAL find an apple pie recipe\nSTEP $stepIndex\nURL https://t.test/x",
            stateFormatVersion = 1,
            triageClass = TriageClass.LOOKUP,
            questionPrimary = primaryQuestion,
            questionShadow = shadowQuestion,
            teacher = navigator.core.log.EngineIdentity("primary", "typesafe/jev-1.13-20260917"),
            teacherAnswer = teacherAnswer,
            shadow = answer?.let { ShadowRef("laya", ShadowPrecision.INT8) },
            shadowAnswer = answer,
            agreementExactMatch = answer?.let { Agreement.exactMatch(teacherAnswer, it) },
            agreementKL = answer?.let { Agreement.klDivergence(teacherAnswer, it) },
            executedAction = ExecutedAction("n12", LoggedActionKind.TAP, ActionDisposition.EXECUTED),
            gateEvent = null,
            outcome = Outcome.PROGRESSED,
            compressedBy = null,
            shadowSampled = sampled,
        )
    }

    private fun companionRow(
        taskId: String,
        stepIndex: Int,
        elementChosen: String = "open-first-result",
        timestamp: Long = 1_760_500_099_000L,
        latencyMs: Long = 5_900,
    ): ShadowResultRow = ShadowResultRow(
        rowId = ShadowResultRow.rowIdFor(taskId, stepIndex),
        taskId = taskId,
        stepIndex = stepIndex,
        timestamp = timestamp,
        shadow = ShadowRef("laya", ShadowPrecision.INT8),
        shadowAnswer = shadowAnswer(elementChosen),
        latencyMs = latencyMs,
    )

    // ---- Gate 8B.1: round-trip ----

    @Test
    fun `companion round-trips through Room with no field loss`(): Unit = runBlocking {
        val decision = decisionRow(stepIndex = 3) // a shadowMiss row
        logger.insert(decision)
        val companion = companionRow(decision.taskId, decision.stepIndex)
        shadows.insert(companion)
        assertEquals(companion, shadows.byTask(decision.taskId).single())
        val decoded = shadows.byTask(decision.taskId).single()
        assertEquals("laya", decoded.shadow.checkpoint)
        assertEquals(ShadowPrecision.INT8, decoded.shadow.precision)
        assertEquals("open-first-result", decoded.shadowAnswer.element.chosen)
        assertEquals(0.75, decoded.shadowAnswer.element.probabilities["open-first-result"]!!, 1e-12)
        assertEquals(5_900, decoded.latencyMs)
        assertEquals(companion.timestamp, decoded.timestamp)
    }

    // ---- Gate 8B.1: duplicate-key ABORT ----

    @Test
    fun `duplicate (taskId, stepIndex) companion insert fails loudly`(): Unit = runBlocking {
        val decision = decisionRow(stepIndex = 3)
        logger.insert(decision)
        shadows.insert(companionRow(decision.taskId, decision.stepIndex))
        val duplicate = companionRow(decision.taskId, decision.stepIndex, latencyMs = 42)
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { shadows.insert(duplicate) }
        }
        assertEquals("exactly one companion per key (rule a)", 1, shadows.companionRowCount())
    }

    @Test
    fun `duplicate companion rowId fails loudly`(): Unit = runBlocking {
        val decision = decisionRow(stepIndex = 3)
        logger.insert(decision)
        val companion = companionRow(decision.taskId, decision.stepIndex)
        shadows.insert(companion)
        // a DIFFERENT key re-using the first companion's rowId still collides on the PK
        logger.insert(decisionRow(stepIndex = 4))
        val stolen = companionRow(decision.taskId, 4).copy(rowId = companion.rowId)
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { shadows.insert(stolen) }
        }
    }

    // ---- Gate 8B.1: the persistence-boundary guard (rule a) ----

    @Test
    fun `companion for a decision row that already carries a shadow answer is rejected loudly`(): Unit = runBlocking {
        val answered = decisionRow(stepIndex = 3, answered = true)
        logger.insert(answered)
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { shadows.insert(companionRow(answered.taskId, answered.stepIndex)) }
        }
        assertTrue("the rejection must name the law: ${error.message}", "rule a" in error.message!!)
        assertEquals("nothing was inserted", 0, shadows.companionRowCount())
    }

    @Test
    fun `orphan companion with no decision row is rejected loudly`(): Unit = runBlocking {
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { shadows.insert(companionRow("task-nobody", 0)) }
        }
        assertTrue("the rejection must name the law: ${error.message}", "no decision row" in error.message!!)
        assertEquals("nothing was inserted", 0, shadows.companionRowCount())
    }

    // ---- Gate 8B.3: joined view + agreement over a crafted Room ledger ----

    @Test
    fun `joined view pairs each decision row with at most one companion - never double`(): Unit = runBlocking {
        val task = "task-join"
        logger.insert(decisionRow(stepIndex = 0, taskId = task, answered = true))
        logger.insert(decisionRow(stepIndex = 1, taskId = task)) // shadowMiss
        shadows.insert(companionRow(task, 1))
        val view = shadows.joinedByTask(task)
        assertEquals("the LEFT JOIN must not multiply rows", 2, view.size)
        assertNull("the answered row carries its own answer (rule a)", view[0].companion)
        assertEquals(ShadowResultRow.rowIdFor(task, 1), view[1].companion?.rowId)
        assertEquals(1, shadows.companionRowCount())
        // decode round-trip inside the view
        assertEquals(companionRow(task, 1), view[1].companion)
    }

    @Test
    fun `joined-view agreement over the mixed Room ledger matches hand computation`(): Unit = runBlocking {
        val task = "task-8b"
        // step 0: FAST shadow, answered in the row, AGREEING (KL 0)
        logger.insert(decisionRow(stepIndex = 0, taskId = task, answered = true))
        // step 1: FAST shadow, answered in the row, DISAGREEING
        logger.insert(decisionRow(stepIndex = 1, taskId = task, answered = true, elementChosen = "scroll-results"))
        // step 2: SLOW shadow, row froze as a miss, companion arrived AGREEING (KL 0)
        logger.insert(decisionRow(stepIndex = 2, taskId = task))
        shadows.insert(companionRow(task, 2))
        // step 3: SLOW shadow, never completed anywhere (bare miss)
        logger.insert(decisionRow(stepIndex = 3, taskId = task))
        // step 4: shadow OFF (unsampled)
        logger.insert(decisionRow(stepIndex = 4, taskId = task, sampled = false))

        val metrics = shadows.joinedAgreementByTask(task)
        assertEquals(4, metrics.sampledRows)
        assertEquals(3, metrics.joinedRows)
        assertEquals(2, metrics.exactMatches)
        assertEquals(2.0 / 3.0, metrics.exactMatchFraction!!, 1e-12)
        assertEquals(3.0 / 4.0, metrics.coverage, 1e-12)
        // mean KL over the joined rows — cross-checked against the raw calculator per row
        val agreeKl = Agreement.klDivergence(teacherAnswer, shadowAnswer("open-first-result"))
        val disagreeKl = Agreement.klDivergence(teacherAnswer, shadowAnswer("scroll-results"))
        assertEquals((agreeKl + disagreeKl + agreeKl) / 3.0, metrics.meanKl!!, 1e-12)
        val metric = JoinedAgreement.uiMetric(metrics)
        assertEquals(66.66666666666667, metric.percentage!!, 1e-9)
        assertEquals(0.75, metric.coverage, 1e-12)
    }

    @Test
    fun `companionRowCount counts only companions`(): Unit = runBlocking {
        val task = "task-count"
        logger.insert(decisionRow(stepIndex = 0, taskId = task))
        logger.insert(decisionRow(stepIndex = 1, taskId = task, answered = true))
        assertEquals(0, shadows.companionRowCount())
        shadows.insert(companionRow(task, 0))
        assertEquals("decision rows never count (rule e)", 1, shadows.companionRowCount())
    }

    // ---- Gate 8B.1: the Room migration v1 → v2 (existing DBs survive) ----

    @Test
    fun `v1 database survives the migration and gains the companion table`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "shadow-result-migration-test.db"
        context.deleteDatabase(name)

        // A faithful v1 database: the exact Room v1 DDL for decision_log + one row + user_version 1.
        val v1 = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        v1.execSQL(
            "CREATE TABLE IF NOT EXISTS `decision_log` (" +
                "`rowId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `goal` TEXT NOT NULL, " +
                "`stepIndex` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `stateText` TEXT NOT NULL, " +
                "`stateFormatVersion` INTEGER NOT NULL, `triageClass` TEXT NOT NULL, " +
                "`questionPrimaryJson` TEXT NOT NULL, `questionShadowJson` TEXT NOT NULL, " +
                "`teacherConfigId` TEXT NOT NULL, `teacherModel` TEXT NOT NULL, " +
                "`teacherAnswerJson` TEXT NOT NULL, `shadowCheckpoint` TEXT, `shadowPrecision` TEXT, " +
                "`shadowAnswerJson` TEXT, `agreementExactMatch` INTEGER, `agreementKl` REAL, " +
                "`executedActionJson` TEXT NOT NULL, `gateEventJson` TEXT, `outcome` TEXT NOT NULL, " +
                "`compressedBy` TEXT, `shadowSampled` INTEGER NOT NULL, PRIMARY KEY(`rowId`))",
        )
        v1.execSQL("CREATE INDEX IF NOT EXISTS `index_decision_log_taskId` ON `decision_log` (`taskId`)")
        v1.execSQL("CREATE INDEX IF NOT EXISTS `index_decision_log_timestamp` ON `decision_log` (`timestamp`)")
        val legacy = decisionRow(stepIndex = 7, taskId = "legacy-task")
        val entity = DecisionLogConverters.toEntity(legacy)
        val values = ContentValues().apply {
            put("rowId", entity.rowId)
            put("taskId", entity.taskId)
            put("goal", entity.goal)
            put("stepIndex", entity.stepIndex)
            put("timestamp", entity.timestamp)
            put("stateText", entity.stateText)
            put("stateFormatVersion", entity.stateFormatVersion)
            put("triageClass", entity.triageClass)
            put("questionPrimaryJson", entity.questionPrimaryJson)
            put("questionShadowJson", entity.questionShadowJson)
            put("teacherConfigId", entity.teacherConfigId)
            put("teacherModel", entity.teacherModel)
            put("teacherAnswerJson", entity.teacherAnswerJson)
            put("shadowCheckpoint", entity.shadowCheckpoint)
            put("shadowPrecision", entity.shadowPrecision)
            put("shadowAnswerJson", entity.shadowAnswerJson)
            put("agreementExactMatch", entity.agreementExactMatch)
            put("agreementKl", entity.agreementKl)
            put("executedActionJson", entity.executedActionJson)
            put("gateEventJson", entity.gateEventJson)
            put("outcome", entity.outcome)
            put("compressedBy", entity.compressedBy)
            put("shadowSampled", if (entity.shadowSampled) 1 else 0)
        }
        v1.insert("decision_log", null, values)
        v1.version = 1
        v1.close()

        // Open through Room: MIGRATION_1_2 (+ the Phase-8C MIGRATION_2_3 and the Phase-8J
        // MIGRATION_3_4 — the database is now at version 4) must run, validate, keep history.
        
        val migrated = Room.databaseBuilder(context, DecisionLogDb::class.java, name)
            .addMigrations(DecisionLogDb.MIGRATION_1_2, DecisionLogDb.MIGRATION_2_3, DecisionLogDb.MIGRATION_3_4, DecisionLogDb.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        try {
            val migratedLogger = DecisionLogger(migrated)
            assertEquals("history survives", legacy, migratedLogger.byTask("legacy-task").single())
            // the new table is live: the surviving miss row can gain a companion
            val migratedShadows = ShadowResultLogger(migrated)
            val companion = companionRow("legacy-task", 7)
            migratedShadows.insert(companion)
            assertEquals(listOf(companion), migratedShadows.byTask("legacy-task"))
            assertEquals(1, migratedShadows.companionRowCount())
            // and the guard holds on the migrated database too
            val answered = decisionRow(stepIndex = 0, taskId = "legacy-task", answered = true)
            migratedLogger.insert(answered)
            assertThrows(IllegalStateException::class.java) {
                runBlocking { migratedShadows.insert(companionRow("legacy-task", 0)) }
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
