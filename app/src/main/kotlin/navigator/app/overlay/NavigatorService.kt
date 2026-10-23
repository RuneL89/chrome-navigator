package navigator.app.overlay

import android.app.ActivityManager
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
import navigator.app.harness.ScriptedGoal
import navigator.app.harness.vision.NavigatorVisionBridge
import navigator.app.harness.vision.ProductRunRoute
import navigator.app.harness.vision.VisionCapture
import navigator.app.harness.vision.VisionMath
import navigator.app.loop.BrowserHomeLauncher
import navigator.app.loop.BrowserLauncher
import navigator.app.loop.BrowserProbe
import navigator.app.loop.ExperimentProbe
import navigator.app.loop.GestureExecutor
import navigator.app.loop.LoopUiListener
import navigator.app.loop.NavigationLoop
import navigator.app.loop.NoOpLogSink
import navigator.app.loop.SpendMeter
import navigator.app.loop.TaskCancelFlag
import navigator.app.observe.CaptureMode
import navigator.app.observe.NavigatorAccessibilityService
import navigator.app.setup.OpenRouterKeyStore
import navigator.app.setup.SetupLogic
import navigator.app.stub.StubEngine
import navigator.core.log.EngineIdentity
import navigator.core.wire.DefaultEngineConfigs
import java.io.File

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

    /**
     * The last goal this service ran, IN MEMORY ONLY (2026-10-17 hotel iteration): the
     * corner log's recovery action row re-runs the SAME goal after a browser-fixable
     * refusal. Goals are not secrets (ART-8 holds — nothing here persists; the memory
     * lifetime is the service's). Null before the first run.
     */
    @Volatile
    private var lastGoalText: String? = null

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
            // PHASE_VEXP6 WI4: the notification's "Stop task" action (working state only)
            // — the lawful stop when the corner log was closed (the run-4 monitoring gap
            // was that the ✕ hid the only Stop control). It cancels through the SAME
            // flag the ⏹ control drives; the loop exits USER_CANCEL at its next step
            // boundary and the usual terminal funnel takes over.
            ACTION_STOP_TASK -> {
                taskCancelFlag.cancelActive()
                NavigatorAccessibilityService.instance?.overlayManager?.appendLogLine(
                    "stop requested — ending at the next step",
                )
            }
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
        lastGoalText = goalText
        notifyState(NOTIFICATION_WORKING, "Navigator working")
        manager.showLiveLog()
        // UAT 9.1 close-out: the ⏹ control's wiring. The flag resets HERE (a stale
        // cancel from before this task never kills it — TaskCancelFlag's law), the
        // stop callback rides the manager into the log's header control, and the loop
        // consumes the flag through its isCancelled seam (USER_CANCEL at the next
        // step boundary — the lawful owner exit, vision 04 §3; the flag only ever
        // STOPS the loop, it never decides or acts).
        manager.onStopTask = { taskCancelFlag.cancelActive() }
        // PHASE_VEXP9: a monitor stop's provenance is per-run (a stale marker must never
        // label the next run), reset beside the cancel flag it shares.
        navigator.app.experiment.ExperimentStop.reset()
        // The recovery row's wiring (2026-10-17): a tap on "Open the browser and retry"
        // (offered only on browser-fixable refusals — LiveLogRecovery decides purely)
        // fires the browser's home launch and re-runs the SAME goal through the exact
        // in-process entry the goal-input submit uses.
        manager.onRecoveryAction = { offerRecoveryRetry() }
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

                // PHASE_VEXP2 — THE ENGINE SWITCH (owner directive 2026-10-19), flipped
                // BY DEFAULT in PHASE_VEXP4: the vision loop IS the app's engine — the
                // wizard's "Use vision loop" preference defaults ON, so a goal typed in
                // the app runs VisionLoop whenever BOTH keys are stored. A missing key
                // refuses LOUDLY naming the channel + the wizard — NEVER a silent
                // fallback to the legacy loop. Only the owner's explicit OFF routes
                // Legacy: it falls straight through to the legacy construction below —
                // untouched, byte-identical (and never reads the second key store).
                // The vision run itself builds + runs in [runVisionLoopTask].
                val visionToggled = visionLoopToggled()
                when (val route = ProductRunRoute.decide(
                    useVisionLoop = visionToggled,
                    openRouterKey = key,
                    typesafeKey = if (visionToggled) {
                        OpenRouterKeyStore.loadTypesafeKey(this)
                    } else {
                        null // the OFF path never even reads the second store
                    },
                )) {
                    is ProductRunRoute.Refused -> {
                        // The polite loud refusal naming the channel (never a silent no-op).
                        Log.e(TAG, "product run refused: ${route.detail}")
                        manager.appendLogLine(route.detail)
                        notifyState(NOTIFICATION_READY, "Navigator ready — vision loop needs both keys (open setup)")
                        return
                    }
                    is ProductRunRoute.Vision -> {
                        runVisionLoopTask(manager, a11y, route, goalText)
                        return
                    }
                    ProductRunRoute.Legacy -> {} // the legacy path continues unchanged below
                }

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
            // PHASE_VEXP3: the training program is RETIRED (owner directive
            // 2026-10-19) — the legacy loop logs through the no-op sink; nothing is
            // persisted (the writeTerminal below no-ops: the terminal's channel is the
            // corner log + notification, per .state/DEBUGGING.md).
            val sink = NoOpLogSink
            // PHASE_VEXP9 Stage 0: the experiment probe records the failure seams the
            // loop reaches. A fire-and-forget recorder — it never steers a decision.
            val experimentProbe = ExperimentProbe.Recorder()
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
                logSink = sink,
                spendMeter = meter,
                uiListener = OverlayUiListener(manager) { notifyState(NOTIFICATION_READY, "Navigator ready") },
                browserProbe = BrowserProbe { HarnessObserver.browserState(a11y, this) },
                browserLauncher = BrowserLauncher { url -> openBrowser(url) },
                // The ladder-path readiness pre-step's home-launch seam (2026-10-17,
                // doc 04 §1 v1.8.3): wired ONLY when an allowlisted browser resolves —
                // the SAME derivation the probe uses — so no browser means the pre-step
                // could never fire anyway (null = never fires).
                homeLauncher = HarnessObserver.installedBrowser(this)?.let { pkg ->
                    BrowserHomeLauncher { openBrowserHome(pkg) }
                },
                ladderSource = genLadder ?: navigator.app.loop.LadderSource.None,
                answerExtractor = genLadder,
                // Phase 10B (phase doc §2.6): the cascade's writer seams over the SAME
                // client — the handshake's assist and the fill-field phrase, metered
                // into the loop's per-task writer budget. Null (no key) degrades to the
                // code fallback / fill-field-to-recovery, never raw-goal typing.
                assistSource = genClient?.asAssistSource(),
                phraseSource = genClient?.asPhraseSource(),
                experimentProbe = experimentProbe,
            )
            val result = loop.run(
                goal = goalText,
                goalId = stubGoal?.id,
                engineIdentity = identity,
                isCancelled = taskCancelFlag::isCancelled,
            )
            // Phase 10C (doc 05 §2 v1.7.0): the terminal rides the database, not only the
            // overlay — one row per task (the battery: the terminal was unreadable from
            // the store, and the live log did not reset between runs).
            runCatching {
                sink.writeTerminal(
                    navigator.app.loop.TerminalRow(
                        taskId = result.taskId,
                        goal = result.goal,
                        terminal = result.terminal.name,
                        detail = result.terminalDetail,
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { Log.w(TAG, "terminal row write failed: ${it::class.simpleName}") }
            // PHASE_VEXP9 Stage 0: persist the a11y run's evidence beside the vision
            // engine's. The RunResult's step log is already scrubbed at the loop's finish
            // boundary, so this file never carries a sanctioned secret. A recorder only —
            // a write failure is logged, never allowed to fail the finished run.
            runCatching {
                val baseDir = getExternalFilesDir(null)
                    ?.let { File(it, navigator.app.experiment.ExperimentEvidence.SUBDIR) }
                val monitorStop = navigator.app.experiment.ExperimentStop.isMonitorStop()
                val stopReason = when {
                    monitorStop -> "MONITOR_CARD_STOP"
                    result.terminal == navigator.app.loop.TerminalState.USER_CANCEL -> "OWNER_CANCEL"
                    else -> "ENGINE_TERMINAL"
                }
                navigator.app.experiment.ExperimentEvidence.write(
                    baseDir = baseDir,
                    runId = result.taskId,
                    result = result,
                    meta = navigator.app.experiment.ExperimentEvidence.Meta(
                        goalId = stubGoal?.id,
                        arm = "tree",
                        stopReason = stopReason,
                        monitorStop = monitorStop,
                        // PHASE_17 Stage 0.6: the a11y arm is the tree-only arm; it has
                        // no escalation cause until the hybrid host loop lands (Stage 1).
                        modality = "TREE",
                        escalationCause = null,
                    ),
                    seams = experimentProbe.snapshot(),
                )
            }.onFailure { Log.w(TAG, "a11y experiment evidence write failed: ${it::class.simpleName}") }
        } finally {
            // The terminal (whichever it is, including the owner's USER_CANCEL) hides
            // the ⏹ control; the log itself STAYS up showing the task-state line.
            manager.setTaskRunning(false)
        }
    }

    /**
     * The engine-switch preference (PHASE_VEXP2; PHASE_VEXP4 the default flip): plain
     * SharedPreferences, the battery-ack file + precedent, key `"use-vision-loop"`,
     * default ON — the vision loop IS the app's engine, and an explicitly stored OFF
     * is the owner's legacy-engine fallback. Written by the setup wizard's toggle
     * card on every flip.
     */
    private fun visionLoopToggled(): Boolean =
        getSharedPreferences(SetupLogic.VISION_LOOP_PREFS_FILE, MODE_PRIVATE)
            .getBoolean(SetupLogic.VISION_LOOP_PREF_KEY, SetupLogic.VISION_LOOP_DEFAULT)

    /**
     * PHASE_VEXP2: the TOGGLED product run — the experiment's VisionLoop over THIS
     * service's own observation/execution/capture edges (HarnessObserver +
     * GestureExecutor + VisionCapture, all a11y-backed), keys from the Setup stores
     * (delivered in by the pure [ProductRunRoute] decision). The vision steps stream
     * into the corner log (the bridge's log lambda → appendLogLine); cancel rides the
     * SAME TaskCancelFlag the ⏹ control drives; the terminal maps through the EXISTING
     * funnel (task-state line + READY notification — NO DB row: the vision loop has no
     * sink; training rows are retired next phase). Evidence (owner-consented,
     * experiment-only) lands under the standard external `run-reports/vision-exp/`.
     * A thrown run propagates to the pump's loud-but-polite catch (the caller's
     * finally still hides the ⏹ control).
     */
    private suspend fun runVisionLoopTask(
        manager: OverlayManager,
        a11y: NavigatorAccessibilityService,
        route: ProductRunRoute.Vision,
        goalText: String,
    ) {
        val runId = "task-${System.currentTimeMillis()}"
        val observer = HarnessObserver(a11y)
        // D1 (live defect, 2026-10-19): the loop must START on a browser, not on
        // whatever screen the goal was submitted from (the setup wizard was front and
        // the loop proposed "Start Navigator" there). The SAME engine-free readiness
        // pre-step the adb activity always had — extracted into the bridge — fires the
        // allowlisted browser's HOME once when a non-browser window is front, then
        // waits bounded; no browser came = loud lines + the run proceeds truthfully.
        // D4 (the rerun repair, 2026-10-19): the HOME launch was the DESTRUCTIVE part —
        // on the webview shell it resets the browser to about:blank, destroying the
        // page the owner had open. The bridge now tries the EXISTING browser task
        // first (bringBrowserTaskForward: ActivityManager.moveTaskToFront — no
        // navigation, page preserved) and falls back to the HOME launch only when no
        // browser task resolves.
        val browserPkg = HarnessObserver.installedBrowser(this)
        NavigatorVisionBridge.ensureBrowserFront(
            observer = observer,
            launchBrowserHome = browserPkg?.let { pkg -> { openBrowserHome(pkg) } },
            bringBrowserTaskForward = browserPkg?.let { pkg -> { bringBrowserTaskForward(pkg) } },
            log = { line -> manager.appendLogLine(line) },
        )
        val loop = NavigatorVisionBridge.newVisionLoop(
            observer = observer,
            executor = GestureExecutor(a11y),
            capture = VisionCapture(a11y),
            evidenceDir = File(getExternalFilesDir(null), "run-reports/vision-exp"),
            runId = runId,
            openRouterKey = route.openRouterKey,
            typesafeKey = route.typesafeKey,
            log = { line -> manager.appendLogLine(line) },
            // PHASE_VEXP3 WI1: the safety gate rides the vision loop — a risky action
            // shows the SAME owner card the legacy engine shows (vision 06 §2).
            gatePresenter = OverlayGatePresenter(a11y),
            // D2 (live defect, 2026-10-19): the loop's own corner live log was visible
            // in its screenshots and got proposed/targeted ("the X close button of the
            // NAVIGATOR overlay"). Every capture paints the log window's CURRENT bounds
            // black; the bounds are re-read per capture (the log grows/moves), and a
            // hidden/not-yet-laid-out log degrades to the unchanged frame + one loud note.
            exclusionBounds = {
                manager.liveLogBounds()?.let { b ->
                    VisionMath.RectPx(b.left, b.top, b.right, b.bottom)
                }
            },
            // PHASE_VEXP5 WI3: the covered-point guard's occluders — the live log plus
            // the soft keyboard, re-read immediately before every dispatch. A point
            // under either one gets an a11y node action (or a loud refusal), never a
            // raw touch on the overlay.
            coveredBounds = {
                listOfNotNull(manager.liveLogBounds(), manager.inputMethodBounds()).map { b ->
                    VisionMath.RectPx(b.left, b.top, b.right, b.bottom)
                }
            },
        )
        val result = loop.run(goalText, runId, taskCancelFlag::isCancelled)
        manager.setTaskStateLine(NavigatorVisionBridge.taskStateLine(result))
        notifyState(NOTIFICATION_READY, "Navigator ready")
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
        // PHASE_16: target the allowlisted browser PACKAGE explicitly (the harness's
        // precedent). A bare ACTION_VIEW lets a native app's URL handler take the intent
        // (VEXP9 R4: youtube.com opened the YouTube app and the loop never saw Chrome).
        runCatching {
            val browser = HarnessObserver.installedBrowser(this)
            if (browser == null) {
                Log.e(TAG, "openBrowser refused: no allowlisted browser is installed (url hidden)")
                return
            }
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .setPackage(browser)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "openBrowser failed: ${it::class.simpleName}: ${it.message}") }
    }

    /**
     * The allowlisted browser's HOME launch (2026-10-17 hotel iteration) — the platform
     * launch intent for [pkg] (derived by [HarnessObserver.installedBrowser], the same
     * derivation the browser probe uses). READINESS, NOT NAVIGATION: no URL is named,
     * no site is chosen; the engine navigates every step from wherever the browser
     * lands. Sibling of [openBrowser] (same style, same failure law: loud log, never a
     * crash).
     */
    private fun openBrowserHome(pkg: String) {
        runCatching {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                Log.w(TAG, "openBrowserHome: no launch intent for $pkg")
            }
        }.onFailure { Log.w(TAG, "openBrowserHome failed: ${it::class.simpleName}: ${it.message}") }
    }

    /**
     * D4 (the second VEXP4 actual-app repair, 2026-10-19): bring the browser's
     * EXISTING task to the front WITHOUT navigating — the readiness pre-step's
     * non-destructive move. The HOME launch resets the webview-shell browser to
     * `about:blank`, destroying the page the owner had open; `moveTaskToFront` on the
     * browser's task resumes exactly where it was.
     *
     * The task id comes from [ActivityManager.getRunningTasks] filtered on the
     * browser package ([REORDER_TASKS_LIMIT] deep — the browser usually sits within
     * the recent few). Both calls are deprecated/restricted on modern Android:
     * `getRunningTasks` may legally return only the caller's OWN tasks, so a false
     * return is EXPECTED when the platform withholds the browser's row — the caller's
     * [NavigatorVisionBridge.ensureBrowserFront] then falls back to the HOME launch
     * (the pre-D4 behavior, the truthful no-task path). `moveTaskToFront` is authorized
     * by the normal REORDER_TASKS permission (manifest). Best-effort: a platform
     * refusal logs loudly and returns false; it never throws into the run.
     */
    private fun bringBrowserTaskForward(pkg: String): Boolean = runCatching {
        val activityManager = getSystemService(ActivityManager::class.java)
            ?: return@runCatching false
        @Suppress("DEPRECATION")
        val browserTask = activityManager.getRunningTasks(REORDER_TASKS_LIMIT)
            .firstOrNull { it.baseActivity?.packageName == pkg }
            ?: return@runCatching false
        activityManager.moveTaskToFront(browserTask.id, 0)
        true
    }.getOrElse {
        Log.w(TAG, "bringBrowserTaskForward failed: ${it::class.simpleName}: ${it.message}")
        false
    }

    /**
     * The corner log's recovery row was tapped ("Open the browser and retry", offered
     * only on browser-fixable refusals — [LiveLogRecovery]): fire the browser's home
     * launch, then re-run the SAME goal through the exact internal entry the goal-input
     * submit uses ([handleGoalSubmitted] → the in-process channel). The row dismisses
     * itself before this runs (the tap handler clears it first).
     */
    private fun offerRecoveryRetry() {
        val goal = lastGoalText
        if (goal.isNullOrBlank()) {
            Log.w(TAG, "recovery tap ignored: no goal in memory")
            return
        }
        HarnessObserver.installedBrowser(this)?.let { openBrowserHome(it) }
        handleGoalSubmitted(goal)
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
     * (foreground) which then triggers the goal-input overlay. PHASE_VEXP6 WI4: WHILE
     * WORKING it also carries a standard "Stop task" action (a PendingIntent.getService
     * with [ACTION_STOP_TASK]) — the lawful stop when the corner log (and its ⏹ control)
     * is closed. No other notification change; READY keeps exactly the "New task"
     * action.
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
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(title)
            .setContentText(text.ifBlank { "Tap to open setup. New task starts the goal overlay." })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openSetup)
            .addAction(0, "New task", newTask)
        if (state == NOTIFICATION_WORKING) {
            val stopTask = PendingIntent.getService(
                this,
                2,
                Intent(this, NavigatorService::class.java).setAction(ACTION_STOP_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Stop task", stopTask)
        }
        return builder.build()
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

        override fun onRecoveryOffer(terminal: String, everBrowserFront: Boolean) {
            manager.offerRecovery(LiveLogRecovery.recoveryEligible(terminal, everBrowserFront))
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

        /**
         * PHASE_VEXP6 WI4: the working notification's "Stop task" action — fires the
         * service, which cancels the active run through the SAME [TaskCancelFlag] the
         * corner log's ⏹ control drives.
         */
        const val ACTION_STOP_TASK = "navigator.app.action.STOP_TASK"
        const val EXTRA_GOAL_ID = "goalId"

        private const val DEBUG_GOALS_ASSET = "harness/goals.json"

        /**
         * D4: how deep the readiness pre-step looks for the browser's EXISTING task
         * ([ActivityManager.getRunningTasks]); the browser sits within the recent few
         * when the platform yields cross-app rows at all.
         */
        private const val REORDER_TASKS_LIMIT = 50

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
