package navigator.app.harness.webview

import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import navigator.app.gen.GenClient
import navigator.app.gen.GenConfig
import navigator.app.gen.GenEngineConfigs
import navigator.app.harness.webview.dom.ConsentHandler
import navigator.app.harness.webview.events.PipelineEventBus
import navigator.core.wire.OkHttpWireTransport
import navigator.core.wire.SystemOneClient
import java.io.File

/**
 * V2 S1 — the lane controller (AC-S1.6): owns ONE task at a time, creates the WebView
 * when a task starts and destroys it when the task ends (the WebView is alive ONLY
 * while a task runs), runs the [DomLaneLoop] on a worker coroutine, and reports the
 * terminal to the service (which stops the FGS when the lane goes idle).
 *
 * The window seam ([LaneWindowHost]) is how the task WebView enters the off-screen
 * overlay window — the WebView is attached when the task starts, detached when it ends.
 *
 * The controller is the wiring point for the carried stack: [GenClient] (the writer),
 * [DomProposalClient] (the DOM proposal), [SystemOneClient] (the judge, maxRetries=0 —
 * the [LaneModelGate] owns the whole retry law), [DomExecutor], [DomLaneLoop].
 */
class LaneController(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val bus: PipelineEventBus,
    private val evidenceDir: File?,
    private val windowHost: LaneWindowHost,
    private val purchaseFlow: PurchaseFlowHandle? = null,
    var autoPlaceLimitCents: Long = 3_000L,
) {
    private var web: LaneWebView? = null
    private var job: kotlinx.coroutines.Job? = null
    private var taskStartedAt = 0L

    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * Starts a task. Returns false (loudly, with the reason logged) when the lane is
     * busy or its keys are missing — it never starts half-configured.
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    fun startTask(goal: String, onTerminal: (DomLaneLoop.Terminal) -> Unit): Boolean {
        if (isRunning) {
            Log.w("NavigatorLane", "a task is already running — refusing the second task")
            return false
        }
        val openRouterKey = LaneKeys.openRouterKey(context)
        if (openRouterKey.isNullOrBlank()) {
            Log.w("NavigatorLane", "no writer/judge key — the lane refuses to start")
            return false
        }

        val events = object : LaneWebEvents {
            override fun onPageEvent(line: String) { Log.i("NavigatorLane", line) }
            override fun onAdoptedPopup(url: String) { Log.i("NavigatorLane", "popup adopted: $url") }
            override fun onFileChooserRefused() { Log.i("NavigatorLane", "file chooser refused (fill-only law: the owner picks)") }
            override fun onDownloadStarted(url: String) { Log.i("NavigatorLane", "download routed to DownloadManager") }
        }
        val runId = "lane-${System.currentTimeMillis()}"
        val evidence = LaneEvidence(evidenceDir, runId)
        val web = LaneWebViewFactory(context, events).create()
        this.web = web
        taskStartedAt = System.currentTimeMillis()
        // The task WebView enters the off-screen window now, leaves it when the task ends.
        runOnMain {
            windowHost.attach(web.view)
        }

        val transport = OkHttpWireTransport()
        val gate = LaneModelGate()
        val gen = GenClient(
            transport = transport,
            config = GenConfig(
                GenEngineConfigs.OPENROUTER_CHAT_URL,
                openRouterKey,
                GenEngineConfigs.GENERATIVE_MODEL,
            ),
        )
        val proposals = DomProposalClient(
            transport = transport,
            config = LaneWriterConfig(
                GenEngineConfigs.OPENROUTER_CHAT_URL,
                openRouterKey,
                GenEngineConfigs.GENERATIVE_MODEL,
            ),
            gate = gate,
            onUsage = { evidence.line("writer-usage ${it.costMicroUsd}microUsd") },
        ).apply {
            debugListener = { line -> evidence.line(line.take(1200)) }
        }
        val judgeClient = SystemOneClient(
            transport = transport,
            // maxRetries = 0: the LaneModelGate owns the entire retry law (3 attempts/90s).
            // The judge endpoint is OpenRouter's decisions API — the OPENROUTER key rides
            // it (the `typesafe/jev` slug is hosted there); the TypeSafe key stays for the
            // direct TypeSafe surface used elsewhere.
            primary = DomJudge.laneJudgeConfig(openRouterKey),
            maxRetries = 0,
        )
        val judge = DomJudge(judgeClient, gate, onUsage = { evidence.line("judge-usage ${it.costUsd}usd") })
        val executor = DomExecutor(web)
        val loop = DomLaneLoop(
            web = web,
            planner = GenClientPlanner(gen),
            proposals = proposals,
            judge = judge,
            executor = executor,
            bus = bus,
            evidence = evidence,
            consentMemory = ConsentHandler.Memory(),
            purchaseFlow = purchaseFlow,
            autoPlaceLimitCents = autoPlaceLimitCents,
        )

        job = scope.launch {
            try {
                val terminal = loop.runTask(goal)
                evidence.line("terminal: $terminal")
                onTerminal(terminal)
            } catch (e: Exception) {
                Log.e("NavigatorLane", "the lane crashed honestly", e)
                evidence.line("lane-crash ${e.message?.take(200)}")
                onTerminal(DomLaneLoop.Terminal.Failed("the lane crashed: ${e.message?.take(160)}"))
            } finally {
                runOnMain {
                    windowHost.detach(web.view)
                }
                web.destroy()
                this@LaneController.web = null
                this@LaneController.job = null
            }
        }
        return true
    }

    fun stop(reason: String) {
        job?.cancel()
        job = null
        web?.let {
            runOnMain { windowHost.detach(it.view) }
            it.destroy()
        }
        web = null
        Log.i("NavigatorLane", "lane stopped: $reason (ran ${System.currentTimeMillis() - taskStartedAt}ms)")
    }

    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(block)
        }
    }
}

/** The window seam: the off-screen overlay window hosts the task WebView for the task's lifetime. */
interface LaneWindowHost {
    fun attach(view: android.view.View)
    fun detach(view: android.view.View)
}
