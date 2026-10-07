package navigator.app.log

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import navigator.core.log.CompressedBy
import navigator.core.log.DecisionLogRow
import navigator.core.log.DECISION_LOG_JSON
import navigator.core.log.EngineIdentity
import navigator.core.log.ExecutedAction
import navigator.core.log.ExitProvenance
import navigator.core.log.FallbackInfo
import navigator.core.log.LoggedGateEvent
import navigator.core.log.LoggedPlan
import navigator.core.log.Outcome
import navigator.core.log.ShadowAnswer
import navigator.core.log.ShadowPrecision
import navigator.core.log.ShadowRef
import navigator.core.log.TeacherAnswer
import navigator.core.log.TriageClass
import navigator.core.log.BucketedQuestion
import navigator.core.log.enumFromSerialName
import navigator.core.log.serialNameOf
import navigator.core.decision.ChoiceQuestion

/**
 * Room persistence for the DecisionLog (vision 05 §2: "Storage: Room (:app), one row per
 * decision, exported as JSONL. Local-only (ART-8)"). Retention is indefinite — every row
 * is potential training data, no auto-pruning (vision 05 §5).
 *
 * INSERT-ONLY LAW: rows are immutable history. The DAO exposes no update and no delete —
 * a duplicate rowId fails loudly ([OnConflictStrategy.ABORT]); the only way to remove
 * history would be a vision-level event. Nested objects are stored as JSON columns of
 * the canonical [DECISION_LOG_JSON] shape, so a corrupt column fails loudly on decode.
 */

/** One decision, one row. Nested log objects are kept as JSON columns. */
@Entity(
    tableName = "decision_log",
    indices = [Index("taskId"), Index("timestamp")],
)
data class DecisionLogEntity(
    @PrimaryKey val rowId: String,
    val taskId: String,
    val goal: String,
    val stepIndex: Int,
    val timestamp: Long,
    val stateText: String,
    val stateFormatVersion: Int,
    @ColumnInfo(name = "triageClass") val triageClass: String,
    val questionPrimaryJson: String,
    val questionShadowJson: String,
    val teacherConfigId: String,
    val teacherModel: String,
    val teacherAnswerJson: String,
    val shadowCheckpoint: String?,
    val shadowPrecision: String?,
    val shadowAnswerJson: String?,
    val agreementExactMatch: Boolean?,
    val agreementKl: Double?,
    val executedActionJson: String,
    val gateEventJson: String?,
    val outcome: String,
    val compressedBy: String?,
    val shadowSampled: Boolean,
    /** Phase 8C (doc 05 v1.2.0): escalation + the fallback's answer. Pre-v3 rows decode
     *  as escalated=false / fallback=null through the migration's column defaults. */
    @ColumnInfo(defaultValue = "0") val escalated: Boolean = false,
    val fallbackJson: String? = null,
    /** Phase 8J (doc 05 v1.4.0): exit provenance on the terminal row. Pre-v4 rows decode
     *  as exit=null through the migration's nullable column (additive only). */
    val exitJson: String? = null,
    /** Phase 08F (doc 05 §2 `typedSecret`, doc 06 §2a): the step typed a card-supplied
     *  credential — the VALUE is never stored anywhere; logs and rows render
     *  `[credential-redacted]`. Pre-v6 rows decode as typedSecret=false through the
     *  migration's column default. */
    @ColumnInfo(defaultValue = "0") val typedSecret: Boolean = false,
    /** Phase 08G (vision 05 §2 `plan`, vision 04 §1): plan-proposal provenance. Pre-v7
     *  rows decode as plan=null through the migration's nullable column (additive only). */
    val planJson: String? = null,
)

/** Bidirectional mapping row ↔ entity. Decoding validates loudly (core log law). */
object DecisionLogConverters {

    fun toEntity(row: DecisionLogRow): DecisionLogEntity = DecisionLogEntity(
        rowId = row.rowId,
        taskId = row.taskId,
        goal = row.goal,
        stepIndex = row.stepIndex,
        timestamp = row.timestamp,
        stateText = row.stateText,
        stateFormatVersion = row.stateFormatVersion,
        triageClass = serialNameOf(TriageClass.serializer(), row.triageClass),
        questionPrimaryJson = DECISION_LOG_JSON.encodeToString(
            ChoiceQuestion.serializer(),
            row.questionPrimary,
        ),
        questionShadowJson = DECISION_LOG_JSON.encodeToString(
            BucketedQuestion.serializer(),
            row.questionShadow,
        ),
        teacherConfigId = row.teacher.configId,
        teacherModel = row.teacher.model,
        teacherAnswerJson = DECISION_LOG_JSON.encodeToString(TeacherAnswer.serializer(), row.teacherAnswer),
        shadowCheckpoint = row.shadow?.checkpoint,
        shadowPrecision = row.shadow?.let { serialNameOf(ShadowPrecision.serializer(), it.precision) },
        shadowAnswerJson = row.shadowAnswer?.let { DECISION_LOG_JSON.encodeToString(ShadowAnswer.serializer(), it) },
        agreementExactMatch = row.agreementExactMatch,
        agreementKl = row.agreementKL,
        executedActionJson = DECISION_LOG_JSON.encodeToString(ExecutedAction.serializer(), row.executedAction),
        gateEventJson = row.gateEvent?.let { DECISION_LOG_JSON.encodeToString(LoggedGateEvent.serializer(), it) },
        outcome = serialNameOf(Outcome.serializer(), row.outcome),
        compressedBy = row.compressedBy?.let { serialNameOf(CompressedBy.serializer(), it) },
        shadowSampled = row.shadowSampled,
        escalated = row.escalated,
        fallbackJson = row.fallback?.let { DECISION_LOG_JSON.encodeToString(FallbackInfo.serializer(), it) },
        exitJson = row.exit?.let { DECISION_LOG_JSON.encodeToString(ExitProvenance.serializer(), it) },
        typedSecret = row.typedSecret,
        planJson = row.plan?.let { DECISION_LOG_JSON.encodeToString(LoggedPlan.serializer(), it) },
    )

    fun toRow(entity: DecisionLogEntity): DecisionLogRow = DecisionLogRow(
        rowId = entity.rowId,
        taskId = entity.taskId,
        goal = entity.goal,
        stepIndex = entity.stepIndex,
        timestamp = entity.timestamp,
        stateText = entity.stateText,
        stateFormatVersion = entity.stateFormatVersion,
        triageClass = enumFromSerialName(TriageClass.serializer(), entity.triageClass),
        questionPrimary = DECISION_LOG_JSON.decodeFromString(
            ChoiceQuestion.serializer(),
            entity.questionPrimaryJson,
        ),
        questionShadow = DECISION_LOG_JSON.decodeFromString(
            BucketedQuestion.serializer(),
            entity.questionShadowJson,
        ),
        teacher = EngineIdentity(entity.teacherConfigId, entity.teacherModel),
        teacherAnswer = DECISION_LOG_JSON.decodeFromString(TeacherAnswer.serializer(), entity.teacherAnswerJson),
        shadow = entity.shadowCheckpoint?.let { checkpoint ->
            ShadowRef(
                checkpoint = checkpoint,
                precision = enumFromSerialName(
                    ShadowPrecision.serializer(),
                    requireNotNull(entity.shadowPrecision) { "shadow checkpoint without precision" },
                ),
            )
        },
        shadowAnswer = entity.shadowAnswerJson?.let {
            DECISION_LOG_JSON.decodeFromString(ShadowAnswer.serializer(), it)
        },
        agreementExactMatch = entity.agreementExactMatch,
        agreementKL = entity.agreementKl,
        executedAction = DECISION_LOG_JSON.decodeFromString(ExecutedAction.serializer(), entity.executedActionJson),
        gateEvent = entity.gateEventJson?.let {
            DECISION_LOG_JSON.decodeFromString(LoggedGateEvent.serializer(), it)
        },
        outcome = enumFromSerialName(Outcome.serializer(), entity.outcome),
        compressedBy = entity.compressedBy?.let { enumFromSerialName(CompressedBy.serializer(), it) },
        shadowSampled = entity.shadowSampled,
        escalated = entity.escalated,
        fallback = entity.fallbackJson?.let {
            DECISION_LOG_JSON.decodeFromString(FallbackInfo.serializer(), it)
        },
        exit = entity.exitJson?.let {
            DECISION_LOG_JSON.decodeFromString(ExitProvenance.serializer(), it)
        },
        typedSecret = entity.typedSecret,
        plan = entity.planJson?.let {
            DECISION_LOG_JSON.decodeFromString(LoggedPlan.serializer(), it)
        },
    )
}

/** Agreement aggregation over the rows where both answers exist (vision 05 §3). */
data class AgreementAggregateRow(
    val rowsConsidered: Int,
    /** Fraction of considered rows where the shadow's top-1 matched the teacher; null over an empty window. */
    val exactMatchFraction: Double?,
)

/**
 * The insert-only DAO (vision 05 §2/§5). Deliberately NO @Update, NO @Delete, and no
 * DELETE/UPDATE @Query — rows are immutable history; the compiler-enforced surface is
 * the contract.
 */
@Dao
interface DecisionLogDao {

    /** Insert-once: a duplicate [DecisionLogEntity.rowId] fails loudly (ABORT). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: DecisionLogEntity)

    /** Batch insert-once, same law. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<DecisionLogEntity>)

    /** All rows of one task in step order. */
    @Query("SELECT * FROM decision_log WHERE taskId = :taskId ORDER BY stepIndex ASC, timestamp ASC")
    suspend fun byTask(taskId: String): List<DecisionLogEntity>

    /** All rows in a wall-clock window, in time order. */
    @Query(
        "SELECT * FROM decision_log WHERE timestamp >= :fromInclusive AND timestamp <= :toInclusive " +
            "ORDER BY timestamp ASC",
    )
    suspend fun byTimeWindow(fromInclusive: Long, toInclusive: Long): List<DecisionLogEntity>

    /** Rows where both answers exist (the model guarantees agreement fields there too). */
    @Query(
        "SELECT * FROM decision_log WHERE shadowAnswerJson IS NOT NULL AND agreementExactMatch IS NOT NULL " +
            "ORDER BY timestamp ASC",
    )
    suspend fun rowsWithBothAnswers(): List<DecisionLogEntity>

    /** The UI agreement metric (vision 05 §3): top-1 match fraction over both-answer rows. */
    @Query(
        "SELECT COUNT(*) AS rowsConsidered, " +
            "AVG(CASE WHEN agreementExactMatch = 1 THEN 1.0 ELSE 0.0 END) AS exactMatchFraction " +
            "FROM decision_log WHERE agreementExactMatch IS NOT NULL",
    )
    suspend fun agreementAggregate(): AgreementAggregateRow
}

@Database(
    entities = [DecisionLogEntity::class, ShadowResultEntity::class, ExitCheckEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class DecisionLogDb : RoomDatabase() {

    abstract fun decisionLogDao(): DecisionLogDao

    abstract fun shadowResultDao(): ShadowResultDao

    abstract fun exitCheckDao(): ExitCheckDao

    companion object {

        /**
         * v1 → v2 (vision 05 §2 amendment 2026-10-04): adds the companion `shadow_result`
         * table beside the untouched `decision_log` table — existing databases keep every
         * historical row (insert-only law; no destructive fallback anywhere). The DDL is
         * exactly what Room expects for [ShadowResultEntity], so the post-migration
         * schema validation passes.
         */
        val MIGRATION_1_2: androidx.room.migration.Migration = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shadow_result` (" +
                        "`rowId` TEXT NOT NULL, " +
                        "`taskId` TEXT NOT NULL, " +
                        "`stepIndex` INTEGER NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`shadowCheckpoint` TEXT NOT NULL, " +
                        "`shadowPrecision` TEXT NOT NULL, " +
                        "`shadowAnswerJson` TEXT NOT NULL, " +
                        "`latencyMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`rowId`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_shadow_result_taskId_stepIndex` " +
                        "ON `shadow_result` (`taskId`, `stepIndex`)",
                )
            }
        }

        /**
         * v2 → v3 (vision 05 v1.2.0, Phase 8C): adds the `escalated` flag (NOT NULL,
         * default 0) and the `fallbackJson` column (nullable) to `decision_log` — the
         * fallback-composition fields (doc 05 v1.2.0, vision 02 §7). ALTER ADD COLUMN
         * only: every historical row survives, decoding as escalated=false /
         * fallback=null (the single-engine shape), never rewritten (insert-only law).
         * The declared defaults match the entity's @ColumnInfo defaultValue so Room's
         * post-migration schema validation passes.
         */
        val MIGRATION_2_3: androidx.room.migration.Migration = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `decision_log` ADD COLUMN `escalated` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `decision_log` ADD COLUMN `fallbackJson` TEXT")
            }
        }

        /**
         * v3 → v4 (vision 05 v1.4.0, Phase 8J): adds the `exitJson` column (nullable) —
         * the exit provenance on the terminal row (doc 04 §3 v1.2.0: path, guard level,
         * noul). ALTER ADD COLUMN only: every historical row survives, decoding as
         * exit=null, never rewritten (insert-only law).
         */
        val MIGRATION_3_4: androidx.room.migration.Migration = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `decision_log` ADD COLUMN `exitJson` TEXT")
            }
        }

        /**
         * v4 → v5 (vision 05 v1.5.0, Phase 8J Round 2): adds the `exit_check` table
         * beside the untouched `decision_log` + `shadow_result` tables — the third
         * insert-only row type (every finish check, accepted or refused; amendment
         * rule a). CREATE TABLE only: every historical row of both existing tables
         * survives untouched (insert-only law; no destructive fallback anywhere). The
         * DDL is exactly what Room expects for [ExitCheckEntity], so the
         * post-migration schema validation passes.
         */
        val MIGRATION_4_5: androidx.room.migration.Migration = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exit_check` (" +
                        "`rowId` TEXT NOT NULL, " +
                        "`taskId` TEXT NOT NULL, " +
                        "`stepIndex` INTEGER NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`stateText` TEXT NOT NULL, " +
                        "`stateFormatVersion` INTEGER NOT NULL, " +
                        "`questionJson` TEXT NOT NULL, " +
                        "`anchoredNoulVersion` INTEGER NOT NULL, " +
                        "`teacherConfigId` TEXT NOT NULL, " +
                        "`teacherModel` TEXT NOT NULL, " +
                        "`answerPTrue` REAL NOT NULL, " +
                        "`answerBand` TEXT NOT NULL, " +
                        "`escalated` INTEGER NOT NULL, " +
                        "`fallbackPTrue` REAL, " +
                        "`guardLevel` TEXT NOT NULL, " +
                        "`verdict` TEXT NOT NULL, " +
                        "`acceptedPath` TEXT, " +
                        "PRIMARY KEY(`rowId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exit_check_taskId` ON `exit_check` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exit_check_timestamp` ON `exit_check` (`timestamp`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_exit_check_taskId_stepIndex` " +
                        "ON `exit_check` (`taskId`, `stepIndex`)",
                )
            }
        }

        /**
         * v5 → v6 (doc 05 §2 `typedSecret`, Phase 08F / doc 06 §2a): adds the
         * `typedSecret` flag (NOT NULL, default 0) to `decision_log` — the step typed a
         * card-supplied credential (the value itself is never stored). ALTER ADD COLUMN
         * only: every historical row survives, decoding as typedSecret=false, never
         * rewritten (insert-only law). The declared default matches the entity's
         * @ColumnInfo defaultValue so Room's post-migration schema validation passes.
         */
        val MIGRATION_5_6: androidx.room.migration.Migration = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `decision_log` ADD COLUMN `typedSecret` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v6 → v7 (vision 05 §2 `plan`, Phase 08G): adds the `planJson` column (nullable)
         * — the plan-proposal provenance (vision 04 §1: writer-LLM plans carry their
         * PROMPT_VERSION). ALTER ADD COLUMN only: every historical row survives,
         * decoding as plan=null, never rewritten (insert-only law).
         */
        val MIGRATION_6_7: androidx.room.migration.Migration = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `decision_log` ADD COLUMN `planJson` TEXT")
            }
        }

        /** Production: a named, on-disk database (indefinite local retention, vision 05 §5). */
        fun open(context: Context, name: String): DecisionLogDb =
            Room.databaseBuilder(context, DecisionLogDb::class.java, name)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                .build()

        /** In-memory variant (tests / ephemeral windows). */
        fun inMemory(context: Context): DecisionLogDb =
            Room.inMemoryDatabaseBuilder(context, DecisionLogDb::class.java)
                .allowMainThreadQueries()
                .build()
    }
}

/**
 * The Phase-6-facing API (phase doc §6): `DecisionLogger.insert(row)` exactly once per
 * decision; queries for replay, export windows, and the agreement figures. All access
 * converts through the validated [DecisionLogRow] so corrupt columns fail loudly.
 */
class DecisionLogger(private val db: DecisionLogDb) {

    private val dao = db.decisionLogDao()

    /** Writes one immutable row; a repeated rowId is a programming error and throws. */
    suspend fun insert(row: DecisionLogRow) = dao.insert(DecisionLogConverters.toEntity(row))

    /** Writes a batch of rows (export/repair paths); same insert-once law. */
    suspend fun insertAll(rows: List<DecisionLogRow>) =
        dao.insertAll(rows.map(DecisionLogConverters::toEntity))

    suspend fun byTask(taskId: String): List<DecisionLogRow> =
        dao.byTask(taskId).map(DecisionLogConverters::toRow)

    suspend fun byTimeWindow(fromInclusive: Long, toInclusive: Long): List<DecisionLogRow> =
        dao.byTimeWindow(fromInclusive, toInclusive).map(DecisionLogConverters::toRow)

    suspend fun rowsWithBothAnswers(): List<DecisionLogRow> =
        dao.rowsWithBothAnswers().map(DecisionLogConverters::toRow)

    suspend fun agreementAggregate(): AgreementAggregateRow = dao.agreementAggregate()
}
