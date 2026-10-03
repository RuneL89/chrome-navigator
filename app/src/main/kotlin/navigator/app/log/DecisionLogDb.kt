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
import navigator.core.log.LoggedGateEvent
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

@Database(entities = [DecisionLogEntity::class], version = 1, exportSchema = false)
abstract class DecisionLogDb : RoomDatabase() {

    abstract fun decisionLogDao(): DecisionLogDao

    companion object {
        /** Production: a named, on-disk database (indefinite local retention, vision 05 §5). */
        fun open(context: Context, name: String): DecisionLogDb =
            Room.databaseBuilder(context, DecisionLogDb::class.java, name).build()

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
