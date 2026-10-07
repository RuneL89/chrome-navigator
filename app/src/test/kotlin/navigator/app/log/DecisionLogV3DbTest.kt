package navigator.app.log

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.NoulResult
import navigator.core.decision.Option
import navigator.core.enumerate.Bucket
import navigator.core.log.ActionDisposition
import navigator.core.log.BucketedQuestion
import navigator.core.log.EngineIdentity
import navigator.core.log.ExecutedAction
import navigator.core.log.FallbackInfo
import navigator.core.log.LoggedActionKind
import navigator.core.log.Outcome
import navigator.core.log.TeacherAnswer
import navigator.core.log.TriageClass
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Gate 8C.4 (Room half), PHASE_08C §3 — the schema v3 escalation fields (vision 05
 * v1.2.0, vision 02 §7): round-trip of escalated/fallback variants (choice + noul
 * fallback answers); the incoherent shapes fail loudly; and the Room migration v2→v3
 * keeping every historical row (a hand-built v2 database) — old rows decode as
 * escalated=false / fallback=null, new-style rows insert on the migrated database.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DecisionLogV3DbTest {

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

    // ---- fixtures (mirrors the :core gate fixtures; :app tests cannot see them) ----

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
            Bucket.NAV_LINKS to listOf(
                Option("open-first-result", "Open first result", "Tap the top hit"),
                Option("open-second-result", "Open second result", "Tap the runner-up hit"),
            ),
            Bucket.BUTTONS to listOf(Option("scroll-results", "Scroll results", "Reveal more hits")),
        ),
    )

    /** The PRIMARY's answer (low confidence — that is WHY the fallback was consulted). */
    private val primaryAnswer = TeacherAnswer(
        chosen = "type-query",
        probabilities = mapOf("type-query" to 0.60, "open-first-result" to 0.30, "scroll-results" to 0.10),
        confidence = 0.60,
    )

    /** The fallback's answer — the one EXECUTED when escalated (vision 02 §7). */
    private val fallbackChoice = ChoiceResult(
        chosenId = "open-first-result",
        probabilities = mapOf("type-query" to 0.05, "open-first-result" to 0.90, "scroll-results" to 0.05),
        confidence = 0.90,
    )

    private fun row(
        rowId: String = "row-fb-1",
        escalated: Boolean = false,
        fallback: FallbackInfo? = null,
        teacherAnswer: TeacherAnswer = primaryAnswer,
    ) = navigator.core.log.DecisionLogRow(
        rowId = rowId,
        taskId = "task-fallback",
        goal = "find an apple pie recipe",
        stepIndex = 2,
        timestamp = 1_760_300_000_000,
        stateText = "GOAL find an apple pie recipe\nURL https://www.google.com\nLINKS [1] \"hit\" (540,320)",
        stateFormatVersion = 1,
        triageClass = TriageClass.RESEARCH_MULTI_STEP,
        questionPrimary = primaryQuestion,
        questionShadow = shadowQuestion,
        teacher = EngineIdentity(configId = "laya", model = "ichenney/laya-browser-v32b@161d54d6"),
        teacherAnswer = teacherAnswer,
        executedAction = ExecutedAction(
            candidateId = "n12",
            kind = LoggedActionKind.TAP,
            disposition = ActionDisposition.EXECUTED,
        ),
        outcome = Outcome.PROGRESSED,
        shadowSampled = false,
        escalated = escalated,
        fallback = fallback,
    )

    // ---- escalated round-trips (vision 05 v1.2.0) -------------------------------------

    @Test
    fun `escalated row with a choice fallback answer round-trips`(): Unit = runBlocking {
        val escalated = row(
            escalated = true,
            fallback = FallbackInfo("jev", "typesafe/jev-1.13-20260917", fallbackChoice),
        )
        logger.insert(escalated)
        val decoded = logger.byTask("task-fallback").single()
        assertEquals(escalated, decoded)
        assertTrue(decoded.escalated)
        val fb = requireNotNull(decoded.fallback) { "fallback info must survive the round-trip" }
        assertEquals("jev", fb.engine)
        assertEquals("typesafe/jev-1.13-20260917", fb.model)
        val answer = fb.answer as ChoiceResult
        assertEquals("open-first-result", answer.chosenId)
        assertEquals(0.90, answer.confidence, 1e-12)
        // the teacher answer stays the PRIMARY's own answer (doc 05 v1.2.0)
        assertEquals("type-query", decoded.teacherAnswer.chosen)
    }

    @Test
    fun `escalated row with a noul fallback answer round-trips`(): Unit = runBlocking {
        val escalated = row(
            rowId = "row-fb-noul",
            escalated = true,
            fallback = FallbackInfo("jev", "typesafe/jev-1.13-20260917", NoulResult(pTrue = 0.95)),
        )
        logger.insert(escalated)
        val decoded = logger.byTask("task-fallback").first { it.rowId == "row-fb-noul" }
        assertEquals(escalated, decoded)
        assertTrue(decoded.fallback!!.answer is NoulResult)
    }

    @Test
    fun `non-escalated rows carry escalated=false and null fallback`(): Unit = runBlocking {
        val plain = row(rowId = "row-plain")
        logger.insert(plain)
        val decoded = logger.byTask("task-fallback").single()
        assertFalse(decoded.escalated)
        assertNull(decoded.fallback)
        assertEquals(plain, decoded)
    }

    // ---- incoherent shapes fail loudly (validation in init) ---------------------------

    @Test
    fun `a fallback answer without escalated=true is incoherent`() {
        assertThrows(IllegalArgumentException::class.java) {
            row(fallback = FallbackInfo("jev", "typesafe/jev-1.13-20260917", fallbackChoice))
        }
    }

    @Test
    fun `escalated=true without a fallback answer is incoherent`() {
        assertThrows(IllegalArgumentException::class.java) { row(escalated = true) }
    }

    @Test
    fun `a fallback choice outside the primary option space is rejected`() {
        val foreign = ChoiceResult(
            chosenId = "not-an-option",
            probabilities = mapOf("not-an-option" to 1.0),
            confidence = 1.0,
        )
        assertThrows(IllegalArgumentException::class.java) {
            row(escalated = true, fallback = FallbackInfo("jev", "typesafe/jev-1.13-20260917", foreign))
        }
    }

    // ---- the Room migration v2 → v3 (existing DBs survive) -----------------------------

    @Test
    fun `v2 database survives the migration and gains the escalation columns`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "decision-log-v3-migration-test.db"
        context.deleteDatabase(name)

        // A faithful v2 database: the Room v2 DDL for decision_log (NO escalation
        // columns) + shadow_result + one historical row + user_version 2.
        val v2 = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        v2.execSQL(
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
        v2.execSQL("CREATE INDEX IF NOT EXISTS `index_decision_log_taskId` ON `decision_log` (`taskId`)")
        v2.execSQL("CREATE INDEX IF NOT EXISTS `index_decision_log_timestamp` ON `decision_log` (`timestamp`)")
        v2.execSQL(
            "CREATE TABLE IF NOT EXISTS `shadow_result` (" +
                "`rowId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `stepIndex` INTEGER NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, `shadowCheckpoint` TEXT NOT NULL, " +
                "`shadowPrecision` TEXT NOT NULL, `shadowAnswerJson` TEXT NOT NULL, " +
                "`latencyMs` INTEGER NOT NULL, PRIMARY KEY(`rowId`))",
        )
        v2.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_shadow_result_taskId_stepIndex` " +
                "ON `shadow_result` (`taskId`, `stepIndex`)",
        )
        val legacy = row(rowId = "legacy-row", escalated = false, fallback = null)
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
            // NO escalated / fallbackJson columns exist yet at v2.
        }
        v2.insert("decision_log", null, values)
        v2.version = 2
        v2.close()

        // Open through Room: MIGRATION_2_3 must run, validate, and keep the history.
        val migrated = Room.databaseBuilder(context, DecisionLogDb::class.java, name)
            .addMigrations(DecisionLogDb.MIGRATION_1_2, DecisionLogDb.MIGRATION_2_3, DecisionLogDb.MIGRATION_3_4, DecisionLogDb.MIGRATION_4_5, DecisionLogDb.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            val migratedLogger = DecisionLogger(migrated)
            val decoded = migratedLogger.byTask("task-fallback").single()
            assertEquals("history survives the v2→v3 migration", legacy, decoded)
            assertFalse("a pre-v3 row decodes as NOT escalated", decoded.escalated)
            assertNull("a pre-v3 row decodes with no fallback", decoded.fallback)

            // the new columns are live on the migrated database: an escalated row inserts
            val escalated = row(
                rowId = "post-migration-escalated",
                escalated = true,
                fallback = FallbackInfo("jev", "typesafe/jev-1.13-20260917", fallbackChoice),
            )
            migratedLogger.insert(escalated)
            val redecoded = migratedLogger.byTask("task-fallback").first { it.rowId == escalated.rowId }
            assertEquals(escalated, redecoded)
            assertTrue(redecoded.escalated)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
