package navigator.app.harness

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import navigator.app.loop.AutoDenyGatePresenter
import navigator.app.loop.AutoKeepGoingCompletionPresenter
import navigator.app.loop.DebugCompletionCardPresenter
import navigator.app.loop.DebugGatePresenter
import navigator.app.loop.GestureExecutor
import navigator.app.loop.NavigationLoop
import navigator.app.loop.RunResult
import navigator.app.loop.ShadowRouter
import navigator.app.gen.asAssistSource
import navigator.app.gen.asPhraseSource
import navigator.app.laya.LayaCheckpoint
import navigator.app.laya.LayaEngine
import navigator.app.laya.LayaOnnxSession
import navigator.app.observe.NavigatorAccessibilityService
import navigator.core.log.DecisionLogRow

/**
 * The headless debug harness (phase doc §2.2): pick engine (STUB with a scripted
 * transcript | LIVE Jev), pick a scripted goal, run the loop, stream the step log to
 * the screen and to a file, write DecisionLog rows, export a run report JSON to app
 * storage. NOT product UI — Phases 9/11 own that.
 *
 * Headless mode (Gate 6.2): launching with intent extras auto-runs goals with the STUB
 * engine and test-mode auto-deny (the flag defaults ON here; product UI has no auto
 * modes). Reports land in the app's external files dir (`run-reports/`), pullable via
 * adb; `suite-report.json` appears when a run batch finishes.
 *
 * Owner-directed visibility tooling (2026-10-06, harness-only): the harness is the UAT
 * session's home base — `--ez showOnly true` brings it to the front without starting or
 * refusing anything; a run keeps a live per-step progress notification on the
 * `navigator-debug` channel and at ANY terminal the harness comes back to the front
 * with one short buzz, a final verdict notification, and the step log scrolled to the
 * verdict. Custom goals ride visibly in the goal list. Whenever the launch requests the
 * shadow (by any alias), EVERY per-run report carries the `shadowReport` object.
 */
class HarnessActivity : ComponentActivity() {

    /**
     * The PROCESS-LIFETIME run scope (Phase 8C harness fix): a run must survive the
     * activity being destroyed under memory pressure while the browser is foreground —
     * measured on the emulator 2026-10-06: the system destroyed the stopped harness
     * activity during the ~100s v32b engine open (tokenizer parse + session load) and
     * the instance-scoped scope cancelled the run mid-flight. The UI lists stay
     * instance-bound (a recreated activity starts a fresh log pane); reports, log rows
     * and the terminal notification are process-level and unaffected.
     */
    private val scope: CoroutineScope get() = runScope
    private val uiLog = mutableStateListOf<String>()
    private val running = mutableStateOf(false)

    /** The full engine mode (Phase 8C: STUB | LIVE(jev) | LAYA | LAYA_FB_JEV); intent-set. */
    private val engineMode = mutableStateOf(HarnessEngineMode.STUB)
    private val autoDeny = mutableStateOf(true)
    private val shadowOn = mutableStateOf(false)
    private val customGoals = mutableStateListOf<ScriptedGoal>()
    private val logListState = LazyListState()

    /** Shadow sampling period for the next runs (1 = every step, vision 02 §4.3). */
    private var shadowEveryNth: Long = 1

    /** The loop's post-gesture page settle for the next runs (adb-settable; ms). */
    private var settleMs: Long = HarnessLaunch.DEFAULT_SETTLE_MS

    /**
     * Phase 10: the LIVE LADDER mode (`--ez ladder true`) — the next runs restructure
     * the goal through the writer and execute over the validated ladder. Needs a
     * non-STUB engine; the writer's per-call usage rides the run report's answer block
     * and the writer-spend field.
     */
    private var ladderMode = false

    /**
     * The LIVE run's OpenRouter key as delivered by the launch intent extra (adb key
     * delivery: an app process has no environment or `.tools/keys.env`). SECRET — it is
     * only ever handed to [HarnessEngineFactory]; it is never logged, never written to
     * any report, and never stored anywhere else.
     */
    @Volatile
    private var deliveredOpenRouterKey: String? = null

    /**
     * The runtime-built custom goal for this launch: set when the intent carries
     * `customGoalText`/`customStartUrl` — it REPLACES the catalog selection for this run
     * and refuses to run under the STUB engine (no script exists to replay). Registered
     * custom goals also appear as rows in the goal list ([customGoals]).
     */
    @Volatile
    private var customGoal: ScriptedGoal? = null

    private lateinit var catalog: GoalCatalog
    private lateinit var pagesServer: HarnessPagesServer
    private val logSink by lazy { RoomDecisionLogSink(this) }

    @Volatile
    private var cancelRequested = false

    /**
     * Per-run shadow bookkeeping (owner-directed 2026-10-06): the uat-applepie-02 device
     * report had no shadow block because `--ez shadowOn true` was silently unrecognized
     * AND because the failure path (`failedReport`) never captured the router snapshot.
     * Every executeGoal now records its shadow state here, so both the success and the
     * failure report can always include the object.
     */
    private class RunShadow {
        @Volatile
        var snapshot: ShadowRouter.Snapshot? = null

        /** Why there is no snapshot despite the shadow being requested (for the report note). */
        @Volatile
        var failure: String? = null
    }

    @Volatile
    private var currentRunShadow: RunShadow? = null

    @Volatile
    private var currentRunWantsShadow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catalog = GoalCatalog.parse(assets.open(GOALS_ASSET).use { it.readBytes().decodeToString() })
        pagesServer = HarnessPagesServer(this).also { it.start() }
        createNotificationChannel()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), POST_NOTIFICATIONS_REQUEST_CODE)
        handleLaunchIntent(intent, freshInstance = true)
        setContent { HarnessScreen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent, freshInstance = false)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == POST_NOTIFICATIONS_REQUEST_CODE &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            log("notifications: POST_NOTIFICATIONS denied - the harness continues without step/verdict notifications")
        }
    }

    override fun onDestroy() {
        // The run scope is process-lifetime now (see [scope]) — destroying the activity
        // (memory pressure, rotation) must never cancel an in-flight run.
        if (this::pagesServer.isInitialized) pagesServer.stop()
        super.onDestroy()
    }

    // -- launch handling -----------------------------------------------------------------

    private fun intentExtras(intent: Intent): HarnessLaunch.Extras = object : HarnessLaunch.Extras {
        override fun string(name: String): String? = intent.getStringExtra(name)
        override fun bool(name: String, default: Boolean): Boolean = intent.getBooleanExtra(name, default)
        override fun long(name: String, default: Long): Long = intent.getLongExtra(name, default)
        override fun keys(): Set<String> = intent.extras?.keySet() ?: emptySet()
    }

    /**
     * Headless launch handling (Gate 6.2 runbook): works on a fresh instance AND on a
     * live one via [onNewIntent] — a silent no-op is a bug. When a run is already in
     * progress the launch is refused LOUDLY (log line), never silently dropped.
     * Exception: `--ez showOnly true` (owner-directed 2026-10-06) NEVER starts and
     * NEVER refuses a run — it only brings the harness to the front and refreshes the
     * goal list / step log (custom-goal extras riding along register rows only).
     */
    private fun handleLaunchIntent(intent: Intent?, freshInstance: Boolean) {
        if (intent == null) {
            if (!freshInstance) log("launch ignored: null intent")
            return
        }
        val launch = HarnessLaunch.parse(intentExtras(intent))
        if (launch.unrecognizedExtras.isNotEmpty()) {
            log(
                "UNRECOGNIZED harness extra(s) ${launch.unrecognizedExtras} - IGNORED (a typo silently " +
                    "changes nothing; known names: navigator.harness.extra.* + short aliases)",
            )
        }
        if (launch.showOnly) {
            launch.customGoal?.let { registerCustomGoal(it) }
            log(
                "show-only: harness at front - no run started" +
                    (launch.customGoal?.let { " (custom goal row ${it.id} registered)" } ?: ""),
            )
            return
        }
        if (!launch.extrasPresent) return // e.g. a notification tap: bring-to-front only
        val extraGoal = launch.goalId
        if (extraGoal == null && !launch.customRequested) {
            if (!freshInstance) log("launch ignored: the intent carries no headless extras")
            return
        }
        if (running.value) {
            log(
                "LAUNCH REFUSED (loud): a run is already in progress - re-launch with " +
                    "`am start -S` for a fresh instance. Requested goal=$extraGoal (ignored)",
            )
            return
        }
        customGoal = if (!launch.customRequested) {
            null
        } else {
            val built = launch.customGoal
            if (built == null) {
                log("LAUNCH REFUSED (loud): ${launch.customError}")
                return
            }
            if (launch.mode == HarnessEngineMode.STUB) {
                log(
                    "LAUNCH REFUSED (loud): a custom goal needs a non-STUB engine - " +
                        "re-launch with `--es engine live` (or laya / laya-fb-jev); " +
                        "custom goals carry no STUB script",
                )
                return
            }
            registerCustomGoal(built)
            log(
                "custom goal: id=${built.id} family=${built.family} " +
                    "start=${built.startUrl} expected=${built.expectedTerminal} " +
                    "goal=\"${built.goal}\"",
            )
            built
        }
        engineMode.value = launch.mode
        autoDeny.value = launch.autoDeny
        shadowOn.value = launch.shadow
        shadowEveryNth = launch.shadowEveryNth
        settleMs = launch.settleMs
        ladderMode = launch.ladder
        if (launch.ladder && launch.mode == HarnessEngineMode.STUB) {
            log(
                "LAUNCH REFUSED (loud): the ladder mode needs a non-STUB engine - the writer " +
                    "restructure call needs the OpenRouter key; re-launch with `--es engine live`",
            )
            return
        }
        // SECRET: parsed and held only; NEVER logged (the headless line below reports
        // only whether an extra was present, never its value).
        deliveredOpenRouterKey = intent.getStringExtra(HarnessLaunch.EXTRA_OPENROUTER_KEY)
            ?: intent.getStringExtra(HarnessLaunch.EXTRA_OPENROUTER_KEY_SHORT)
        log(
            "headless run: goal=$extraGoal engine=${engineMode.value.name.lowercase()} " +
                "autoDeny=${autoDeny.value} shadow=${shadowOn.value} " +
                "shadowEveryNth=$shadowEveryNth delay=${launch.startDelayMs}ms fresh=$freshInstance " +
                "keyExtra=${launch.keyPresent}",
        )
        scope.launch {
            delay(launch.startDelayMs)
            // A custom goal replaces the catalog selection; the selection string is then
            // unused (requestedGoals returns the custom goal alone).
            runSelected(extraGoal ?: customGoal?.id ?: return@launch)
        }
    }

    // -- orchestration -----------------------------------------------------------------

    private fun log(message: String) {
        val stamped = "[${System.currentTimeMillis()}] $message"
        uiLog.add(0, stamped) // newest first for the debug pane
        android.util.Log.d(LOG_TAG, message)
    }

    /** Upserts a runtime-built custom goal into the on-screen goal list (visible UAT rows). */
    private fun registerCustomGoal(goal: ScriptedGoal) {
        val existing = customGoals.indexOfFirst { it.id == goal.id }
        if (existing >= 0) customGoals[existing] = goal else customGoals.add(goal)
    }

    private suspend fun awaitService(): NavigatorAccessibilityService {
        val deadline = System.currentTimeMillis() + SERVICE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            NavigatorAccessibilityService.instance?.let { return it }
            delay(SERVICE_POLL_MS)
        }
        throw IllegalStateException(
            "NavigatorAccessibilityService is not enabled - enable it in Settings > Accessibility",
        )
    }

    private fun openBrowser(url: String, forceRecreate: Boolean = false) {
        val browser = HarnessObserver.installedBrowser(this)
            ?: throw IllegalStateException("no allowlisted browser is installed")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(browser)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (forceRecreate) {
            // Gate-105 escalation: a WARM webview shell ignores onNewIntent URLs (the
            // activity only reads the intent in onCreate), and killBackgroundProcesses is
            // unreliable under modern background-kill restrictions. CLEAR_TASK destroys
            // the shell's task so the next start runs onCreate and honors the URL —
            // used only on the verified-navigation RETRY path (never the first fire, so
            // an ordinary run keeps its browsing context).
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        // Both loop VIEW intents ride this seam — cold-start and the 2026-10-08
        // card-restore — so the run trace shows every re-front of the browser.
        log("browser: firing VIEW intent for $url${if (forceRecreate) " (force-recreate)" else ""}")
        try {
            runOnUiThread { startActivity(intent) }
        } catch (e: Exception) {
            // the shell throws on navigations it will not handle (e.g. about:); the
            // readiness loop re-fires, so this is recoverable — never crash the harness
            log("openBrowser($url) failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun goalIds(selection: String): List<String> =
        if (selection.equals(ALL_GOALS, ignoreCase = true)) catalog.goals.map { it.id }
        else listOf(catalog.byId(selection).id)

    /**
     * The goals this launch actually runs: a custom goal REPLACES the catalog selection
     * entirely (one launch, one real-site goal); `--es goal all` still means the whole
     * fixture catalog when no custom goal was requested.
     */
    private fun requestedGoals(selection: String): List<ScriptedGoal> {
        val custom = customGoal ?: return goalIds(selection).map { catalog.byId(it) }
        return listOf(custom)
    }

    /**
     * A goal-list Run button: runs EXACTLY the picked goal (custom rows included — the
     * same executeGoal path as the extras flow). A custom goal under the STUB engine is
     * refused loudly here at run time (the launch-time refusal only covers headless
     * launches, where show-only must stay silent).
     */
    private fun runGoal(goal: ScriptedGoal) {
        if (goal.family == GoalCatalog.CUSTOM_FAMILY && engineMode.value == HarnessEngineMode.STUB) {
            log(
                "RUN REFUSED (loud): custom goal ${goal.id} needs a non-STUB engine - flip the " +
                    "LIVE Jev switch or launch with `--es engine laya` (custom goals carry no STUB script)",
            )
            return
        }
        scope.launch { runGoals(listOf(goal)) }
    }

    private suspend fun runSelected(selection: String) {
        runGoals(requestedGoals(selection))
    }

    private suspend fun runGoals(goals: List<ScriptedGoal>) {
        if (running.value) return
        running.value = true
        cancelRequested = false
        currentRunShadow = null
        currentRunWantsShadow = shadowOn.value
        val reports = mutableListOf<RunReport>()
        notifyProgress(null, "starting...")
        try {
            // The FIRST page load after the service binds takes ~90s to expose its web
            // content through the a11y bridge on this emulator; every later load is
            // instant. Cold-start the browser (service already bound) and prime the
            // pipeline once: alternate target <- blank <- target, polling fresh reads,
            // until real page content is visible.
            warmUpWebAccessibility()
            for (goal in goals) {
                if (cancelRequested) break
                log("=== goal ${goal.id} (${goal.family}): \"${goal.goal}\"")
                notifyProgress(goal.id, "running")
                // Fault isolation + environment-flake retries: a REFUSED run that never
                // got a successful decision is observation starvation (0-line/collapsed
                // trees), not a genuine refusal — retry up to STARVATION_RETRIES times,
                // cold-starting the browser between attempts. Genuine refusals (the
                // unreadable-tree family, whose expected terminal IS REFUSED) are never
                // retried.
                var attempt = 1
                var report = try {
                    withTimeout(GOAL_TIMEOUT_MS) { executeGoal(goal, attempt) }
                } catch (t: Throwable) {
                    log("GOAL ${goal.id} FAILED: ${t::class.simpleName}: ${t.message}")
                    failedReport(goal, t, attempt)
                }
                while (attempt <= STARVATION_RETRIES && isStarvationRefusal(report)) {
                    attempt += 1
                    log("goal ${goal.id} starved (observation starvation) - cold-start retry $attempt")
                    try {
                        val service = awaitService()
                        coldStartBrowser(service, goal.startUrl)
                        delay(PAGE_SETTLE_MS)
                    } catch (t: Throwable) {
                        log("cold start before retry failed: ${t::class.simpleName}: ${t.message}")
                    }
                    report = try {
                        withTimeout(GOAL_TIMEOUT_MS) { executeGoal(goal, attempt) }
                    } catch (t: Throwable) {
                        log("GOAL ${goal.id} FAILED on retry: ${t::class.simpleName}: ${t.message}")
                        failedReport(goal, t, attempt)
                    }
                }
                reports += report
            }
        } catch (t: Throwable) {
            log("HARNESS ERROR: ${t::class.simpleName}: ${t.message}")
            announceTerminal(null, "HARNESS_ERROR", "${t::class.simpleName}: ${t.message}")
        } finally {
            writeSuiteReport(reports)
            running.value = false
        }
    }

    /** Observation starvation: a REFUSED that is the loop's unreadable-tree refusal
     *  (0-line or collapsed-subtree trees — never a genuine expected refusal, whose
     *  expected terminal IS REFUSED), or a HARNESS_ERROR (harness/environment failure,
     *  never a goal outcome). Only these are flake-retried; genuine refusals are not. */
    private fun isStarvationRefusal(report: RunReport): Boolean =
        (report.terminal == "REFUSED" &&
            report.expectedTerminal != "REFUSED" &&
            report.terminalDetail == navigator.app.loop.NavigationLoop.REFUSAL_MESSAGE) ||
            report.terminal == "HARNESS_ERROR" ||
            // Gate-105: a kill whose cause is an empty stage-2 slate is the dark/partial
            // tree's signature too (the grace ran out) — retry it like a starvation.
            (report.terminal == "STUCK_KILL" && report.terminalDetail.contains("slate was empty"))

    /**
     * Navigates target <-> blank until the target page's CONTENT subtree is readable
     * (pageNodeCount >= 5) or the budget runs out. Failure is not fatal: the goals'
     * own readiness windows handle it from there.
     */
    private suspend fun warmUpWebAccessibility() {
        val service = awaitService()
        val firstGoal = catalog.goals.first()
        log("warm-up: cold-starting the browser with the observation service bound")
        coldStartBrowser(service, firstGoal.startUrl)
        val deadline = System.currentTimeMillis() + WARMUP_WAIT_MS
        var navigateToTarget = false // the cold start already navigated to the target
        val startMarker = firstGoal.startUrl.substringAfterLast('/')
        while (System.currentTimeMillis() < deadline) {
            if (SystemDialogAutoDismiss.dismissIfPresent(service)) {
                log("warm-up: dismissed a system error dialog (ANR)")
            }
            val read = service.observeForLoop()
            val urlText = when (val u = read.url) {
                is navigator.app.observe.UrlBarReader.Url.Value -> u.text
                else -> ""
            }
            // Gate-105: "primed" must mean ready for the LOOP, not merely text-visible —
            // the shell materializes prose before interactive nodes, and a run started on
            // a text-only tree reads an EMPTY candidate slate (the empty-slate grace's
            // root case). Require a CLICKABLE page-content node (the webview container is
            // scrollable-only and the browser chrome is excluded — neither proves links).
            val hasPageCandidate = read.nodes.any {
                val s = it.snapshot
                !s.browserChrome && s.clickable
            }
            if (urlText.contains(startMarker) && read.lines.size >= 5 &&
                read.pageNodeCount >= 5 && hasPageCandidate
            ) {
                log("warm-up: web a11y primed (content visible, candidates present)")
                return
            }
            openBrowser(if (navigateToTarget) firstGoal.startUrl else WARMUP_BLANK)
            navigateToTarget = !navigateToTarget
            delay(WARMUP_STEP_MS)
        }
        log("warm-up: content still not visible after ${WARMUP_WAIT_MS}ms - continuing")
    }

    /**
     * The only browser state that reliably exposes web content through the a11y bridge:
     * a process cold-started WHILE the observation service is bound. HOME drops the
     * browser to background so [ActivityManager.killBackgroundProcesses] can kill it,
     * then the harness navigates to [url] — a fresh WebView initialized with
     * accessibility already enabled — and VERIFIES the navigation actually landed
     * ([navigateAndVerify]: the emulator's webview shell ignores VIEW intents while its
     * activity is warm — proven gate-105 — so an unverified fire silently leaves the
     * loop reading the previous goal's page).
     */
    private suspend fun coldStartBrowser(
        service: NavigatorAccessibilityService,
        url: String,
    ) = navigateAndVerify(service, url)

    /**
     * Fires the browser at [url] and VERIFIES it arrived within a bounded window,
     * retrying the whole cold-start sequence (HOME -> kill -> VIEW) on failure. Bound:
     * [NAVIGATE_VERIFY_ATTEMPTS] attempts x [NAVIGATE_VERIFY_WINDOW_MS]. Returns whether
     * the target URL was observed; callers continue either way (the goals' own
     * readiness windows own the rest) — but the run trace always knows.
     */
    private suspend fun navigateAndVerify(
        service: NavigatorAccessibilityService,
        url: String,
    ): Boolean {
        val marker = url.substringAfterLast('/')
        repeat(NAVIGATE_VERIFY_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                log("navigate: retrying the browser cold start for $url (attempt ${attempt + 1})")
            }
            runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
            delay(BACKGROUND_SETTLE_MS)
            HarnessObserver.installedBrowser(this)?.let { browser ->
                runCatching {
                    (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                        ?.killBackgroundProcesses(browser)
                }
            }
            delay(COLD_START_SETTLE_MS)
            openBrowser(url, forceRecreate = attempt >= 2)
            val deadline = System.currentTimeMillis() + NAVIGATE_VERIFY_WINDOW_MS
            while (System.currentTimeMillis() < deadline) {
                SystemDialogAutoDismiss.dismissIfPresent(service)
                val read = service.observeForLoop()
                val urlText = when (val u = read.url) {
                    is navigator.app.observe.UrlBarReader.Url.Value -> u.text
                    else -> ""
                }
                if (urlText.contains(marker)) {
                    if (attempt > 0) log("navigate: browser reached $url on attempt ${attempt + 1}")
                    return true
                }
                delay(NAVIGATE_VERIFY_POLL_MS)
            }
        }
        log(
            "navigate: browser did NOT reach $url after $NAVIGATE_VERIFY_ATTEMPTS " +
                "cold-start attempts - proceeding (the run's own readiness windows own it)",
        )
        return false
    }

    /** A failure record so the suite report shows the gap instead of hiding it.
     *  Carries the shadow block too whenever the run requested the shadow (the
     *  zeroed-with-note shape — see [shadowReport]). */
    private fun failedReport(goal: ScriptedGoal, t: Throwable, attempt: Int = 1) = RunReport(
        goalId = goal.id,
        family = goal.family,
        goal = goal.goal,
        taskId = "failed-${System.currentTimeMillis()}",
        engine = engineMode.value.name.lowercase(),
        autoDeny = autoDeny.value,
        triage = "unknown",
        terminal = "HARNESS_ERROR",
        terminalDetail = "${t::class.simpleName}: ${t.message}",
        expectedTerminal = goal.expectedTerminal,
        terminalMatchesExpected = false,
        steps = 0,
        rowsLogged = 0,
        metaDecisions = 0,
        logGaps = 0,
        gateEvents = 0,
        spendMicroUsd = 0,
        elapsedMs = 0,
        softWarnings = emptyList(),
        partialResult = "",
        startedAt = System.currentTimeMillis(),
        finishedAt = System.currentTimeMillis(),
        attempts = attempt,
        flakeRetried = attempt > 1,
        stepLog = emptyList(),
        shadow = shadowReport(currentRunWantsShadow, currentRunShadow, joined = null),
    )

    private suspend fun executeGoal(goal: ScriptedGoal, attempt: Int = 1): RunReport {
        val mode = engineMode.value
        // The laya modes run BARE (vision 02 §7: no shadow, no shadow sampling) — a
        // shadow request under them is refused LOUDLY and the run proceeds without it.
        val wantShadow = shadowOn.value && !mode.isLaya
        if (shadowOn.value && mode.isLaya) {
            log("shadow requested under engine=${mode.name.lowercase()} - REFUSED (the laya " +
                "modes carry no shadow; vision 02 §7) - running without it")
        }
        currentRunWantsShadow = wantShadow
        // Registered BEFORE anything can fail so even a harness error before the loop
        // still reports a (zeroed, noted) shadow block when the shadow was requested.
        val runShadow = if (wantShadow) RunShadow().also { currentRunShadow = it } else null
        // Bind the observation service BEFORE launching the browser: Chromium only
        // builds its a11y tree for services connected at (or after) page load.
        val service = awaitService()
        openBrowser(goal.startUrl)
        delay(PAGE_SETTLE_MS)
        // Readiness: poll fresh reads until the START PAGE ITSELF is readable (URL match
        // + a real content subtree). Chromium enables web-content a11y on the SECOND
        // page load after the service binds, so retries alternate target <- blank <-
        // target: each retry forces a fresh load. Any system error dialog (the "System
        // UI isn't responding" ANR) is auto-dismissed on sight. (unreadable-1 times out
        // here by design, then refuses in the loop.)
        val startMarker = goal.startUrl.substringAfterLast('/')
        val readinessDeadline = System.currentTimeMillis() + READINESS_WAIT_MS
        var navigationAttempt = 0
        while (System.currentTimeMillis() < readinessDeadline) {
            if (SystemDialogAutoDismiss.dismissIfPresent(service)) {
                log("readiness: dismissed a system error dialog (ANR)")
            }
            val read = service.observeForLoop()
            val urlText = when (val u = read.url) {
                is navigator.app.observe.UrlBarReader.Url.Value -> u.text
                else -> ""
            }
            if (urlText.contains(startMarker) && read.lines.size >= 5 && read.pageNodeCount >= 5) break
            navigationAttempt += 1
            openBrowser(if (navigationAttempt % 2 == 0) goal.startUrl else ABOUT_BLANK)
            delay(READINESS_POLL_MS * (READINESS_RETRY_POLL_STEPS - 1).coerceAtLeast(1))
        }
        if (attempt > 1) log("goal ${goal.id} attempt $attempt")
        val built = HarnessEngineFactory.build(
            mode,
            goal,
            deliveredKey = deliveredOpenRouterKey,
            laya = if (mode.isLaya) {
                LayaEngineSource {
                    // The ACTIVE checkpoint is the Phase-8 421M base: the owner demoted
                    // v32b to eval-only on 2026-10-06 (UAT Tests 2-3 measured it
                    // disqualifying as primary — inverted exit calibration + poor
                    // navigation transfer; an eval-gated config decision, vision 02 §5).
                    LayaCheckpoint.LAYA_421M.engine(this@HarnessActivity).also {
                        log("laya: ${LayaCheckpoint.LAYA_421M.id} engine open " +
                            "(${LayaOnnxSession.ASSET_DIR}/${LayaCheckpoint.LAYA_421M.modelAsset}, " +
                            "cpu, 1 intra-op thread)")
                    }
                }
            } else {
                null
            },
        )
        log(
            "engine: ${built.mode} (${built.identity.configId}, ${built.identity.model})" +
                (built.fallbackIdentity?.let {
                    " + fallback (${it.configId}, ${it.model})"
                } ?: "") +
                "; auto-deny=${autoDeny.value}" +
                (built.keySource?.let { "; key source: ${it.name.lowercase()}" } ?: ""),
        )
        // Phase 8: shadow ON wraps the primary in the ShadowRouter (vision 02 §4) — the
        // loop cannot tell (ART-2); the sink gains the shadow fields of every row.
        val shadowSession = if (wantShadow) LayaOnnxSession.open(this) else null
        val shadowEngine = shadowSession?.let {
            log(
                "shadow: laya int8 session open (${LayaOnnxSession.ASSET_DIR}/${LayaOnnxSession.ASSET_FILE}, " +
                    "cpu, 1 intra-op thread)",
            )
            LayaEngine(it)
        }
        val router = shadowEngine?.let {
            ShadowRouter(
                primary = built.engine,
                shadow = it,
                samplingPolicy = ShadowRouter.SamplingPolicy.everyNth(shadowEveryNth),
                companionSink = logSink.companionSink, // late answers land as companion rows (8B)
            )
        }
        val loop = NavigationLoop(
            engine = router ?: built.engine,
            observer = HarnessObserver(service),
            executor = GestureExecutor(service),
            gatePresenter = if (autoDeny.value) AutoDenyGatePresenter else DebugGatePresenter(this),
            // Phase 8J: the completion card (vision 04 §3 Path C). Auto mode keeps going
            // (fail-safe, the auto-deny mirror); the interactive mode shows the law's
            // card with the 30s timeout = keep going.
            completionCardPresenter = if (autoDeny.value) {
                AutoKeepGoingCompletionPresenter
            } else {
                DebugCompletionCardPresenter(this)
            },
            logSink = notifyingSink(goal.id, router?.sink(logSink) ?: logSink),
            spendMeter = built.spendMeter,
            // Page-settle pacing (the loop's own post-gesture settle, NEVER shadow-driven —
            // vision 02 §4.3). adb-settable so a slow emulator can run Gate 8.4 with a step
            // period above the MEASURED shadow latency; default mirrors the loop default.
            config = NavigationLoop.Config(settleMs = settleMs),
            // Phase 8D cold-start (vision 04 §1): the loop's deterministic pre-step, run
            // BEFORE triage and the first OBSERVE — derive the goal's start URL in code,
            // probe the browser, fire the VIEW intent only when the browser is absent or
            // on an unrelated page. NO engine is consulted. (On a LADDER run the site
            // entry folds under the ladder — this seam stays wired and unused there.)
            browserProbe = navigator.app.loop.BrowserProbe {
                HarnessObserver.browserState(service, this)
            },
            browserLauncher = navigator.app.loop.BrowserLauncher { url -> openBrowser(url) },
            // Phase 10 (Gate 10.4): the LIVE LADDER mode — goal → restructure → ladder
            // loop → extraction, all calls metered into the separate writer budget.
            ladderSource = if (ladderMode) ladderSource(deliveredOpenRouterKey) else {
                navigator.app.loop.LadderSource.None
            },
            answerExtractor = if (ladderMode) {
                ladderSource(deliveredOpenRouterKey)
            } else {
                null
            },
            // Phase 10B (phase doc §2.6): the cascade's writer seams — the handshake's
            // assist + the fill-field phrase. LIVE modes use the GenClient adapters over
            // the SAME key delivery; a STUB run has no writer — its fill-field steps use
            // the $0 deterministic [StubPhraseSource] (the pre-cascade behavior) and an
            // UNCERTAIN stage-1 falls to the code fallback.
            assistSource = if (built.mode == HarnessEngineMode.STUB) null else {
                writerSources(deliveredOpenRouterKey)?.first
            },
            phraseSource = if (built.mode == HarnessEngineMode.STUB) {
                navigator.app.stub.StubPhraseSource
            } else {
                writerSources(deliveredOpenRouterKey)?.second
            },
        )
        val startedAt = System.currentTimeMillis()
        val result = try {
            loop.run(
                goal = goal.goal,
                goalId = goal.id,
                engineIdentity = built.identity,
                isCancelled = { cancelRequested },
                fallbackIdentity = built.fallbackIdentity,
                startUrl = goal.startUrl,
            )
        } finally {
            // The shadow is fire-and-forget: settle it before the report freezes counts.
            try {
                val snapshot = router?.let { r ->
                    r.awaitIdle(SHADOW_SETTLE_TIMEOUT_MS)
                    r.snapshot()
                }
                if (wantShadow) {
                    if (snapshot != null) runShadow?.snapshot = snapshot
                    else runShadow?.failure = "router produced no snapshot at settle"
                }
                snapshot?.let {
                    // percentile() takes SORTED input — sort here too, or the log line's
                    // p50/p95 disagree with the report's (measured in the 2026-10-05 sweep).
                    val forwardsSorted = it.latenciesMs.map { p -> p.first }.sorted()
                    val totalsSorted = it.latenciesMs.map { p -> p.second }.sorted()
                    log(
                        "shadow: sampled=${it.sampled} completed=${it.completions} miss=${it.misses} " +
                            "fail=${it.failures} companions=${it.companionRows} " +
                            "droppedAfterClose=${it.droppedAfterClose} " +
                            "forwardMs(p50/p95)=" +
                            "${percentile(forwardsSorted, 50)}/" +
                            "${percentile(forwardsSorted, 95)} " +
                            "totalMs(p95)=${percentile(totalsSorted, 95)}",
                    )
                }
                // Phase 8B: the logger is closed for companions — any straggler completion
                // from here on is dropped AND counted (amendment rule c).
                router?.close()
            } catch (t: Throwable) {
                // Settle failures must not hide the run's outcome — record them so the
                // report's shadow block explains its own emptiness instead.
                if (wantShadow) {
                    runShadow?.failure = "shadow settle failed: ${t::class.simpleName}: ${t.message}"
                }
                log("shadow settle failed: ${t::class.simpleName}: ${t.message}")
            } finally {
                shadowSession?.close()
                // Phase 8C: the on-device laya engine is per-ATTEMPT — close it here so a
                // starvation retry never opens a second ~1.4 GB session beside the first
                // (measured LMK kill on the 2-4 GB emulator, 2026-10-06).
                built.primaryCloseable?.let { closer ->
                    runCatching { closer.close() }.onFailure {
                        log("laya engine close failed: ${it::class.simpleName}: ${it.message}")
                    }
                }
            }
        }
        result.stepLog.forEach { log(it) }
        // Phase 8B: the run-report coverage is the JOINED view (vision 05 §3 amendment
        // rule d) — decision-row shadows + companions for THIS task, from the database.
        val joinedAgreement = runCatching {
            logSink.shadowResults.joinedAgreementByTask(result.taskId)
        }.onFailure {
            log("joined agreement read failed: ${it::class.simpleName}: ${it.message}")
        }.getOrNull()
        val report = RunReport(
            goalId = goal.id,
            family = goal.family,
            goal = goal.goal,
            taskId = result.taskId,
            engine = built.mode.name.lowercase(),
            autoDeny = autoDeny.value,
            triage = result.triage,
            triageClass = result.triageClass,
            budgetProfile = result.budgetProfile,
            terminal = result.terminal.name,
            terminalDetail = result.terminalDetail,
            expectedTerminal = goal.expectedTerminalFor(autoDeny.value),
            terminalMatchesExpected = result.terminal.name == goal.expectedTerminalFor(autoDeny.value),
            steps = result.steps,
            rowsLogged = result.rowsLogged,
            metaDecisions = result.metaDecisions,
            /**
             * decisions without a row beyond the documented exclusion: the rephrase
             * recovery stub is a goal-text meta-decision (no page action, no row —
             * counted in metaDecisions), so "zero log gaps" stays meaningful: every
             * NAVIGATION decision is logged exactly once.
             */
            logGaps = result.logGaps,
            gateEvents = result.gateEvents,
            spendMicroUsd = result.spendMicroUsd,
            elapsedMs = result.elapsedMs,
            softWarnings = result.softWarnings,
            partialResult = result.partialResult,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            attempts = attempt,
            flakeRetried = attempt > 1,
            stepLog = result.stepLog,
            shadow = shadowReport(wantShadow, runShadow, joinedAgreement),
            escalations = built.fallbackEngine?.stats()?.let { stats ->
                log(
                    "escalations: ${stats.escalations}/${stats.decisions} decisions " +
                        "(rate ${"%.1f".format(stats.rate * 100)}%)",
                )
                EscalationStats(
                    decisions = stats.decisions,
                    escalations = stats.escalations,
                    rate = stats.rate,
                )
            },
            exit = result.exit?.let { exit ->
                log(
                    "exit: path=${exit.path} guard=${exit.guardLevel} noul=${exit.noul} " +
                        "checks=${exit.finishChecks} card=${exit.completionCardShown}" +
                        (exit.completionCardResolution?.let { " ($it)" } ?: ""),
                )
                ExitReport(
                    path = exit.path,
                    guardLevel = exit.guardLevel,
                    noul = exit.noul,
                    finishChecks = exit.finishChecks,
                    completionCardShown = exit.completionCardShown,
                    completionCardResolution = exit.completionCardResolution,
                )
            },
            targets = result.targets?.let { targets ->
                log("targets: $targets")
                TargetTrackerReport(
                    tracked = targets.tracked,
                    processed = targets.processed,
                    pending = targets.pending,
                    skipped = targets.skipped,
                    drained = targets.drained,
                )
            },
            plan = result.plan?.let { plan ->
                log("plan: v${plan.version} (${plan.subgoals.size} subgoals, provenance only)")
                PlanReport(version = plan.version, subgoals = plan.subgoals)
            },
            ladder = result.ladder?.let { ladder ->
                log(
                    "ladder: v${ladder.version} gen${ladder.generation} " +
                        "(${ladder.subtasks.count { it.completed }}/${ladder.subtasks.size} done, " +
                        "class ${ladder.taskClass})",
                )
                LadderReport(
                    version = ladder.version,
                    generation = ladder.generation,
                    summary = ladder.summary,
                    taskClass = ladder.taskClass,
                    subtasks = ladder.subtasks.map {
                        LadderSubtaskReport(
                            instruction = it.instruction,
                            done = it.done,
                            completed = it.completed,
                            active = it.active,
                        )
                    },
                )
            },
            answer = result.answer?.let { answer ->
                log(
                    "answer: ${answer.promptVersion} (${answer.text.length} chars, " +
                        "${answer.inputTokens}+${answer.outputTokens} tokens, " +
                        "${answer.costMicroUsd} micro-USD)",
                )
                AnswerReport(
                    text = answer.text,
                    promptVersion = answer.promptVersion,
                    inputTokens = answer.inputTokens,
                    outputTokens = answer.outputTokens,
                    costMicroUsd = answer.costMicroUsd,
                )
            },
            writerSpendMicroUsd = result.writerSpendMicroUsd,
        )
        val path = RunReportStore.write(this, "run-${goal.id}-${result.taskId}.json", RunReports.json(report))
        log("report: $path")
        log(
            "=== ${goal.id}: ${result.terminal} (expected ${goal.expectedTerminal}, " +
                "gaps=${result.logGaps}, attempts=$attempt)",
        )
        announceTerminal(goal.id, result.terminal.name, result.terminalDetail)
        return report
    }

    /**
     * Phase 10B: the cascade's writer seams (assist + phrase) over the pinned
     * generative config, keyed with the SAME delivery priority as the ladder mode
     * (intent extra > env > keys file). Null when no key is configured — a STUB run
     * needs no writer; the loop's null-seam degradation is the lawful behavior (the
     * code fallback / fill-field-to-recovery). Never throws.
     */
    private fun writerSources(
        deliveredKey: String?,
    ): Pair<navigator.app.loop.AssistSource, navigator.app.loop.PhraseSource>? {
        val envKey = System.getenv(navigator.core.wire.DefaultEngineConfigs.OPENROUTER_KEY_ENV)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val key = deliveredKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: envKey
            ?: runCatching {
                navigator.core.wire.DefaultEngineConfigs.resolveKey(
                    navigator.core.wire.DefaultEngineConfigs.OPENROUTER_KEY_ENV,
                )
            }.getOrNull()
            ?: return null
        val client = navigator.app.gen.GenClient(
            transport = navigator.core.wire.OkHttpWireTransport(),
            config = navigator.app.gen.GenEngineConfigs.generative(key),
        )
        return client.asAssistSource() to client.asPhraseSource()
    }

    /**
     * Phase 10: the LIVE ladder source + answer extractor over the pinned generative
     * config ([navigator.app.gen.GenEngineConfigs]), keyed with priority the adb
     * intent extra > env > the keys file (the [HarnessEngineFactory] delivery law).
     * A missing key fails LOUDLY — never a silent un-laddered run when the launch
     * requested the ladder mode. The key's value is never logged (the source only).
     */
    private fun ladderSource(deliveredKey: String?): navigator.app.gen.GenLadderSource {
        val envKey = System.getenv(navigator.core.wire.DefaultEngineConfigs.OPENROUTER_KEY_ENV)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val key = deliveredKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: envKey
            ?: navigator.core.wire.DefaultEngineConfigs.resolveKey(
                navigator.core.wire.DefaultEngineConfigs.OPENROUTER_KEY_ENV,
            )
        if (key == null) {
            throw IllegalStateException(
                "the ladder mode needs an OpenRouter key for the writer - pass it via the " +
                    "harness intent extra (--es openrouterKey <KEY>), or set " +
                    "${navigator.core.wire.DefaultEngineConfigs.OPENROUTER_KEY_ENV}, or add it " +
                    "to the git-ignored .tools/keys.env. No key configured, refusing.",
            )
        }
        val client = navigator.app.gen.GenClient(
            transport = navigator.core.wire.OkHttpWireTransport(),
            config = navigator.app.gen.GenEngineConfigs.generative(key),
            onUsage = { usage ->
                log(
                    "writer: ${usage.inputTokens}+${usage.outputTokens} tokens, " +
                        "${usage.costMicroUsd} micro-USD",
                )
            },
        )
        return navigator.app.gen.GenLadderSource(client)
    }

    /**
     * Phase 8/8B evidence: the shadow side of one run — ALWAYS present when the run
     * requested the shadow (owner-directed 2026-10-06: uat-applepie-02's report had no
     * shadow block at all; that must be impossible). [joined] is the database's
     * joined-view agreement for this task (vision 05 §3 amendment rule d) — when
     * present with sampled rows, [ShadowReport.coverage] is the joined coverage
     * (decision-row shadows + companions); the in-memory row-enrichment ratio is only
     * the fallback. On the degraded paths (no router snapshot: session open failed,
     * harness error before the loop, settle failure) the block is zeroed with a [note].
     */
    private fun shadowReport(
        wantShadow: Boolean,
        runShadow: RunShadow?,
        joined: navigator.core.log.JoinedAgreementMetrics?,
    ): ShadowReport? {
        if (!wantShadow) return null
        val snapshot = runShadow?.snapshot
        if (snapshot == null) {
            return ShadowReport(
                enabled = true,
                everyNth = shadowEveryNth,
                decisions = 0,
                sampled = 0,
                completed = 0,
                missed = 0,
                failed = 0,
                coverage = 0.0,
                forwardLatencyP50Ms = 0,
                forwardLatencyP95Ms = 0,
                totalLatencyP95Ms = 0,
                companionRows = 0,
                droppedAfterClose = 0,
                companionRejected = 0,
                note = runShadow?.failure ?: "no router snapshot was taken for this run",
            )
        }
        val forwards = snapshot.latenciesMs.map { p -> p.first }.sorted()
        val totals = snapshot.latenciesMs.map { p -> p.second }.sorted()
        return ShadowReport(
            enabled = true,
            everyNth = snapshot.samplingEveryNth,
            decisions = snapshot.decisions,
            sampled = snapshot.sampled,
            completed = snapshot.completions,
            missed = snapshot.misses,
            failed = snapshot.failures,
            coverage = when {
                joined != null && joined.sampledRows > 0 -> joined.coverage
                snapshot.sampled > 0 -> snapshot.enrichedRows.toDouble() / snapshot.sampled
                else -> 0.0
            },
            forwardLatencyP50Ms = percentile(forwards, 50),
            forwardLatencyP95Ms = percentile(forwards, 95),
            totalLatencyP95Ms = percentile(totals, 95),
            companionRows = snapshot.companionRows,
            droppedAfterClose = snapshot.droppedAfterClose,
            companionRejected = snapshot.companionRejected,
        )
    }

    /** Nearest-rank percentile of a SORTED list (empty → 0). */
    private fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0
        val index = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun writeSuiteReport(reports: List<RunReport>) {
        val suite = SuiteReport(
            runs = reports,
            allPassed = reports.isNotEmpty() &&
                reports.all { it.terminalMatchesExpected == true && it.logGaps == 0 },
            finishedAt = System.currentTimeMillis(),
        )
        val path = RunReportStore.write(this, RunReportStore.SUITE_FILE, RunReports.suiteJson(suite))
        log("suite report (${reports.size} runs, allPassed=${suite.allPassed}): $path")
    }

    // -- run-over visibility (owner-directed UAT tooling) --------------------------------

    /**
     * The run-over moment, at ANY terminal: a FINAL verdict notification (non-ongoing)
     * replaces the progress notification, one short buzz fires, the harness comes back
     * to the front, and the step log scrolls to the verdict — which is already the
     * newest-first entry.
     */
    private fun announceTerminal(goalId: String?, terminal: String, detail: String) {
        notifyFinal(goalId, terminal, detail)
        buzz()
        returnToHarness()
    }

    /** One short buzz (~200ms). No runtime permission is needed for one-shot vibrations;
     *  defaults (no VibrationAttributes) are respected on purpose. Best-effort: a
     *  vibration failure never touches the run. */
    private fun buzz() {
        runCatching {
            val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
            vibrator.vibrate(VibrationEffect.createOneShot(BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }.onFailure { log("buzz failed: ${it::class.simpleName}: ${it.message}") }
    }

    /** Brings the harness to the front from the run coroutine (its home base) and
     *  scrolls the step log to the newest entry. Best-effort: never touches the run. */
    private fun returnToHarness() {
        runCatching {
            runOnUiThread {
                startActivity(
                    Intent(this, HarnessActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    ),
                )
                scope.launch(Dispatchers.Main) {
                    runCatching { logListState.scrollToItem(0) }
                }
            }
        }.onFailure { log("return-to-harness failed: ${it::class.simpleName}: ${it.message}") }
    }

    // -- notifications (harness-debug only; Phase 9 owns the product ones) ---------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            HarnessNotifications.CHANNEL_ID,
            HarnessNotifications.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notificationsAllowed(): Boolean =
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun runContentIntent(): PendingIntent {
        val intent = Intent(this, HarnessActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** The ONGOING per-step progress notification (id [HarnessNotifications.RUN_NOTIFICATION_ID];
     *  replaced in place at every step — the text carries ids/counts only, never typed
     *  values). Absent when POST_NOTIFICATIONS was denied. */
    private fun notifyProgress(goalId: String?, text: String) {
        if (!notificationsAllowed()) return
        runCatching {
            val notification = Notification.Builder(this, HarnessNotifications.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(HarnessNotifications.progressTitle(goalId))
                .setContentText(text)
                .setContentIntent(runContentIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            getSystemService(NotificationManager::class.java)
                .notify(HarnessNotifications.RUN_NOTIFICATION_ID, notification.build())
        }.onFailure { log("progress notification failed: ${it::class.simpleName}: ${it.message}") }
    }

    /** The FINAL verdict notification (non-ongoing, same slot). */
    private fun notifyFinal(goalId: String?, terminal: String, detail: String) {
        if (!notificationsAllowed()) return
        runCatching {
            val notification = Notification.Builder(this, HarnessNotifications.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(HarnessNotifications.finalTitle(goalId))
                .setContentText(HarnessNotifications.finalText(terminal, detail))
                .setContentIntent(runContentIntent())
                .setOngoing(false)
            getSystemService(NotificationManager::class.java)
                .notify(HarnessNotifications.RUN_NOTIFICATION_ID, notification.build())
        }.onFailure { log("final notification failed: ${it::class.simpleName}: ${it.message}") }
    }

    /**
     * The loop's per-step heartbeat: every logged row refreshes the progress notification
     * BEFORE the row is delegated. ONLY non-secret fields reach the notification (goal
     * id, step number, action kind, chosen option id, disposition — never state text,
     * never typed values; see [HarnessNotifications]).
     */
    private fun notifyingSink(goalId: String, delegate: navigator.app.loop.DecisionLogSink) =
        object : navigator.app.loop.DecisionLogSink {
            override suspend fun write(row: DecisionLogRow) {
                notifyProgress(
                    goalId,
                    HarnessNotifications.stepText(
                        stepIndex = row.stepIndex,
                        actionKind = row.executedAction.kind.name,
                        chosenId = row.teacherAnswer.chosen,
                        disposition = row.executedAction.disposition.name,
                    ),
                )
                delegate.write(row)
            }

            // Exit-check rows are not navigation decisions (no page action, no
            // notification content) — they delegate untouched.
            override suspend fun writeExitCheck(row: navigator.core.log.ExitCheckRow) {
                delegate.writeExitCheck(row)
            }
        }

    // -- UI ------------------------------------------------------------------------------

    @Composable
    private fun HarnessScreen() {
        val goals = remember { catalog.goals }
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text("Navigator debug harness", style = MaterialTheme.typography.titleLarge)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("engine: ${engineMode.value.name.lowercase()}")
                // The switch covers the STUB <-> LIVE(jev) pair only; the laya modes
                // arrive via the launch intent (`--es engine laya|laya-fb-jev`) and
                // disable the switch (they are measurement modes, vision 02 §7).
                Text("LIVE Jev")
                Switch(
                    checked = engineMode.value == HarnessEngineMode.LIVE,
                    onCheckedChange = { live ->
                        engineMode.value = if (live) HarnessEngineMode.LIVE else HarnessEngineMode.STUB
                    },
                    enabled = engineMode.value == HarnessEngineMode.STUB ||
                        engineMode.value == HarnessEngineMode.LIVE,
                )
                Text("auto-deny (harness test mode)")
                Switch(checked = autoDeny.value, onCheckedChange = { autoDeny.value = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { runSelected(ALL_GOALS) } }, enabled = !running.value) {
                    Text(if (running.value) "Running..." else "Run all goals")
                }
                OutlinedButton(onClick = { cancelRequested = true }, enabled = running.value) {
                    Text("Cancel")
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                items(goals) { goal -> GoalRow(goal, custom = false) }
                items(customGoals) { goal -> GoalRow(goal, custom = true) }
            }
            Text("Step log (newest first)", style = MaterialTheme.typography.titleSmall)
            LazyColumn(modifier = Modifier.fillMaxSize(), state = logListState) {
                items(uiLog) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    /**
     * One goal row: the six scripted goals as shipped, plus runtime-built custom goals
     * (owner-directed 2026-10-06) with a distinct "custom" prefix, their goal text, and
     * the expected GOAL_ACHIEVED marker — so every UAT task is visible in the harness,
     * and its Run button takes the exact same path as the extras flow.
     */
    @Composable
    private fun GoalRow(goal: ScriptedGoal, custom: Boolean) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (if (custom) "custom " else "") +
                        "${goal.id} -> expected: ${goal.expectedTerminal}",
                    modifier = Modifier.padding(end = 8.dp),
                    color = Color.Black,
                )
                OutlinedButton(enabled = !running.value, onClick = { runGoal(goal) }) {
                    Text("Run")
                }
            }
            if (custom) {
                Text(goal.goal, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    companion object {
        /**
         * The process-lifetime run scope (see [scope]); never cancelled in-process —
         * the OS reaps it with the process.
         */
        private val runScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        const val LOG_TAG = "NavigatorHarness"
        const val GOALS_ASSET = "harness/goals.json"

        const val ALL_GOALS = "all"
        const val PAGE_SETTLE_MS = 4_000L
        const val SERVICE_WAIT_MS = 20_000L
        const val SERVICE_POLL_MS = 250L
        const val READINESS_WAIT_MS = 45_000L
        const val READINESS_POLL_MS = 1_000L
        const val READINESS_RETRY_POLL_STEPS = 8
        const val ABOUT_BLANK = "http://127.0.0.1:8099/blank.html"
        const val WARMUP_BLANK = "http://127.0.0.1:8099/blank.html"
        // 120s: the FIRST load after a fresh emulator boot has measured at ~90s before the
        // WebView exposes its content tree to the accessibility bridge (the 60s budget
        // starved Gate 8.4's run on a cold-booted AVD); every later load is instant.
        const val WARMUP_WAIT_MS = 120_000L
        const val WARMUP_STEP_MS = 8_000L
        const val STARVATION_RETRIES = 2
        const val BACKGROUND_SETTLE_MS = 1_500L
        const val COLD_START_SETTLE_MS = 500L

        /** Verified-navigation bounds (gate-105): attempts x window, poll cadence. */
        const val NAVIGATE_VERIFY_ATTEMPTS = 3
        const val NAVIGATE_VERIFY_WINDOW_MS = 8_000L
        const val NAVIGATE_VERIFY_POLL_MS = 1_000L

        /** The loop's own budgets bound a run; this catches a wedged run on top. */
        const val GOAL_TIMEOUT_MS = 15L * 60_000L

        /** Bounded settle for the fire-and-forget shadow after the loop returns. */
        const val SHADOW_SETTLE_TIMEOUT_MS = 30_000L

        /** The run-over buzz length (owner-directed: one short ~200ms vibration). */
        const val BUZZ_MS = 200L

        /** Same value as [HarnessNotifications.POST_NOTIFICATIONS_REQUEST_CODE]. */
        const val POST_NOTIFICATIONS_REQUEST_CODE = HarnessNotifications.POST_NOTIFICATIONS_REQUEST_CODE
    }
}
