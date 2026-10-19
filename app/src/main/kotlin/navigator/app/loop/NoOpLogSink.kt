package navigator.app.loop

import navigator.core.log.DecisionLogRow
import navigator.core.log.ExitCheckRow

/**
 * The no-op [DecisionLogSink] (PHASE_VEXP3 — the training-data program RETIRED, owner
 * directive 2026-10-19: "remove that noise completely"). Every write is a no-op: no
 * decision rows, no exit-check rows, no terminal rows are persisted anywhere, so the
 * legacy [NavigationLoop] keeps compiling and running with ZERO persistence (the Room
 * store, the shadow-result companions, and the RLCD export were deleted with the
 * program). `writeTerminal` keeps the interface's default no-op. Vision runs never
 * touch this seam at all (their evidence is the events.log + report store).
 */
object NoOpLogSink : DecisionLogSink {
    override suspend fun write(row: DecisionLogRow) {}

    override suspend fun writeExitCheck(row: ExitCheckRow) {}
}
