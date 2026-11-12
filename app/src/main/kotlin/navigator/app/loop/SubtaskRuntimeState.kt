package navigator.app.loop

import navigator.core.ladder.DoneCondition
import navigator.core.ladder.DoneConditionChecker
import navigator.core.ladder.GoalFidelity

/**
 * PHASE_18 Stage 18.C CN-06 — the RUNTIME PREFIX STATES (doc 04 §9.5, ratified): every
 * ladder subtask carries an explicit runtime state (`PENDING`/`ACTIVE`/`VERIFIED`/
 * `INVALIDATED`/`BLOCKED`), and a VERIFIED milestone keeps its PROOF — the condition it
 * verified, the proving snapshot reference, the host context it was proven on, the
 * timestamp and the evidence type — plus the revalidation policy its done-condition type
 * earns (§9.5: location conditions revalidate against current navigation; content
 * conditions only while their declared evidence scope is persistent; a count keeps the
 * action history that produced it; an extracted answer never proves the other
 * conditions).
 *
 * THE APPEND LAW: invalidation APPENDS evidence — the proof history is never deleted.
 * [proofs] and [invalidations] are append-only; a re-verification appends a NEW proof
 * (the record of what happened stays complete for the evidence record and the rollback
 * line, which names BOTH the original proof and the invalidation reason).
 *
 * Pure data + a small state machine; no Android types (the [ActionPostcondition]
 * placement precedent — engine-agnostic, JVM-testable; Stage 18.E's extraction consumes
 * the same contract for both perception tools).
 */
enum class SubtaskRuntimePhase {
    /** Not yet entered. */
    PENDING,
    /** The loop's current subtask. */
    ACTIVE,
    /** Completed with a kept proof ([ProvingEvidence]). */
    VERIFIED,
    /** A revalidation found the proof false — the reason is APPENDED, proofs stay. */
    INVALIDATED,
    /** Owner denial or an unmet precondition parked the subtask (Stage 18.D's terminals). */
    BLOCKED,
}

/** The evidence type that proved a VERIFIED milestone (the §9.5 "evidence type"). */
enum class ProofEvidenceType(val wire: String) {
    /** The a11y snapshot's code-read facts (the [DoneConditionChecker.Evidence] check). */
    SNAPSHOT_TEXT("snapshot-text"),
    /** The URL-bar identity read (a urlHost/urlContains proof). */
    URL_BAR("url-bar"),
    /** The code-owned working-memory counter (a count proof). */
    WORKING_MEMORY("working-memory"),
    /** The code-owned extraction record (an answerExtracted proof). */
    EXTRACTION_RECORD("extraction-record"),
    ;
}

/**
 * The revalidation policy a done-condition type earns (doc 04 §9.5): how a VERIFIED
 * proof may be re-checked against CURRENT navigation without fabricating a false
 * rollback.
 */
enum class RevalidationPolicy(val wire: String) {
    /** `urlHost`/`urlContains` — page identity; revalidated against current navigation ALWAYS. */
    ALWAYS_CURRENT("always-current"),
    /**
     * `markerText`/`elementPresent` — page-local content; revalidated ONLY while the
     * declared evidence scope is persistent (the proof's host is still the current
     * host). After a lawful transition the marker stays HISTORICAL — no false rollback.
     */
    PERSISTENT_SCOPE("persistent-scope"),
    /**
     * `count` — the count keeps the ACTION HISTORY that produced it: the code-owned
     * counter is re-read only when the current evidence still carries the key.
     */
    ACTION_HISTORY("action-history"),
    /**
     * `answerExtracted` — the extraction record is code-owned; it never proves the
     * OTHER conditions and is never invalidated by navigation.
     */
    ANSWER_RECORD("answer-record"),
    ;
}

/** The per-condition-type policy (the §9.5 mapping, in one place). */
fun revalidationPolicyOf(condition: DoneCondition): RevalidationPolicy = when (condition) {
    is DoneCondition.UrlHost -> RevalidationPolicy.ALWAYS_CURRENT
    is DoneCondition.UrlContains -> RevalidationPolicy.ALWAYS_CURRENT
    is DoneCondition.MarkerText -> RevalidationPolicy.PERSISTENT_SCOPE
    is DoneCondition.ElementPresent -> RevalidationPolicy.PERSISTENT_SCOPE
    is DoneCondition.CountReached -> RevalidationPolicy.ACTION_HISTORY
    is DoneCondition.AnswerExtracted -> RevalidationPolicy.ANSWER_RECORD
}

/**
 * ONE kept proof of a VERIFIED milestone (doc 04 §9.5: condition, proving snapshot,
 * host context, timestamp, evidence type). [evidenceText] is the bounded page text the
 * proof was read from (title + URL + lines) — the recorded evidence a later check (the
 * account-switch end state, the final verifier) may consult WITHOUT re-navigating;
 * page-derived data only, never a secret.
 */
data class ProvingEvidence(
    val condition: DoneCondition,
    val snapshotRef: String,
    /** The normalized host the proof was made on (null = no host was readable). */
    val hostContext: String?,
    val timestampMs: Long,
    val evidenceType: ProofEvidenceType,
    val evidenceText: String,
)

/** ONE appended invalidation record (the reason is never allowed to overwrite the proof). */
data class InvalidationRecord(
    val reason: String,
    val snapshotRef: String,
    val hostContext: String?,
    val timestampMs: Long,
)

/**
 * The runtime state of ONE ladder subtask (0-based [ordinal] in its OWN ladder
 * generation — a re-plan rebuilds the list; see [PrefixAuditor.mapNewLadder]).
 */
class SubtaskRuntimeState(
    val ordinal: Int,
    val instruction: String,
    val condition: DoneCondition,
) {
    /** The revalidation policy this subtask's condition type earns (fixed at build). */
    val policy: RevalidationPolicy = revalidationPolicyOf(condition)

    private var phase: SubtaskRuntimePhase = SubtaskRuntimePhase.PENDING

    /** Append-only proof history (never deleted; the newest proof is the last). */
    private val proofHistory = mutableListOf<ProvingEvidence>()

    /** Append-only invalidation history (never deleted). */
    private val invalidationHistory = mutableListOf<InvalidationRecord>()

    val state: SubtaskRuntimePhase get() = phase
    val proofs: List<ProvingEvidence> get() = proofHistory.toList()
    val invalidations: List<InvalidationRecord> get() = invalidationHistory.toList()

    /** The newest proof, or null when never verified. */
    val latestProof: ProvingEvidence? get() = proofHistory.lastOrNull()

    /**
     * The condition the audit revalidates: the KEPT PROOF's own condition when one
     * exists (a verified milestone "keeps its condition", doc 04 §9.5 — the runtime
     * condition repair accepts a repaired condition whose shape differs from the
     * ladder's original), else the state's build-time condition.
     */
    val auditedCondition: DoneCondition get() = latestProof?.condition ?: condition

    /** Whether a completed order-confirmation milestone rides this subtask (never re-executed by rollback). The KEPT repaired condition is checked, not the build-time one — a repair that introduces order-confirming language trips the stop too. */
    val irreversibleMilestone: Boolean
        get() = GoalFidelity.evidencesOrderConfirmation(DoneConditionChecker.render(auditedCondition))

    fun markActive() {
        phase = SubtaskRuntimePhase.ACTIVE
    }

    /**
     * Returns a rolled-back subtask to the pending pool (a rollback re-enters it); the
     * kept proofs and appended invalidations stay (the append law — history never
     * deletes).
     */
    fun markPending() {
        phase = SubtaskRuntimePhase.PENDING
    }

    /** Verifies the subtask: APPENDS the proof and flips the phase to VERIFIED. */
    fun markVerified(proof: ProvingEvidence) {
        proofHistory.add(proof)
        phase = SubtaskRuntimePhase.VERIFIED
    }

    /** Invalidates the subtask: APPENDS the reason (proofs stay) and flips the phase. */
    fun invalidate(record: InvalidationRecord) {
        invalidationHistory.add(record)
        phase = SubtaskRuntimePhase.INVALIDATED
    }

    fun markBlocked() {
        phase = SubtaskRuntimePhase.BLOCKED
    }

    /**
     * Whether the subtask's declared content-evidence scope persists onto the current
     * PAGE: the proof was made on the SAME page the loop now reads — the same host AND
     * the same snapshot identity (the §9.5 page-local law: markers are page-local, so a
     * lawful transition — even within one host — leaves the proof HISTORICAL; only the
     * marker's own page may speak about its absence). Always true for
     * [RevalidationPolicy.ALWAYS_CURRENT] (no page scope to persist).
     */
    fun contentScopePersists(currentHost: String?, currentSnapshotRef: String): Boolean =
        when (policy) {
            RevalidationPolicy.ALWAYS_CURRENT -> true
            else -> {
                val proof = latestProof
                proof != null && proof.hostContext != null && proof.hostContext == currentHost &&
                    proof.snapshotRef == currentSnapshotRef
            }
        }

    /**
     * Whether the kept proof still counts as valid evidence for the subtask on the
     * CURRENT facts — the "verified or validly persistent" half of the final verifier's
     * subtask check (doc 04 §9.6). [currentSatisfied] is the CURRENT-evidence code
     * check ([DoneConditionChecker.satisfied]); [currentCountersPresent] says whether
     * the current evidence still carries this count's key.
     */
    fun proofValidOn(
        currentSatisfied: Boolean,
        currentHost: String?,
        currentSnapshotRef: String,
        currentCountersPresent: Boolean,
        answersStillProduced: Boolean,
    ): Boolean {
        val proof = latestProof ?: return false
        return when (policy) {
            RevalidationPolicy.ALWAYS_CURRENT -> currentSatisfied
            RevalidationPolicy.PERSISTENT_SCOPE -> when {
                // The declared scope persists (the current read is the proof's OWN
                // page — same host AND same snapshot identity): the CURRENT facts
                // decide — a marker gone from its own page is a real invalidation,
                // not a false rollback.
                proof.hostContext != null && proof.hostContext == currentHost &&
                    proof.snapshotRef == currentSnapshotRef -> currentSatisfied
                // A lawful transition (any other page — the page-local marker stays
                // HISTORICAL, doc 04 §9.5). No host read at proof time: scope
                // unknowable, the proof stands (conservative on no data).
                else -> true
            }
            RevalidationPolicy.ACTION_HISTORY ->
                // The count keeps its action history: re-read only when the key survives.
                if (currentCountersPresent) currentSatisfied else true
            RevalidationPolicy.ANSWER_RECORD ->
                // The extraction record is code-owned; navigation never un-produces it.
                answersStillProduced || proof.evidenceType == ProofEvidenceType.EXTRACTION_RECORD
        }
    }

    /** The compact state mark the evidence lines use (`[1/4 VERIFIED]`). */
    fun renderMark(size: Int): String = "[${ordinal + 1}/$size ${state.name}]"
}
