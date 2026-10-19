package navigator.app.overlay

import android.app.Notification
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import navigator.app.loop.TaskCancelFlag
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PHASE_VEXP6 WI4 — the notification's lawful stop (JVM, Robolectric; the
 * VisionLoopPrefRoundTripTest precedent): the persistent notification carries a
 * standard "Stop task" action ONLY while the run state is WORKING, and
 * [NavigatorService.ACTION_STOP_TASK] cancels through the SAME [TaskCancelFlag] the
 * corner log's ⏹ control drives. This is the run-4 monitoring fix: the ✕ can hide the
 * corner log (and its stop control), so the notification must offer a stop path.
 *
 * No device, no emulator, no adb — the service is a Robolectric instance.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NavigatorServiceNotificationTest {

    private fun state(name: String): Int =
        NavigatorService::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(null)

    private fun build(service: NavigatorService, state: Int): Notification {
        val method = NavigatorService::class.java.getDeclaredMethod(
            "buildNotification", Int::class.javaPrimitiveType, String::class.java,
        )
        method.isAccessible = true
        return method.invoke(service, state, "") as Notification
    }

    private fun actionTitles(notification: Notification): List<String> =
        notification.actions?.map { it.title?.toString().orEmpty() }.orEmpty()

    private fun cancelFlag(service: NavigatorService): TaskCancelFlag =
        NavigatorService::class.java.getDeclaredField("taskCancelFlag").apply { isAccessible = true }
            .get(service) as TaskCancelFlag

    @Test
    fun `the Stop task action rides only the working notification`() {
        val controller = Robolectric.buildService(NavigatorService::class.java)
        val service = controller.create().get()
        try {
            val ready = build(service, state("NOTIFICATION_READY"))
            assertEquals(listOf("New task"), actionTitles(ready), "READY keeps exactly the New task action")
            val working = build(service, state("NOTIFICATION_WORKING"))
            assertEquals(
                listOf("New task", "Stop task"),
                actionTitles(working),
                "WORKING carries the standard Stop task action after New task",
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `the stop action cancels the active task through the shared flag`() {
        val controller = Robolectric.buildService(NavigatorService::class.java)
        val service = controller.create().get()
        try {
            val flag = cancelFlag(service)
            flag.beginTask()
            assertFalse(flag.isCancelled(), "a fresh task starts uncancelled")
            service.onStartCommand(
                Intent(service, NavigatorService::class.java).setAction(NavigatorService.ACTION_STOP_TASK),
                0,
                1,
            )
            assertTrue(flag.isCancelled(), "ACTION_STOP_TASK cancels through the SAME TaskCancelFlag")
        } finally {
            controller.destroy()
        }
    }
}
