package navigator.app.overlay

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import navigator.app.observe.NavigatorAccessibilityService

/**
 * Shared machinery for the THIN Gate 9.1-9.4 JUnit wrappers.
 *
 * EVIDENCE PATH NOTE (one line, per the restructure): the gates' evidence comes from
 * the IN-APP debug runner — `adb shell am broadcast -a navigator.app.action.GATE91..94`
 * → `logcat -s NavigatorGates` — because the instrumented shell is platform-blocked on
 * the API 35 emulator (the a11y service binds system-wide but the in-process handshake
 * never completes under `am instrument`; measured, see `.state/phase-9-status.json`).
 * These wrappers exist for devices where the handshake works; the assertion logic
 * itself lives ONCE in [GateScenarios] (main source).
 */
object OverlayGateSupport {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    /** The enable/disable machinery's logcat tag. */
    private const val TAG = "NavigatorGateSupport"

    const val SERVICE_FLAT_SHORT = "navigator.app/.observe.NavigatorAccessibilityService"

    /** Runs a shell command through UiAutomation and drains its output stream. */
    fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        try {
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                .bufferedReader()
                .readText()
                .trim()
        } finally {
            descriptor.close()
        }
    }

    /**
     * Ensures the observation service is enabled and waits for its overlay manager.
     *
     * THE MEASURED PLATFORM LAW (orchestrator runs, API 35 emulator; final 2026-10-09):
     *  - `am instrument` FORCE-STOPS navigator.app, which NULLS
     *    enabled_accessibility_services and unbinds the service at every session start;
     *  - a settings write through the UiAutomation identity (`executeShellCommand`)
     *    LANDS IN THE PROVIDER (adb readback confirms it set) but AMS NEVER REBINDS
     *    from it — proven absolute across 3 runs / 24+ attempts, INCLUDING a real
     *    ''→value transition;
     *  - the IDENTICAL command string from a plain adb shell identity REBINDS in ≤6s —
     *    live-proven (dumpsys bound=1) even while the instrumentation runs.
     *
     * Therefore this helper NEVER clears a set value and performs NO dance: clearing a
     * working external enable KILLS the service and its own UiAutomation re-put never
     * re-binds (the measured failure mode). Connected runs that start with the setting
     * nulled need an EXTERNAL adb settings-put loop DURING the run (the orchestrator's
     * run book); the error below names the proven trigger.
     */
    fun enableAccessibilityService() {
        // Already connected (warm service left on by a previous test class): no-op.
        if (NavigatorAccessibilityService.instance?.overlayManager != null) return
        val current = shell("settings get secure enabled_accessibility_services")
        if (!current.contains("navigator.app")) {
            val merged = if (current.isNullOrBlank() || current == "null") {
                SERVICE_FLAT_SHORT
            } else {
                "$current:$SERVICE_FLAT_SHORT"
            }
            shell("settings put secure enabled_accessibility_services $merged")
            Log.i(TAG, "enable: settings put '$merged' (readback lacked the service)")
        }
        shell("settings put secure accessibility_enabled 1")
        Log.i(TAG, "enable: accessibility_enabled 1; polling for the bind (external adb puts are the proven rebind trigger)")
        // NO dance, NO clearing under any circumstance (see the KDoc law): poll only.
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (NavigatorAccessibilityService.instance?.overlayManager != null) return
            Thread.sleep(250)
        }
        if (NavigatorAccessibilityService.instance?.overlayManager != null) return
        val readback = shell("settings get secure enabled_accessibility_services")
        error(
            "the service did not connect within 60s — if the settings readback contains " +
                "the service but no bind occurred, an external `adb shell settings put " +
                "secure enabled_accessibility_services " + SERVICE_FLAT_SHORT + "` during " +
                "the run is the proven trigger (AMS ignores UiAutomation-identity writes; " +
                "measured 2026-10-09); live readback='$readback'",
        )
    }

    /**
     * Disables the observation service. NOT called from per-class @After teardowns:
     * every disable forces the next class to re-pay the bind; the connected run
     * uninstalls the app at the end (auto-clearing the setting). Call only from an
     * explicit suite-end teardown if one is ever needed.
     */
    fun disableAccessibilityService() {
        shell("settings put secure enabled_accessibility_services \"\"")
        shell("settings put secure accessibility_enabled 0")
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (NavigatorAccessibilityService.instance == null) return
            Thread.sleep(250)
        }
    }

    /** The connected a11y service (the scenarios take the SERVICE, not the manager). */
    fun a11yService(): NavigatorAccessibilityService =
        NavigatorAccessibilityService.instance
            ?: error("no observation service (enable the accessibility service first)")

    /** Sends one debug overlay command broadcast (the window-command surface). */
    fun broadcast(action: String, extras: (Intent.() -> Unit)? = null) {
        val intent = Intent(action).setClassName(context, OverlayDebugReceiver::class.java.name)
        extras?.invoke(intent)
        context.sendBroadcast(intent)
    }
}
