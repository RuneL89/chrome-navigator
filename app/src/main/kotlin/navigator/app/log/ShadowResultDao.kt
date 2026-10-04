package navigator.app.log

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import navigator.core.log.DECISION_LOG_JSON
import navigator.core.log.JoinedDecisionRow
import navigator.core.log.ShadowAnswer
import navigator.core.log.ShadowPrecision
import navigator.core.log.ShadowRef
import navigator.core.log.ShadowResultRow
import navigator.core.log.enumFromSerialName
import navigator.core.log.serialNameOf

/**
 * Room persistence for the companion shadow-result rows (vision 05 §2, amendment
 * 2026-10-04 — owner-ratified): the immutable record of a shadow answer that completed
 * after its decision row froze as a `shadowMiss`.
 *
 * INSERT-ONLY LAW (amendment rule b): no @Update, no @Delete, no DELETE/UPDATE query may
 * ever exist — a duplicate rowId OR a duplicate `(taskId, stepIndex)` key fails loudly
 * via [OnConflictStrategy.ABORT] (unique index; exactly one companion per key, rule a).
 *
 * PERSISTENCE-BOUNDARY GUARD (rule a, enforced — not convention): an insert is rejected
 * loudly, inside the same transaction, when the referenced decision row (1) already
 * carries a shadow answer, or (2) does not exist at all for `(taskId, stepIndex)`.
 *
 * THE JOIN (amendment rule d): decision rows LEFT JOIN companions by
 * `(taskId, stepIndex)` — the joined view the agreement aggregation runs over, with
 * coverage = joined/total-sampled reported alongside the percentage (see
 * [ShadowResultLogger.joinedAgreementByTask]).
 */

/** One companion shadow result; every column is required (a companion is always complete). */
@Entity(
    tableName = "shadow_result",
    indices = [Index(value = ["taskId", "stepIndex"], unique = true)],
)
data class ShadowResultEntity(
    @PrimaryKey val rowId: String,
    val taskId: String,
    val stepIndex: Int,
    val timestamp: Long,
    val shadowCheckpoint: String,
    val shadowPrecision: String,
    val shadowAnswerJson: String,
    val latencyMs: Long,
)

/** Bidirectional mapping companion row ↔ entity. Decoding validates loudly (core log law). */
object ShadowResultConverters {

    fun toEntity(row: ShadowResultRow): ShadowResultEntity = ShadowResultEntity(
        rowId = row.rowId,
        taskId = row.taskId,
        stepIndex = row.stepIndex,
        timestamp = row.timestamp,
        shadowCheckpoint = row.shadow.checkpoint,
        shadowPrecision = serialNameOf(ShadowPrecision.serializer(), row.shadow.precision),
        shadowAnswerJson = DECISION_LOG_JSON.encodeToString(ShadowAnswer.serializer(), row.shadowAnswer),
        latencyMs = row.latencyMs,
    )

    fun toRow(entity: ShadowResultEntity): ShadowResultRow = ShadowResultRow(
        rowId = entity.rowId,
        taskId = entity.taskId,
        stepIndex = entity.stepIndex,
        timestamp = entity.timestamp,
        shadow = ShadowRef(
            checkpoint = entity.shadowCheckpoint,
            precision = enumFromSerialName(ShadowPrecision.serializer(), entity.shadowPrecision),
        ),
        shadowAnswer = DECISION_LOG_JSON.decodeFromString(ShadowAnswer.serializer(), entity.shadowAnswerJson),
        latencyMs = entity.latencyMs,
    )
}

/**
 * One decision row LEFT JOINed with its (nullable) companion by `(taskId, stepIndex)`.
 * The companion side is present as a whole or not at all — partial presence is corrupt
 * data and fails loudly in [ShadowResultConverters.toJoined].
 */
data class DecisionShadowJoin(
    @Embedded val decision: DecisionLogEntity,
    val companionRowId: String?,
    val companionTimestamp: Long?,
    val companionShadowCheckpoint: String?,
    val companionShadowPrecision: String?,
    val companionShadowAnswerJson: String?,
    val companionLatencyMs: Long?,
)

private fun DecisionShadowJoin.toJoinedRow(): JoinedDecisionRow = JoinedDecisionRow(
    decision = DecisionLogConverters.toRow(decision),
    companion = when (companionRowId) {
        null -> null
        else -> ShadowResultRow(
            rowId = companionRowId,
            taskId = decision.taskId,
            stepIndex = decision.stepIndex,
            timestamp = requireNotNull(companionTimestamp) { "companion $companionRowId without timestamp" },
            shadow = ShadowRef(
                checkpoint = requireNotNull(companionShadowCheckpoint) { "companion $companionRowId without checkpoint" },
                precision = enumFromSerialName(
                    ShadowPrecision.serializer(),
                    requireNotNull(companionShadowPrecision) { "companion $companionRowId without precision" },
                ),
            ),
            shadowAnswer = DECISION_LOG_JSON.decodeFromString(
                ShadowAnswer.serializer(),
                requireNotNull(companionShadowAnswerJson) { "companion $companionRowId without an answer" },
            ),
            latencyMs = requireNotNull(companionLatencyMs) { "companion $companionRowId without latency" },
        )
    },
)

/**
 * The insert-only companion DAO (vision 05 §2 amendment, rules a–b). Deliberately NO
 * @Update, NO @Delete, and no DELETE/UPDATE @Query — the compiler-enforced surface is
 * the contract.
 */
@Dao
abstract class ShadowResultDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertInternal(entity: ShadowResultEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM decision_log WHERE taskId = :taskId AND stepIndex = :stepIndex)")
    protected abstract suspend fun decisionRowExists(taskId: String, stepIndex: Int): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM decision_log WHERE taskId = :taskId AND stepIndex = :stepIndex " +
            "AND shadowCheckpoint IS NOT NULL)",
    )
    protected abstract suspend fun decisionRowCarriesShadow(taskId: String, stepIndex: Int): Boolean

    /**
     * Guarded, exactly-once insert (amendment rule a at the persistence boundary): the
     * guard checks and the ABORT insert run in ONE transaction, so no interleaving can
     * sneak a companion past the law. A duplicate rowId or duplicate `(taskId,
     * stepIndex)` key surfaces as a loud SQLite constraint failure; an illegal
     * companion (answered row, orphan) as a loud [IllegalStateException].
     */
    @Transaction
    open suspend fun insertGuarded(entity: ShadowResultEntity) {
        if (decisionRowCarriesShadow(entity.taskId, entity.stepIndex)) {
            throw IllegalStateException(
                "companion shadow result rejected (amendment rule a): decision row " +
                    "(${entity.taskId}, step ${entity.stepIndex}) already carries a shadow answer",
            )
        }
        if (!decisionRowExists(entity.taskId, entity.stepIndex)) {
            throw IllegalStateException(
                "companion shadow result rejected: no decision row exists for " +
                    "(${entity.taskId}, step ${entity.stepIndex}) — orphan companions are forbidden",
            )
        }
        insertInternal(entity)
    }

    /** All companions of one task in step order. */
    @Query("SELECT * FROM shadow_result WHERE taskId = :taskId ORDER BY stepIndex ASC, timestamp ASC")
    abstract suspend fun byTask(taskId: String): List<ShadowResultEntity>

    /** The joined view for one task: decision rows LEFT JOIN companions by (taskId, stepIndex). */
    @Query(
        "SELECT d.*, s.rowId AS companionRowId, s.timestamp AS companionTimestamp, " +
            "s.shadowCheckpoint AS companionShadowCheckpoint, s.shadowPrecision AS companionShadowPrecision, " +
            "s.shadowAnswerJson AS companionShadowAnswerJson, s.latencyMs AS companionLatencyMs " +
            "FROM decision_log d LEFT JOIN shadow_result s ON s.taskId = d.taskId AND s.stepIndex = d.stepIndex " +
            "WHERE d.taskId = :taskId ORDER BY d.stepIndex ASC, d.timestamp ASC",
    )
    abstract suspend fun joinedByTask(taskId: String): List<DecisionShadowJoin>

    /** The joined view over a wall-clock window (decision-row time decides membership). */
    @Query(
        "SELECT d.*, s.rowId AS companionRowId, s.timestamp AS companionTimestamp, " +
            "s.shadowCheckpoint AS companionShadowCheckpoint, s.shadowPrecision AS companionShadowPrecision, " +
            "s.shadowAnswerJson AS companionShadowAnswerJson, s.latencyMs AS companionLatencyMs " +
            "FROM decision_log d LEFT JOIN shadow_result s ON s.taskId = d.taskId AND s.stepIndex = d.stepIndex " +
            "WHERE d.timestamp >= :fromInclusive AND d.timestamp <= :toInclusive " +
            "ORDER BY d.timestamp ASC",
    )
    abstract suspend fun joinedByTimeWindow(fromInclusive: Long, toInclusive: Long): List<DecisionShadowJoin>

    /** Informational count for the RLCD export manifest (amendment rule e). */
    @Query("SELECT COUNT(*) FROM shadow_result")
    abstract suspend fun count(): Int
}

/**
 * The companion-row API (phase 8B §2.2): guarded exactly-once inserts, the joined view,
 * and the joined-view agreement aggregation with coverage. All access converts through
 * the validated `:core` [ShadowResultRow] so corrupt columns fail loudly on decode.
 */
class ShadowResultLogger(private val db: DecisionLogDb) {

    private val dao = db.shadowResultDao()

    /**
     * Writes one immutable companion — guarded per amendment rule (a); a repeated key
     * or an illegal companion is a programming error and throws.
     */
    suspend fun insert(row: ShadowResultRow) = dao.insertGuarded(ShadowResultConverters.toEntity(row))

    suspend fun byTask(taskId: String): List<ShadowResultRow> =
        dao.byTask(taskId).map(ShadowResultConverters::toRow)

    /** The joined view for one task (decision rows LEFT JOIN companions). */
    suspend fun joinedByTask(taskId: String): List<JoinedDecisionRow> =
        dao.joinedByTask(taskId).map { it.toJoinedRow() }

    /** The joined view over a wall-clock window (decision-row time decides membership). */
    suspend fun joinedByTimeWindow(fromInclusive: Long, toInclusive: Long): List<JoinedDecisionRow> =
        dao.joinedByTimeWindow(fromInclusive, toInclusive).map { it.toJoinedRow() }

    /**
     * The agreement metrics over one task's joined view (vision 05 §3, amendment rule
     * d): exact-match % + mean KL over every sampled decision with a shadow answer in
     * the view, coverage = joined/total-sampled. See [navigator.core.log.JoinedAgreement].
     */
    suspend fun joinedAgreementByTask(taskId: String) =
        navigator.core.log.JoinedAgreement.metrics(joinedByTask(taskId))

    /** Same aggregation over a wall-clock window (the export/graduation windows). */
    suspend fun joinedAgreementByTimeWindow(fromInclusive: Long, toInclusive: Long) =
        navigator.core.log.JoinedAgreement.metrics(joinedByTimeWindow(fromInclusive, toInclusive))

    /** Companion rows in the database (informational, RLCD manifest rule e). */
    suspend fun companionRowCount(): Int = dao.count()
}
