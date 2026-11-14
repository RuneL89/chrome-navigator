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
import kotlinx.coroutines.launch
import navigator.app.cards.PurchaseDecisionClient
import navigator.app.harness.webview.dom.DomSnapshot
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
/** The receiver's route to the live service (the actions are the machines' to lawfully apply). */
object LaneServiceHolder {
    @Volatile
    var instance: LaneService? = null
}

class LaneService : Service() {

    companion object {
        const val TAG = "NavigatorLane"
        const val CHANNEL = "navigator-lane"
        const val NOTIF_ID = 4811
        const val ACTION_START_TASK = "navigator.app.lane.START_TASK"
        const val ACTION_STOP = "navigator.app.lane.STOP"
        const val ACTION_SITECHECK = "navigator.app.lane.SITECHECK"
        const val ACTION_CREDPROBE = "navigator.app.lane.CREDPROBE"
        const val ACTION_CARD_ANSWER = "navigator.app.lane.CARD_ANSWER"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bus = LaneBusHolder.bus
    private var controller: LaneController? = null
    private var wm: WindowManager? = null
    private var windowView: android.view.View? = null
    private var laneUi: LaneUi? = null
    private var activePurchaseMachine: navigator.app.cards.CardMachine? = null
    private var activeCancelWindow: navigator.app.cards.CancelWindow? = null
    private var stepCount = 0
    private var chipDone = false

    /** The notification/card actions route here (the receiver is a thin router). */
    fun onStopAction() {
        startService(Intent(this, LaneService::class.java).setAction(ACTION_STOP))
    }

    fun onCardAnswer(approve: Boolean) {
        val machine = activePurchaseMachine
        if (machine == null) {
            Log.w(TAG, "a card answer arrived with no live card - ignored")
            return
        }
        // The decision is the MACHINE's law: approve/decline once, and the answer is final.
        // A decline during the cancel window cancels the WINDOW (silence-is-yes lives there).
        if (!approve) activeCancelWindow?.cancel()
        // Repeated broadcasts are idempotent: a decided machine ignores further answers.
        if (machine.state is navigator.app.cards.CardState.Pending) {
            if (approve) machine.approve() else machine.decline()
        }
    }

    override fun onCreate() {
        super.onCreate()
        LaneServiceHolder.instance = this
        startForegroundCompat()
        laneUi = LaneUi(this, navigator.app.cards.NotificationPolicy { System.currentTimeMillis() }, busTap = {
            busTapLines()
        }).apply { ensureChannel() }
        // FR-70: the chip rides the pipeline events (the step count is the caption).
        bus.subscribe { event ->
            if (event is navigator.app.harness.webview.events.PipelineEvent.Executed) {
                stepCount = event.stepIndex
                laneUi?.updateChip("task " + (event.subtaskIndex + 1) + " - step " + event.stepIndex, chipDone)
            }
        }
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
            purchaseFlow = purchaseFlowHandle(),
        )
    }

    private fun busTapLines(): List<String> {
        val dir = java.io.File(filesDir, "lane-evidence")
        val latest = dir.listFiles()?.maxByOrNull { it.lastModified() } ?: return emptyList()
        return try { latest.readLines().takeLast(6) } catch (_: Exception) { emptyList() }
    }

    /**
     * The live purchase flow (FR-42/43): the writer extracts the decision, the auto-place
     * law decides, the owner answers through the notification's inline actions (below the
     * limit: the 15 s cancel window) or the card overlay (at or above the limit).
     */
    private fun purchaseFlowHandle(): PurchaseFlowHandle {
        val gate = LaneModelGate()
        val decisions = PurchaseDecisionClient(
            transport = navigator.core.wire.OkHttpWireTransport(),
            config = LaneWriterConfig(
                navigator.app.gen.GenEngineConfigs.OPENROUTER_CHAT_URL,
                LaneKeys.openRouterKey(this) ?: "",
                navigator.app.gen.GenEngineConfigs.GENERATIVE_MODEL,
            ),
            gate = gate,
        )
        val limit = navigator.app.cards.LaneSettings(this).autoPlaceLimitCents
        return object : PurchaseFlowHandle {
            override suspend fun decide(goal: String, snapshot: DomSnapshot) = decisions.decide(goal, snapshot)

            override fun surface(
                decision: navigator.app.cards.PurchaseDecisionClient.Decision,
                belowLimit: Boolean,
                taskId: String,
            ): AnswerAwaiter {
                val machine = navigator.app.cards.CardMachine(navigator.app.cards.CardClass.PURCHASE) { System.currentTimeMillis() }
                activePurchaseMachine = machine
                if (belowLimit) {
                    // The 15 s cancel window rides the notification (inline Cancel); silence
                    // past the window PLACES (the owner-ratified auto-place law).
                    val window = navigator.app.cards.CancelWindow()
                    activeCancelWindow = window
                    laneUi?.notifyNeedsYou(taskId, listOf("Cancel"), machine)
                    return AnswerAwaiter {
                        while (!window.places() && machine.state is navigator.app.cards.CardState.Pending) {
                            kotlinx.coroutines.delay(250)
                        }
                        if (machine.state is navigator.app.cards.CardState.Pending) {
                            if (window.cancelled) machine.decline() else machine.approve()
                        }
                        machine.state
                    }
                } else {
                    laneUi?.showPurchaseCard(machine, decision)
                    laneUi?.notifyNeedsYou(taskId, listOf("Approve", "Not now"), machine)
                    return AnswerAwaiter { navigator.app.cards.ApprovalGate().await(machine) }
                }
            }
        }.also {
            // The limit rides to the loop (the handle's consumer).
            controller?.autoPlaceLimitCents = limit
        }
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
                stepCount = 0
                chipDone = false
                laneUi?.showChip("task - starting", onStop = { onStopAction() })
                laneUi?.notifyTaskStarted("task")
                val started = controller?.startTask(goal) { terminal ->
                    Log.i(TAG, "task terminal: $terminal")
                    chipDone = true
                    laneUi?.updateChip("task done", true)
                    laneUi?.notifyTaskEnded("task")
                    // FR-70: the chip stays on its DONE face (an X to close) for a grace
                    // period before the lane goes idle — the owner sees the outcome.
                    scope.launch {
                        kotlinx.coroutines.delay(20_000)
                        stopSelf()
                    }
                } ?: false
                if (!started) stopSelf()
            }

            ACTION_STOP -> {
                controller?.stop("owner stop")
                stopSelf()
            }

            ACTION_SITECHECK -> {
                // The S1 corpus re-measure path (AC-S1.11): one load through the lane's OWN
                // LoadPolicy (commit + fixed dwell + retry-once) + one snapshot — no model
                // calls, one honest line per site. Replaced by S4's UI like the rest of the
                // debug scaffolding.
                val url = intent.getStringExtra("url") ?: ""
                if (url.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureWindowManager()
                // WebView construction needs a Looper thread — onStartCommand runs on main,
                // so the factory call happens HERE, before the worker coroutine takes over.
                val events = object : LaneWebEvents {
                    override fun onPageEvent(line: String) { Log.i(TAG, "lanesite $line") }
                    override fun onAdoptedPopup(popup: String) { Log.i(TAG, "lanesite popup $popup") }
                    override fun onFileChooserRefused() {}
                    override fun onDownloadStarted(download: String) {}
                }
                val web = LaneWebViewFactory(this@LaneService, events).create()
                scope.launch {
                    try {
                        runOnMain { attachToWindow(web.view) }
                        val started = System.currentTimeMillis()
                        val settled = web.load(url)
                        val snapshot = web.snapshot()
                        val elapsed = System.currentTimeMillis() - started
                        Log.i(
                            TAG,
                            "lanesite RESULT url=$settled title=\"${snapshot.page.title.take(80)}\" " +
                                "elements=${snapshot.elements.size} text_chars=${snapshot.text.length} " +
                                "load_ms=$elapsed",
                        )
                    } catch (e: Exception) {
                        Log.i(TAG, "lanesite FAILED url=$url error=${e.message?.take(120)}")
                    } finally {
                        runOnMain {
                            try {
                                wm?.removeView(web.view)
                            } catch (_: Exception) {
                            }
                        }
                        web.destroy()
                        stopSelf()
                    }
                }
            }

            ACTION_CREDPROBE -> {
                // The S3 phishing probe (AC-S3.10): load the lookalike page, then run the
                // REAL guard chain (DomGuard + PhishingGuard — the loop's own code) over a
                // credential ask. Deterministic on-device evidence; S4 replaces the debug
                // actions like the rest of the harness.
                val url = intent.getStringExtra("url") ?: ""
                val expectedSite = intent.getStringExtra("site") ?: ""
                if (url.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureWindowManager()
                val web = LaneWebViewFactory(this@LaneService, object : LaneWebEvents {
                    override fun onPageEvent(line: String) { Log.i(TAG, "credprobe " + line) }
                    override fun onAdoptedPopup(popup: String) {}
                    override fun onFileChooserRefused() {}
                    override fun onDownloadStarted(download: String) {}
                }).create()
                scope.launch {
                    try {
                        runOnMain { attachToWindow(web.view) }
                        web.load(url)
                        val snapshot = web.snapshot()
                        val password = snapshot.elements.firstOrNull {
                            it.type.equals("password", ignoreCase = true)
                        }
                        if (password == null) {
                            Log.i(TAG, "credprobe RESULT no credential field on " + snapshot.page.url.take(80))
                        } else {
                            val proposal = DomProposalClient.Proposal(
                                password, navigator.app.harness.webview.dom.DomAction.TEXT,
                                "test1234", "the password fills", "{}",
                            )
                            val guard = navigator.app.harness.webview.DomGuard.guard(
                                proposal, snapshot, snapshot, "buy the kettle on " + expectedSite,
                            )
                            Log.i(TAG, "credprobe GUARD " + guard::class.simpleName)
                            val stop = navigator.app.cards.PhishingGuard.check(
                                snapshot.page.url, expectedSite.ifBlank { null }, credentialOrPaymentAsk = true,
                            )
                            if (stop != null) {
                                Log.i(TAG, "credprobe PHISHING STOP: " + stop.reason.take(160))
                            } else {
                                Log.i(TAG, "credprobe RESULT no phishing stop (the domain is the task's own)")
                            }
                        }
                    } catch (e: Exception) {
                        Log.i(TAG, "credprobe FAILED " + (e.message?.take(120) ?: ""))
                    } finally {
                        runOnMain {
                            try { wm?.removeView(web.view) } catch (_: Exception) {}
                        }
                        web.destroy()
                        stopSelf()
                    }
                }
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

    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(block)
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
        LaneServiceHolder.instance = null
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
