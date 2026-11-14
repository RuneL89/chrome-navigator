package navigator.app.harness.webview

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import navigator.app.harness.webview.events.PipelineEventBus

/**
 * V2 S1 — the off-screen lane service (FR-23, AC-S1.6): the P0-proven hosting recipe,
 * now the lane's home. A phone-sized `TYPE_APPLICATION_OVERLAY` window positioned FULLY
 * OFF the display (x = -width) hosts the task WebView; a `specialUse` foreground service
 * keeps the lane alive while another app is in the foreground (the user sees nothing,
 * the page renders at full rate — prototype #5). The WebView is created when a task
 * starts and destroyed when it ends ([LaneController]); the service stops itself when
 * the lane goes idle.
 *
 * Started from visible UI ([LaneDebugActivity] in S1; the V2 home in Sprint S4).
 * Requires `SYSTEM_ALERT_WINDOW` (the consent popup is the S3 settings flow).
 */
class LaneService : Service() {

    private companion object {
        const val TAG = "NavigatorLane"
        const val CHANNEL = "navigator-lane"
        const val NOTIF_ID = 4811
        const val ACTION_START_TASK = "navigator.app.lane.START_TASK"
        const val ACTION_STOP = "navigator.app.lane.STOP"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bus = LaneBusHolder.bus
    private var controller: LaneController? = null
    private var wm: WindowManager? = null
    private var windowView: android.view.View? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        controller = LaneController(
            context = this,
            scope = scope,
            bus = bus,
            evidenceDir = java.io.File(filesDir, "lane-evidence"),
            windowHost = object : LaneWindowHost {
                override fun attach(view: android.view.View) = attachToWindow(view)
                override fun detach(view: android.view.View) {
                    try {
                        wm?.removeView(view)
                    } catch (_: Exception) {
                    }
                }
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TASK -> {
                val goal = intent.getStringExtra("goal") ?: ""
                if (goal.isBlank()) {
                    Log.w(TAG, "a blank goal refuses to run")
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureWindowManager()
                val started = controller?.startTask(goal) { terminal ->
                    Log.i(TAG, "task terminal: $terminal")
                    stopSelf() // the WebView died with the task; the FGS stops when idle
                } ?: false
                if (!started) stopSelf()
            }

            ACTION_STOP -> {
                controller?.stop("owner stop")
                stopSelf()
            }

            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /** The overlay window the task WebView attaches into (created on first attach). */
    private fun attachToWindow(view: android.view.View) {
        ensureWindowManager()
        val bounds = wm?.maximumWindowMetrics?.bounds
        val width = bounds?.width() ?: 720
        val height = bounds?.height() ?: 1520
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // OFF the display: the renderer sees a real phone-sized surface, the user sees
            // nothing (P0 prototype #5's exact recipe).
            x = -width
            y = 0
        }
        try {
            wm?.addView(view, params)
            windowView = view
            Log.i(TAG, "lane overlay added ${width}x$height at x=-$width")
        } catch (e: Exception) {
            Log.e(TAG, "lane overlay add FAILED: ${e.message}")
        }
    }

    private fun ensureWindowManager() {
        if (wm == null) {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Navigator lane", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Navigator working")
            .setContentText("A task is running in the background lane")
            .build()
        startForeground(
            NOTIF_ID,
            n,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller?.stop("service destroyed")
        controller = null
        scope.cancel()
        windowView?.let {
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
        }
        windowView = null
        Log.i(TAG, "lane service destroyed")
        super.onDestroy()
    }
}
