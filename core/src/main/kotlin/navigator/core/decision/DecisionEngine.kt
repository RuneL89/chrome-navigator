package navigator.core.decision

/**
 * The one decision gateway (ART-2, vision 02 §1). Every navigation-related decision —
 * task triage, action choice, goal-achieved judgment, recovery-strategy choice — is
 * expressed as one of three typed question primitives answered through this interface.
 *
 * Law (vision 02 §1):
 * - No component outside this interface constructs provider HTTP requests.
 * - The interface is engine-agnostic: `JevEngine`, `LayaEngine`, `VonEngine`,
 *   `RunwareEngine` are implementations or configs of it; nothing names a provider
 *   except the config.
 * - Graduation is a config flip (`primary = LAYA`) with no code change anywhere else.
 */
interface DecisionEngine {
    suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult
    suspend fun judge(state: String, question: NoulQuestion): NoulResult
    suspend fun score(state: String, question: ScoreQuestion): ScoreResult

    /**
     * The cascade's stage-2 batch (vision 02 §3 v1.3.0, Phase 10B): ONE decision request
     * carrying N per-candidate `score` questions (`cand-<candidate-id>`, all over the same
     * rubric) about [state] — the TARGET STATE VIEW. Answers come back name-keyed, one
     * [ScoreResult] per question. The default asks each question through [score]
     * (N requests); wire-backed engines override it to emit the ONE wire request the
     * cascade law names (vision 02 §3: "ONE wire request carrying N per-candidate score
     * questions"). The expected-level mapping stays in `:core` code (ART-5) either way.
     */
    suspend fun scoreBatch(state: String, questions: Map<String, ScoreQuestion>): Map<String, ScoreResult> =
        questions.mapValues { (_, question) -> score(state, question) }
}
