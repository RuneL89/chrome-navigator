package navigator.app.observe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug capture trigger for adb-driven fixture sessions: lets the capture fire while the
 * BROWSER stays in the foreground (the a11y window list only carries visible windows, so
 * the CaptureActivity-on-top flow cannot see the browser tree — the receiver path reads
 * the live active window instead, exactly like production observation).
 *
 * ```
 * adb shell am broadcast -a navigator.app.action.CAPTURE \
 *   -n navigator.app/.observe.CaptureReceiver \
 *   --es site google-search --es url "https://…" --es title "…"
 * adb shell am broadcast -a navigator.app.action.LIST -n navigator.app/.observe.CaptureReceiver
 * ```
 *
 * No-op unless the app is debuggable and the observation service is connected.
 * CaptureActivity remains the manual on-screen harness; this receiver is its adb twin.
 */
class CaptureReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CAPTURE -> {
                val site = intent.getStringExtra(EXTRA_SITE) ?: "site"
                val url = intent.getStringExtra(EXTRA_URL) ?: ""
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                val file = CaptureMode.capture(context, site, url, title)
                Log.i(
                    TAG,
                    if (file != null) "captured ${file.absolutePath} (${file.length()} bytes)"
                    else "capture failed (debuggable=${CaptureMode.isDebuggable(context)}, " +
                        "service=${NavigatorAccessibilityService.instance != null})",
                )
            }
            ACTION_LIST -> {
                val names = CaptureMode.captures(context).joinToString(", ") { it.name }
                Log.i(TAG, "captures: $names")
            }
        }
    }

    companion object {
        const val ACTION_CAPTURE = "navigator.app.action.CAPTURE"
        const val ACTION_LIST = "navigator.app.action.LIST"
        const val EXTRA_SITE = CaptureActivity.EXTRA_SITE
        const val EXTRA_URL = CaptureActivity.EXTRA_URL
        const val EXTRA_TITLE = CaptureActivity.EXTRA_TITLE
        private const val TAG = "CaptureReceiver"
    }
}
