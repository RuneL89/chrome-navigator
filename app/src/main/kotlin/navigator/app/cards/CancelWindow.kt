package navigator.app.cards

/**
 * V2 S3 — the auto-place cancel window's law (FR-42): BELOW the owner's limit the
 * purchase places after a 15 s window in which the owner may cancel. SILENCE IS YES in
 * this window (the owner ratified the auto-place); a cancel is a NO. This is the one
 * place the timeout-NO law does not apply — and it is scoped to exactly this window.
 */
class CancelWindow(private val nowMs: () -> Long = System::currentTimeMillis) {
    val shownAtMs: Long = nowMs()

    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() {
        cancelled = true
    }

    fun elapsed(): Boolean = nowMs() - shownAtMs >= CardMachine.AUTO_PLACE_CANCEL_WINDOW_MS

    /** The window's outcome: silence past the window places; a cancel never does. */
    fun places(): Boolean = !cancelled && elapsed()
}
