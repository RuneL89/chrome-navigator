package navigator.app.loop

import navigator.core.ladder.DoneCondition
import navigator.core.ladder.DoneConditionChecker
import navigator.core.ladder.Ladder
import navigator.core.ladder.PrefixAudit

/**
 * PHASE_18 Stage 18.C CN-06 — the EVIDENCE-SCOPED prefix auditor (doc 04 §9.5, ratified).
 * This EXTENDS `:core`'s [PrefixAudit] (the Phase 17 completed-prefix audit) with the
 * runtime states: the audit walks VERIFIED subtasks, revalidates each proof by its
 * condition-type policy, and never duplicates the matching — every "does this condition
 * hold on the current facts" question is delegated to [DoneConditionChecker], and the
 * irreversible-milestone stop composes [navigator.core.ladder.GoalFidelity]'s
 * order-confirmation vocabulary (the purchase law, doc 06 §4 — an order confirmation is
 * never re-executed by a rollback).
 *
 * Policies (the §9.5 law, per done-condition type):
 * - `urlHost`/`urlContains` revalidate against the CURRENT navigation ALWAYS (a lost
 *   site is a real invalidation — the R2 lost-site shape).
 * - `markerText`/`elementPresent` revalidate ONLY when their declared evidence scope is
 *   persistent: the proof's host context is still the current host. After a lawful
 *   transition a page-local marker stays HISTORICAL — no false rollback.
 * - `count` keeps the ACTION HISTORY that produced it: the code-owned counter is
 *   re-read only when the current evidence still carries its key.
 * - `answerExtracted` never proves the OTHER conditions (its proof rides only its own
 *   state, whose condition must match for a proof to exist at all).
 *
 * The rollback goes to the EARLIEST invalid subtask and NEVER crosses a verified
 * irreversible milestone (a verified order confirmation). The CALLER owns the state
 * transitions ([SubtaskRuntimeState.invalidate] appends the reason; proofs stay).
 */
object PrefixAuditor {

    /** ONE invalid prefix subtask: the earliest one, with the appended reason. */
    data class InvalidPrefix(
        val index: Int,
        val conditionRender: String,
        /** WHY the proof no longer holds (the appended invalidation's text). */
        val reason: String,
        /** The ORIGINAL proof's one-line description (the rollback line names BOTH). */
        val proofRender: String,
    )

    /**
     * The runtime audit over the VERIFIED prefix: returns the EARLIEST invalid subtask,
     * or null when the prefix holds. [activePointer] bounds the walk (only states before
     * the active subtask are completed); [currentSatisfied] is the CURRENT-evidence code
     * check for ONE condition ([DoneConditionChecker.satisfied] over the caller's fresh
     * evidence — the auditor passes each state's [SubtaskRuntimeState.auditedCondition],
     * the kept proof's own condition); [currentHost] is the current page's normalized
     * host (null = unreadable).
     *
     * Composition, not duplication: condition matching is [DoneConditionChecker]'s (via
     * [currentSatisfied]), the scope persistence is the runtime state's, the
     * irreversibility law is [SubtaskRuntimeState.irreversibleMilestone] (the same
     * [PrefixAudit] vocabulary), and the earliest-invalid walk mirrors [PrefixAudit]'s
     * contract.
     */
    fun earliestInvalid(
        states: List<SubtaskRuntimeState>,
        activePointer: Int,
        currentSatisfied: (DoneCondition) -> Boolean,
        currentHost: String?,
        currentSnapshotRef: String,
        countersPresent: (Int) -> Boolean = { true },
        answersStillProduced: Boolean = true,
    ): InvalidPrefix? {
        val limit = activePointer.coerceIn(0, states.size)
        for (index in 0 until limit) {
            val state = states[index]
            // Never roll back past a VERIFIED order confirmation (the safety law; the
            // core audit's stop, now keyed to the runtime milestone).
            if (state.state == SubtaskRuntimePhase.VERIFIED && state.irreversibleMilestone) break
            if (state.state != SubtaskRuntimePhase.VERIFIED &&
                state.state != SubtaskRuntimePhase.INVALIDATED
            ) continue
            // The proof's policy decides whether the current facts may even speak:
            // an ALWAYS_CURRENT proof is always re-checked; a PERSISTENT_SCOPE proof
            // only while its page scope persists (the proof's own page is still the
            // current read); the others keep their history.
            val auditable = when (state.policy) {
                RevalidationPolicy.ALWAYS_CURRENT -> true
                RevalidationPolicy.PERSISTENT_SCOPE -> state.contentScopePersists(currentHost, currentSnapshotRef)
                RevalidationPolicy.ACTION_HISTORY -> countersPresent(index)
                RevalidationPolicy.ANSWER_RECORD -> true
            }
            if (!auditable) continue
            val holds = state.proofValidOn(
                currentSatisfied = currentSatisfied(state.auditedCondition),
                currentHost = currentHost,
                currentSnapshotRef = currentSnapshotRef,
                currentCountersPresent = countersPresent(index),
                answersStillProduced = answersStillProduced,
            )
            if (!holds) {
                val proof = state.latestProof
                return InvalidPrefix(
                    index = index,
                    conditionRender = DoneConditionChecker.render(state.auditedCondition),
                    reason = invalidationReason(state, currentHost),
                    proofRender = proof?.let { proofLine(it) } ?: "(no kept proof)",
                )
            }
        }
        return null
    }

    /** The ORIGINAL proof's one-line description (the rollback line's first half). */
    fun proofLine(proof: ProvingEvidence): String =
        "proved ${proof.evidenceType.wire} on host ${proof.hostContext ?: "unreadable"} " +
            "at ${proof.snapshotRef.take(40)}"

    /** The invalidation reason (the rollback line's second half — APPENDED, not replacing). */
    fun invalidationReason(state: SubtaskRuntimeState, currentHost: String?): String =
        when (state.policy) {
            RevalidationPolicy.ALWAYS_CURRENT ->
                "the condition's ${if (state.condition is DoneCondition.UrlHost ||
                    state.condition is DoneCondition.UrlContains
                ) "page identity" else "facts"} no longer holds on the current navigation " +
                    "(now on host ${currentHost ?: "unreadable"})"
            RevalidationPolicy.PERSISTENT_SCOPE ->
                "the marker's own page no longer carries it (scope host ${state.latestProof?.hostContext ?: "unreadable"})"
            RevalidationPolicy.ACTION_HISTORY ->
                "the counted work no longer holds in the working memory"
            RevalidationPolicy.ANSWER_RECORD ->
                "the extraction record is gone"
        }

    // -- the new-ladder mapping (the writer's prefix claims are never proof) -------------

    /**
     * The re-plan install mapping (doc 04 §9.5: "A new ladder's claimed prefix is mapped
     * to contracts and evidence — the writer's word is not proof; the first subtask
     * without valid current or recorded evidence becomes active").
     *
     * Each new subtask is mapped to a previous runtime state BY CONTRACT/CONTENT: the
     * normalized instruction tokens AND the rendered done-condition must match (a
     * rewording of the same step binds; a different step never inherits a proof). A
     * matched state lends its proof ONLY when the proof still revalidates under the
     * subtask's policy ([SubtaskRuntimeState.proofValidOn]); an invalid match appends an
     * invalidation to the NEW state (the reason carries over) and the subtask stays
     * unverified. A matched PROOF also never lets one subtask's extraction prove
     * another's condition: the join requires the condition renders to be equal, so an
     * `answerExtracted` proof can only ever land on an `answerExtracted` subtask.
     *
     * Returns the fresh states for [newLadder] plus the RESUME index: the first subtask
     * without valid current or recorded evidence (never the writer's ordinal claim).
     */
    fun mapNewLadder(
        newLadder: Ladder,
        previous: List<SubtaskRuntimeState>,
        currentSatisfied: (DoneCondition) -> Boolean,
        currentHost: String?,
        currentSnapshotRef: String,
        timestampMs: Long,
        snapshotRef: String,
        countersPresent: (Int) -> Boolean = { true },
        answersStillProduced: Boolean = true,
    ): MappedLadder {
        val states = newLadder.subtasks.mapIndexed { index, subtask ->
            SubtaskRuntimeState(
                ordinal = index,
                instruction = subtask.instruction,
                condition = subtask.done,
            )
        }
        val taken = mutableSetOf<Int>()
        var resume = states.size - 1
        for (index in states.indices) {
            val state = states[index]
            val previousMatch = previous.indices.firstOrNull { candidate ->
                candidate !in taken && matchesByContract(previous[candidate], state)
            }
            if (previousMatch == null) {
                // No recorded evidence at all — the first such subtask becomes active.
                resume = index
                break
            }
            taken.add(previousMatch)
            val old = previous[previousMatch]
            val proof = old.latestProof
            // The validity question is about the OLD state's KEPT proof (the new state
            // has none yet — proofValidOn reads latestProof): does the recorded evidence
            // still revalidate under the policy on the current facts?
            val valid = proof != null && old.state != SubtaskRuntimePhase.BLOCKED && old.proofValidOn(
                currentSatisfied = currentSatisfied(old.auditedCondition),
                currentHost = currentHost,
                currentSnapshotRef = currentSnapshotRef,
                currentCountersPresent = countersPresent(index),
                answersStillProduced = answersStillProduced,
            )
            if (proof != null && valid) {
                // The recorded evidence still stands — the subtask carries it VERIFIED
                // (the proof history rides the NEW state; the old state is kept whole).
                state.markVerified(proof)
            } else if (proof != null) {
                // A claimed prefix whose proof is now invalid: the invalidation is
                // APPENDED and the subtask is the resume point.
                state.invalidate(
                    InvalidationRecord(
                        reason = invalidationReason(old, currentHost),
                        snapshotRef = snapshotRef,
                        hostContext = currentHost,
                        timestampMs = timestampMs,
                    ),
                )
                resume = index
                break
            } else {
                resume = index
                break
            }
        }
        return MappedLadder(states, resume)
    }

    /** The mapped states + where the loop actually resumes (the first unverified subtask). */
    data class MappedLadder(
        val states: List<SubtaskRuntimeState>,
        val resumeIndex: Int,
    )

    /**
     * The contract/content join: the SAME step re-worded (or byte-equal) — the
     * normalized instruction tokens and the rendered done-condition must both match.
     * The condition comparison reads the OLD state's [SubtaskRuntimeState.auditedCondition]
     * too: a subtask verified through the runtime condition repair keeps its repaired
     * condition, and the re-planned same step inherits THAT proof (the repair's
     * evidence, not the mis-shaped original).
     */
    private fun matchesByContract(old: SubtaskRuntimeState, new: SubtaskRuntimeState): Boolean {
        val oldInstruction = navigator.core.ladder.GoalParameter.tokens(old.instruction)
        val newInstruction = navigator.core.ladder.GoalParameter.tokens(new.instruction)
        val sameInstruction = oldInstruction == newInstruction ||
            old.instruction.trim().equals(new.instruction.trim(), ignoreCase = true)
        val oldRender = DoneConditionChecker.render(old.auditedCondition)
        val newRender = DoneConditionChecker.render(new.condition)
        val sameCondition = oldRender == newRender ||
            DoneConditionChecker.render(old.condition) == newRender
        return sameInstruction && sameCondition
    }
}
