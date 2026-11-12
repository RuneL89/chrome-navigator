package navigator.app.loop

import navigator.core.ladder.DoneCondition
import navigator.core.ladder.DoneConditionChecker
import navigator.core.ladder.GoalContract
import navigator.core.ladder.Ladder

/**
 * PHASE_18 Stage 18.C CN-07 — the EVIDENCE BUNDLE the single final verifier consumes
 * (doc 04 §9.6, ratified): the current observation's identity + code-read facts, the
 * runtime prefix states, the immutable goal contract, the extracted answer (+ the source
 * observation it was read from), the recorded switching-subtask account evidence, and
 * the red-flag / owner-denial flags.
 *
 * Pure data — every field is what the loop ALREADY holds at a success path (the current
 * observation, the runtime states, the contract); the verifier is a pure function of
 * this bundle and adds ZERO model calls (the hatch's pre-filters stay the cheap
 * pre-checks; the verifier is the final code gate).
 *
 * SECRETS: the evidence text fields are page-derived content only (title + URL + page
 * lines, bounded); the doc 06 §2a masking stays the loop's emission law — a bundle is
 * never built with a sanctioned secret value in it.
 */
data class GoalCompletionEvidence(
    /** The goal text verbatim (the contract's own copy rides [contract]). */
    val goalText: String,
    /** The ladder generation being verified (the CURRENT one, post re-plans). */
    val ladder: Ladder,
    /** The immutable goal contract (built before first navigation; reused by every re-plan). */
    val contract: GoalContract,
    // -- the CURRENT snapshot's identity + code-read facts -------------------------------
    val observationTitle: String,
    val observationUrl: String?,
    val observationLines: List<String>,
    /** The normalized current host (null = unreadable — the loop's own derivation). */
    val currentHost: String?,
    /** The current snapshot's identity ref (the page-local scope test reads it). */
    val currentSnapshotRef: String,
    /** The code-read facts the [DoneConditionChecker] judges (the loop's own assembly). */
    val checkerEvidence: DoneConditionChecker.Evidence,
    // -- the runtime prefix (CN-06's states) ----------------------------------------------
    val runtimeStates: List<SubtaskRuntimeState>,
    // -- the answer (report goals) ---------------------------------------------------------
    /** The extracted answer; null = none produced. */
    val answer: String?,
    /** The observation reference the answer was extracted FROM (null = untied). */
    val answerSource: String?,
    // -- the final condition's non-code evidence (the §4.1 hatch / the §VEXP7 repair) ------
    /**
     * The §4.1 hatch's recorded concurrence (the Jev-noul + Haiku-confirm pair AND the
     * goal-level noul agreed). True only on the hatch path, AFTER its own guards.
     */
    val modelsConfirmedFinal: Boolean = false,
    /**
     * The PHASE_VEXP7 repair path's recorded evidence: the repaired condition held on
     * the CURRENT observation and the trigger battery concurred (the scoped weak-at-start
     * bypass — the battery is the arrival evidence).
     */
    val repairedConditionObserved: Boolean = false,
    // -- the failure flags -------------------------------------------------------------------
    /** An unresolved verification-battery red flag stands against completion. */
    val unresolvedRedFlag: Boolean = false,
    /** An unresolved owner denial stands against completion. */
    val unresolvedOwnerDenial: Boolean = false,
) {
    /** The bounded current-page text (title + URL + lines) the class checks read. */
    val observationText: String
        get() = (listOf(observationTitle, observationUrl ?: "") + observationLines)
            .joinToString(" ")

    /**
     * The recorded account evidence from the VERIFIED switching subtask — read from the
     * RUNTIME STATES, never from the ladder text (doc 04 §9.6: "fresh OR recorded
     * account evidence from the verified switching subtask"; the R4 law — the account
     * name is NOT required to be visible on an unrelated final media page). Null when no
     * VERIFIED switching subtask exists.
     */
    fun recordedSwitchingEvidenceText(): String? {
        val account = contract.parameters.filterIsInstance<navigator.core.ladder.GoalParameter.Account>()
            .firstOrNull() ?: return null
        val switching = runtimeStates.filter { it.state == SubtaskRuntimePhase.VERIFIED }
            .firstOrNull { state ->
                navigator.core.ladder.GoalParameter.tokens(state.instruction)
                    .let { account.bindsIn(it) }
            }
        return switching?.latestProof?.evidenceText
    }

    /** The final subtask's done-condition (the ladder law: one condition per subtask). */
    val finalCondition: DoneCondition get() = ladder.subtasks.last().done
}
