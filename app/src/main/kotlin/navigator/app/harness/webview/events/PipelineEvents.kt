package navigator.app.harness.webview.events

/**
 * V2 S1 — the pipeline event stream (FR-39, AC-S1.8): the five-step event vocabulary the
 * lane emits for EVERY step, per `docs/V2_PLAN.md` §5.3 — proposed / judged / executed /
 * verified / refused — which the two-bot UI (Sprint S4) animates as the handoff.
 *
 * SECRET LAW (FR-39): an event carries step provenance, element labels, actions and
 * refusal reasons — NEVER cookie values, tokens, key material or message bodies. The
 * labels come from the page's own DOM; the sweep in every functional run greps the event
 * log for secret-shaped material.
 */
sealed interface PipelineEvent {
    /** The task run this event belongs to (the lane run id). */
    val taskId: String

    /** Monotonic step counter within the task (0-based). */
    val stepIndex: Int

    /** The active subtask ordinal (0-based) at the time of the event. */
    val subtaskIndex: Int

    /** Wall-clock ms since the task started. */
    val elapsedMs: Long

    /**
     * PROPOSED — the writer chose an action on an element (FR-31). Carries the element's
     * snapshot id and the action word; the expected effect rides for the verifier.
     */
    data class Proposed(
        override val taskId: String,
        override val stepIndex: Int,
        override val subtaskIndex: Int,
        override val elapsedMs: Long,
        val action: String,
        val elementId: Int,
        val elementLabel: String,
        val expectedEffect: String,
    ) : PipelineEvent

    /**
     * JUDGED — the judge's independent verdict on the proposal (FR-32). `accepted=false`
     * is a loud judge refusal: the action is NOT dispatched as a fallback pick.
     */
    data class Judged(
        override val taskId: String,
        override val stepIndex: Int,
        override val subtaskIndex: Int,
        override val elapsedMs: Long,
        val accepted: Boolean,
        val reason: String,
    ) : PipelineEvent

    /**
     * REFUSED — a pre-dispatch refusal by CODE (FR-33): the guard found the element gone
     * or unusable, the risk gate classified a fast-lane-forbidden class, or the action is
     * outside the lane's action policy (e.g. form submit — the fill-only law). Zero
     * dispatch follows a REFUSED.
     */
    data class Refused(
        override val taskId: String,
        override val stepIndex: Int,
        override val subtaskIndex: Int,
        override val elapsedMs: Long,
        val stage: RefusalStage,
        val reason: String,
    ) : PipelineEvent

    /**
     * EXECUTED — the dispatch ran (FR-34). `dispatchReported` is the executor's own
     * report — NEVER a success claim; only [Verified] `confirmed` counts as progress.
     */
    data class Executed(
        override val taskId: String,
        override val stepIndex: Int,
        override val subtaskIndex: Int,
        override val elapsedMs: Long,
        val action: String,
        val elementLabel: String,
        val dispatchReported: Boolean,
    ) : PipelineEvent

    /**
     * VERIFIED — the postcondition verdict (FR-35): `confirmed=true` means the DOM/URL/
     * field evidence shows the expected effect (progress); `confirmed=false` is the
     * VERIFIED-REFUSED face — dispatched but no effect, feeding the no-progress budget.
     */
    data class Verified(
        override val taskId: String,
        override val subtaskIndex: Int,
        override val stepIndex: Int,
        override val elapsedMs: Long,
        val confirmed: Boolean,
        val evidence: String,
    ) : PipelineEvent
}

/** Why the lane refused before dispatching (FR-33 and the action policy). */
enum class RefusalStage {
    /** The pre-dispatch guard: element identity/visibility/enabled/page ownership. */
    GUARD,

    /** The H1–H9 risk gate: a class the fast lane may never act on (payment, purchase…). */
    RISK_GATE,

    /** The lane's action policy: e.g. form submit is the owner's tap (the fill-only law). */
    ACTION_POLICY,
}

/** A consumer of the pipeline stream (the S4 bot UI, the debug screen, the evidence file). */
fun interface PipelineListener {
    fun onEvent(event: PipelineEvent)
}

/**
 * The in-memory event bus. Synchronous, thread-safe, unbounded listeners; the lane emits
 * from its loop coroutine, listeners decide their own threading. The debug screen and
 * the evidence writer both subscribe; Sprint S4's bot UI subscribes the same way.
 */
class PipelineEventBus {
    private val listeners = mutableListOf<PipelineListener>()

    @Synchronized
    fun subscribe(listener: PipelineListener) {
        listeners.add(listener)
    }

    @Synchronized
    fun unsubscribe(listener: PipelineListener) {
        listeners.remove(listener)
    }

    fun emit(event: PipelineEvent) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        for (listener in snapshot) {
            // A broken listener (a UI bug) must never break the lane's loop — the event
            // stream is observability, not control flow.
            try {
                listener.onEvent(event)
            } catch (_: Exception) {
            }
        }
    }
}
