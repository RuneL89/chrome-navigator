package navigator.app.log

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import navigator.core.decision.NoulQuestion
import navigator.core.log.EngineIdentity
import navigator.core.log.ExitCheckAnswer
import navigator.core.log.ExitCheckGuardLevel
import navigator.core.log.ExitCheckRow
import navigator.core.log.ExitCheckVerdict
import navigator.core.log.LoggedBand
import navigator.core.log.LoggedExitPath
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Gate 8J.8 (Room half, vision 05 v1.5.0) — the schema v5 `exit_check` table (the third
 * insert-only row type): exit-check round-trips; duplicate keys ABORT; and the
 * migration CHAIN v3→v4→v5 keeps every historical row (a hand-built v3 database) —
 * old decision rows decode unchanged and exit-check rows insert on the migrated
 * database.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DecisionLogV5DbTest {

    private lateinit var db: DecisionLogDb
    private lateinit var exitChecks: ExitCheckLogger

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, DecisionLogDb::class.java)
            .allowMainThreadQueries()
            .build()
        exitChecks = ExitCheckLogger(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- fixtures --------------------------------------------------------------

    private val question = NoulQuestion(
        name = "goal-achieved",
        proposition = "The target page about \"Tarte Tatin\" is currently displayed.\n" +
            "PAGE IDENTITY: the currently displayed page's title is \"Tarte Tatin - Wikipedia\" " +
            "and its address is https://en.wikipedia.org/wiki/Tarte_Tatin.",
    )

    private fun row(
        rowId: String = "exit-check:task-exit:9",
        stepIndex: Int = 9,
        verdict: ExitCheckVerdict = ExitCheckVerdict.REFUSED,
        acceptedPath: LoggedExitPath? = null,
        guardLevel: ExitCheckGuardLevel = ExitCheckGuardLevel.STRONG,
        escalated: Boolean = false,
        fallbackPTrue: Double? = null,
        pTrue: Double = 0.61,
    ) = ExitCheckRow(
        rowId = rowId,
        taskId = "task-exit",
        stepIndex = stepIndex,
        timestamp = 1_760_400_000_000,
        stateText = "GOAL Open Wikipedia article Tarte Tatin.\nURL https://en.wikipedia.org/wiki/Tarte_Tatin",
        stateFormatVersion = 1,
        question = question,
        anchoredNoulVersion = 2,
        teacher = EngineIdentity(configId = "jev-primary", model = "typesafe/jev-1.13-20260917"),
        answer = ExitCheckAnswer(pTrue = pTrue, band = LoggedBand.UNCERTAIN),
        escalated = escalated,
        fallbackAnswer = fallbackPTrue?.let { navigator.core.log.ExitCheckFallbackAnswer(it) },
        guardLevel = guardLevel,
        verdict = verdict,
        acceptedPath = acceptedPath,
    )

    // ---- exit-check round-trips (vision 05 v1.5.0) ---------------------------------

    @Test
    fun `a refused check round-trips through Room`(): Unit = runBlocking {
        val refused = row()
        exitChecks.insert(refused)
        assertEquals(refused, exitChecks.byTask("task-exit").single())
    }

    @Test
    fun `an accepted check round-trips with its path and band`(): Unit = runBlocking {
        val accepted = row(
            rowId = "exit-check:task-exit:5",
            stepIndex = 5,
            verdict = ExitCheckVerdict.ACCEPTED,
            acceptedPath = LoggedExitPath.SUSTAINED,
            pTrue = 0.58,
        )
        exitChecks.insert(accepted)
        val decoded = exitChecks.byTask("task-exit").single()
        assertEquals(accepted, decoded)
        assertEquals(LoggedExitPath.SUSTAINED, decoded.acceptedPath)
        assertEquals(LoggedBand.UNCERTAIN, decoded.answer.band)
    }

    @Test
    fun `an escalated check round-trips with both answers`(): Unit = runBlocking {
        val escalated = row(escalated = true, fallbackPTrue = 0.81)
        exitChecks.insert(escalated)
        val decoded = exitChecks.byTask("task-exit").single()
        assertEquals(true, decoded.escalated)
        assertEquals(0.81, decoded.fallbackAnswer?.pTrue)
    }

    @Test
    fun `a duplicate (taskId, stepIndex) key ABORTs loudly - exactly one row per check`(): Unit = runBlocking {
        exitChecks.insert(row())
        try {
            exitChecks.insert(row(rowId = "exit-check:task-exit:9-dup"))
            throw AssertionError("the duplicate key insert must fail")
        } catch (expected: Exception) {
            // SQLite constraint failure (ABORT) — exactly-once per finish check.
        }
        assertEquals(1, exitChecks.byTask("task-exit").size)
    }

    // ---- the migration CHAIN v3 → v4 → v5 (existing DBs survive) --------------------

    @Test
    fun `a v3 database survives the chain to v5 and gains the exit_check table`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "decision-log-v5-migration-test.db"
        context.deleteDatabase(name)

        // A faithful v3 database: the Room v3 DDL for decision_log (escalated +
        // fallbackJson; NO exitJson) + shadow_result + one historical row + version 3.
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
        val values = ContentValues().apply {
            put("rowId", "legacy-row")
            put("taskId", "task-exit")
            put("goal", "Open Wikipedia article Tarte Tatin.")
            put("stepIndex", 8)
            put("timestamp", 1_760_400_000_000L)
            put("stateText", "GOAL Open Wikipedia article Tarte Tatin.")
            put("stateFormatVersion", 1)
            put("triageClass", "lookup")
            put("questionPrimaryJson", PRIMARY_QUESTION_JSON)
            put("questionShadowJson", SHADOW_QUESTION_JSON)
            put("teacherConfigId", "jev-primary")
            put("teacherModel", "typesafe/jev-1.13-20260917")
            put("teacherAnswerJson", TEACHER_ANSWER_JSON)
            put("shadowCheckpoint", null as String?)
            put("shadowPrecision", null as String?)
            put("shadowAnswerJson", null as String?)
            put("agreementExactMatch", null as Boolean?)
            put("agreementKl", null as Double?)
            put("executedActionJson", EXECUTED_ACTION_JSON)
            put("gateEventJson", null as String?)
            put("outcome", "exited")
            put("compressedBy", null as String?)
            put("shadowSampled", 0)
            put("escalated", 0)
            put("fallbackJson", null as String?)
            // NO exitJson column exists yet at v3.
        }
        v3.insert("decision_log", null, values)
        v3.version = 3
        v3.close()

        // Open through Room: MIGRATION_3_4 → MIGRATION_4_5 → MIGRATION_5_6 must run in
        // the chain, validate, and keep the history.
        val migrated = Room.databaseBuilder(context, DecisionLogDb::class.java, name)
            .addMigrations(
                DecisionLogDb.MIGRATION_1_2,
                DecisionLogDb.MIGRATION_2_3,
                DecisionLogDb.MIGRATION_3_4,
                DecisionLogDb.MIGRATION_4_5,
                DecisionLogDb.MIGRATION_5_6,
                DecisionLogDb.MIGRATION_6_7,
                DecisionLogDb.MIGRATION_7_8,
                DecisionLogDb.MIGRATION_8_9,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val decisionLogger = DecisionLogger(migrated)
            val exitLogger = ExitCheckLogger(migrated)
            // history survives the whole chain
            val legacyRow = decisionLogger.byTask("task-exit").single()
            assertEquals("legacy-row", legacyRow.rowId)
            assertNull("a pre-v4 row decodes with no exit provenance", legacyRow.exit)

            // the new table is live on the migrated database: an exit-check row inserts
            val check = row(rowId = "post-migration-exit-check", stepIndex = 9)
            exitLogger.insert(check)
            assertEquals(check, exitLogger.byTask("task-exit").single())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    // -- minimal valid JSON payloads for the hand-built v3 row (the :app tests cannot
    //    see :core's LogRowFixtures) --

    private val PRIMARY_QUESTION_JSON =
        """{"name":"next-action","options":[""" +
            """{"id":"open-first-result","label":"Open first result","description":"Tap the top hit"},""" +
            """{"id":"finish","label":"Finish","description":"bucket=finish (0,0)"}]}"""

    private val SHADOW_QUESTION_JSON =
        """{"name":"next-action-bucketed","buckets":{""" +
            """"nav-links":[{"id":"open-first-result","label":"Open first result","description":"Tap the top hit"}],""" +
            """"finish":[{"id":"finish","label":"Finish","description":"bucket=finish (0,0)"}]}}"""

    private val TEACHER_ANSWER_JSON =
        """{"chosen":"finish","probabilities":{"open-first-result":0.10,"finish":0.90},"confidence":0.90}"""

    private val EXECUTED_ACTION_JSON =
        """{"candidateId":null,"kind":"finish","disposition":"executed"}"""
}
