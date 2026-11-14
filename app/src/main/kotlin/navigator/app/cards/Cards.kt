package navigator.app.cards

/**
 * V2 S3 — the approval-card layer's LAWS (FR-41/42/43): pure state machines and
 * policies. The Android surfaces (the overlay cards, the notifications) render and feed
 * these; every decision is the machine's, never the surface's whim.
 *
 * Owner-ratified rules carried from `docs/V2_PLAN.md` §3:
 * - every send/submit-class action is carded (the card classes below);
 * - the purchase auto-place rule: below the owner's auto-place limit (default €30) the
 *   purchase places after a 15 s cancel window; at or above it a card asks; TIMEOUT IS NO;
 * - an over-budget purchase asks WITH THE DELTA; timeout = no order;
 * - a consent card's timeout is NO (the strictest rule wins).
 */

/** The card classes that always gate a consequential action (FR-41). */
enum class CardClass {
    /** A message send (email/WhatsApp draft) — the draft card + 30 s hold live elsewhere. */
    SEND,

    /** A form submit — the fill-only law: the owner's tap IS the card's approve. */
    SUBMIT,

    /** A social write (post/comment). */
    POST,

    /** A settings/account change. */
    CHANGE,

    /** A message/account deletion (trash/archive only). */
    DELETE,

    /** A subscription/booking cancellation. */
    CANCEL,

    /** A booking review. */
    BOOKING,

    /** An invite (RSVP/invite draft). */
    INVITE,

    /** A purchase at or above the auto-place limit (or any purchase the owner reviews). */
    PURCHASE,

    /** A consent decision (cookies, permissions, terms). */
    CONSENT,
}

/** One card's lifecycle (FR-41's state machines — one law for every class). */
sealed interface CardState {
    /** Shown, waiting for the owner. `deadlineMs` is the no-answer law's cutoff (null = wait indefinitely). */
    data class Pending(val shownAtMs: Long, val deadlineMs: Long?) : CardState

    /** The owner approved (or, where lawful, the cancel window elapsed without a cancel). */
    data class Approved(val atMs: Long) : CardState

    /** The owner dismissed, or the deadline passed — NO (the timeout is a no, never a yes). */
    data class Declined(val atMs: Long, val byTimeout: Boolean) : CardState

    /** The owner edited the content inline (review cards); the card returns to Pending. */
    data class Edited(val atMs: Long, val edits: List<String>) : CardState
}

/**
 * One card machine (FR-41): every class moves through the same lawful transitions.
 * The machine is the only writer of the decision — the UI calls it, never decides.
 */
class CardMachine(val cardClass: CardClass, private val nowMs: () -> Long) {
    var state: CardState = CardState.Pending(nowMs(), deadlineFor(cardClass, nowMs()))
        private set

    /** The timeout law: an approval-class card's cutoff (null = the owner may take their time). */
    companion object {
        /** The purchase/consent no-answer cutoff: a NO by timeout (owner-ratified). */
        const val APPROVAL_TIMEOUT_MS = 120_000L

        fun deadlineFor(cardClass: CardClass, nowMs: Long): Long? = when (cardClass) {
            CardClass.PURCHASE, CardClass.CONSENT, CardClass.SEND, CardClass.SUBMIT, CardClass.POST,
            CardClass.CHANGE, CardClass.DELETE, CardClass.CANCEL, CardClass.BOOKING, CardClass.INVITE,
            -> nowMs + APPROVAL_TIMEOUT_MS
        }

        /** The purchase auto-place cancel window (FR-42, owner-ratified: 15 s). */
        const val AUTO_PLACE_CANCEL_WINDOW_MS = 15_000L
    }

    fun approve(): CardState {
        check(state is CardState.Pending) { "approve requires Pending (got $state)" }
        state = CardState.Approved(nowMs())
        return state
    }

    fun decline(byTimeout: Boolean = false): CardState {
        check(state is CardState.Pending) { "decline requires Pending (got $state)" }
        state = CardState.Declined(nowMs(), byTimeout)
        return state
    }

    /** The owner edited the inline review — the card returns to Pending with a fresh deadline. */
    fun edit(edit: String): CardState {
        val prior = state as? CardState.Edited
        state = CardState.Edited(nowMs(), (prior?.edits ?: emptyList()) + edit)
        return state
    }

    fun commitEdits(): CardState {
        check(state is CardState.Edited) { "commitEdits requires Edited (got $state)" }
        state = CardState.Pending(nowMs(), deadlineFor(cardClass, nowMs()))
        return state
    }

    /** The deadline law: a Pending card past its cutoff becomes a timeout-NO (never a yes). */
    fun enforceTimeout(): CardState {
        val pending = state as? CardState.Pending ?: return state
        val deadline = pending.deadlineMs ?: return state
        if (nowMs() >= deadline) state = CardState.Declined(nowMs(), byTimeout = true)
        return state
    }
}

/**
 * The purchase auto-place rule (FR-42, AC-S3.2): below the owner's limit the purchase
 * places after the 15 s cancel window; at or above it a card asks (timeout = no).
 */
object AutoPlacePolicy {

    /** The owner's editable limit (the settings number input; the owner-ratified default). */
    const val DEFAULT_LIMIT_EUR = 30.00

    enum class Decision { CANCEL_WINDOW, ASK_CARD }

    fun decide(totalEur: Double, limitEur: Double = DEFAULT_LIMIT_EUR): Decision =
        if (totalEur < limitEur) Decision.CANCEL_WINDOW else Decision.ASK_CARD

    /**
     * The cancel window's outcome: the purchase places when the window elapsed WITHOUT a
     * cancel; an owner cancel inside the window is a NO. The window NEVER places early.
     */
    fun cancelWindowOutcome(shownAtMs: Long, nowMs: Long, cancelled: Boolean): Boolean {
        if (cancelled) return false
        return nowMs - shownAtMs >= CardMachine.AUTO_PLACE_CANCEL_WINDOW_MS
    }
}

/**
 * The over-budget ask (FR-43, AC-S3.3): the card names the DELTA (the amount over the
 * owner's limit); timeout = no order.
 */
object OverBudgetCard {
    /** Integer cents (ART-5's integer law — never a float in a money decision). */
    data class Ask(val totalCents: Long, val limitCents: Long, val deltaCents: Long)

    fun ask(totalCents: Long, limitCents: Long): Ask =
        Ask(totalCents, limitCents, totalCents - limitCents)

    fun render(ask: Ask): String {
        val euros = { cents: Long -> "%.2f".format(cents / 100.0) }
        return "the total " + euros(ask.totalCents) + " is " + euros(ask.deltaCents) +
            " over your " + euros(ask.limitCents) + " limit — approve the extra, or the order is not placed"
    }

    /** The timeout law: no answer = no order (never a place). */
    fun timeoutOutcome(): Boolean = false
}
