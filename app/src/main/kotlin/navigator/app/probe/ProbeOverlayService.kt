package navigator.app.probe

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
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * V2 FEASIBILITY PROBE — OFF-SCREEN WEBVIEW SERVICE (docs/V2_FEASIBILITY.md §9 #5).
 * DEBUG TOOLING, not product code.
 *
 * Hosts a phone-sized WebView in a `TYPE_APPLICATION_OVERLAY` window positioned OFF the
 * display (x = -width), under a `specialUse` foreground service, and logs page-side
 * counters every second (setInterval ticks, rAF frames, IntersectionObserver lazy items).
 * The point: measure whether page timers and lazy loading keep running while another app
 * is in the foreground and the user uses the phone.
 *
 * Usage:
 *   adb shell am start-foreground-service -n navigator.app/.probe.ProbeOverlayService \
 *     --ei seconds 60
 *   (optionally --es url <URL>; default is the local probe page)
 * Then bring another app to the front and `adb logcat -s NavigatorProbe`.
 * Requires `appops set navigator.app SYSTEM_ALERT_WINDOW allow`.
 */
class ProbeOverlayService : Service() {

    private companion object {
        const val TAG = "NavigatorProbe"
        const val CHANNEL = "navigator-probe"
        const val NOTIF_ID = 4711
        const val WIDTH = 720
        const val HEIGHT = 1520
    }

    private var webView: WebView? = null
    private var wm: WindowManager? = null
    private var durationSec = 60
    private var startedAt = 0L
    private var lastTicks = -1
    private var lastLazy = -1
    private var urlLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i(TAG, "PROBE_OFFSCREEN page finished ${view?.url}")
                }
            }
        }
        webView = wv
        val params = WindowManager.LayoutParams(
            WIDTH,
            HEIGHT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // OFF the display: the renderer sees a real phone-sized surface, the user sees
            // nothing. x = -WIDTH puts it fully to the left of the visible area.
            x = -WIDTH
            y = 0
        }
        try {
            wm?.addView(wv, params)
            Log.i(TAG, "PROBE_OFFSCREEN overlay added ${WIDTH}x$HEIGHT at x=-$WIDTH")
        } catch (e: Exception) {
            Log.e(TAG, "PROBE_OFFSCREEN overlay add FAILED: ${e.message}")
        }
        startedAt = SystemClock.uptimeMillis()
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        durationSec = intent?.getIntExtra("seconds", 60) ?: 60
        val requested = intent?.getStringExtra("url")
        val target = requested ?: if (!urlLoaded) "file:///android_asset/probe/probe.html" else null
        if (target != null) {
            urlLoaded = true
            webView?.loadUrl(target)
            Log.i(TAG, "PROBE_OFFSCREEN loading $target")
        }
        return START_NOT_STICKY
    }

    private fun startPolling() {
        val wv = webView ?: return
        wv.postDelayed(object : Runnable {
            override fun run() {
                val elapsed = (SystemClock.uptimeMillis() - startedAt) / 1000
                wv.evaluateJavascript(
                    "(function(){var s=window.__snapshot?window.__snapshot():'{}';" +
                        "try{return s}catch(e){return '{}'}})()",
                ) { s ->
                    // evaluateJavascript escapes the returned JSON string; unescape first.
                    val raw = s.replace("\\\"", "\"")
                    val ticks = Regex("\"ticks\":([0-9]+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    val raf = Regex("\"raf\":([0-9]+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    val lazy = Regex("\"lazy\":([0-9]+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    val dTicks = if (lastTicks >= 0) ticks - lastTicks else 0
                    lastTicks = ticks
                    lastLazy = lazy
                    val line = "PROBE_OFFSCREEN t=${elapsed}s ticks=$ticks (+$dTicks/s) raf=$raf lazy=$lazy"
                    Log.i(TAG, line)
                    try {
                        java.io.File(filesDir, "probe-offscreen.txt").appendText(line + "\n")
                    } catch (_: Exception) {
                    }
                    if (elapsed >= durationSec) {
                        Log.i(TAG, "PROBE_OFFSCREEN DONE ticks=$ticks raf=$raf lazy=$lazy")
                        stopSelf()
                        return@evaluateJavascript
                    }
                }
                if (elapsed < durationSec) wv.postDelayed(this, 1000)
            }
        }, 1500)
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Navigator probe", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Probe running")
            .setContentText("Off-screen WebView probe")
            .build()
        startForeground(
            NOTIF_ID,
            n,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        webView?.let {
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
            it.removeAllViews()
            it.destroy()
        }
        webView = null
        Log.i(TAG, "PROBE_OFFSCREEN service destroyed")
        super.onDestroy()
    }
}
