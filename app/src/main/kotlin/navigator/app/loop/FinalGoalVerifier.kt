package navigator.app.loop

import navigator.core.ladder.DoneCondition
import navigator.core.ladder.DoneConditionChecker
import navigator.core.ladder.GoalFidelity

/**
 * PHASE_18 Stage 18.C CN-07 — THE SINGLE FINAL VERIFIER (doc 04 §9.6, ratified): ONE
 * verifier gates EVERY success path — the code accept, the repaired-condition accept,
 * and the §4.1 model hatch. A failed verification returns a STRUCTURED deficiency (the
 * active subtask + what is missing) and NEVER yields a success terminal, from any path.
 *
 * The checks (the §9.6 law, in order):
 * 1. the contract's parameters satisfied by runtime evidence — the CURRENT ladder still
 *    binds every stated parameter (the immutable contract's parameter-specific binding,
 *    never a plan-text substring);
 * 2. every required subtask VERIFIED or validly persistent — the runtime states (CN-06)
 *    say the completed prefix holds under each proof's revalidation policy;
 * 3. the final condition holds on the CURRENT snapshot — code for code-verifiable
 *    conditions; the hatch's recorded concurrence or the repair path's recorded
 *    observation for a code-unverifiable (WEAK) one — a STRONG condition the checker
 *    calls false is authoritative everywhere;
 * 4. class-specific end-state evidence — a PURCHASE completes ONLY on order-specific
 *    evidence (an order number or explicit order-placed language; generic
 *    "confirmation" does not complete it — the §4.2 extension); an ACCOUNT_SWITCH on
 *    fresh OR recorded account evidence from the VERIFIED switching subtask (the R4
 *    law — the account need not be visible on an unrelated final media page); a
 *    PLAYBACK on a playback marker;
 * 5. answers present, nonblank, and tied to a source observation (whenever the ladder
 *    carries an `answerExtracted` condition);
 * 6. no unresolved red flag or owner denial.
 *
 * Pure, deterministic, zero model calls (ART-2/ART-5): a function of the
 * [GoalCompletionEvidence] bundle only.
 */
object FinalGoalVerifier {

    sealed interface Verdict {
        /** Every check holds — the caller may take its success terminal. */
        data object Accept : Verdict

        /**
         * The completion is deficient: [activeSubtask] (1-based) is where the run must
         * keep working and [deficiencies] name what is missing (every failed check, in
         * the law's order). NEVER a success terminal.
         */
        data class Deficient(val activeSubtask: Int, val deficiencies: List<String>) : Verdict
    }

    fun verify(evidence: GoalCompletionEvidence): Verdict {
        val deficiencies = mutableListOf<String>()
        val subtasks = evidence.ladder.subtasks
        val finalIndex = subtasks.size - 1
        val obsText = GoalFidelity.normalize(evidence.observationText)

        // 1. The contract's parameters satisfied by runtime evidence: the CURRENT ladder
        // still binds every stated parameter (the contract's own parameter-specific
        // check — a weakened re-plan can never have installed).
        evidence.contract.validateLadder(evidence.ladder).forEach { deficiencies.add(it) }

        // 2. Every required subtask VERIFIED or validly persistent (the completed prefix;
        //    the final subtask's own condition is check 3's subject). A VERIFIED
        //    IRREVERSIBLE milestone (a verified order confirmation) is validly
        //    persistent BY LAW — it is never re-validated and never rolled back
        //    (doc 04 §9.5), so the walk stops there exactly like the prefix audit.
        for (index in 0 until finalIndex) {
            val state = evidence.runtimeStates.getOrNull(index)
            if (state == null) {
                deficiencies.add(
                    "subtask ${index + 1}/${subtasks.size} carries no runtime state — the prefix is not evidenced",
                )
                continue
            }
            if (state.state == SubtaskRuntimePhase.VERIFIED && state.irreversibleMilestone) break
            if (state.state == SubtaskRuntimePhase.BLOCKED) {
                deficiencies.add("subtask ${index + 1}/${subtasks.size} is BLOCKED (an owner denial or unmet precondition)")
                continue
            }
            if (state.state != SubtaskRuntimePhase.VERIFIED &&
                state.state != SubtaskRuntimePhase.INVALIDATED
            ) {
                deficiencies.add(
                    "subtask ${index + 1}/${subtasks.size} is ${state.state} — the prefix subtask is not verified",
                )
                continue
            }
            if (state.latestProof == null) {
                deficiencies.add(
                    "subtask ${index + 1}/${subtasks.size} keeps no proof — the verified milestone lost its evidence",
                )
                continue
            }
            val holds = state.proofValidOn(
                currentSatisfied = DoneConditionChecker.satisfied(
                    // The kept proof's OWN condition is what revalidates (doc 04 §9.5:
                    // a verified milestone keeps its condition — the runtime repair's
                    // accepted condition, not the mis-shaped original).
                    state.auditedCondition,
                    evidence.checkerEvidence,
                ),
                currentHost = evidence.currentHost,
                currentSnapshotRef = evidence.currentSnapshotRef,
                currentCountersPresent = countersPresent(state.auditedCondition, evidence),
                answersStillProduced = evidence.answer != null,
            )
            if (!holds) {
                deficiencies.add(
                    "subtask ${index + 1}/${subtasks.size}'s proof no longer holds on the current facts " +
                        "(${DoneConditionChecker.render(state.auditedCondition)})",
                )
            }
        }

        // 3. The final condition on the CURRENT snapshot: code first; the hatch's
        // recorded concurrence or the repair path's recorded observation only for a
        // WEAK (code-unverifiable) condition — a STRONG false is authoritative.
        val finalHoldsInCode =
            DoneConditionChecker.satisfied(evidence.finalCondition, evidence.checkerEvidence)
        val finalWeak = DoneConditionChecker.guardStrength(evidence.finalCondition) ==
            DoneConditionChecker.GuardStrength.WEAK
        if (!finalHoldsInCode) {
            val rescued = finalWeak && (evidence.modelsConfirmedFinal || evidence.repairedConditionObserved)
            if (!rescued) {
                deficiencies.add(
                    "the final condition does not hold on the current snapshot: " +
                        DoneConditionChecker.render(evidence.finalCondition) +
                        if (finalWeak) {
                            " (code-unverifiable here and no recorded model concurrence or observed repair)"
                        } else {
                            " (the code checker is authoritative)"
                        },
                )
            }
        }

        // 4. The class-specific end-state evidence (compose GoalFidelity — the same
        // vocabulary the build-time fidelity check and the hatch's sixth guard use).
        val classes = evidence.contract.classes
        if (GoalFidelity.GoalClass.PURCHASE in classes) {
            if (!evidencesOrderSpecificCompletion(evidence.observationText, obsText)) {
                deficiencies.add(
                    "purchase: the current page carries no order-specific completion evidence " +
                        "(an order number or explicit order-placed language) — generic " +
                        "\"confirmation\" does not complete a purchase (doc 04 §9.6, §4.2)",
                )
            }
        }
        if (GoalFidelity.GoalClass.ACCOUNT_SWITCH in classes) {
            // Fresh evidence = the current page; recorded = the VERIFIED switching
            // subtask's kept proof text (from the runtime states, not the ladder text).
            val recorded = evidence.recordedSwitchingEvidenceText()
            val violation = GoalFidelity.finalAcceptViolation(
                goal = evidence.goalText,
                ladderText = evidence.ladder.summary + " " + evidence.ladder.subtasks.joinToString(" ") {
                    it.instruction + " " + DoneConditionChecker.render(it.done)
                },
                currentObservationText = evidence.observationText,
                recordedEvidenceText = recorded,
            )
            if (violation != null) deficiencies.add(violation)
        }
        if (GoalFidelity.GoalClass.PLAYBACK in classes) {
            if (!GoalFidelity.evidencesPlayback(obsText)) {
                deficiencies.add(
                    "playback: the current page carries no playback marker — the media is not evidenced as playing",
                )
            }
        }

        // 5. Answers present, nonblank, tied to a source observation.
        if (subtasks.any { it.done is DoneCondition.AnswerExtracted }) {
            val answer = evidence.answer?.trim()
            when {
                answer.isNullOrEmpty() ->
                    deficiencies.add("the report answer is missing or blank — the deliverable was never produced")
                evidence.answerSource.isNullOrBlank() ->
                    deficiencies.add("the report answer is not tied to a source observation")
            }
        }

        // 6. No unresolved red flag or owner denial.
        if (evidence.unresolvedRedFlag) {
            deficiencies.add("an unresolved verification red flag stands against completion")
        }
        if (evidence.unresolvedOwnerDenial) {
            deficiencies.add("an unresolved owner denial stands against completion")
        }

        return if (deficiencies.isEmpty()) {
            Verdict.Accept
        } else {
            Verdict.Deficient(activeSubtask = finalIndex + 1, deficiencies = deficiencies)
        }
    }

    /**
     * The PURCHASE completion evidence (doc 04 §9.6, extending §4.2): an explicit
     * order-placed language ([GoalFidelity.evidencesOrderConfirmation] — order-specific
     * vocabulary only since this amendment) OR an explicit ORDER NUMBER on the page.
     * Generic "confirmation" matches neither.
     */
    fun evidencesOrderSpecificCompletion(rawText: String, normalizedText: String): Boolean {
        if (GoalFidelity.evidencesOrderConfirmation(normalizedText)) return true
        val raw = rawText.lowercase()
        // An explicit order number: "order ... <digits>" / "tilaus ... <digits>" within a
        // short window, or a bare order reference "#<digits>".
        return ORDER_NUMBER_NEAR_LABEL.containsMatchIn(raw) || ORDER_REFERENCE.containsMatchIn(raw)
    }

    /** "order (no/number/…)? <3+ digits>" and the Finnish "tilaus(numero) …" shapes. */
    private val ORDER_NUMBER_NEAR_LABEL = Regex(
        """(?:order|tilaus)[^a-z0-9]{0,16}[0-9]{3,}""",
    )

    /** A bare order reference: "#12345". */
    private val ORDER_REFERENCE = Regex("""#[0-9]{3,}""")

    /** Whether the current evidence carries the key a count condition reads. */
    private fun countersPresent(condition: DoneCondition, evidence: GoalCompletionEvidence): Boolean =
        when (condition) {
            is DoneCondition.CountReached ->
                evidence.checkerEvidence.counters.containsKey(condition.target) ||
                    evidence.checkerEvidence.counters.containsKey(DoneConditionChecker.WORKING_MEMORY_KEY)
            else -> true
        }
}
