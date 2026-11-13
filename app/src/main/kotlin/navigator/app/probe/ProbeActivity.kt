package navigator.app.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

/**
 * V2 FEASIBILITY PROBE HARNESS (docs/V2_FEASIBILITY.md §9). DEBUG TOOLING — not part of
 * the product; every probe logs under the `NavigatorProbe` tag and writes the same line
 * to `filesDir/probe-<name>.txt` for adb pull.
 *
 * Usage (device):
 *   adb shell am start -n navigator.app/.probe.ProbeActivity --es probe trust
 *   adb shell am start -n navigator.app/.probe.ProbeActivity --es probe site --es url <URL>
 *   adb shell am start -n navigator.app/.probe.ProbeActivity --es probe cookies --es url <URL>
 *
 * The trust probe: the page records `isTrusted` per event; this activity fires a
 * PROGRAMMATIC dispatchTouchEvent after load, and the owner/agent may also send a real
 * `adb shell input tap` at the logged button rect, so the log shows both arms:
 *   PROBE_TRUST programmatic=[...]        (isTrusted expected false)
 *   PROBE_TRUST external=[...]            (a real injected input: isTrusted expected true)
 * Results are polled every second and logged as `PROBE_STATE ...` snapshots.
 */
class ProbeActivity : Activity() {

    private companion object {
        const val TAG = "NavigatorProbe"
    }

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var probe = "trust"
    private var url = ""
    private var programmaticFired = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probe = intent.getStringExtra("probe") ?: "trust"
        url = intent.getStringExtra("url") ?: ""
        Log.i(TAG, "start probe=$probe url=$url")

        val root = FrameLayout(this)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i(TAG, "page finished ${view?.url}")
                    if (probe == "cookies") logCookieNames(view?.url ?: url.orEmpty())
                    view?.evaluateJavascript(snapshotJs()) { s ->
                        Log.i(TAG, "PROBE_PAGE $s")
                        view.postDelayed({ maybeFireProgrammaticTap() }, 600)
                    }
                }
            }
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.argb(200, 0, 0, 0))
        }
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                120,
                android.view.Gravity.BOTTOM,
            ),
        )
        setContentView(root)

        when (probe) {
            "trust", "trustjs" -> webView.loadUrl("file:///android_asset/probe/probe.html")
            "site", "cookies" -> if (url.isNotBlank()) webView.loadUrl(url) else finish()
            else -> finish()
        }
        startPolling()
    }

    /** The programmatic arm: raw MotionEvents through WebView.dispatchTouchEvent. */
    private fun maybeFireProgrammaticTap() {
        if (probe == "trustjs") {
            // Negative control (CHG-02): a JS element.click() must show isTrusted=false.
            if (programmaticFired) return
            programmaticFired = true
            webView.postDelayed({
                webView.evaluateJavascript(
                    "document.getElementById('t').click(); 'js-click-fired'",
                ) { r -> Log.i(TAG, "PROBE_TRUST js-click result=$r") }
            }, 400)
            return
        }
        if (probe != "trust" || programmaticFired) return
        programmaticFired = true
        webView.postDelayed({
            val loc = IntArray(2)
            webView.getLocationOnScreen(loc)
            // The button sits at page margin 16 + button box; compute its centre from the
            // page's own layout via JS for exactness, then dispatch at that view point.
            webView.evaluateJavascript(
                "(function(){var r=document.getElementById('t').getBoundingClientRect();" +
                    "return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2," +
                    "iw:window.innerWidth,ih:window.innerHeight});})()",
            ) { s ->
                // evaluateJavascript returns the string JSON-ESCAPED ("{\"x\":…}"); unescape
                // before parsing, or every regex silently falls back to the defaults.
                val raw = s.replace("\\\"", "\"")
                val cssX = Regex("\"x\":([0-9.]+)").find(raw)?.groupValues?.get(1)?.toFloatOrNull() ?: 110f
                val cssY = Regex("\"y\":([0-9.]+)").find(raw)?.groupValues?.get(1)?.toFloatOrNull() ?: 100f
                val iw = Regex("\"iw\":([0-9.]+)").find(raw)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                // CSS px -> view px: the page is laid out at devicePixelRatio = viewWidth/innerWidth.
                val scale = if (iw > 0f) webView.width / iw else 1f
                val viewX = cssX * scale
                val viewY = cssY * scale
                val screenX = (loc[0] + viewX).toInt()
                val screenY = (loc[1] + viewY).toInt()
                Log.i(TAG, "PROBE_BUTTON css=($cssX,$cssY) scale=$scale view=($viewX,$viewY) screen=($screenX,$screenY)")
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, viewX, viewY, 0)
                val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, viewX, viewY, 0)
                webView.dispatchTouchEvent(down)
                webView.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
                Log.i(TAG, "PROBE_TRUST programmatic dispatched at view=($viewX,$viewY)")
            }
        }, 400)
    }

    /** Cookie probe: logs the app-private WebView jar's cookie NAMES (values never logged). */
    private fun logCookieNames(pageUrl: String) {
        try {
            val cookies = android.webkit.CookieManager.getInstance().getCookie(pageUrl).orEmpty()
            val names = cookies.split(';').map { it.trim().substringBefore('=') }.filter { it.isNotBlank() }
            Log.i(TAG, "PROBE_COOKIES url=${pageUrl.take(60)} count=${names.size} names=$names")
        } catch (e: Exception) {
            Log.w(TAG, "PROBE_COOKIES failed: ${e.message}")
        }
    }

    /** The snapshot JS: the probe page's own richer snapshot, or a generic page snapshot. */
    private fun snapshotJs(): String = if (probe == "trust" || probe == "trustjs") {
        "(function(){var s=window.__snapshot?window.__snapshot():'{}';" +
            "try{return s}catch(e){return '{}'}})()"
    } else {
        "(function(){return JSON.stringify({title:document.title,url:location.href," +
            "text:(document.body?document.body.innerText.slice(0,700):''), " +
            "forms:document.forms.length, iframes:document.querySelectorAll('iframe').length, " +
            "inputs:document.querySelectorAll('input,textarea').length});})()"
    }
    private fun startPolling() {
        val start = SystemClock.uptimeMillis()
        webView.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing) return
                webView.evaluateJavascript(snapshotJs()) { s ->
                    val line = "PROBE_STATE t=${(SystemClock.uptimeMillis() - start) / 1000}s $s"
                    Log.i(TAG, line)
                    status.text = line.take(400)
                    appendToFile(line)
                }
                webView.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun appendToFile(line: String) {
        try {
            val f = java.io.File(filesDir, "probe-$probe.txt")
            f.appendText(line + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "probe file write failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }
}
