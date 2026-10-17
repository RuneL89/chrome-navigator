package navigator.app.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import navigator.app.overlay.NavigatorService

/**
 * The guided setup wizard (phase doc §2.2): five checks with state and deep-link
 * actions —
 *  1. accessibility-service enable (with the Android 13+ "Allow restricted settings"
 *     path explained — a sideloaded a11y app is restricted after install);
 *  2. Samsung battery whitelist (TEXT-GUIDED — no programmatic whitelist on stock
 *     Android is lawful; non-Samsung passes with an info line, so the wizard completes
 *     green on the emulator);
 *  3. OpenRouter key entry ([OpenRouterKeyStore]; presence check only — never renders
 *     the key back; ART-8 local-only);
 *  4. on-demand live-key validation (the 2026-10-17 hotel-iteration addition: a
 *     "Test key" button fires ONE free GET to OpenRouter — presence says nothing about
 *     validity, and that day's run died on a stored-but-dead key; the result is an
 *     inline status line on its own card, never an auto-call);
 *  5. model-download status (informational; eval-only since the 2026-10-06 owner
 *     demotion — Jev is primary, never blocks).
 * A summary line reports the all-required-green state (check 4 never gates). The screen
 * also STARTS the [NavigatorService] (a lawful FOREGROUND start — the FGS
 * background-start law) and triggers the goal-input overlay for the notification's
 * "New task" action.
 *
 * Presentation (owner design pass 2026-10-09, ai-ui-design-skills interview: warm
 * brand-forward, dark-first compact, restructure + polish): warm-dark token set
 * ([NavigatorColors]) under a dark [MaterialTheme], the runtime amber as the single
 * brand accent, page header + required-checks progress summary + one card per check.
 * Presentation only — every behavior, handler, and UAT-approved string below is
 * unchanged (R3 live refresh, R4 battery-ack persistence, R5 saved-key law). The
 * live-key card follows the 2026-10-17 owner interview (a new check card, wizard
 * language, tap-to-test, inline status line).
 */
class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            POST_NOTIFICATIONS_REQUEST,
        )
        if (intent.getBooleanExtra(EXTRA_SHOW_GOAL_INPUT, false)) {
            startNavigatorService(showGoalInput = true)
        }
        setContent { SetupTheme { SetupScreen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_GOAL_INPUT, false)) {
            startNavigatorService(showGoalInput = true)
        }
    }

    /**
     * UAT 9.2 revision R3: the wizard must live-update. Every resume — and every
     * window-focus gain (the return from a Settings deep-link) — bumps [refreshTick],
     * which re-runs ALL checks. A check flips green the moment the user comes back
     * with it satisfied; no app restart needed.
     */
    override fun onResume() {
        super.onResume()
        refreshTick++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshTick++
    }

    /** Bumped on resume/focus (R3); the wizard's refresh [LaunchedEffect] keys on it. */
    private var refreshTick by mutableStateOf(0)

    /** R4: the persisted battery "Done" acknowledgement (plain prefs — see SetupLogic). */
    private fun readBatteryAck(): Boolean =
        getSharedPreferences(SetupLogic.BATTERY_ACK_PREFS_FILE, MODE_PRIVATE)
            .getBoolean(SetupLogic.BATTERY_ACK_PREF_KEY, false)

    private fun writeBatteryAck(acknowledged: Boolean) {
        getSharedPreferences(SetupLogic.BATTERY_ACK_PREFS_FILE, MODE_PRIVATE)
            .edit()
            .putBoolean(SetupLogic.BATTERY_ACK_PREF_KEY, acknowledged)
            .apply()
    }

    /** FOREGROUND-context FGS start (the activity is visible) + optional goal input. */
    private fun startNavigatorService(showGoalInput: Boolean) {
        val service = Intent(this, NavigatorService::class.java).apply {
            if (showGoalInput) action = NavigatorService.ACTION_SHOW_GOAL_INPUT
        }
        startForegroundService(service)
    }

    @Composable
    private fun SetupScreen() {
        val checks = remember { mutableStateListOf<SetupLogic.CheckState>() }
        // R4: restore the persisted acknowledgement so a restart shows the battery
        // step still acknowledged.
        var batteryAcknowledged by remember { mutableStateOf(readBatteryAck()) }
        var keyInput by remember { mutableStateOf("") }
        var keySaved by remember { mutableStateOf(false) }
        var keyPresent by remember { mutableStateOf(OpenRouterKeyStore.has(this@SetupActivity)) }
        // Check 5 (the on-demand live-key test, 2026-10-17): its state lives OUTSIDE the
        // refresh cycle — refresh() renders it from these vars but never triggers the
        // network call; only the card's Test button does.
        var keyTestState by remember { mutableStateOf(SetupLogic.KeyTestState.UNTESTED) }
        var keyTestDetail by remember {
            mutableStateOf("Not tested yet — tap \"Test key\" to validate the stored key against " +
                "OpenRouter (a free call; no tokens are spent).")
        }
        val scope = rememberCoroutineScope()

        fun refresh() {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            val modelPresent = runCatching {
                assets.list(LAYA_ASSET_DIR)?.contains(LAYA_ASSET_FILE) == true
            }.getOrDefault(false)
            val keyStored = OpenRouterKeyStore.has(this@SetupActivity)
            checks.clear()
            checks.add(SetupLogic.accessibilityCheck(
                SetupLogic.isAccessibilityServiceEnabled(enabledServices, packageName),
            ))
            checks.add(SetupLogic.batteryCheck(Build.MANUFACTURER, batteryAcknowledged))
            checks.add(SetupLogic.keyCheck(keyStored))
            checks.add(SetupLogic.keyLiveCheck(keyTestState, keyTestDetail))
            checks.add(SetupLogic.modelCheck(modelPresent))
            keyPresent = keyStored
        }

        // R3: the tick re-runs every check on resume/window-focus (returning from a
        // Settings deep-link flips the row immediately); the other keys re-run on the
        // checkbox and the save button.
        LaunchedEffect(batteryAcknowledged, keySaved, refreshTick) { refresh() }

        Surface(modifier = Modifier.fillMaxSize(), color = NavigatorColors.Canvas) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // targetSdk 36 enforces edge-to-edge (the platform theme's bar-color
                    // items are ignored on API 35+), so the content pads itself out of
                    // the status bar, cutout, IME, and gesture-nav areas.
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SetupHeader()

                val requiredChecks = checks.filter { it.required }
                if (requiredChecks.isNotEmpty()) {
                    SetupProgress(
                        done = requiredChecks.count { it.status == SetupLogic.Status.GREEN },
                        total = requiredChecks.size,
                    )
                }

                checks.forEach { check ->
                    CheckCard(check = check) {
                        when (check.id) {
                            SetupLogic.CHECK_ACCESSIBILITY -> Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        runCatching {
                                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Open Accessibility settings") }
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            startActivity(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:$packageName"),
                                                ),
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("App info (allow restricted settings)") }
                            }

                            SetupLogic.CHECK_BATTERY -> if (SetupLogic.samsungBatteryStepRequired(Build.MANUFACTURER)) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            runCatching {
                                                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Open Battery settings") }
                                    // R4: the acknowledgement is remembered across
                                    // restarts (plain prefs). The whole row toggles.
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .toggleable(
                                                value = batteryAcknowledged,
                                                role = Role.Checkbox,
                                                onValueChange = { checked ->
                                                    writeBatteryAck(checked)
                                                    batteryAcknowledged = checked
                                                },
                                            ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(checked = batteryAcknowledged, onCheckedChange = null)
                                        Text(
                                            "Done (Never sleeping + Unrestricted)",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }

                            SetupLogic.CHECK_KEY -> Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (keyPresent) {
                                    // R5: the saved-key indicator — a FIXED dot string,
                                    // presence only. Never the value, never its true
                                    // length; the field below stays EMPTY (tapping in
                                    // echoes nothing).
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Lock,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            text = "Saved: ${SetupLogic.SAVED_KEY_INDICATOR}",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                    Text(
                                        text = "Saving again replaces the stored key.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedTextField(
                                    value = keyInput,
                                    onValueChange = { keyInput = it },
                                    label = {
                                        Text(
                                            if (keyPresent) {
                                                "OpenRouter key (type a new one to replace)"
                                            } else {
                                                "OpenRouter key"
                                            },
                                        )
                                    },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    onClick = {
                                        if (keyInput.isNotBlank()) {
                                            OpenRouterKeyStore.save(this@SetupActivity, keyInput)
                                            // The field clears; the state shows saved-only.
                                            keyInput = ""
                                            // A new key invalidates the previous test result.
                                            keyTestState = SetupLogic.KeyTestState.UNTESTED
                                            keyTestDetail = (
                                                "Not tested yet — tap \"Test key\" to validate " +
                                                    "the stored key against OpenRouter (a free " +
                                                    "call; no tokens are spent)."
                                                )
                                            keySaved = !keySaved // re-trigger the LaunchedEffect
                                        }
                                    },
                                    enabled = keyInput.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(if (keyPresent) "Replace key" else "Save key") }
                            }

                            SetupLogic.CHECK_KEY_LIVE -> Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (keyPresent) {
                                    OutlinedButton(
                                        onClick = {
                                            keyTestState = SetupLogic.KeyTestState.TESTING
                                            keyTestDetail = "Testing the key against OpenRouter…"
                                            scope.launch {
                                                val outcome = withContext(Dispatchers.IO) {
                                                    testStoredKey()
                                                }
                                                keyTestState = outcome.state
                                                keyTestDetail = outcome.detail
                                            }
                                        },
                                        enabled = keyTestState != SetupLogic.KeyTestState.TESTING,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            if (keyTestState == SetupLogic.KeyTestState.TESTING) {
                                                "Testing…"
                                            } else {
                                                "Test key"
                                            },
                                        )
                                    }
                                }
                                // No key stored: no button — the card's detail line already
                                // points at check 3 (save a key first).
                            }
                        }
                    }
                }

                HorizontalDivider(color = NavigatorColors.Border)

                val allGreen = SetupLogic.allRequiredGreen(checks)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (allGreen) Icons.Filled.Check else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = if (allGreen) NavigatorColors.Success else NavigatorColors.Danger,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (allGreen) {
                            "Setup complete — all required checks green."
                        } else {
                            "Setup incomplete — finish the required checks above."
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (allGreen) NavigatorColors.Success else NavigatorColors.Danger,
                    )
                }

                Button(
                    onClick = { startNavigatorService(showGoalInput = false) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text(if (allGreen) "Start Navigator" else "Start Navigator anyway")
                }
                OutlinedButton(
                    onClick = { startNavigatorService(showGoalInput = true) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("New task (goal overlay)")
                }
            }
        }
    }

    /**
     * Check 5's network edge (2026-10-17): ONE free GET against OpenRouter's key endpoint
     * with the stored key — `200` = live, `401` = rejected (the exact failure the
     * 2026-10-17 hotel run hit at its first paid call), anything else = an honest error
     * line. Runs on [Dispatchers.IO] only (the button's coroutine dispatches); the key
     * rides the Authorization header alone and is NEVER logged — the outcome carries
     * only the HTTP code class.
     */
    private fun testStoredKey(): SetupLogic.KeyTestOutcome {
        val key = OpenRouterKeyStore.load(this)
            ?: return SetupLogic.KeyTestOutcome(
                SetupLogic.KeyTestState.REJECTED,
                "No key is stored — save one above first.",
            )
        val request = okhttp3.Request.Builder()
            .url("https://openrouter.ai/api/v1/key")
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        return try {
            keyTestClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> SetupLogic.KeyTestOutcome(
                        SetupLogic.KeyTestState.LIVE,
                        "OpenRouter accepted the key — it is live.",
                    )
                    401 -> SetupLogic.KeyTestOutcome(
                        SetupLogic.KeyTestState.REJECTED,
                        "OpenRouter rejected the key (401 — not found or revoked). " +
                            "Enter a fresh key above and save, then test again.",
                    )
                    else -> SetupLogic.KeyTestOutcome(
                        SetupLogic.KeyTestState.ERROR,
                        "OpenRouter answered HTTP ${response.code} — try again.",
                    )
                }
            }
        } catch (t: java.io.IOException) {
            SetupLogic.KeyTestOutcome(
                SetupLogic.KeyTestState.ERROR,
                "Network error (${t::class.java.simpleName}) — check the connection and try again.",
            )
        }
    }

    /** The live-key test's HTTP client (short timeouts — a check must never hang the card). */
    private val keyTestClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .callTimeout(java.time.Duration.ofSeconds(15))
            .build()
    }

    /** Page header: brand overline, title, one-line description (compact scale). */
    @Composable
    private fun SetupHeader() {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "NAVIGATOR",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Setup",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Complete the required checks, then start Navigator. Everything stays " +
                    "on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /** Current-state summary (what is active right now) + the required-checks bar. */
    @Composable
    private fun SetupProgress(done: Int, total: Int) {
        val complete = done >= total
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (complete) {
                        "All $total required checks green"
                    } else {
                        "$done of $total required checks complete"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (complete) NavigatorColors.Success else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (complete) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = NavigatorColors.Success,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = if (complete) NavigatorColors.Success else MaterialTheme.colorScheme.primary,
                trackColor = NavigatorColors.Border,
            )
        }
    }

    /** One wizard check as a card: status chip + title + status word, detail, controls. */
    @Composable
    private fun CheckCard(check: SetupLogic.CheckState, controls: @Composable ColumnScope.() -> Unit = {}) {
        val (icon, accent) = when (check.status) {
            SetupLogic.Status.GREEN -> Icons.Filled.Check to NavigatorColors.Success
            SetupLogic.Status.RED -> Icons.Filled.Warning to NavigatorColors.Danger
            SetupLogic.Status.INFO -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = NavigatorColors.Card,
            border = BorderStroke(1.dp, NavigatorColors.Border),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(accent.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = checkTitle(check.id),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = statusWord(check.status),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                }
                Text(
                    text = check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                controls()
            }
        }
    }

    private fun checkTitle(id: String): String = when (id) {
        SetupLogic.CHECK_ACCESSIBILITY -> "Accessibility service"
        SetupLogic.CHECK_BATTERY -> "Battery whitelist"
        SetupLogic.CHECK_KEY -> "OpenRouter key"
        SetupLogic.CHECK_KEY_LIVE -> "API key is live"
        SetupLogic.CHECK_MODEL -> "On-device model"
        else -> id.replaceFirstChar { it.uppercase() }
    }

    private fun statusWord(status: SetupLogic.Status): String = when (status) {
        SetupLogic.Status.GREEN -> "Done"
        SetupLogic.Status.RED -> "Action needed"
        SetupLogic.Status.INFO -> "Info"
    }

    companion object {
        /** The "New task" notification action lands here (then opens the goal overlay). */
        const val EXTRA_SHOW_GOAL_INPUT = "showGoalInput"

        private const val POST_NOTIFICATIONS_REQUEST = 91
        private const val LAYA_ASSET_DIR = "laya"
        private const val LAYA_ASSET_FILE = "laya-int8.onnx"
    }
}

/**
 * The wizard's warm-dark token set (owner design pass 2026-10-09): warm-tinted neutral
 * surfaces, the runtime amber as the ONE brand accent, semantic green/red for status
 * only. The matching window/system-bar theme lives in `res/values/themes.xml`
 * (`Theme.Navigator.Setup` — same canvas color, so no light flash at launch).
 */
private object NavigatorColors {
    val Canvas = Color(0xFF14110D)
    val Card = Color(0xFF1E1913)
    val CardElevated = Color(0xFF262019)
    val Border = Color(0xFF362C1F)
    val TextPrimary = Color(0xFFF0EBE2)
    val TextMuted = Color(0xFFA79E8F)
    val Accent = Color(0xFFE8962E)
    val OnAccent = Color(0xFF241703)
    val Success = Color(0xFF4CC38A)
    val Danger = Color(0xFFF0716C)
}

private val SetupColorScheme = darkColorScheme(
    primary = NavigatorColors.Accent,
    onPrimary = NavigatorColors.OnAccent,
    primaryContainer = Color(0xFF3A2A12),
    onPrimaryContainer = Color(0xFFF2C078),
    secondary = NavigatorColors.Accent,
    onSecondary = NavigatorColors.OnAccent,
    background = NavigatorColors.Canvas,
    onBackground = NavigatorColors.TextPrimary,
    surface = NavigatorColors.Canvas,
    onSurface = NavigatorColors.TextPrimary,
    surfaceContainer = NavigatorColors.Card,
    surfaceContainerHigh = NavigatorColors.CardElevated,
    surfaceVariant = Color(0xFF2A231A),
    onSurfaceVariant = NavigatorColors.TextMuted,
    outline = Color(0xFF4A3D2C),
    outlineVariant = NavigatorColors.Border,
    error = NavigatorColors.Danger,
    onError = Color(0xFF2B0B09),
)

/** The wizard's MaterialTheme wrapper (dark-first compact, warm brand accent). */
@Composable
private fun SetupTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SetupColorScheme, content = content)
}
