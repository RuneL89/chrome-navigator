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
import navigator.core.log.ExitProvenance
import navigator.core.log.LoggedActionKind
import navigator.core.log.LoggedExitPath
import navigator.core.log.LoggedGuardLevel
import navigator.core.log.Outcome
import navigator.core.log.TeacherAnswer
import navigator.core.log.TriageClass
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Gate 8J (Room half) — the schema v4 exit column (vision 05 v1.4.0, vision 04 §3
 * v1.2.0): the terminal row's exit provenance round-trips; non-terminal rows decode
 * with exit=null; and the Room migration v3→v4 keeps every historical row (a
 * hand-built v3 database) — old rows decode as exit=null, new-style rows insert on
 * the migrated database.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DecisionLogV4DbTest {

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
            Option("open-first-result", "Open first result", "Tap the top hit"),
            Option("finish", "Finish", "bucket=finish (0,0)"),
        ),
    )

    private val shadowQuestion = BucketedQuestion(
        name = "next-action-bucketed",
        buckets = mapOf(
            Bucket.NAV_LINKS to listOf(Option("open-first-result", "Open first result", "Tap the top hit")),
            Bucket.FINISH to listOf(Option("finish", "Finish", "bucket=finish (0,0)")),
        ),
    )

    private val teacherAnswer = TeacherAnswer(
        chosen = "finish",
        probabilities = mapOf("open-first-result" to 0.10, "finish" to 0.90),
        confidence = 0.90,
    )

    private fun row(rowId: String, exit: ExitProvenance?) = navigator.core.log.DecisionLogRow(
        rowId = rowId,
        taskId = "task-exit",
        goal = "Open Wikipedia article Tarte Tatin.",
        stepIndex = 8,
        timestamp = 1_760_400_000_000,
        stateText = "GOAL Open Wikipedia article Tarte Tatin.\nURL https://en.wikipedia.org/wiki/Tarte_Tatin",
        stateFormatVersion = 1,
        triageClass = TriageClass.LOOKUP,
        questionPrimary = primaryQuestion,
        questionShadow = shadowQuestion,
        teacher = EngineIdentity(configId = "jev-primary", model = "typesafe/jev-1.13-20260917"),
        teacherAnswer = teacherAnswer,
        executedAction = ExecutedAction(
            candidateId = null,
            kind = LoggedActionKind.FINISH,
            disposition = ActionDisposition.EXECUTED,
        ),
        outcome = Outcome.EXITED,
        shadowSampled = false,
        exit = exit,
    )

    // ---- exit provenance round-trips (vision 05 v1.4.0) ---------------------------

    @Test
    fun `terminal row with exit provenance round-trips through Room`(): Unit = runBlocking {
        val terminal = row(
            "row-exit",
            ExitProvenance(path = LoggedExitPath.SUSTAINED, guardLevel = LoggedGuardLevel.STRONG, noul = 0.61),
        )
        logger.insert(terminal)
        val decoded = logger.byTask("task-exit").single()
        assertEquals(terminal, decoded)
        val exit = requireNotNull(decoded.exit) { "exit provenance must survive the Room round-trip" }
        assertEquals(LoggedExitPath.SUSTAINED, exit.path)
        assertEquals(LoggedGuardLevel.STRONG, exit.guardLevel)
        assertEquals(0.61, exit.noul, 1e-12)
    }

    @Test
    fun `a row without exit provenance decodes with exit null`(): Unit = runBlocking {
        val plain = row("row-plain", exit = null)
        logger.insert(plain)
        val decoded = logger.byTask("task-exit").single()
        assertEquals(plain, decoded)
        assertNull(decoded.exit)
    }

    @Test
    fun `owner-confirm provenance round-trips too`(): Unit = runBlocking {
        val confirmed = row(
            "row-owner-confirm",
            ExitProvenance(path = LoggedExitPath.OWNER_CONFIRM, guardLevel = LoggedGuardLevel.WEAK, noul = 0.44),
        )
        logger.insert(confirmed)
        assertEquals(LoggedExitPath.OWNER_CONFIRM, logger.byTask("task-exit").single().exit?.path)
    }

    // ---- the Room migration v3 → v4 (existing DBs survive) --------------------------

    @Test
    fun `v3 database survives the migration and gains the exit column`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "decision-log-v4-migration-test.db"
        context.deleteDatabase(name)

        // A faithful v3 database: the Room v3 DDL for decision_log (escalated +
        // fallbackJson, NO exitJson) + shadow_result + one historical row + version 3.
        val v3 = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        v3.execSQL(
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
                "`escalated` INTEGER NOT NULL DEFAULT 0, `fallbackJson` TEXT, PRIMARY KEY(`rowId`))",
        )
        v3.execSQL("CREATE INDEX IF NOT EXISTS `index_decision_log_taskId` ON `decision_log` (`taskId`)")
        v3.execSQL("CREATE INDEX IF NOT EXISTS `index_decision_log_timestamp` ON `decision_log` (`timestamp`)")
        v3.execSQL(
            "CREATE TABLE IF NOT EXISTS `shadow_result` (" +
                "`rowId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `stepIndex` INTEGER NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, `shadowCheckpoint` TEXT NOT NULL, " +
                "`shadowPrecision` TEXT NOT NULL, `shadowAnswerJson` TEXT NOT NULL, " +
                "`latencyMs` INTEGER NOT NULL, PRIMARY KEY(`rowId`))",
        )
        v3.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_shadow_result_taskId_stepIndex` " +
                "ON `shadow_result` (`taskId`, `stepIndex`)",
        )
        val legacy = row("legacy-row", exit = null)
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
            // NO exitJson column exists yet at v3.
        }
        v3.insert("decision_log", null, values)
        v3.version = 3
        v3.close()

        // Open through Room: MIGRATION_3_4 must run, validate, and keep the history.
        val migrated = Room.databaseBuilder(context, DecisionLogDb::class.java, name)
            .addMigrations(DecisionLogDb.MIGRATION_1_2, DecisionLogDb.MIGRATION_2_3, DecisionLogDb.MIGRATION_3_4, DecisionLogDb.MIGRATION_4_5, DecisionLogDb.MIGRATION_5_6, DecisionLogDb.MIGRATION_6_7, DecisionLogDb.MIGRATION_7_8, DecisionLogDb.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
        try {
            val migratedLogger = DecisionLogger(migrated)
            val decoded = migratedLogger.byTask("task-exit").single()
            assertEquals("history survives the v3→v4 migration", legacy, decoded)
            assertNull("a pre-v4 row decodes with no exit provenance", decoded.exit)

            // the new column is live on the migrated database: an exit row inserts
            val terminal = row(
                "post-migration-exit",
                ExitProvenance(path = LoggedExitPath.HIGH, guardLevel = LoggedGuardLevel.STRONG, noul = 0.95),
            )
            migratedLogger.insert(terminal)
            val redecoded = migratedLogger.byTask("task-exit").first { it.rowId == terminal.rowId }
            assertEquals(terminal, redecoded)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
