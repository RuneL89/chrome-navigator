package navigator.app.harness.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import navigator.app.harness.webview.dom.DomSnapshot
import navigator.app.harness.webview.dom.ExtractJs
import kotlin.coroutines.resume

/**
 * V2 S1 — the WebView seam the lane loop talks to (FR-09/10/19/20/23). The loop runs on
 * `Dispatchers.Default`; every operation here hops to the main thread where the WebView
 * lives (the off-screen overlay window, hosted by [LaneService]).
 *
 * Laws carried here:
 * - Load policy: [LoadPolicy] — commit + fixed dwell + hard timeout, retry-once for
 *   transient errors (AC-S1.11's fixed policy).
 * - FR-10: `onCreateWindow` (target=_blank / window.open) is ADOPTED, never silently
 *   dropped — the child's target URL transfers to the lane's own WebView.
 * - FR-19: `onShowFileChooser` is REFUSED — the owner picks files (Sprint S5's card);
 *   the lane never auto-uploads.
 * - FR-20: downloads route to the system DownloadManager via the WebView's own listener.
 * - FR-22: the real WebView UA rides — nothing here touches the user-agent string.
 * - FR-18: cookies stay in the app-private jar; third-party cookies are refused
 *   (strictest default — a consented account session may relax it in a later sprint).
 */
interface LaneWebView {
    /** The underlying view — the lane window host puts this into the off-screen window. */
    val view: android.view.View

    /** Loads [url] under [LoadPolicy]; returns the settled URL. */
    suspend fun load(url: String): String

    suspend fun currentUrl(): String

    suspend fun snapshot(): DomSnapshot

    /** Runs [js] and returns the raw result text (the object's JSON). */
    suspend fun evaluate(js: String): String

    /** The FR-13 escalation: a trusted touch gesture at CSS coordinates. */
    suspend fun dispatchTrustedTouch(cssX: Int, cssY: Int)

    suspend fun goBack()

    fun destroy()
}

/** Observability hooks the controller/event bus consume (and L3 evidence pulls). */
interface LaneWebEvents {
    fun onPageEvent(line: String)
    fun onAdoptedPopup(url: String)
    fun onFileChooserRefused()
    fun onDownloadStarted(url: String)
}

class LaneWebViewFactory(
    private val context: Context,
    private val events: LaneWebEvents,
) {
    @SuppressLint("SetJavaScriptEnabled")
    fun create(): LaneWebView {
        val cookieManager = CookieManager.getInstance()
        val web = MainThreadWebView(context, events)
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(web.webView, false)
        cookieManager.flush()
        return web
    }
}

private class MainThreadWebView(
    context: Context,
    private val events: LaneWebEvents,
) : LaneWebView {

    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // FR-22: the UA string is NEVER touched — the real WebView UA rides.
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override val view: android.view.View get() = webView

    /**
     * One lock over the load state: the last main-frame error, the last committed URL,
     * and the continuations waiting for a commit. WebViewClient callbacks and the load
     * path both touch it, always under this lock.
     */
    private val loadLock = Any()
    private var lastError: String? = null
    private var commitUrl: String? = null
    private val commitWaiters = mutableListOf<kotlinx.coroutines.CancellableContinuation<Unit>>()

    init {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                val waiters = synchronized(loadLock) {
                    commitUrl = url
                    events.onPageEvent("commit-visible url=$url")
                    resumeWaitersLocked()
                }
                waiters.forEach { it.resume(Unit) }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame != true) return
                val waiters = synchronized(loadLock) {
                    lastError = error?.description?.toString() ?: "unknown error"
                    events.onPageEvent("load-error ${error?.description} code=${error?.errorCode}")
                    resumeWaitersLocked()
                }
                waiters.forEach { it.resume(Unit) }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                // FR-10: adopt the popup — create the child, let it resolve its target URL,
                // transfer that URL to the lane's own WebView, and destroy the child.
                return adoptPopup(view, resultMsg)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                // FR-19: the lane never auto-picks files — the owner picks (Sprint S5 card).
                events.onFileChooserRefused()
                filePathCallback?.onReceiveValue(null)
                return true
            }
        }
        webView.setDownloadListener { url, _, _, _, _ ->
            // FR-20: hand the download to the system DownloadManager; never auto-open.
            events.onDownloadStarted(url)
            try {
                val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                dm.enqueue(request)
            } catch (e: Exception) {
                events.onPageEvent("download-failed ${e.message}")
            }
        }
    }

    private fun adoptPopup(parentView: WebView?, resultMsg: android.os.Message?): Boolean {
        val child = WebView(parentView?.context ?: webView.context)
        val holder = arrayOfNulls<String>(1)
        child.webViewClient = object : WebViewClient() {
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (url != null && holder[0] == null) holder[0] = url
            }
        }
        val transport = resultMsg?.obj as? WebView.WebViewTransport
        if (transport == null) {
            child.destroy()
            events.onPageEvent("popup-adopt-failed no-transport")
            return false
        }
        transport.setWebView(child)
        resultMsg.sendToTarget()
        // Give the child a bounded window to resolve its target, then transfer + destroy.
        webView.postDelayed({
            val url = holder[0]
            child.destroy()
            if (url != null) {
                events.onAdoptedPopup(url)
                webView.loadUrl(url)
            } else {
                events.onPageEvent("popup-adopt-failed no-url")
            }
        }, 1_500L)
        return true
    }

    private fun resumeWaitersLocked(): List<kotlinx.coroutines.CancellableContinuation<Unit>> {
        val snapshot = commitWaiters.toList()
        commitWaiters.clear()
        return snapshot
    }

    override suspend fun load(url: String): String {
        var attempt = 1
        while (true) {
            synchronized(loadLock) {
                lastError = null
                commitUrl = null
            }
            withContext(Dispatchers.Main) { webView.loadUrl(url) }
            val committed = withTimeoutOrNull(LoadPolicy.PAGE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    synchronized(loadLock) {
                        if (commitUrl != null) {
                            cont.resume(Unit)
                        } else {
                            commitWaiters.add(cont)
                            cont.invokeOnCancellation {
                                synchronized(loadLock) { commitWaiters.remove(cont) }
                            }
                        }
                    }
                }
            }
            if (committed != null) {
                delay(LoadPolicy.SETTLE_DWELL_MS)
                return currentUrl()
            }
            val error = synchronized(loadLock) { lastError }
            if (attempt < LoadPolicy.MAX_LOAD_ATTEMPTS && error != null && LoadPolicy.isTransient(error)) {
                attempt += 1
                events.onPageEvent("load-retry-1 error=$error")
                continue
            }
            throw LaneLoadException(error ?: "timeout after ${LoadPolicy.PAGE_TIMEOUT_MS}ms")
        }
    }

    override suspend fun currentUrl(): String = withContext(Dispatchers.Main) { webView.url ?: "" }

    override suspend fun snapshot(): DomSnapshot = DomJson.parseSnapshot(evaluate(ExtractJs.snapshot))

    override suspend fun evaluate(js: String): String = withContext(Dispatchers.Main) {
        check(Looper.myLooper() == Looper.getMainLooper())
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(js) { value -> cont.resume(value ?: "") }
        }
    }

    override suspend fun dispatchTrustedTouch(cssX: Int, cssY: Int) {
        // The trusted gesture (FR-13 escalation): DOWN now, UP after a short real-feeling
        // hold — posted, never a blocking sleep on the main thread. The prototype proved
        // this path produces the full trusted event sequence.
        withContext(Dispatchers.Main) {
            val density = webView.resources.displayMetrics.density
            val x = cssX * density
            val y = cssY * density
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            webView.dispatchTouchEvent(down)
            down.recycle()
            webView.postDelayed({
                val up = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0,
                )
                webView.dispatchTouchEvent(up)
                up.recycle()
            }, 60L)
        }
    }

    override suspend fun goBack() {
        withContext(Dispatchers.Main) { if (webView.canGoBack()) webView.goBack() }
    }

    override fun destroy() {
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(Looper.getMainLooper()).post {
            try {
                webView.removeAllViews()
                webView.destroy()
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        events.onPageEvent("webview-destroyed")
    }
}

/** A loud load failure (the error name rides; never page content). */
class LaneLoadException(message: String) : Exception(message)

/** Parses [raw] (the object's JSON text from evaluateJavascript) into a [DomSnapshot]. */
object DomJson {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseSnapshot(raw: String): DomSnapshot {
        val text = raw.trim()
        if (text.isEmpty()) throw LaneLoadException("empty snapshot result")
        return json.decodeFromString(DomSnapshot.serializer(), text)
    }
}
