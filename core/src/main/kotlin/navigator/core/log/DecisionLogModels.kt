package navigator.core.log

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.Option
import navigator.core.decision.TypedResult
import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.gate.ActionKind
import navigator.core.gate.HClass

/**
 * The DecisionLog row schema in its final day-one shape (vision 05 §2 — law): one row per
 * decision, simultaneously the replayable demo, the debugging record, and the RLCD
 * training corpus. Retrofitting is explicitly rejected — this is the shape.
 *
 * All types are [Serializable]; validation lives in `init` blocks so deserialization of
 * bad data fails loudly (core DOX). Probability maps are name/id-keyed, never positional
 * (vision 02 §2).
 */

/** Canonical JSON configuration for the decision log and its exports: every declared
 *  field is always written, so golden snapshots and exports are schema-complete. */
val DECISION_LOG_JSON: Json = Json { encodeDefaults = true }

/** The @SerialName of an enum entry, read from its serializer descriptor — single source
 *  of truth for the serial vocabulary (e.g. [Bucket] names are the shadow's training
 *  vocabulary, vision 03 §3). */
fun <E : Enum<E>> serialNameOf(serializer: KSerializer<E>, value: E): String =
    serializer.descriptor.getElementName(value.ordinal)

/** Decodes an enum from its @SerialName; an unknown name fails loudly, never silently. */
fun <E : Enum<E>> enumFromSerialName(serializer: KSerializer<E>, name: String): E =
    try {
        DECISION_LOG_JSON.decodeFromString(serializer, "\"" + name + "\"")
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException(
            "unknown serial name '$name' for ${serializer.descriptor.serialName}",
            e,
        )
    }

/** The [Bucket]'s serial name (its @SerialName in the serialization vocabulary). */
val Bucket.serialName: String
    get() = serialNameOf(Bucket.serializer(), this)

/**
 * Task triage (vision 05 §2 `triageClass`): the task kinds v1 distinguishes. Phase 8D
 * (vision 04 §1 v1.1.0) added `action` (one consequential operation on the user's
 * accounts/content/data) and `action-multi` (one operation over a set of targets
 * enumerated from the page at run time) — ADDITIVE serial names; the original three
 * entries and their serial names are unchanged and earlier rows decode as before.
 * Logged rows echo the class verbatim.
 */
@Serializable
enum class TriageClass {
    @SerialName("lookup") LOOKUP,
    @SerialName("research-multi-step") RESEARCH_MULTI_STEP,
    @SerialName("form-fill") FORM_FILL,
    @SerialName("action") ACTION,
    @SerialName("action-multi") ACTION_MULTI,
}

/**
 * The engine that answered, as configured (vision 05 §2 `teacher`:
 * `{engine: config-id, model: pinned-id}`). Pinned model IDs live only in
 * `DefaultEngineConfigs` (vision 02 §5); the log echoes what answered.
 */
@Serializable
data class EngineIdentity(
    val configId: String,
    val model: String,
) {
    init {
        require(configId.isNotBlank()) { "EngineIdentity.configId must not be blank" }
        require(model.isNotBlank()) { "EngineIdentity.model must not be blank (pinned snapshot ID, vision 02 §5)" }
    }
}

/**
 * The teacher's answer: the chosen option plus the **full probability distribution**
 * plus confidence (vision 05 §2 `teacherAnswer`) — the distribution is what makes the
 * row RLCD gold (vision 05 §4), so it is never reduced to a bare label.
 */
@Serializable
data class TeacherAnswer(
    val chosen: String,
    val probabilities: Map<String, Double>,
    val confidence: Double,
) {
    init {
        require(probabilities.containsKey(chosen)) {
            "TeacherAnswer: chosen '$chosen' missing from probability map keys ${probabilities.keys}"
        }
        val outOfRange = probabilities.values.filter { it !in 0.0..1.0 }
        require(outOfRange.isEmpty()) { "TeacherAnswer: probabilities out of range [0,1]: $outOfRange" }
        require(confidence in 0.0..1.0) { "TeacherAnswer: confidence out of range [0,1]: $confidence" }
    }
}

/**
 * The shadow engine that ran (or was scheduled) this step (vision 05 §2 `shadow`).
 * [checkpoint] is the pinned checkpoint id (vision 05 §6: `laya`); [precision] is the
 * eval-harness-measured quantization (int8 per-tensor vs fp16 — never preference).
 */
@Serializable
enum class ShadowPrecision {
    @SerialName("int8") INT8,
    @SerialName("fp16") FP16,
}

@Serializable
data class ShadowRef(
    val checkpoint: String,
    val precision: ShadowPrecision,
) {
    init {
        require(checkpoint.isNotBlank()) { "ShadowRef.checkpoint must not be blank" }
    }
}

/** One stage of the shadow's two-stage bucketed answer: a choice plus its distribution. */
@Serializable
data class ShadowStageAnswer(
    val chosen: String,
    val probabilities: Map<String, Double>,
) {
    init {
        require(probabilities.containsKey(chosen)) {
            "ShadowStageAnswer: chosen '$chosen' missing from probability map keys ${probabilities.keys}"
        }
        val outOfRange = probabilities.values.filter { it !in 0.0..1.0 }
        require(outOfRange.isEmpty()) { "ShadowStageAnswer: probabilities out of range [0,1]: $outOfRange" }
    }
}

/**
 * The complete shadow answer (vision 05 §2 `shadowAnswer`): the bucket choice (keyed by
 * bucket serial name) plus the element choice within that bucket. The comparable
 * top-1 choice against the teacher is [element].
 */
@Serializable
data class ShadowAnswer(
    val bucket: ShadowStageAnswer,
    val element: ShadowStageAnswer,
)

/**
 * The shadow's two-stage question form (vision 02 §3, vision 05 §2 `questionShadow`):
 * stage one chooses a [Bucket], stage two chooses among that bucket's ≤20 options —
 * the upstream-sanctioned two-stage pattern and the exact form a graduated Laya is
 * asked (vision 05 §4). Bucket serial names are the shadow's training vocabulary.
 */
@Serializable
data class BucketedQuestion(
    val name: String,
    val buckets: Map<Bucket, List<Option>>,
) {
    init {
        require(name.isNotBlank()) { "BucketedQuestion.name must not be blank" }
        require(buckets.isNotEmpty()) { "BucketedQuestion '$name' must offer at least one bucket" }
        val overSized = buckets.filterValues { it.size > Candidate.MAX_PER_BUCKET }.keys
        require(overSized.isEmpty()) {
            "BucketedQuestion '$name' buckets exceed the 20-option limit (vision 03 §3): " +
                overSized.map { it.serialName }
        }
        require(buckets.values.none { it.isEmpty() }) {
            "BucketedQuestion '$name' has an empty bucket — omit it instead"
        }
        for ((bucket, options) in buckets) {
            val ids = options.map { it.id }
            require(ids.size == ids.toSet().size) {
                "BucketedQuestion '$name' bucket ${bucket.serialName} has duplicate option ids"
            }
        }
    }
}

/**
 * The high-stakes class a logged gate event resolved to (vision 05 §2 `gateEvent`:
 * `class: H1–H8`). A log-side echo of [HClass] — the log owns its serialization
 * vocabulary; the mapping is exhaustive so a new gate class fails compilation here.
 */
@Serializable
enum class LoggedHClass {
    @SerialName("H1") H1,
    @SerialName("H2") H2,
    @SerialName("H3") H3,
    @SerialName("H4") H4,
    @SerialName("H5") H5,
    @SerialName("H6") H6,
    @SerialName("H7") H7,
    @SerialName("H8") H8,
}

fun HClass.toLoggedHClass(): LoggedHClass = when (this) {
    HClass.H1_PAYMENT -> LoggedHClass.H1
    HClass.H2_PURCHASE_CONFIRM -> LoggedHClass.H2
    HClass.H3_PASSWORD_CHANGE -> LoggedHClass.H3
    HClass.H4_LOGIN -> LoggedHClass.H4
    HClass.H5_ACCOUNT -> LoggedHClass.H5
    HClass.H6_UPLOAD -> LoggedHClass.H6
    HClass.H7_DOWNLOAD -> LoggedHClass.H7
    HClass.H8_CREDENTIAL_TYPING -> LoggedHClass.H8
}

/** Action-kind echo for the log (`executedAction.kind`); maps [ActionKind] exhaustively. */
@Serializable
enum class LoggedActionKind {
    @SerialName("tap") TAP,
    @SerialName("type") TYPE,
    @SerialName("submit") SUBMIT,
    @SerialName("scroll") SCROLL,
    @SerialName("back") BACK,
    @SerialName("finish") FINISH,
}

fun ActionKind.toLoggedActionKind(): LoggedActionKind = when (this) {
    ActionKind.TAP -> LoggedActionKind.TAP
    ActionKind.TYPE -> LoggedActionKind.TYPE
    ActionKind.SUBMIT -> LoggedActionKind.SUBMIT
    ActionKind.SCROLL -> LoggedActionKind.SCROLL
    ActionKind.BACK -> LoggedActionKind.BACK
    ActionKind.FINISH -> LoggedActionKind.FINISH
}

/** What code actually did (vision 05 §2 `executedAction`), refusals included. */
@Serializable
enum class ActionDisposition {
    /** The action was executed. */
    @SerialName("executed") EXECUTED,

    /** The owner denied the gate card — the action did not run (vision 06 §2). */
    @SerialName("refused-by-owner") REFUSED_BY_OWNER,

    /** The gate card expired unused (30s = deny, fail-safe) — the action did not run. */
    @SerialName("gate-timeout") GATE_TIMEOUT,

    /** The same (class, site) was refused twice in one task — clean task abort. */
    @SerialName("task-aborted") TASK_ABORTED,

    /**
     * The loop refused the engine's self-reported finish because the code completion
     * guard/noul verification did not confirm it (ART-4: never trust self-report,
     * vision 04 §3). Additive Phase-6 vocabulary — earlier rows decode unchanged.
     */
    @SerialName("refused-by-guard") REFUSED_BY_GUARD,
}

/**
 * What code actually did this step (vision 05 §2 `executedAction`), including refusals
 * and gate outcomes. [candidateId] is the candidate acted on (or that would have run,
 * for refused dispositions); null for the target-less synthetic actions (back, finish).
 */
@Serializable
data class ExecutedAction(
    val candidateId: String? = null,
    val kind: LoggedActionKind,
    val disposition: ActionDisposition,
) {
    init {
        require(candidateId?.isNotBlank() != false) {
            "ExecutedAction.candidateId must not be blank — omit it for target-less actions"
        }
    }
}

/** The gate decision as logged (`gateEvent.decision`): timeout is decided as a denial. */
@Serializable
enum class LoggedGateDecision {
    @SerialName("approved") APPROVED,
    @SerialName("denied") DENIED,
    @SerialName("timeout") TIMEOUT,
}

/**
 * The gate record for this step (vision 05 §2 `gateEvent`, vision 06 §2): null unless a
 * high-stakes gate decision was made. [trustedFromTask] marks execution granted by the
 * per-task trust toggle (approve → execute + trust for the remainder of THIS task;
 * trust never crosses tasks, sites, or classes).
 */
@Serializable
data class LoggedGateEvent(
    @SerialName("class") val hclass: LoggedHClass,
    val decision: LoggedGateDecision,
    val trustedFromTask: Boolean = false,
) {
    init {
        require(!trustedFromTask || decision == LoggedGateDecision.APPROVED) {
            "LoggedGateEvent: trustedFromTask=true is only meaningful for an approved decision " +
                "(denied/timeout did not execute)"
        }
    }
}

/** Post-step label from the observation diff (vision 05 §2 `outcome`). */
@Serializable
enum class Outcome {
    @SerialName("progressed") PROGRESSED,
    @SerialName("no-change") NO_CHANGE,
    @SerialName("error") ERROR,
    @SerialName("exited") EXITED,
}

/**
 * The fallback engine's answer for an escalated decision (vision 05 §2 `fallback`, doc 05
 * v1.2.0: `{engine, model, answer}`): which fallback config answered and its full answer
 * (choice/noul/score result + distribution — the polymorphic [TypedResult] forms). The
 * fallback's answer is the one EXECUTED when escalated (vision 02 §7); the row's
 * [TeacherAnswer] still carries the primary's own answer when it produced one.
 */
@Serializable
data class FallbackInfo(
    val engine: String,
    val model: String,
    val answer: TypedResult,
) {
    init {
        require(engine.isNotBlank()) { "FallbackInfo.engine must not be blank" }
        require(model.isNotBlank()) { "FallbackInfo.model must not be blank (pinned snapshot ID, vision 02 §5)" }
    }
}

/** What compressed the state text this step (vision 05 §2 `compressedBy`, vision 03 §4). */
@Serializable
enum class CompressedBy {
    @SerialName("code") CODE,
    @SerialName("llm") LLM,
}

/**
 * The goal-achieved path an accepted finish took (vision 04 §3 v1.2.0, doc 05 v1.4.0
 * `exit.path`). The engine can never finish alone (ART-4) — the path names WHICH code
 * verification accepted: HIGH (classic high-band noul over a passing guard), SUSTAINED
 * (STRONG guard + noul over the sustained floor, K consecutive checks), OWNER_CONFIRM
 * (the owner's completion card).
 */
@Serializable
enum class LoggedExitPath {
    @SerialName("HIGH") HIGH,
    @SerialName("SUSTAINED") SUSTAINED,
    @SerialName("OWNER_CONFIRM") OWNER_CONFIRM,
}

/**
 * The code guard level of an accepted exit (vision 04 §3 v1.2.0, doc 05 v1.4.0
 * `exit.guardLevel`): WEAK (per-triage-class contentful predicates) or STRONG
 * (deterministic target-entity match). A failing guard never exits, so the logged
 * vocabulary has no NONE.
 */
@Serializable
enum class LoggedGuardLevel {
    @SerialName("WEAK") WEAK,
    @SerialName("STRONG") STRONG,
}

/**
 * Exit provenance on the TERMINAL row (doc 05 v1.4.0 `exit`: null on every
 * non-terminal row): which path accepted the finish, at which guard level, and the
 * anchored noul of the accepting check. Additive 2026-10-06 amendment — earlier rows
 * decode unchanged with `exit = null`.
 */
@Serializable
data class ExitProvenance(
    val path: LoggedExitPath,
    val guardLevel: LoggedGuardLevel,
    val noul: Double,
) {
    init {
        require(noul in 0.0..1.0) { "ExitProvenance: noul out of range [0,1]: $noul" }
    }
}

/**
 * One DecisionLog row (vision 05 §2 — the full schema, one row per decision).
 *
 * Cross-field consistency fails loudly in [init]: the teacher answer must live on the
 * primary question's option space; the shadow answer on its chosen bucket's options;
 * agreement fields are present exactly when both answers exist (vision 05 §3) and
 * `agreementExactMatch` must equal the stored choices' top-1. A `shadowAnswer` without
 * a [shadow] is incoherent and rejected. Null [shadow] is the explicit `shadowMiss`
 * (vision 02 §4: the answer wasn't ready before the next step). `escalated` and
 * `fallback` (doc 05 v1.2.0) are an all-or-nothing pair — an escalation always names
 * the fallback that answered, and a fallback answer only exists for an escalation.
 */
@Serializable
data class DecisionLogRow(
    val rowId: String,
    val taskId: String,
    val goal: String,
    val stepIndex: Int,
    val timestamp: Long,
    val stateText: String,
    val stateFormatVersion: Int,
    val triageClass: TriageClass,
    val questionPrimary: ChoiceQuestion,
    val questionShadow: BucketedQuestion,
    val teacher: EngineIdentity,
    val teacherAnswer: TeacherAnswer,
    val shadow: ShadowRef? = null,
    val shadowAnswer: ShadowAnswer? = null,
    val agreementExactMatch: Boolean? = null,
    val agreementKL: Double? = null,
    val executedAction: ExecutedAction,
    val gateEvent: LoggedGateEvent? = null,
    val outcome: Outcome,
    val compressedBy: CompressedBy? = null,
    val shadowSampled: Boolean,
    /** True when the fallback engine was consulted for this decision (vision 02 §7);
     *  always false in single-engine modes (doc 05 v1.2.0). */
    val escalated: Boolean = false,
    /** The fallback's answer when escalated, else null (doc 05 v1.2.0). */
    val fallback: FallbackInfo? = null,
    /** Exit provenance on the terminal row (doc 05 v1.4.0): null on every other row. */
    val exit: ExitProvenance? = null,
) {
    init {
        require(rowId.isNotBlank()) { "DecisionLogRow.rowId must not be blank" }
        require(taskId.isNotBlank()) { "DecisionLogRow.taskId must not be blank" }
        require(goal.isNotBlank()) { "DecisionLogRow.goal must not be blank" }
        require(stepIndex >= 0) { "DecisionLogRow: stepIndex must be >= 0, was $stepIndex" }
        require(timestamp > 0) { "DecisionLogRow: wall-clock timestamp must be > 0, was $timestamp" }
        require(stateText.isNotBlank()) { "DecisionLogRow: stateText must not be blank" }
        require(stateFormatVersion >= 1) {
            "DecisionLogRow: stateFormatVersion must be >= 1 (ART-7 echo), was $stateFormatVersion"
        }

        // Teacher answer lives on the primary question's option space (wire law, vision 02 §2)
        val primaryIds = questionPrimary.options.map { it.id }.toSet()
        require(teacherAnswer.chosen in primaryIds) {
            "DecisionLogRow: teacherAnswer.chosen '${teacherAnswer.chosen}' is not an option of " +
                "questionPrimary '${questionPrimary.name}'"
        }
        val teacherForeignKeys = teacherAnswer.probabilities.keys - primaryIds
        require(teacherForeignKeys.isEmpty()) {
            "DecisionLogRow: teacherAnswer distribution keys outside questionPrimary option ids: $teacherForeignKeys"
        }

        // Shadow answer lives on its chosen bucket's options
        if (shadowAnswer != null) {
            require(shadow != null) {
                "DecisionLogRow ${rowId}: a shadowAnswer without a shadow ref is incoherent"
            }
            val bucketNames = questionShadow.buckets.keys.map { it.serialName }.toSet()
            require(shadowAnswer.bucket.chosen in bucketNames) {
                "DecisionLogRow ${rowId}: shadowAnswer.bucket.chosen '${shadowAnswer.bucket.chosen}' " +
                    "is not a bucket of questionShadow '${questionShadow.name}'"
            }
            val bucketForeignKeys = shadowAnswer.bucket.probabilities.keys - bucketNames
            require(bucketForeignKeys.isEmpty()) {
                "DecisionLogRow ${rowId}: shadowAnswer bucket distribution keys outside questionShadow buckets: $bucketForeignKeys"
            }
            val chosenBucketOptions = questionShadow.buckets.entries
                .first { it.key.serialName == shadowAnswer.bucket.chosen }.value
            val elementIds = chosenBucketOptions.map { it.id }.toSet()
            require(shadowAnswer.element.chosen in elementIds) {
                "DecisionLogRow ${rowId}: shadowAnswer.element.chosen '${shadowAnswer.element.chosen}' " +
                    "is not an option of the chosen bucket"
            }
            val elementForeignKeys = shadowAnswer.element.probabilities.keys - elementIds
            require(elementForeignKeys.isEmpty()) {
                "DecisionLogRow ${rowId}: shadowAnswer element distribution keys outside the chosen bucket: $elementForeignKeys"
            }
        }

        // Agreement fields are computed as a pair, exactly when both answers exist (vision 05 §3)
        require((agreementExactMatch != null) == (shadowAnswer != null)) {
            "DecisionLogRow ${rowId}: agreementExactMatch is present iff both answers exist " +
                "(shadowAnswer present=${shadowAnswer != null})"
        }
        require((agreementKL != null) == (shadowAnswer != null)) {
            "DecisionLogRow ${rowId}: agreementKL is present iff both answers exist " +
                "(shadowAnswer present=${shadowAnswer != null})"
        }
        if (agreementExactMatch != null && shadowAnswer != null) {
            val derived = teacherAnswer.chosen == shadowAnswer.element.chosen
            require(agreementExactMatch == derived) {
                "DecisionLogRow ${rowId}: agreementExactMatch=$agreementExactMatch contradicts the " +
                    "stored choices (teacher '${teacherAnswer.chosen}' vs shadow element '${shadowAnswer.element.chosen}')"
            }
            require(agreementKL!! >= -NORMALIZATION_TOLERANCE) {
                "DecisionLogRow ${rowId}: agreementKL must be non-negative, was $agreementKL"
            }
        }

        // Escalation fields are an all-or-nothing pair (doc 05 v1.2.0, vision 02 §7):
        // an escalation always names the fallback that answered, and a fallback answer
        // exists only for an escalation.
        require(escalated || fallback == null) {
            "DecisionLogRow ${rowId}: a fallback answer without escalated=true is incoherent"
        }
        require(!escalated || fallback != null) {
            "DecisionLogRow ${rowId}: escalated=true without a fallback answer is incoherent"
        }
        if (fallback != null && fallback.answer is ChoiceResult) {
            // The fallback answers the SAME question the primary was asked (one consult,
            // same option space, vision 02 §7) — its choice must live on that space too.
            val fallbackForeignKeys = fallback.answer.probabilities.keys - primaryIds
            require(fallback.answer.chosenId in primaryIds) {
                "DecisionLogRow ${rowId}: fallback.answer.chosen '${fallback.answer.chosenId}' is not an " +
                    "option of questionPrimary '${questionPrimary.name}'"
            }
            require(fallbackForeignKeys.isEmpty()) {
                "DecisionLogRow ${rowId}: fallback.answer distribution keys outside questionPrimary " +
                    "option ids: $fallbackForeignKeys"
            }
        }
    }

    companion object {
        /**
         * Normalization tolerance shared with the agreement calculators ([Agreement]):
         * stored KL values may carry numerical noise up to this magnitude.
         */
        const val NORMALIZATION_TOLERANCE: Double = 1e-6
    }
}
