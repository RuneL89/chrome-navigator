package navigator.app.loop

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The product task's cancellation flag (UAT 9.1 close-out — the ⏹ control): an atomic
 * boolean consumed by [NavigationLoop.run]'s `isCancelled` seam. The loop polls it at
 * each step boundary and exits USER_CANCEL (vision 04 §3's lawful owner exit); the
 * flag only ever STOPS the loop — it never decides or acts (ART-2/ART-4 intact).
 *
 * Law: [beginTask] RESETS the flag at every task start — a cancel requested while no
 * task was running must never kill the NEXT task (the UI hides ⏹ when idle; this is
 * the belt behind that). [cancelActive] is idempotent. Pure JVM — pinned by
 * `TaskCancelFlagTest`.
 */
class TaskCancelFlag {

    private val cancelled = AtomicBoolean(false)

    /** Called at every task start: the new task begins uncancelled. */
    fun beginTask() {
        cancelled.set(false)
    }

    /** The owner's ⏹: the active task exits USER_CANCEL at its next step boundary. */
    fun cancelActive() {
        cancelled.set(true)
    }

    /** The loop's seam read. */
    fun isCancelled(): Boolean = cancelled.get()
}
