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
import navigator.core.log.Outcome
import navigator.core.log.TeacherAnswer
import navigator.core.log.TriageClass
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Gate 8F (Room half, doc 05 §2 `typedSecret`, doc 06 §2a) — the schema v6 column: a
 * credential-typing row round-trips the FLAG (the value itself is never stored — logs
 * and rows render `[credential-redacted]`); plain rows decode with typedSecret=false;
 * and the Room migration v5→v6 keeps every historical row (a hand-built v5 database) —
 * old rows decode as typedSecret=false, new-style rows insert on the migrated database.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DecisionLogV6DbTest {

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
            Option("type-password", "Type the password", "bucket=form-fields (30,80)"),
            Option("submit-sign-in", "Submit sign in", "bucket=buttons (30,160)"),
        ),
    )

    private val shadowQuestion = BucketedQuestion(
        name = "next-action-bucketed",
        buckets = mapOf(
            Bucket.FORM_FIELDS to listOf(Option("type-password", "Type the password", "bucket=form-fields (30,80)")),
            Bucket.BUTTONS to listOf(Option("submit-sign-in", "Submit sign in", "bucket=buttons (30,160)")),
        ),
    )

    private val teacherAnswer = TeacherAnswer(
        chosen = "type-password",
        probabilities = mapOf("type-password" to 0.90, "submit-sign-in" to 0.10),
        confidence = 0.90,
    )

    private fun row(rowId: String, typedSecret: Boolean) = navigator.core.log.DecisionLogRow(
        rowId = rowId,
        taskId = "task-login",
        goal = "Sign in to the fixture bank demo account.",
        stepIndex = 1,
        timestamp = 1_760_500_000_000,
        stateText = "GOAL: Sign in to the fixture bank demo account.\nURL: http://127.0.0.1:8099/login.html",
        stateFormatVersion = 1,
        triageClass = TriageClass.FORM_FILL,
        questionPrimary = primaryQuestion,
        questionShadow = shadowQuestion,
        teacher = EngineIdentity(configId = "jev-primary", model = "typesafe/jev-1.13-20260917"),
        teacherAnswer = teacherAnswer,
        executedAction = ExecutedAction(
            candidateId = if (typedSecret) "pw-field" else "submit-btn",
            kind = if (typedSecret) LoggedActionKind.TYPE else LoggedActionKind.SUBMIT,
            disposition = ActionDisposition.EXECUTED,
        ),
        outcome = Outcome.PROGRESSED,
        shadowSampled = false,
        typedSecret = typedSecret,
    )

    // ---- typedSecret round-trips (doc 05 §2, Phase 08F) -----------------------------

    @Test
    fun `a credential-typing row round-trips the flag through Room`(): Unit = runBlocking {
        val typed = row("row-typed", typedSecret = true)
        logger.insert(typed)
        val decoded = logger.byTask("task-login").single()
        assertEquals(typed, decoded)
        assertTrue("the flag must survive the Room round-trip", decoded.typedSecret)
    }

    @Test
    fun `a plain row decodes with typedSecret false`(): Unit = runBlocking {
        val plain = row("row-plain", typedSecret = false)
        logger.insert(plain)
        val decoded = logger.byTask("task-login").single()
        assertEquals(plain, decoded)
        assertFalse(decoded.typedSecret)
    }

    // ---- the Room migration v5 → v6 (existing DBs survive) ---------------------------

    @Test
    fun `v5 database survives the migration and gains the typedSecret column`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "decision-log-v6-migration-test.db"
        context.deleteDatabase(name)

        // A faithful v5 database: the Room v5 DDL for decision_log (exitJson, NO
        // typedSecret) + shadow_result + exit_check + one historical row + version 5.
        val v5 = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        v5.execSQL(
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
                "PRIMARY KEY(`rowId`))",
        )
        v5.execSQL("CREATE INDEX IF NOT EXISTS `index_decision_log_taskId` ON `decision_log` (`taskId`)")
        v5.execSQL("CREATE INDEX IF NOT EXISTS `index_decision_log_timestamp` ON `decision_log` (`timestamp`)")
        v5.execSQL(
            "CREATE TABLE IF NOT EXISTS `shadow_result` (" +
                "`rowId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `stepIndex` INTEGER NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, `shadowCheckpoint` TEXT NOT NULL, " +
                "`shadowPrecision` TEXT NOT NULL, `shadowAnswerJson` TEXT NOT NULL, " +
                "`latencyMs` INTEGER NOT NULL, PRIMARY KEY(`rowId`))",
        )
        v5.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_shadow_result_taskId_stepIndex` " +
                "ON `shadow_result` (`taskId`, `stepIndex`)",
        )
        v5.execSQL(
            "CREATE TABLE IF NOT EXISTS `exit_check` (" +
                "`rowId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `stepIndex` INTEGER NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, `stateText` TEXT NOT NULL, `stateFormatVersion` INTEGER NOT NULL, " +
                "`questionJson` TEXT NOT NULL, `anchoredNoulVersion` INTEGER NOT NULL, " +
                "`teacherConfigId` TEXT NOT NULL, `teacherModel` TEXT NOT NULL, " +
                "`answerPTrue` REAL NOT NULL, `answerBand` TEXT NOT NULL, `escalated` INTEGER NOT NULL, " +
                "`fallbackPTrue` REAL, `guardLevel` TEXT NOT NULL, `verdict` TEXT NOT NULL, " +
                "`acceptedPath` TEXT, PRIMARY KEY(`rowId`))",
        )
        v5.execSQL("CREATE INDEX IF NOT EXISTS `index_exit_check_taskId` ON `exit_check` (`taskId`)")
        v5.execSQL("CREATE INDEX IF NOT EXISTS `index_exit_check_timestamp` ON `exit_check` (`timestamp`)")
        v5.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_exit_check_taskId_stepIndex` " +
                "ON `exit_check` (`taskId`, `stepIndex`)",
        )
        val legacy = row("legacy-row", typedSecret = false)
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
            // NO typedSecret column exists yet at v5.
        }
        v5.insert("decision_log", null, values)
        v5.version = 5
        v5.close()

        // Open through Room: MIGRATION_5_6 must run, validate, and keep the history.
        val migrated = Room.databaseBuilder(context, DecisionLogDb::class.java, name)
            .addMigrations(
                DecisionLogDb.MIGRATION_1_2,
                DecisionLogDb.MIGRATION_2_3,
                DecisionLogDb.MIGRATION_3_4,
                DecisionLogDb.MIGRATION_4_5,
                DecisionLogDb.MIGRATION_5_6,
                DecisionLogDb.MIGRATION_6_7,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val migratedLogger = DecisionLogger(migrated)
            val decoded = migratedLogger.byTask("task-login").single()
            assertEquals("history survives the v5→v6 migration", legacy, decoded)
            assertFalse("a pre-v6 row decodes with typedSecret=false", decoded.typedSecret)

            // the new column is live on the migrated database: a credential row inserts
            val typed = row("post-migration-typed", typedSecret = true)
            migratedLogger.insert(typed)
            val redecoded = migratedLogger.byTask("task-login").first { it.rowId == typed.rowId }
            assertEquals(typed, redecoded)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
