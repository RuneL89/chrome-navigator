package navigator.app.harness.webview

import navigator.app.cards.AutoPlacePolicy
import navigator.app.cards.CardState
import navigator.app.cards.PurchaseDecisionClient
import navigator.app.harness.webview.dom.DomSnapshot

/**
 * V2 S3 — the purchase flow's seam between the lane loop and the Android surfaces
 * (FR-42/43). The loop hits a purchase dispatch and hands over:
 *
 *   decide → AutoPlacePolicy → surface (the 15 s cancel-window notification below the
 *   limit; the card at or above it) → await the owner's answer → dispatch or refuse.
 *
 * The loop stays surface-blind; the L2 suites script this seam. The interpretation note
 * in [navigator.app.cards.PurchaseFlow] (auto-place as the ratified exception) governs.
 */
interface PurchaseFlowHandle {
    suspend fun decide(goal: String, snapshot: DomSnapshot): PurchaseDecisionClient.Decision

    /**
     * Surfaces the decision to the owner: below the limit → the cancel-window
     * notification (silence past the window PLACES); at or above → the purchase card
     * (item/qty/total + Approve/Decline, timeout = no). Returns the awaiter.
     */
    fun surface(decision: PurchaseDecisionClient.Decision, belowLimit: Boolean, taskId: String): AnswerAwaiter
}

/** What the loop awaits: the owner's answer, in the shape the surface chose. */
class AnswerAwaiter(private val block: suspend () -> CardState) {
    suspend fun await(): CardState = block()
}

/** The law application, shared by the live handle and the scripted one. */
object PurchaseFlowLaw {
    sealed interface Outcome {
        /** Dispatch the order button (auto-place approved by the silent window). */
        data class Place(val decision: PurchaseDecisionClient.Decision, val viaCancelWindow: Boolean) : Outcome

        /** The owner declined or the timeout elapsed — nothing dispatches. */
        data class Refused(val decision: PurchaseDecisionClient.Decision, val byTimeout: Boolean) : Outcome
    }

    suspend fun run(
        goal: String,
        snapshot: DomSnapshot,
        limitCents: Long,
        handle: PurchaseFlowHandle,
        taskId: String,
    ): Outcome {
        val decision = handle.decide(goal, snapshot)
        val belowLimit = decision.totalCents < limitCents
        val state = handle.surface(decision, belowLimit, taskId).await()
        return when (state) {
            is navigator.app.cards.CardState.Approved ->
                Outcome.Place(decision, viaCancelWindow = belowLimit)

            is navigator.app.cards.CardState.Declined ->
                Outcome.Refused(decision, byTimeout = state.byTimeout)

            else -> Outcome.Refused(decision, byTimeout = false)
        }
    }

    /** The limit law rides the shared [AutoPlacePolicy]. */
    fun decidesCancelWindow(totalCents: Long, limitCents: Long): Boolean =
        AutoPlacePolicy.decide(totalCents / 100.0, limitCents / 100.0) == AutoPlacePolicy.Decision.CANCEL_WINDOW
}
