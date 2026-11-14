package navigator.app.harness.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import navigator.app.harness.webview.events.PipelineEvent
import navigator.app.harness.webview.events.PipelineEventBus

/**
 * V2 S1 — the lane's visible entry + status screen (AC-S1.6's "started from visible UI").
 * SCAFFOLDING: the V2 home screen replaces this in Sprint S4; until then it is the adb-
 * and-owner-facing way to start a lane task and watch the pipeline event stream. Kept
 * out of release builds the same way the probe harness is (CHG-01's gate scope).
 *
 * adb:
 *   am start -n navigator.app/.harness.webview.LaneDebugActivity --es goal "<goal>" --ez autostart true
 */
class LaneDebugActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var goalInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val listener: (PipelineEvent) -> Unit = { event -> runOnUiThread { appendLine(render(event)) } }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        goalInput = EditText(this).apply {
            hint = "Goal (English)"
            setSingleLine(false)
        }
        startButton = Button(this).apply { text = "Start lane task" }
        stopButton = Button(this).apply { text = "Stop" }
        status = TextView(this).apply { text = "lane idle" }
        log = TextView(this).apply { text = "" }
        box.addView(goalInput)
        box.addView(startButton)
        box.addView(stopButton)
        box.addView(status)
        box.addView(log)
        root.addView(box)
        setContentView(root)

        startButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                status.text = "grant the overlay permission (Settings → Display over other apps) first"
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                return@setOnClickListener
            }
            val goal = goalInput.text.toString().trim()
            if (goal.isEmpty()) {
                status.text = "enter a goal first"
                return@setOnClickListener
            }
            startService(Intent(this, LaneService::class.java).apply {
                action = "navigator.app.lane.START_TASK"
                putExtra("goal", goal)
            })
            status.text = "task started"
        }
        stopButton.setOnClickListener {
            startService(Intent(this, LaneService::class.java).apply {
                action = "navigator.app.lane.STOP"
            })
            status.text = "stop requested"
        }

        val goal = intent?.getStringExtra("goal")
        val autostart = intent?.getBooleanExtra("autostart", false) ?: false
        if (goal != null) goalInput.setText(goal)
        if (autostart && goal != null) {
            startService(Intent(this, LaneService::class.java).apply {
                action = "navigator.app.lane.START_TASK"
                putExtra("goal", goal)
            })
            status.text = "task started (autostart)"
        }
    }

    override fun onResume() {
        super.onResume()
        subscribe()
    }

    override fun onPause() {
        unsubscribe()
        super.onPause()
    }

    @SuppressLint("SetTextI18n")
    private fun subscribe() {
        // The application-scoped bus is the same instance the service's controller emits to.
        LaneBusHolder.bus.subscribe(listener)
    }

    private fun unsubscribe() {
        // subscribing a fresh lambda each resume leaks; keep the same listener instance
        LaneBusHolder.bus.unsubscribe(listener)
    }

    @SuppressLint("SetTextI18n")
    private fun appendLine(line: String) {
        log.text = (if (log.text.length > 8000) "" else log.text.toString()) + line + "\n"
    }

    private fun render(event: PipelineEvent): String = when (event) {
        is PipelineEvent.Proposed ->
            "step ${event.stepIndex}: PROPOSED ${event.action} on [${event.elementId}] \"${event.elementLabel}\" — ${event.expectedEffect}"
        is PipelineEvent.Judged ->
            "step ${event.stepIndex}: JUDGED ${if (event.accepted) "accept" else "refuse"} — ${event.reason}"
        is PipelineEvent.Refused ->
            "step ${event.stepIndex}: REFUSED (${event.stage}) — ${event.reason}"
        is PipelineEvent.Executed ->
            "step ${event.stepIndex}: EXECUTED ${event.action} on \"${event.elementLabel}\""
        is PipelineEvent.Verified ->
            "step ${event.stepIndex}: VERIFIED ${if (event.confirmed) "✓" else "✗ no effect"} — ${event.evidence}"
    }
}

/**
 * The S1 application-scoped event bus holder (the S4 UI replaces it with the real
 * pipeline stream plumbing).
 */
object LaneBusHolder {
    val bus = PipelineEventBus()
}
