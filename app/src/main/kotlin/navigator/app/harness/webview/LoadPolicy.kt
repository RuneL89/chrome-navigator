package navigator.app.harness.webview

/**
 * V2 S1 — the lane's fixed dwell/timeout navigation policy (FR-09, AC-S1.11): the
 * BUG-01 lesson turned into law. The contested Red Bull verdict came from unfixed
 * dwell/timeout behavior (one run screenshotted a blank shell, the next loaded fully).
 * The lane loads with ONE policy: commit-visible, then a fixed settle dwell, then a
 * hard page timeout with retry-once for transient transport errors
 * (`ERR_CONNECTION_ABORTED` was measured on the emulator's first Wikipedia attempt).
 * Numbers are law here (tunable by amendment), never per-run whim.
 */
object LoadPolicy {
    /** Wait after the page commits visible before extracting (SPA hydration room). */
    const val SETTLE_DWELL_MS = 2_500L

    /** Hard ceiling on one page load (commit + dwell included). */
    const val PAGE_TIMEOUT_MS = 20_000L

    /** Transient load errors retry ONCE, never more. */
    const val MAX_LOAD_ATTEMPTS = 2

    /** The abort/timeout family that earns the single retry (Android net error names). */
    private val TRANSIENT_ERRORS = setOf(
        "ERR_CONNECTION_ABORTED", "ERR_CONNECTION_RESET", "ERR_CONNECTION_TIMED_OUT",
        "ERR_TIMED_OUT", "ERR_NETWORK_CHANGED", "ERR_EMPTY_RESPONSE",
    )

    /** True when a load error text earns the single retry (pure, L1). */
    fun isTransient(errorText: String): Boolean {
        val upper = errorText.uppercase()
        return TRANSIENT_ERRORS.any { upper.contains(it) }
    }

    /** SPA route-change watch interval while a task runs (the loop also re-snapshots). */
    const val SPA_POLL_MS = 500L
}
