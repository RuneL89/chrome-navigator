package navigator.app.harness

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import navigator.app.loop.AutoDenyGatePresenter
import navigator.app.loop.DebugGatePresenter
import navigator.app.loop.GestureExecutor
import navigator.app.loop.NavigationLoop
import navigator.app.loop.RunResult
import navigator.app.observe.NavigatorAccessibilityService

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
 */
class HarnessActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val uiLog = mutableStateListOf<String>()
    private val running = mutableStateOf(false)
    private val liveMode = mutableStateOf(false)
    private val autoDeny = mutableStateOf(true)

    private lateinit var catalog: GoalCatalog
    private lateinit var pagesServer: HarnessPagesServer
    private val logSink by lazy { RoomDecisionLogSink(this) }

    @Volatile
    private var cancelRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catalog = GoalCatalog.parse(assets.open(GOALS_ASSET).use { it.readBytes().decodeToString() })
        pagesServer = HarnessPagesServer(this).also { it.start() }
        handleLaunchIntent(intent, freshInstance = true)
        setContent { HarnessScreen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent, freshInstance = false)
    }

    /**
     * Headless launch handling (Gate 6.2 runbook): works on a fresh instance AND on a
     * live one via [onNewIntent] — a silent no-op is a bug. When a run is already in
     * progress the launch is refused LOUDLY (log line), never silently dropped.
     */
    private fun handleLaunchIntent(intent: Intent?, freshInstance: Boolean) {
        if (intent == null) {
            if (!freshInstance) log("launch ignored: null intent")
            return
        }
        val extraGoal = intent.getStringExtra(EXTRA_GOAL) ?: intent.getStringExtra(EXTRA_GOAL_SHORT)
        if (extraGoal == null) {
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
        liveMode.value = HarnessEngineMode.parse(
            intent.getStringExtra(EXTRA_ENGINE) ?: intent.getStringExtra(EXTRA_ENGINE_SHORT),
        ) == HarnessEngineMode.LIVE
        autoDeny.value = intent.getBooleanExtra(
            EXTRA_AUTO_DENY,
            intent.getBooleanExtra(EXTRA_AUTO_DENY_SHORT, true),
        )
        val startDelay = intent.getLongExtra(
            EXTRA_START_DELAY_MS,
            intent.getLongExtra(EXTRA_START_DELAY_SHORT_MS, DEFAULT_START_DELAY_MS),
        )
        log(
            "headless run: goal=$extraGoal live=${liveMode.value} " +
                "autoDeny=${autoDeny.value} delay=${startDelay}ms fresh=$freshInstance",
        )
        scope.launch {
            delay(startDelay)
            runSelected(extraGoal)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (this::pagesServer.isInitialized) pagesServer.stop()
        super.onDestroy()
    }

    // -- orchestration -----------------------------------------------------------------

    private fun log(message: String) {
        val stamped = "[${System.currentTimeMillis()}] $message"
        uiLog.add(0, stamped) // newest first for the debug pane
        android.util.Log.d(LOG_TAG, message)
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

    private fun openBrowser(url: String) {
        val browser = HarnessObserver.installedBrowser(this)
            ?: throw IllegalStateException("no allowlisted browser is installed")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(browser)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    private suspend fun runSelected(selection: String) {
        if (running.value) return
        running.value = true
        cancelRequested = false
        val reports = mutableListOf<RunReport>()
        try {
            // The FIRST page load after the service binds takes ~90s to expose its web
            // content through the a11y bridge on this emulator; every later load is
            // instant. Cold-start the browser (service already bound) and prime the
            // pipeline once: alternate target <- blank <- target, polling fresh reads,
            // until real page content is visible.
            warmUpWebAccessibility()
            for (id in goalIds(selection)) {
                if (cancelRequested) break
                val goal = catalog.byId(id)
                log("=== goal ${goal.id} (${goal.family}): \"${goal.goal}\"")
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
            report.terminal == "HARNESS_ERROR"

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
            if (urlText.contains(startMarker) && read.lines.size >= 5 && read.pageNodeCount >= 5) {
                log("warm-up: web a11y primed (content visible)")
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
     * accessibility already enabled.
     */
    private suspend fun coldStartBrowser(
        service: NavigatorAccessibilityService,
        url: String,
    ) {
        runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
        delay(BACKGROUND_SETTLE_MS)
        val browser = HarnessObserver.installedBrowser(this)
        if (browser != null) {
            runCatching {
                (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                    ?.killBackgroundProcesses(browser)
            }
        }
        delay(COLD_START_SETTLE_MS)
        openBrowser(url)
    }

    /** A failure record so the suite report shows the gap instead of hiding it. */
    private fun failedReport(goal: ScriptedGoal, t: Throwable, attempt: Int = 1) = RunReport(
        goalId = goal.id,
        family = goal.family,
        goal = goal.goal,
        taskId = "failed-${System.currentTimeMillis()}",
        engine = if (liveMode.value) "live" else "stub",
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
    )

    private suspend fun executeGoal(goal: ScriptedGoal, attempt: Int = 1): RunReport {
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
        val mode = if (liveMode.value) HarnessEngineMode.LIVE else HarnessEngineMode.STUB
        val built = HarnessEngineFactory.build(mode, goal)
        log("engine: ${built.mode} (${built.identity.configId}, ${built.identity.model}); auto-deny=${autoDeny.value}")
        val loop = NavigationLoop(
            engine = built.engine,
            observer = HarnessObserver(service),
            executor = GestureExecutor(service),
            gatePresenter = if (autoDeny.value) AutoDenyGatePresenter else DebugGatePresenter(this),
            logSink = logSink,
            spendMeter = built.spendMeter,
        )
        val startedAt = System.currentTimeMillis()
        val result = loop.run(
            goal = goal.goal,
            goalId = goal.id,
            engineIdentity = built.identity,
            isCancelled = { cancelRequested },
        )
        result.stepLog.forEach { log(it) }
        val report = RunReport(
            goalId = goal.id,
            family = goal.family,
            goal = goal.goal,
            taskId = result.taskId,
            engine = built.mode.name.lowercase(),
            autoDeny = autoDeny.value,
            triage = result.triage,
            terminal = result.terminal.name,
            terminalDetail = result.terminalDetail,
            expectedTerminal = goal.expectedTerminal,
            terminalMatchesExpected = result.terminal.name == goal.expectedTerminal,
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
        )
        val path = RunReportStore.write(this, "run-${goal.id}-${result.taskId}.json", reportJson(report))
        log("report: $path")
        log(
            "=== ${goal.id}: ${result.terminal} (expected ${goal.expectedTerminal}, " +
                "gaps=${result.logGaps}, attempts=$attempt)",
        )
        return report
    }

    private fun writeSuiteReport(reports: List<RunReport>) {
        val suite = SuiteReport(
            runs = reports,
            allPassed = reports.isNotEmpty() &&
                reports.all { it.terminalMatchesExpected == true && it.logGaps == 0 },
            finishedAt = System.currentTimeMillis(),
        )
        val path = RunReportStore.write(this, RunReportStore.SUITE_FILE, suiteJson(suite))
        log("suite report (${reports.size} runs, allPassed=${suite.allPassed}): $path")
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
                Text("LIVE Jev")
                Switch(checked = liveMode.value, onCheckedChange = { liveMode.value = it })
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
                items(goals) { goal ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${goal.id} -> ${goal.expectedTerminal}",
                            modifier = Modifier.padding(end = 8.dp),
                            color = Color.Black,
                        )
                        OutlinedButton(
                            enabled = !running.value,
                            onClick = { scope.launch { runSelected(goal.id) } },
                        ) {
                            Text("Run")
                        }
                    }
                }
            }
            Text("Step log (newest first)", style = MaterialTheme.typography.titleSmall)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiLog) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    companion object {
        const val LOG_TAG = "NavigatorHarness"
        const val GOALS_ASSET = "harness/goals.json"
        const val EXTRA_GOAL = "navigator.harness.extra.GOAL"
        const val EXTRA_ENGINE = "navigator.harness.extra.ENGINE"
        const val EXTRA_AUTO_DENY = "navigator.harness.extra.AUTO_DENY"
        const val EXTRA_START_DELAY_MS = "navigator.harness.extra.START_DELAY_MS"

        /** adb-friendly aliases (`am start --es goal ...`). */
        const val EXTRA_GOAL_SHORT = "goal"
        const val EXTRA_ENGINE_SHORT = "engine"
        const val EXTRA_AUTO_DENY_SHORT = "autoDeny"
        const val EXTRA_START_DELAY_SHORT_MS = "startDelayMs"

        const val ALL_GOALS = "all"
        const val DEFAULT_START_DELAY_MS = 4_000L
        const val PAGE_SETTLE_MS = 4_000L
        const val SERVICE_WAIT_MS = 20_000L
        const val SERVICE_POLL_MS = 250L
        const val READINESS_WAIT_MS = 45_000L
        const val READINESS_POLL_MS = 1_000L
        const val READINESS_RETRY_POLL_STEPS = 8
        const val ABOUT_BLANK = "http://127.0.0.1:8099/blank.html"
        const val WARMUP_BLANK = "http://127.0.0.1:8099/blank.html"
        const val WARMUP_WAIT_MS = 60_000L
        const val WARMUP_STEP_MS = 8_000L
        const val STARVATION_RETRIES = 2
        const val BACKGROUND_SETTLE_MS = 1_500L
        const val COLD_START_SETTLE_MS = 500L

        /** The loop's own budgets bound a run; this catches a wedged run on top. */
        const val GOAL_TIMEOUT_MS = 15L * 60_000L

        private val pretty = Json { prettyPrint = true }

        fun reportJson(report: RunReport): String = pretty.encodeToString(RunReport.serializer(), report)

        fun suiteJson(suite: SuiteReport): String = pretty.encodeToString(SuiteReport.serializer(), suite)
    }
}

/** One exported run (the Gate 6.2 evidence artifact). */
@Serializable
data class RunReport(
    val goalId: String?,
    val family: String?,
    val goal: String,
    val taskId: String,
    val engine: String,
    val autoDeny: Boolean,
    val triage: String,
    val terminal: String,
    val terminalDetail: String,
    val expectedTerminal: String?,
    val terminalMatchesExpected: Boolean?,
    val steps: Int,
    val rowsLogged: Int,
    val metaDecisions: Int,
    /**
     * Navigation decisions without a row. The rephrase recovery stub is a goal-text
     * meta-decision ([metaDecisions], no page action, no row) and is EXCLUDED by the
     * formula (loop: `logGaps = steps - rows - metaDecisions`), so "zero log gaps"
     * stays meaningful: every navigation decision is logged exactly once.
     */
    val logGaps: Int,
    val gateEvents: Int,
    val spendMicroUsd: Long,
    val elapsedMs: Long,
    val softWarnings: List<String>,
    val partialResult: String,
    val startedAt: Long,
    val finishedAt: Long,
    /** How many times this goal was attempted in this suite run (1 = no retry). */
    val attempts: Int = 1,
    /** True when attempt 1 ended in observation starvation and a retry followed. */
    val flakeRetried: Boolean = false,
    val stepLog: List<String>,
)

/** The batch report: every family at its defined terminal state, zero log gaps. */
@Serializable
data class SuiteReport(
    val runs: List<RunReport>,
    val allPassed: Boolean,
    val finishedAt: Long,
)
