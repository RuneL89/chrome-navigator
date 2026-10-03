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
}
