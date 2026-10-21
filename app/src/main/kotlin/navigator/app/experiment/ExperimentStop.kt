package navigator.app.experiment

/**
 * PHASE_VEXP9 — the monitor-stop state and routing (the R3 card-screen safeguard, and
 * the general attended-run stop).
 *
 * The stop itself is the product's lawful ⏹ path: [invoke] calls the same
 * `OverlayManager.onStopTask` callback the corner log's ⏹ drives, which cancels the
 * active run through the service's `TaskCancelFlag` (USER_CANCEL at the next step
 * boundary). This object only carries the PROVENANCE — whether the stop came from the
 * experiment monitor — so the evidence can record `MONITOR_CARD_STOP` instead of
 * `OWNER_CANCEL`.
 *
 * A monitor stop is not a failure: the run is a success up to the safe boundary.
 */
object ExperimentStop {

    /** The experiment-only broadcast action (debug builds only; see OverlayDebugReceiver). */
    const val ACTION = "navigator.app.action.EXPERIMENT_STOP_TASK"

    @Volatile
    private var monitorStop = false

    /** Called at every task start: a previous run's monitor stop never leaks forward. */
    fun reset() {
        monitorStop = false
    }

    /** Marks the stop's provenance before invoking the product stop path. */
    fun noteMonitorStop() {
        monitorStop = true
    }

    fun isMonitorStop(): Boolean = monitorStop

    /**
     * Invokes the manager's stop callback. True when the callback was wired (the service
     * and the overlay are connected); false is a truthfully reported no-op.
     */
    fun invoke(stop: (() -> Unit)?): Boolean {
        stop ?: return false
        stop()
        return true
    }
}
