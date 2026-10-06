package navigator.core.log

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import navigator.core.decision.Band
import navigator.core.decision.NoulQuestion
import navigator.core.decision.band as probabilityBand

/**
 * The exit-check row (vision 05 §2 amendment 2026-10-06 — owner-ratified law): the
 * third insert-only row type, one row per finish check — **accepted or refused**. A
 * `noul` answer is a judgment over an anchored proposition, not a choice over options,
 * so choice rows cannot carry the finish verification; the refusals are the decoy
 * contrast that teaches exit calibration (both UAT failure directions of 2026-10-06
 * measured on exactly this axis).
 *
 * Laws (vision 05 §2 amendment, rules a–d):
 * - (a) insert-only like every row type; exactly one row per finish check, refused
 *   checks included;
 * - (b) [answer] is the primary's noul; when the confidence band escalated
 *   (vision 02 §7), `escalated=true` and [fallbackAnswer] records the fallback's noul —
 *   the executed judgment (a primary that hard-failed produced no answer: the executed
 *   fallback answer stands in as [answer], the decision-row precedent);
 * - (c) exit checks are choice-free and are EXCLUDED from the §3 agreement metrics —
 *   graduation's exit leg is the exit-calibration family (vision 07 §3a);
 * - (d) training data spans decision rows (choose cases) AND exit-check rows (noul
 *   cases, vision 05 §4) — companions remain agreement-only.
 *
 * Validation lives in [init] so deserialization of bad data fails loudly (core log
 * law): an escalated row without a fallback answer (and vice versa), an ACCEPTED
 * verdict without a path (and vice versa), and a band that contradicts the stored
 * pTrue are all incoherent shapes and rejected.
 */
@Serializable
data class ExitCheckRow(
    val rowId: String,
    val taskId: String,
    val stepIndex: Int,
    val timestamp: Long,
    /** The canonical state text the finish check judged, verbatim (vision 05 §4). */
    val stateText: String,
    val stateFormatVersion: Int,
    /** The anchored `NoulQuestion` payload the engine judged (vision 04 §3). */
    val question: NoulQuestion,
    val anchoredNoulVersion: Int,
    /** The PRIMARY that answered (the teacher of record, vision 05 §2). */
    val teacher: EngineIdentity,
    val answer: ExitCheckAnswer,
    val escalated: Boolean = false,
    val fallbackAnswer: ExitCheckFallbackAnswer? = null,
    val guardLevel: ExitCheckGuardLevel,
    val verdict: ExitCheckVerdict,
    /** The goal-achieved path of an ACCEPTED check; null on every refusal. */
    val acceptedPath: LoggedExitPath? = null,
) {
    init {
        require(rowId.isNotBlank()) { "ExitCheckRow.rowId must not be blank" }
        require(taskId.isNotBlank()) { "ExitCheckRow.taskId must not be blank" }
        require(stepIndex >= 0) { "ExitCheckRow: stepIndex must be >= 0, was $stepIndex" }
        require(timestamp > 0) { "ExitCheckRow: wall-clock timestamp must be > 0, was $timestamp" }
        require(stateText.isNotBlank()) { "ExitCheckRow: stateText must not be blank" }
        require(stateFormatVersion >= 1) {
            "ExitCheckRow: stateFormatVersion must be >= 1 (ART-7 echo), was $stateFormatVersion"
        }
        require(anchoredNoulVersion >= 1) {
            "ExitCheckRow: anchoredNoulVersion must be >= 1, was $anchoredNoulVersion"
        }
        // Escalation fields are an all-or-nothing pair (rule b), like the decision rows.
        require(escalated || fallbackAnswer == null) {
            "ExitCheckRow ${rowId}: a fallbackAnswer without escalated=true is incoherent"
        }
        require(!escalated || fallbackAnswer != null) {
            "ExitCheckRow ${rowId}: escalated=true without a fallbackAnswer is incoherent"
        }
        // An accepted check always names the path that accepted it; a refusal never does.
        require((verdict == ExitCheckVerdict.ACCEPTED) == (acceptedPath != null)) {
            "ExitCheckRow ${rowId}: acceptedPath is present iff verdict is ACCEPTED " +
                "(verdict=$verdict, acceptedPath=$acceptedPath)"
        }
        // Code evidence is a prerequisite of every path (ART-4): a NONE guard can
        // refuse, but no path accepts without it.
        require(acceptedPath == null || guardLevel != ExitCheckGuardLevel.NONE) {
            "ExitCheckRow ${rowId}: an ACCEPTED check with a NONE guard is incoherent " +
                "(no path accepts without code evidence, vision 04 §3)"
        }
    }

    companion object {
        /**
         * The deterministic exit-check rowId for a `(taskId, stepIndex)` key: one finish
         * check consumes one step (a refusal advances the step counter), so the key IS
         * the identity — exactly-once per check fails the insert-only ABORT loudly.
         */
        fun rowIdFor(taskId: String, stepIndex: Int): String =
            "exit-check:$taskId:$stepIndex"
    }
}

/** The primary's noul answer of one finish check: the pTrue plus the band it landed in. */
@Serializable
data class ExitCheckAnswer(
    val pTrue: Double,
    val band: LoggedBand,
) {
    init {
        require(pTrue in 0.0..1.0) { "ExitCheckAnswer: pTrue out of range [0,1]: $pTrue" }
        require(band == probabilityBand(pTrue).toLoggedBand()) {
            "ExitCheckAnswer: band $band contradicts the stored pTrue $pTrue"
        }
    }
}

/** The fallback engine's noul when the check escalated — the executed judgment (rule b). */
@Serializable
data class ExitCheckFallbackAnswer(
    val pTrue: Double,
) {
    init {
        require(pTrue in 0.0..1.0) { "ExitCheckFallbackAnswer: pTrue out of range [0,1]: $pTrue" }
    }
}

/** The [navigator.core.decision.Band] echo for the log (serial vocabulary owned here). */
@Serializable
enum class LoggedBand {
    @SerialName("LOW") LOW,
    @SerialName("UNCERTAIN") UNCERTAIN,
    @SerialName("HIGH") HIGH,
}

fun Band.toLoggedBand(): LoggedBand = when (this) {
    Band.LOW -> LoggedBand.LOW
    Band.UNCERTAIN -> LoggedBand.UNCERTAIN
    Band.HIGH -> LoggedBand.HIGH
}

/**
 * The guard level a finish check saw. Unlike [LoggedGuardLevel] (the exit provenance
 * vocabulary, where a failing guard never exits), the check-level vocabulary carries
 * NONE: a guard-failed finish is a refusal, and rule (a) logs every check.
 */
@Serializable
enum class ExitCheckGuardLevel {
    @SerialName("NONE") NONE,
    @SerialName("WEAK") WEAK,
    @SerialName("STRONG") STRONG,
}

/** The policy's verdict on one finish check (vision 05 §2 amendment). */
@Serializable
enum class ExitCheckVerdict {
    @SerialName("ACCEPTED") ACCEPTED,
    @SerialName("REFUSED") REFUSED,
}
