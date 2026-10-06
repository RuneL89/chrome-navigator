package navigator.app.log

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import navigator.core.log.DECISION_LOG_JSON
import navigator.core.log.EngineIdentity
import navigator.core.log.ExitCheckAnswer
import navigator.core.log.ExitCheckFallbackAnswer
import navigator.core.log.ExitCheckGuardLevel
import navigator.core.log.ExitCheckRow
import navigator.core.log.ExitCheckVerdict
import navigator.core.log.LoggedExitPath
import navigator.core.log.enumFromSerialName
import navigator.core.log.serialNameOf
import navigator.core.decision.NoulQuestion

/**
 * Room persistence for the exit-check rows (vision 05 §2 amendment 2026-10-06 — the
 * exit-calibration training path): the immutable record of every finish check,
 * **accepted or refused**.
 *
 * INSERT-ONLY LAW (amendment rule a): no @Update, no @Delete, no DELETE/UPDATE query
 * may ever exist — a duplicate rowId OR a duplicate `(taskId, stepIndex)` key fails
 * loudly via [OnConflictStrategy.ABORT] (one finish check consumes one step, so the
 * key IS the check's identity; exactly one exit-check row per check).
 */

/** One finish check; the question payload rides as a JSON column (loud decode). */
@Entity(
    tableName = "exit_check",
    indices = [
        Index("taskId"),
        Index("timestamp"),
        Index(value = ["taskId", "stepIndex"], unique = true),
    ],
)
data class ExitCheckEntity(
    @PrimaryKey val rowId: String,
    val taskId: String,
    val stepIndex: Int,
    val timestamp: Long,
    val stateText: String,
    val stateFormatVersion: Int,
    val questionJson: String,
    val anchoredNoulVersion: Int,
    val teacherConfigId: String,
    val teacherModel: String,
    val answerPTrue: Double,
    val answerBand: String,
    val escalated: Boolean,
    val fallbackPTrue: Double?,
    val guardLevel: String,
    val verdict: String,
    val acceptedPath: String?,
)

/** Bidirectional mapping exit-check row ↔ entity. Decoding validates loudly (core law). */
object ExitCheckConverters {

    fun toEntity(row: ExitCheckRow): ExitCheckEntity = ExitCheckEntity(
        rowId = row.rowId,
        taskId = row.taskId,
        stepIndex = row.stepIndex,
        timestamp = row.timestamp,
        stateText = row.stateText,
        stateFormatVersion = row.stateFormatVersion,
        questionJson = DECISION_LOG_JSON.encodeToString(NoulQuestion.serializer(), row.question),
        anchoredNoulVersion = row.anchoredNoulVersion,
        teacherConfigId = row.teacher.configId,
        teacherModel = row.teacher.model,
        answerPTrue = row.answer.pTrue,
        answerBand = serialNameOf(navigator.core.log.LoggedBand.serializer(), row.answer.band),
        escalated = row.escalated,
        fallbackPTrue = row.fallbackAnswer?.pTrue,
        guardLevel = serialNameOf(ExitCheckGuardLevel.serializer(), row.guardLevel),
        verdict = serialNameOf(ExitCheckVerdict.serializer(), row.verdict),
        acceptedPath = row.acceptedPath?.let { serialNameOf(LoggedExitPath.serializer(), it) },
    )

    fun toRow(entity: ExitCheckEntity): ExitCheckRow = ExitCheckRow(
        rowId = entity.rowId,
        taskId = entity.taskId,
        stepIndex = entity.stepIndex,
        timestamp = entity.timestamp,
        stateText = entity.stateText,
        stateFormatVersion = entity.stateFormatVersion,
        question = DECISION_LOG_JSON.decodeFromString(NoulQuestion.serializer(), entity.questionJson),
        anchoredNoulVersion = entity.anchoredNoulVersion,
        teacher = EngineIdentity(entity.teacherConfigId, entity.teacherModel),
        answer = ExitCheckAnswer(
            pTrue = entity.answerPTrue,
            band = enumFromSerialName(navigator.core.log.LoggedBand.serializer(), entity.answerBand),
        ),
        escalated = entity.escalated,
        fallbackAnswer = entity.fallbackPTrue?.let { ExitCheckFallbackAnswer(it) },
        guardLevel = enumFromSerialName(ExitCheckGuardLevel.serializer(), entity.guardLevel),
        verdict = enumFromSerialName(ExitCheckVerdict.serializer(), entity.verdict),
        acceptedPath = entity.acceptedPath?.let { enumFromSerialName(LoggedExitPath.serializer(), it) },
    )
}

/**
 * The insert-only exit-check DAO (vision 05 §2 amendment, rules a–b). Deliberately NO
 * @Update, NO @Delete, and no DELETE/UPDATE @Query — the compiler-enforced surface is
 * the contract.
 */
@Dao
interface ExitCheckDao {

    /** Insert-once: a duplicate [ExitCheckEntity.rowId] or `(taskId, stepIndex)` fails loudly. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ExitCheckEntity)

    /** All exit-check rows of one task in step order. */
    @Query("SELECT * FROM exit_check WHERE taskId = :taskId ORDER BY stepIndex ASC, timestamp ASC")
    suspend fun byTask(taskId: String): List<ExitCheckEntity>

    /** All exit-check rows in time order (the export window, vision 05 §4). */
    @Query(
        "SELECT * FROM exit_check WHERE timestamp >= :fromInclusive AND timestamp <= :toInclusive " +
            "ORDER BY timestamp ASC",
    )
    suspend fun byTimeWindow(fromInclusive: Long, toInclusive: Long): List<ExitCheckEntity>
}

/**
 * The exit-check-row API (Phase 8J Round 2): insert-only writes and the query surface
 * the RLCD noul-kind export reads (vision 05 §4). All access converts through the
 * validated `:core` [ExitCheckRow] so corrupt columns fail loudly on decode.
 */
class ExitCheckLogger(private val db: DecisionLogDb) {

    private val dao = db.exitCheckDao()

    /** Writes one immutable exit-check row; a repeated key is a programming error and throws. */
    suspend fun insert(row: ExitCheckRow) = dao.insert(ExitCheckConverters.toEntity(row))

    suspend fun byTask(taskId: String): List<ExitCheckRow> =
        dao.byTask(taskId).map(ExitCheckConverters::toRow)

    suspend fun byTimeWindow(fromInclusive: Long, toInclusive: Long): List<ExitCheckRow> =
        dao.byTimeWindow(fromInclusive, toInclusive).map(ExitCheckConverters::toRow)
}
