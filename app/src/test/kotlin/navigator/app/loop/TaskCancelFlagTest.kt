package navigator.app.loop

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ⏹ control's cancellation-flag semantics (UAT 9.1 close-out): the flag the
 * NavigatorService hands to [NavigationLoop.run]'s `isCancelled` seam. Pure JVM — the
 * law pinned here: a cancel requested while NO task was running never kills the NEXT
 * task (beginTask resets), and cancelActive is idempotent.
 */
class TaskCancelFlagTest {

    @Test
    fun `a fresh flag is uncancelled`() {
        assertFalse(TaskCancelFlag().isCancelled())
    }

    @Test
    fun `cancelActive marks the active task cancelled`() {
        val flag = TaskCancelFlag()
        flag.beginTask()
        flag.cancelActive()
        assertTrue(flag.isCancelled())
    }

    @Test
    fun `beginTask resets a cancelled flag - a stale cancel never kills the next task`() {
        val flag = TaskCancelFlag()
        flag.cancelActive() // no task running (the UI hides ⏹; the belt behind that)
        assertTrue(flag.isCancelled())
        flag.beginTask() // the next task starts
        assertFalse(flag.isCancelled(), "the next task must begin uncancelled")
    }

    @Test
    fun `cancelActive is idempotent`() {
        val flag = TaskCancelFlag()
        flag.beginTask()
        flag.cancelActive()
        flag.cancelActive()
        assertTrue(flag.isCancelled())
    }
}
