package navigator.app.loop

import navigator.core.enumerate.CandidateEnumerator
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.DoneConditionChecker

/**
 * PHASE_18 Stage 18.E part 1 — THE SHARED COMPLETION-EVIDENCE ASSEMBLY (doc 04 §9.5/§9.6):
 * the pure helpers BOTH perception tools use to build the [GoalCompletionEvidence] bundle
 * and the kept [ProvingEvidence] proofs. Extracted verbatim from `VisionLoop`'s private
 * assembly (the 18.C build) so the hybrid host (`NavigationLoop`) assembles the SAME
 * bundle for the ONE [FinalGoalVerifier] — the extraction is byte-identical by
 * construction (the vision loop delegates to these functions; the characterization suite
 * must stay green).
 *
 * Pure, deterministic, zero model calls (ART-2/ART-5); page-derived data only, never a
 * secret (the doc 06 §2a masking stays each loop's emission law).
 */
object CompletionEvidenceSupport {

    /** The normalized current host of an observation (null = unreadable). */
    fun readableHost(obs: StepObservation): String? {
        val url = obs.url.takeUnless { it == "hidden" || it == "unavailable" } ?: return null
        val normalized = DoneConditionChecker.normalizeUrl(url) ?: return null
        val host = normalized.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.takeIf { it.isNotBlank() && it.contains('.') }
    }

    /** The code-read facts the [DoneConditionChecker] judges over ONE observation. */
    fun checkerEvidence(
        obs: StepObservation,
        counters: Map<String, Int>,
        answersProduced: Int,
    ): DoneConditionChecker.Evidence = DoneConditionChecker.Evidence(
        url = obs.url.takeUnless { it == "hidden" || it == "unavailable" },
        title = obs.title,
        pageText = obs.lines + obs.excerpt,
        candidateLabels = CandidateEnumerator.enumerate(obs.nodes).flat.map { it.label },
        counters = counters,
        answersProduced = answersProduced,
    )

    /**
     * The kept proof for ONE verified milestone (doc 04 §9.5: condition, proving
     * snapshot, host context, timestamp, evidence type + the bounded page text the proof
     * was read from — the recorded evidence later checks consult).
     */
    fun provingEvidence(condition: DoneCondition, obs: StepObservation): ProvingEvidence =
        ProvingEvidence(
            condition = condition,
            snapshotRef = "hash=${obs.screenHash}",
            hostContext = readableHost(obs),
            timestampMs = System.currentTimeMillis(),
            evidenceType = when (condition) {
                is DoneCondition.UrlHost, is DoneCondition.UrlContains -> ProofEvidenceType.URL_BAR
                is DoneCondition.CountReached -> ProofEvidenceType.WORKING_MEMORY
                is DoneCondition.AnswerExtracted -> ProofEvidenceType.EXTRACTION_RECORD
                else -> ProofEvidenceType.SNAPSHOT_TEXT
            },
            evidenceText = (listOf(obs.title, obs.url) + obs.lines.take(12))
                .joinToString(" ").take(400),
        )

    /** Whether the working memory still carries the key a count condition reads. */
    fun countKeyPresent(condition: DoneCondition, counters: Map<String, Int>): Boolean =
        condition !is DoneCondition.CountReached ||
            counters.containsKey(condition.target) ||
            counters.containsKey(DoneConditionChecker.WORKING_MEMORY_KEY)
}
