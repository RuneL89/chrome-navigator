package navigator.app.log

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.Option
import navigator.core.enumerate.Bucket
import navigator.core.log.ActionDisposition
import navigator.core.log.BucketedQuestion
import navigator.core.log.EngineIdentity
import navigator.core.log.ExecutedAction
import navigator.core.log.LoggedActionKind
import navigator.core.log.LoggedPlan
import navigator.core.log.Outcome
import navigator.core.log.TeacherAnswer
import navigator.core.log.TriageClass
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Gate 8G.3 (Room half, vision 05 §2 `plan`, vision 04 §1 plan-proposal law) — the
 * schema v7 column: a planned task's rows round-trip the plan provenance `{version,
 * subgoals}`; plain rows decode with plan=null; and the Room migration v6→v7 keeps every
 * historical row (a hand-built v6 database) — old rows decode as plan=null, new-style
 * rows insert on the migrated database.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DecisionLogV7DbTest {

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

    // ---- fixtures --------------------------------------------------------------

    private val primaryQuestion = ChoiceQuestion(
        name = "next-action",
        options = listOf(
            Option("tap-target-row", "Tap the target row", "bucket=nav-links (30,80)"),
            Option("finish", "Finish", "bucket=finish (30,160)"),
        ),
    )

    private val shadowQuestion = BucketedQuestion(
        name = "next-action-bucketed",
        buckets = mapOf(
            Bucket.NAV_LINKS to listOf(Option("tap-target-row", "Tap the target row", "bucket=nav-links (30,80)")),
            Bucket.FINISH to listOf(Option("finish", "Finish", "bucket=finish (30,160)")),
        ),
    )

    private val teacherAnswer = TeacherAnswer(
        chosen = "tap-target-row",
        probabilities = mapOf("tap-target-row" to 0.90, "finish" to 0.10),
        confidence = 0.90,
    )

    private val plan = LoggedPlan(
        version = "plan-prompt-v1",
        subgoals = listOf("open the target list page", "apply the operation to each target row"),
    )

    private fun row(rowId: String, withPlan: Boolean) = navigator.core.log.DecisionLogRow(
        rowId = rowId,
        taskId = "task-sweep",
        goal = "Apply the operation to every target row on the page.",
        stepIndex = 1,
        timestamp = 1_760_600_000_000,
        stateText = "GOAL: Apply the operation to every target row on the page.\nURL: http://127.0.0.1:8099/list.html",
        stateFormatVersion = 1,
        triageClass = TriageClass.ACTION_MULTI,
        questionPrimary = primaryQuestion,
        questionShadow = shadowQuestion,
        teacher = EngineIdentity(configId = "jev-primary", model = "typesafe/jev-1.13-20260917"),
        teacherAnswer = teacherAnswer,
        executedAction = ExecutedAction(
            candidateId = "tap-target-row",
            kind = LoggedActionKind.TAP,
            disposition = ActionDisposition.EXECUTED,
        ),
        outcome = Outcome.PROGRESSED,
        shadowSampled = false,
        plan = if (withPlan) plan else null,
    )

    // ---- plan provenance round-trips (vision 05 §2, Phase 08G) ----------------------

    @Test
    fun `a planned row round-trips the plan provenance through Room`(): Unit = runBlocking {
        val planned = row("row-planned", withPlan = true)
        logger.insert(planned)
        val decoded = logger.byTask("task-sweep").single()
        assertEquals(planned, decoded)
        assertEquals(plan, decoded.plan)
    }

    @Test
    fun `a plain row decodes with plan null`(): Unit = runBlocking {
        val plain = row("row-plain", withPlan = false)
        logger.insert(plain)
        val decoded = logger.byTask("task-sweep").single()
        assertEquals(plain, decoded)
        assertNull(decoded.plan)
    }

    // ---- the Room migration v6 → v7 (existing DBs survive) ---------------------------

    @Test
    fun `v6 database survives the migration and gains the planJson column`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "decision-log-v7-migration-test.db"
        context.deleteDatabase(name)

        // A faithful v6 database: the Room v6 DDL for decision_log (typedSecret, NO
        // planJson) + shadow_result + exit_check + one historical row + version 6.
        val v6 = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        v6.execSQL(
            "CREATE TABLE IF NOT EXISTS `decision_log` (" +
                "`rowId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `goal` TEXT NOT NULL, " +
                "`stepIndex` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `stateText` TEXT NOT NULL, " +
                "`stateFormatVersion` INTEGER NOT NULL, `triageClass` TEXT NOT NULL, " +
                "`questionPrimaryJson` TEXT NOT NULL, `questionShadowJson` TEXT NOT NULL, " +
                "`teacherConfigId` TEXT NOT NULL, `teacherModel` TEXT NOT NULL, " +
                "`teacherAnswerJson` TEXT NOT NULL, `shadowCheckpoint` TEXT, `shadowPrecision` TEXT, " +
                "`shadowAnswerJson` TEXT, `agreementExactMatch` INTEGER, `agreementKl` REAL, " +
                "`executedActionJson` TEXT NOT NULL, `gateEventJson` TEXT, `outcome` TEXT NOT NULL, " +
                "`compressedBy` TEXT, `shadowSampled` INTEGER NOT NULL, " +
                "`escalated` INTEGER NOT NULL DEFAULT 0, `fallbackJson` TEXT, `exitJson` TEXT, " +
                "`typedSecret` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`rowId`))",
        )
        v6.execSQL("CREATE INDEX IF NOT EXISTS `index_decision_log_taskId` ON `decision_log` (`taskId`)")
        v6.execSQL("CREATE INDEX IF NOT EXISTS `index_decision_log_timestamp` ON `decision_log` (`timestamp`)")
        v6.execSQL(
            "CREATE TABLE IF NOT EXISTS `shadow_result` (" +
                "`rowId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `stepIndex` INTEGER NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, `shadowCheckpoint` TEXT NOT NULL, " +
                "`shadowPrecision` TEXT NOT NULL, `shadowAnswerJson` TEXT NOT NULL, " +
                "`latencyMs` INTEGER NOT NULL, PRIMARY KEY(`rowId`))",
        )
        v6.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_shadow_result_taskId_stepIndex` " +
                "ON `shadow_result` (`taskId`, `stepIndex`)",
        )
        v6.execSQL(
            "CREATE TABLE IF NOT EXISTS `exit_check` (" +
                "`rowId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `stepIndex` INTEGER NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, `stateText` TEXT NOT NULL, `stateFormatVersion` INTEGER NOT NULL, " +
                "`questionJson` TEXT NOT NULL, `anchoredNoulVersion` INTEGER NOT NULL, " +
                "`teacherConfigId` TEXT NOT NULL, `teacherModel` TEXT NOT NULL, " +
                "`answerPTrue` REAL NOT NULL, `answerBand` TEXT NOT NULL, `escalated` INTEGER NOT NULL, " +
                "`fallbackPTrue` REAL, `guardLevel` TEXT NOT NULL, `verdict` TEXT NOT NULL, " +
                "`acceptedPath` TEXT, PRIMARY KEY(`rowId`))",
        )
        v6.execSQL("CREATE INDEX IF NOT EXISTS `index_exit_check_taskId` ON `exit_check` (`taskId`)")
        v6.execSQL("CREATE INDEX IF NOT EXISTS `index_exit_check_timestamp` ON `exit_check` (`timestamp`)")
        v6.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_exit_check_taskId_stepIndex` " +
                "ON `exit_check` (`taskId`, `stepIndex`)",
        )
        val legacy = row("legacy-row", withPlan = false)
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
            put("escalated", if (entity.escalated) 1 else 0)
            put("fallbackJson", entity.fallbackJson)
            put("exitJson", entity.exitJson)
            put("typedSecret", if (entity.typedSecret) 1 else 0)
            // NO planJson column exists yet at v6.
        }
        v6.insert("decision_log", null, values)
        v6.version = 6
        v6.close()

        // Open through Room: MIGRATION_6_7 must run, validate, and keep the history.
        val migrated = Room.databaseBuilder(context, DecisionLogDb::class.java, name)
            .addMigrations(
                DecisionLogDb.MIGRATION_1_2,
                DecisionLogDb.MIGRATION_2_3,
                DecisionLogDb.MIGRATION_3_4,
                DecisionLogDb.MIGRATION_4_5,
                DecisionLogDb.MIGRATION_5_6,
                DecisionLogDb.MIGRATION_6_7,
                DecisionLogDb.MIGRATION_7_8,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val migratedLogger = DecisionLogger(migrated)
            val decoded = migratedLogger.byTask("task-sweep").single()
            assertEquals("history survives the v6→v7 migration", legacy, decoded)
            assertFalse("a pre-v7 row decodes with plan=null", decoded.plan != null)

            // the new column is live on the migrated database: a planned row inserts
            val planned = row("post-migration-planned", withPlan = true)
            migratedLogger.insert(planned)
            val redecoded = migratedLogger.byTask("task-sweep").first { it.rowId == planned.rowId }
            assertEquals(planned, redecoded)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
