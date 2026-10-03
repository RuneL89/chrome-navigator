package navigator.app.observe

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Debug capture harness UI (used once, on-device, to produce the real fixtures).
 *
 * Drive it either by tapping the buttons, or over adb —
 *
 * ```
 * adb shell am start -n navigator.app/navigator.app.observe.CaptureActivity \
 *   --es action capture --es site google-search \
 *   --es url "https://…" --es title "…"
 * adb shell am start -n navigator.app/navigator.app.observe.CaptureActivity --es action list
 * ```
 *
 * Capture is a no-op unless the app is debuggable and the observation service is enabled.
 */
class CaptureActivity : ComponentActivity() {

    private var status by mutableStateOf("Capture harness ready.")
    private var output by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                HarnessUi(
                    status = status,
                    output = output,
                    onCapture = { capture(lastSite, lastUrl, lastTitle) },
                    onList = { listCaptures() },
                )
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_CAPTURE -> capture(
                intent.getStringExtra(EXTRA_SITE) ?: "site",
                intent.getStringExtra(EXTRA_URL) ?: "",
                intent.getStringExtra(EXTRA_TITLE) ?: "",
            )
            ACTION_LIST -> listCaptures()
        }
    }

    private fun capture(site: String, url: String, title: String) {
        lastSite = site
        lastUrl = url
        lastTitle = title
        if (!CaptureMode.isDebuggable(this)) {
            status = "Capture disabled: not a debug build."
            return
        }
        val file = CaptureMode.capture(this, site, url, title)
        status = if (file != null) {
            "Captured ${file.name} (${file.length()} bytes)."
        } else {
            "Capture failed: service not connected or unusable tree."
        }
    }

    private fun listCaptures() {
        val captures = CaptureMode.captures(this)
        status = "${captures.size} capture(s)."
        output = captures.joinToString("\n") { it.name }
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_CAPTURE = "capture"
        const val ACTION_LIST = "list"
        const val EXTRA_SITE = "site"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        // Last-driven values so the on-screen button repeats the latest adb capture.
        private var lastSite = "site"
        private var lastUrl = ""
        private var lastTitle = ""
    }
}

@androidx.compose.runtime.Composable
private fun HarnessUi(status: String, output: String, onCapture: () -> Unit, onList: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Chrome Navigator — capture harness", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onCapture) { Text("Capture current Chrome state") }
        Button(onClick = onList) { Text("List captures") }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (output.isNotBlank()) Text(output, style = MaterialTheme.typography.bodySmall)
    }
}
