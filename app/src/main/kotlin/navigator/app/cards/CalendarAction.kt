package navigator.app.cards

/**
 * V2 S3 — the calendar law (FR-57): the owner's OWN calendar inserts are auto-confirmed
 * with a one-minute undo window; INVITES never auto-confirm — they draft like email
 * (the draft-card law). The Android insert intent / CalendarContract wiring rides this
 * machine (the wiring lands with the calendar task types).
 */
class CalendarAction private constructor(
    val title: String,
    val kind: Kind,
    private val confirmedAtMs: Long,
    val startsAtMs: Long,
) {
    enum class Kind { OWN_INSERT, INVITE }
    enum class State { CONFIRMED, DRAFT, UNDONE }

    var state: State = if (kind == Kind.OWN_INSERT) State.CONFIRMED else State.DRAFT
        private set

    /** The undo window: exactly one minute from the confirmation. */
    fun undoPlaces(atMs: Long): Boolean =
        state == State.CONFIRMED && atMs - confirmedAtMs <= UNDO_WINDOW_MS

    /** The owner's undo inside the window: the insert is removed. */
    fun undo(atMs: Long): CalendarAction {
        check(undoPlaces(atMs)) { "the undo window has closed" }
        state = State.UNDONE
        return this
    }

    companion object {
        /** FR-57's ratified undo window: 1 minute. */
        const val UNDO_WINDOW_MS = 60_000L

        fun insert(title: String, startsAtMs: Long, nowMs: Long = System.currentTimeMillis()): CalendarAction =
            CalendarAction(title, Kind.OWN_INSERT, nowMs, startsAtMs)

        fun invite(title: String, nowMs: Long = System.currentTimeMillis()): CalendarAction =
            CalendarAction(title, Kind.INVITE, nowMs, startsAtMs = 0)
    }
}
