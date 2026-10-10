package navigator.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import navigator.app.gen.asAssistSource
import navigator.app.gen.asPhraseSource
import navigator.app.harness.GoalCatalog
import navigator.app.harness.HarnessEngineFactory
import navigator.app.harness.HarnessObserver
import navigator.app.harness.HarnessPagesServer
import navigator.app.harness.RoomDecisionLogSink
import navigator.app.harness.ScriptedGoal
import navigator.app.loop.BrowserLauncher
import navigator.app.loop.BrowserProbe
import navigator.app.loop.GestureExecutor
import navigator.app.loop.LoopUiListener
import navigator.app.loop.NavigationLoop
import navigator.app.loop.SpendMeter
import navigator.app.loop.TaskCancelFlag
import navigator.app.observe.CaptureMode
import navigator.app.observe.NavigatorAccessibilityService
import navigator.app.setup.OpenRouterKeyStore
import navigator.app.stub.StubEngine
import navigator.core.log.EngineIdentity
import navigator.core.wire.DefaultEngineConfigs

/**
 * The product foreground service (phase doc §2.3): the persistent "Navigator" host for
 * the overlay surfaces + navigation runs. It survives task duration (doc 01 risk 4),
 * owns the persistent notification ("Navigator ready / working / idle" on the product
 * `navigator` channel), and runs the loop with the PRODUCT presenters (the overlay
 * cards — NO auto modes) streaming via the ADDITIVE [LoopUiListener].
 *
 * Start law (Android 12+ background-start restrictions): the service is started from
 * FOREGROUND contexts only — the Setup wizard's start button and the notification's
 * "New task" action (which opens the wizard activity first); START_STICKY restarts are
 * the system's own (always lawful). The goal-input submit hands the goal to the ALREADY
 * RUNNING service through the in-process channel (one process: a11y service, overlays,
 * and this service) — no background service start ever happens.
 *
 * Engine law (ART-2): the product run builds the SAME Jev primary the harness builds
 * ([HarnessEngineFactory]), keyed by the Setup-stored OpenRouter key. NO KEY → a
 * POLITE refusal (log + notification + live-log line — never a silent no-op).
 *
 * DEBUG-ONLY stub override (Gate 9.3's $0 emulator flow): a debuggable-build-only
 * broadcast ([OverlayDebugReceiver]) forces the STUB engine for ONE product run over a
 * catalog goal — the CaptureReceiver precedent; never reachable in a release build.
 */
class NavigatorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val goals = Channel<String>(capacity = 8)
    private var pumpStarted = false

    /**
     * The ⏹ control's cancellation flag (UAT 9.1 close-out): consumed by the loop's
     * `isCancelled` seam — USER_CANCEL at the next step boundary; reset at every task
     * start (a stale cancel never kills the next task).
     */
    private val taskCancelFlag = TaskCancelFlag()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground(NOTIFICATION_READY)
        instance = this
    }

    override fun onDestroy() {
        instance = null
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground must be entered promptly on every (re)start; sticky restarts included.
        startInForeground(NOTIFICATION_READY)
        when (intent?.action) {
            ACTION_SHOW_GOAL_INPUT -> showGoalInputWithSubmission()
            ACTION_DEBUG_STUB_RUN -> runDebugStubTask(intent.getStringExtra(EXTRA_GOAL_ID))
            else -> startGoalPump()
        }
        return START_STICKY
    }

    // -- goal input + the in-process handoff ----------------------------------------------

    private fun showGoalInputWithSubmission() {
        val manager = NavigatorAccessibilityService.instance?.overlayManager
        if (manager == null) {
            Log.e(
                TAG,
                "goal input refused: no overlay manager (the accessibility service is not " +
                    "connected) - complete the setup wizard first",
            )
            notifyState(NOTIFICATION_READY, "Navigator — enable the accessibility service (open setup)")
            return
        }
        startGoalPump()
        manager.showGoalInput(
            onSubmit = { goal -> handleGoalSubmitted(goal) },
            onCancel = { Log.i(TAG, "goal input cancelled") },
        )
    }

    private fun handleGoalSubmitted(goal: String) {
        if (goal.isBlank()) {
            NavigatorAccessibilityService.instance?.overlayManager?.appendLogLine(
                "empty goal — nothing to do",
            )
            return
        }
        // The channel handoff needs the RUNNING service; a failed send is a LOUD,
        // visible refusal (never a silent no-op). trySend is non-suspending.
        val sent = goals.trySend(goal).isSuccess
        if (!sent) {
            Log.e(TAG, "goal refused: the Navigator service is not running - open the app")
            NavigatorAccessibilityService.instance?.overlayManager?.appendLogLine(
                "Navigator service is not running — open the app to start it",
            )
            return
        }
        notifyState(NOTIFICATION_WORKING, "Navigator working")
    }

    private fun startGoalPump() {
        if (pumpStarted) return
        pumpStarted = true
        scope.launch {
            for (goal in goals) {
                try {
                    runProductTask(goal, stubGoal = null)
                } catch (t: Throwable) {
                    Log.e(TAG, "product run FAILED loudly: ${t::class.simpleName}: ${t.message}")
                    NavigatorAccessibilityService.instance?.overlayManager?.appendLogLine(
                        "run failed: ${t::class.simpleName} — open the harness logcat for detail",
                    )
                    notifyState(NOTIFICATION_READY, "Navigator ready (last run failed)")
                }
            }
        }
    }

    // -- the product run -------------------------------------------------------------------

    /** One product task with the overlay presenters + the live-log listener. */
    private suspend fun runProductTask(goalText: String, stubGoal: ScriptedGoal?) {
        val a11y = NavigatorAccessibilityService.instance ?: throw IllegalStateException(
            "product run: the accessibility service is not connected (enable it in setup)",
        )
        val manager = a11y.overlayManager ?: throw IllegalStateException(
            "product run: no overlay manager - overlays are impossible",
        )
        notifyState(NOTIFICATION_WORKING, "Navigator working")
        manager.showLiveLog()
        // UAT 9.1 close-out: the ⏹ control's wiring. The flag resets HERE (a stale
        // cancel from before this task never kills it — TaskCancelFlag's law), the
        // stop callback rides the manager into the log's header control, and the loop
        // consumes the flag through its isCancelled seam (USER_CANCEL at the next
        // step boundary — the lawful owner exit, vision 04 §3; the flag only ever
        // STOPS the loop, it never decides or acts).
        manager.onStopTask = { taskCancelFlag.cancelActive() }
        taskCancelFlag.beginTask()
        manager.setTaskRunning(true)
        try {
            // The engine triple (ART-2: the loop cannot tell stub from live).
            val engine: navigator.core.decision.DecisionEngine
            val identity: EngineIdentity
            val meter: navigator.app.loop.SpendMeter
            // The Setup-stored key serves BOTH writers (decision primary + the Phase-10
            // generative writer) — one key, one storage; the value never surfaces.
            var writerKey: String? = null
            if (stubGoal != null) {
                // DEBUG-ONLY (the caller checked debuggable): the $0 scripted engine.
                engine = navigator.app.stub.StubEngine(stubGoal.stubScript())
                identity = EngineIdentity(
                    HarnessEngineFactory.STUB_CONFIG_ID,
                    HarnessEngineFactory.STUB_MODEL,
                )
                meter = navigator.app.loop.SpendMeter()
            } else {
                val key = OpenRouterKeyStore.load(this)
                if (key.isNullOrBlank()) {
                    // The polite refusal (phase doc §2.3): never a silent no-op.
                    Log.e(TAG, "product run refused: no OpenRouter key saved - open setup")
                    manager.appendLogLine("no OpenRouter key saved — open the app (setup) to add it")
                    notifyState(NOTIFICATION_READY, "Navigator ready — no key (open setup)")
                    return
                }
                writerKey = key
                // The SAME recorded Jev primary the harness builds (one wiring, one
                // redaction law — the key's value never surfaces, only its source).
                val wiredJev = HarnessEngineFactory.jevSpendRecordedEngine(
                    deliveredKey = key,
                    env = emptyMap(),
                    keysFile = DefaultEngineConfigs.DEFAULT_KEYS_FILE,
                    transport = navigator.core.wire.OkHttpWireTransport(),
                )
                engine = wiredJev.clientEngine
                meter = wiredJev.meter
                identity = EngineIdentity(
                    HarnessEngineFactory.LIVE_CONFIG_ID,
                    DefaultEngineConfigs.OPENROUTER_MODEL,
                )
            }

            // Phase 10 (vision 01 §3 v1.4.0): every PRODUCT goal restructures first —
            // the writer's task ladder rides the run (the ACTIVE subtask in the corner
            // log, per-subtask IS-DONE, bounded re-plans, answer extraction). The
            // DEBUG-ONLY stub override stays un-laddered (a scripted $0 run has no
            // writer). One GenClient serves ALL the writer seams (ladder + extraction +
            // the Phase-10B cascade assist/phrase); the key's value lives only inside
            // the client (redacted toString).
            val genClient = writerKey?.let { key ->
                navigator.app.gen.GenClient(
                    transport = navigator.core.wire.OkHttpWireTransport(),
                    config = navigator.app.gen.GenEngineConfigs.generative(key),
                )
            }
            val genLadder = genClient?.let { navigator.app.gen.GenLadderSource(it) }
            val loop = NavigationLoop(
                engine = engine,
                observer = HarnessObserver(a11y),
                executor = GestureExecutor(a11y),
                // PRODUCT presenters: the overlay cards, no auto modes (phase doc §2.1).
                gatePresenter = OverlayGatePresenter(a11y),
                completionCardPresenter = OverlayCompletionCardPresenter(a11y),
                // Phase 10: the owner escalation card ("Retry / Re-plan / Stop"; 30s
                // timeout = Stop) riding the Phase-9 card window.
                escalationCardPresenter = OverlayEscalationCardPresenter(a11y),
                logSink = RoomDecisionLogSink(this),
                spendMeter = meter,
                uiListener = OverlayUiListener(manager) { notifyState(NOTIFICATION_READY, "Navigator ready") },
                browserProbe = BrowserProbe { HarnessObserver.browserState(a11y, this) },
                browserLauncher = BrowserLauncher { url -> openBrowser(url) },
                ladderSource = genLadder ?: navigator.app.loop.LadderSource.None,
                answerExtractor = genLadder,
                // Phase 10B (phase doc §2.6): the cascade's writer seams over the SAME
                // client — the handshake's assist and the fill-field phrase, metered
                // into the loop's per-task writer budget. Null (no key) degrades to the
                // code fallback / fill-field-to-recovery, never raw-goal typing.
                assistSource = genClient?.asAssistSource(),
                phraseSource = genClient?.asPhraseSource(),
            )
            loop.run(
                goal = goalText,
                goalId = stubGoal?.id,
                engineIdentity = identity,
                isCancelled = taskCancelFlag::isCancelled,
            )
        } finally {
            // The terminal (whichever it is, including the owner's USER_CANCEL) hides
            // the ⏹ control; the log itself STAYS up showing the task-state line.
            manager.setTaskRunning(false)
        }
    }

    /** DEBUG-ONLY: the Gate 9.3 $0 emulator flow — a product run over a catalog goal. */
    private fun runDebugStubTask(goalId: String?) {
        if (!CaptureMode.isDebuggable(this)) {
            Log.w(TAG, "debug stub run refused: the build is not debuggable (CaptureReceiver precedent)")
            return
        }
        if (goalId.isNullOrBlank()) {
            Log.w(TAG, "debug stub run refused: no goal id extra")
            return
        }
        startGoalPump()
        scope.launch {
            val catalog = assets.open(DEBUG_GOALS_ASSET).use {
                GoalCatalog.parse(it.readBytes().decodeToString())
            }
            val goal = catalog.byId(goalId)
            Log.i(TAG, "debug stub product run: goal=$goalId (STUB engine, $0)")
            val server = HarnessPagesServer(this@NavigatorService).also { it.start() }
            try {
                runProductTask(goal.goal, stubGoal = goal)
            } catch (t: Throwable) {
                // The DEBUG path mirrors the pump's loud-but-polite catch: a stub
                // mismatch (the fixture page not in front — an environment race) must
                // fail the RUN, never kill the PROCESS (the a11y service and the
                // overlay host live here too).
                Log.e(TAG, "product run FAILED loudly: ${t::class.simpleName}: ${t.message}")
                NavigatorAccessibilityService.instance?.overlayManager?.appendLogLine(
                    "run failed: ${t::class.simpleName} — open the harness logcat for detail",
                )
                notifyState(NOTIFICATION_READY, "Navigator ready (last run failed)")
            } finally {
                server.stop()
            }
        }
    }

    // -- notification + browser edges -------------------------------------------------------

    private fun openBrowser(url: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "openBrowser failed: ${it::class.simpleName}: ${it.message}") }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Navigator",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startInForeground(state: Int) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyState(state: Int, text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(state, text)) }
            .onFailure { Log.w(TAG, "notification failed: ${it::class.simpleName}") }
    }

    /**
     * The persistent notification: "Navigator ready / working / idle" state line; tap →
     * SetupActivity (the phase doc's link); a "New task" action → the wizard activity
     * (foreground) which then triggers the goal-input overlay.
     */
    private fun buildNotification(state: Int = NOTIFICATION_READY, text: String = ""): Notification {
        val openSetup = PendingIntent.getActivity(
            this,
            0,
            Intent(this, navigator.app.setup.SetupActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val newTask = PendingIntent.getActivity(
            this,
            1,
            Intent(this, navigator.app.setup.SetupActivity::class.java).apply {
                putExtra(navigator.app.setup.SetupActivity.EXTRA_SHOW_GOAL_INPUT, true)
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (state) {
            NOTIFICATION_WORKING -> "Navigator working"
            else -> "Navigator ready"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(title)
            .setContentText(text.ifBlank { "Tap to open setup. New task starts the goal overlay." })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openSetup)
            .addAction(0, "New task", newTask)
            .build()
    }

    /**
     * The overlay's view of the loop (the ADDITIVE [LoopUiListener]; presentation only,
     * ART-2). The step line is measured at the overlay (Gate 9.4): the receipt timestamp
     * is taken at [onStep] entry, the rendered timestamp at the frame's pre-draw.
     */
    private class OverlayUiListener(
        private val manager: OverlayManager,
        private val onTerminal: (LoopUiListener.TaskStateUpdate) -> Unit,
    ) : LoopUiListener {
        override fun onThinking(active: Boolean) = manager.setThinking(active)
        override fun onPlan(subgoals: List<String>) = manager.updatePlan(subgoals)

        override fun onLadderBlock(lines: List<String>) = manager.setLadderBlock(lines)

        override fun onStep(facts: LoopUiListener.StepFacts) {
            val receipt = android.os.SystemClock.uptimeMillis()
            manager.appendMeasuredLogLine(LoopUiListener.compactLine(facts), receipt)
        }

        override fun onTaskState(state: LoopUiListener.TaskStateUpdate) {
            manager.setTaskStateLine(LoopUiListener.taskStateLine(state))
            onTerminal(state)
        }
    }

    companion object {
        private const val TAG = "NavigatorService"

        /** The PRODUCT channel (the harness's `navigator-debug` stays harness-only). */
        const val CHANNEL_ID = "navigator"
        const val NOTIFICATION_ID = 100

        private const val NOTIFICATION_READY = 1
        private const val NOTIFICATION_WORKING = 2

        const val ACTION_SHOW_GOAL_INPUT = "navigator.app.action.SHOW_GOAL_INPUT"
        const val ACTION_DEBUG_STUB_RUN = "navigator.app.action.DEBUG_STUB_RUN"
        const val EXTRA_GOAL_ID = "goalId"

        private const val DEBUG_GOALS_ASSET = "harness/goals.json"

        /**
         * In-process handle (same process as the overlays): the goal-input submit hands
         * the goal here; null (not running) makes the submit refuse LOUDLY + politely.
         */
        @Volatile
        var instance: NavigatorService? = null
            private set

        /** Whether the running service can accept a submitted goal right now. */
        val isRunning: Boolean get() = instance != null
    }
}
