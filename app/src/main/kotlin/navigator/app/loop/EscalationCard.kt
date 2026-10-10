package navigator.app.loop

/**
 * The owner escalation card (Phase 10, vision 04 §1 v1.4.0): after **3 consecutive
 * subtask failures** the loop asks the owner — "Retry / Re-plan / Stop". The card rides
 * the Phase-9 card window machinery (the [GatePresenter]/[CompletionCardPresenter]
 * precedent); a 30s timeout resolves STOP (fail-safe, vision 06 §2's timeout=deny
 * discipline). Payload is plain-words data only — no UI, no secrets.
 */
data class EscalationCard(
    /** The ACTIVE subtask's instruction — what the agent is stuck on. */
    val subtaskInstruction: String,
    /** The done-condition, rendered one-line (what "done" means here). */
    val doneCondition: String,
    /** Consecutive subtask failures so far (>= 3 by construction). */
    val failureCount: Int,
    /** Bounded re-plan calls that remain within the task cap (may be 0). */
    val rePlansRemaining: Int,
)

/** The owner's escalation resolution (30s timeout = STOP, fail-safe). */
enum class EscalationResolution {
    /** Re-attempt the SAME subtask (the failure streak resets). */
    RETRY,

    /** Ask the writer for a fresh ladder generation (draws the same bounded re-plan cap). */
    REPLAN,

    /** Stop the task cleanly — partial results, everything logged. */
    STOP,
}

/**
 * The escalation-card presenter seam (the [CompletionCardPresenter] pattern): the loop
 * suspends on it; the product wires the overlay card window, JVM tests script
 * resolutions. The default is the FAIL-SAFE: stop the task (the timeout's resolution) —
 * an unwired presenter can never spin an unattended run (vision 06 §2: fail-safe, never
 * fail-open).
 */
interface EscalationCardPresenter {
    suspend fun present(card: EscalationCard): EscalationResolution
}

/** The fail-safe default: stop the task immediately (the timeout resolution, unattended). */
object StopEscalationPresenter : EscalationCardPresenter {
    override suspend fun present(card: EscalationCard): EscalationResolution = EscalationResolution.STOP
}

/** JVM-test presenter: resolves from a scripted queue; an exhausted queue fails loudly. */
class ScriptedEscalationCardPresenter(
    answers: MutableList<EscalationResolution>,
) : EscalationCardPresenter {
    val presented = mutableListOf<EscalationCard>()
    private val queue = answers

    override suspend fun present(card: EscalationCard): EscalationResolution {
        presented.add(card)
        return queue.removeFirstOrNull()
            ?: throw IllegalStateException("no scripted escalation resolution left for: ${card.subtaskInstruction}")
    }
}
