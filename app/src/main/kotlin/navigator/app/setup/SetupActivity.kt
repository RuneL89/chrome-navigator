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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The guided setup wizard (phase doc §2.2) — checks with state and deep-link actions:
 *  1. accessibility-service enable (with the Android 13+ "Allow restricted settings"
 *     path explained — a sideloaded a11y app is restricted after install);
 *  2. Samsung battery whitelist (TEXT-GUIDED — no programmatic whitelist on stock
 *     Android is lawful; non-Samsung passes with an info line, so the wizard completes
 *     green on the emulator);
 *  3. OpenRouter key entry ([OpenRouterKeyStore]; presence check only — never renders
 *     the key back; ART-8 local-only);
 *  4. TypeSafe key entry (PHASE_VEXP1 work item 2: the vision experiment's vendor image
 *     channel; same R5 law — masked entry, fixed-dot saved marker, replace wording);
 *  5. the DUAL live-key validation (the 2026-10-17 hotel-iteration card, dual since
 *     PHASE_VEXP1: one "Test keys" button fires BOTH the free OpenRouter GET AND a
 *     minimal one-noul TypeSafe probe, rendering TWO independent verdict lines — never
 *     an auto-call, never a resume/focus ping, each save resets its own verdict).
 * A summary line reports the all-required-green state (the dual-tester card never
 * gates). The screen also STARTS the [NavigatorService] (a lawful FOREGROUND start —
 * the FGS background-start law) and triggers the goal-input overlay for the
 * notification's "New task" action. PHASE_VEXP4: the dormant laya model-status check
 * is REMOVED (the last functional laya reference in :app main — dormant since
 * PHASE_VEXP3 retired the training program) and the vision-loop toggle card reads
 * "Use vision loop (recommended)" — the vision loop IS the app's engine by default
 * (the preference defaults ON); the legacy loop is the owner's explicit fallback.
 *
 * Presentation (owner design pass 2026-10-09, ai-ui-design-skills interview: warm
 * brand-forward, dark-first compact, restructure + polish): warm-dark token set
 * ([NavigatorColors]) under a dark [MaterialTheme], the runtime amber as the single
 * brand accent, page header + required-checks progress summary + one card per check.
 * Presentation only — every behavior, handler, and UAT-approved string below is
 * unchanged (R3 live refresh, R4 battery-ack persistence, R5 saved-key law) except the
 * phase-prescribed dual-key additions, which mirror the R5 laws verbatim.
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

    /**
     * PHASE_VEXP2: the "Use vision loop (recommended)" preference (plain prefs, the
     * battery-ack precedent) — read on every refresh (R3), written on every flip.
     * PHASE_VEXP4: the default is ON (the vision loop IS the app's engine); an
     * explicitly stored OFF is the owner's legacy-engine fallback.
     */
    private fun readVisionLoopPref(): Boolean =
        getSharedPreferences(SetupLogic.VISION_LOOP_PREFS_FILE, MODE_PRIVATE)
            .getBoolean(SetupLogic.VISION_LOOP_PREF_KEY, SetupLogic.VISION_LOOP_DEFAULT)

    private fun writeVisionLoopPref(on: Boolean) {
        getSharedPreferences(SetupLogic.VISION_LOOP_PREFS_FILE, MODE_PRIVATE)
            .edit()
            .putBoolean(SetupLogic.VISION_LOOP_PREF_KEY, on)
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
        // The TypeSafe key card (PHASE_VEXP1 work item 2): same R5 law as the OpenRouter
        // card — masked entry, fixed-dot saved marker, "type a new one to replace".
        var typesafeKeyInput by remember { mutableStateOf("") }
        var typesafeSaved by remember { mutableStateOf(false) }
        var typesafePresent by remember { mutableStateOf(OpenRouterKeyStore.hasTypesafeKey(this@SetupActivity)) }
        // The DUAL key test (one "Test keys" tap fires BOTH channels): each channel's
        // verdict lives OUTSIDE the refresh cycle — refresh() renders them from these
        // vars but never triggers the network calls; only the card's button does.
        var keyTestState by remember { mutableStateOf(SetupLogic.KeyTestState.UNTESTED) }
        var keyTestDetail by remember {
            mutableStateOf(UNTESTED_DETAIL)
        }
        var typesafeTestState by remember { mutableStateOf(SetupLogic.KeyTestState.UNTESTED) }
        var typesafeTestDetail by remember {
            mutableStateOf(UNTESTED_DETAIL)
        }
        // PHASE_VEXP2: the vision-loop toggle's persisted value — re-read on every
        // refresh (R3: resume/focus re-evaluates it like every other check).
        var visionLoopOn by remember { mutableStateOf(readVisionLoopPref()) }
        val scope = rememberCoroutineScope()

        fun refresh() {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            val keyStored = OpenRouterKeyStore.has(this@SetupActivity)
            val typesafeStored = OpenRouterKeyStore.hasTypesafeKey(this@SetupActivity)
            checks.clear()
            checks.add(SetupLogic.accessibilityCheck(
                SetupLogic.isAccessibilityServiceEnabled(enabledServices, packageName),
            ))
            checks.add(SetupLogic.batteryCheck(Build.MANUFACTURER, batteryAcknowledged))
            checks.add(SetupLogic.keyCheck(keyStored))
            checks.add(SetupLogic.typesafeKeyCheck(typesafeStored))
            checks.add(
                SetupLogic.dualKeyLiveCheck(
                    SetupLogic.DualKeyTest(keyTestState, keyTestDetail, typesafeTestState, typesafeTestDetail),
                ),
            )
            keyPresent = keyStored
            typesafePresent = typesafeStored
            visionLoopOn = readVisionLoopPref()
        }

        // R3: the tick re-runs every check on resume/window-focus (returning from a
        // Settings deep-link flips the row immediately); the other keys re-run on the
        // checkbox and the save buttons. The test states/details ride the keys too:
        // the dual-tester card's chip + detail render from `checks` (derived here), so
        // an outcome write must re-run refresh() or the card would stay on "Testing…"
        // forever while only the button updated.
        LaunchedEffect(
            batteryAcknowledged,
            keySaved,
            typesafeSaved,
            refreshTick,
            keyTestState,
            keyTestDetail,
            typesafeTestState,
            typesafeTestDetail,
        ) { refresh() }

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
                                            // A new key invalidates THIS channel's previous
                                            // test result (the TypeSafe verdict is untouched).
                                            keyTestState = SetupLogic.KeyTestState.UNTESTED
                                            keyTestDetail = UNTESTED_DETAIL
                                            keySaved = !keySaved // re-trigger the LaunchedEffect
                                        }
                                    },
                                    enabled = keyInput.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(if (keyPresent) "Replace key" else "Save key") }
                            }

                            SetupLogic.CHECK_TYPESAFE_KEY -> Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (typesafePresent) {
                                    // R5 verbatim: the FIXED dot row beside the always-EMPTY
                                    // entry field — no echo, no length leak.
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
                                    value = typesafeKeyInput,
                                    onValueChange = { typesafeKeyInput = it },
                                    label = {
                                        Text(
                                            if (typesafePresent) {
                                                "TypeSafe key (type a new one to replace)"
                                            } else {
                                                "TypeSafe key"
                                            },
                                        )
                                    },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    onClick = {
                                        if (typesafeKeyInput.isNotBlank()) {
                                            OpenRouterKeyStore.saveTypesafeKey(this@SetupActivity, typesafeKeyInput)
                                            typesafeKeyInput = ""
                                            // A new key invalidates THIS channel's previous
                                            // test result (the OpenRouter verdict is untouched).
                                            typesafeTestState = SetupLogic.KeyTestState.UNTESTED
                                            typesafeTestDetail = UNTESTED_DETAIL
                                            typesafeSaved = !typesafeSaved // re-trigger the LaunchedEffect
                                        }
                                    },
                                    enabled = typesafeKeyInput.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(if (typesafePresent) "Replace key" else "Save key") }
                            }

                            SetupLogic.CHECK_KEY_LIVE -> Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (keyPresent || typesafePresent) {
                                    // The DUAL tester (PHASE_VEXP1 work item 2): ONE tap
                                    // fires BOTH channels and renders TWO independent
                                    // verdict lines. Never auto-runs; never pings on
                                    // resume/focus (the state lives outside the refresh).
                                    OutlinedButton(
                                        onClick = {
                                            keyTestState = SetupLogic.KeyTestState.TESTING
                                            keyTestDetail = "Testing the key against OpenRouter…"
                                            typesafeTestState = SetupLogic.KeyTestState.TESTING
                                            typesafeTestDetail = "Testing the key against TypeSafe…"
                                            scope.launch {
                                                val orOutcome = withContext(Dispatchers.IO) {
                                                    testStoredKey()
                                                }
                                                keyTestState = orOutcome.state
                                                keyTestDetail = orOutcome.detail
                                                val tsOutcome = withContext(Dispatchers.IO) {
                                                    testTypesafeKey()
                                                }
                                                typesafeTestState = tsOutcome.state
                                                typesafeTestDetail = tsOutcome.detail
                                            }
                                        },
                                        enabled = keyTestState != SetupLogic.KeyTestState.TESTING &&
                                            typesafeTestState != SetupLogic.KeyTestState.TESTING,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            if (keyTestState == SetupLogic.KeyTestState.TESTING ||
                                                typesafeTestState == SetupLogic.KeyTestState.TESTING
                                            ) {
                                                "Testing…"
                                            } else {
                                                "Test keys"
                                            },
                                        )
                                    }
                                }
                                // No key stored on either channel: no button — the card's
                                // detail lines already point at the save cards.
                            }
                        }
                    }
                }

                // PHASE_VEXP2 (the engine switch) + PHASE_VEXP4 (the default flip): the
                // vision-loop toggle card, beside the key cards. NOT a wizard check — it
                // never gates the summary; the pure derivation
                // (SetupLogic.visionLoopToggleState) makes the control interactive only
                // when BOTH keys are stored, every flip persists immediately (plain
                // prefs, the battery-ack precedent), and the stored value defaults ON
                // (the vision loop IS the app's engine; explicit OFF = the legacy
                // fallback).
                val visionToggle = SetupLogic.visionLoopToggleState(
                    storedOn = visionLoopOn,
                    openRouterPresent = keyPresent,
                    typesafePresent = typesafePresent,
                )
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = visionToggle.on,
                                    enabled = visionToggle.interactive,
                                    role = Role.Checkbox,
                                    onValueChange = { checked ->
                                        writeVisionLoopPref(checked)
                                        visionLoopOn = checked
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(
                                checked = visionToggle.on,
                                onCheckedChange = null,
                                enabled = visionToggle.interactive,
                            )
                            Text(
                                text = "Use vision loop (recommended)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            text = when {
                                !visionToggle.interactive ->
                                    "The vision loop is the app's engine and needs both keys " +
                                        "saved (OpenRouter + TypeSafe) — goals refuse to run " +
                                        "until you save them above."
                                visionToggle.on ->
                                    "On (default) — typed goals run the vision engine " +
                                        "(screenshots + both AI channels). Turn off to fall " +
                                        "back to the legacy engine."
                                else ->
                                    "Off — typed goals run the legacy engine (the fallback). " +
                                        "Flip it on to return to the vision engine."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
     * The dual tester's OpenRouter edge (2026-10-17; dual since PHASE_VEXP1): ONE free
     * GET against OpenRouter's key endpoint with the stored key — `200` = live, `401` =
     * rejected (the exact failure the 2026-10-17 hotel run hit at its first paid call),
     * anything else = an honest error line. Runs on [Dispatchers.IO] only (the button's
     * coroutine dispatches); the key rides the Authorization header alone and is NEVER
     * logged — the outcome carries only the HTTP code class.
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

    /**
     * The dual tester's TypeSafe edge (PHASE_VEXP1 work item 2): ONE minimal one-noul
     * request against the vendor endpoint — the cheapest proven validation (≈ $0.000012
     * per the run-1 transport matrix). `200` = live (the key authenticated; 401 would
     * have fired otherwise), `401` = rejected, anything else = an honest error line.
     * Runs on [Dispatchers.IO] only; the key rides the Authorization header alone and is
     * NEVER logged — the outcome carries only the HTTP code class. No danger header: the
     * probe carries no image.
     */
    private fun testTypesafeKey(): SetupLogic.KeyTestOutcome {
        val key = OpenRouterKeyStore.loadTypesafeKey(this)
            ?: return SetupLogic.KeyTestOutcome(
                SetupLogic.KeyTestState.REJECTED,
                "No TypeSafe key is stored — save one above first.",
            )
        val body = "{\"state\":\"key validation probe\",\"model\":\"$TYPESAFE_PROBE_MODEL\"," +
            "\"questions\":{\"live\":{\"type\":\"noul\",\"instructions\":\"This is a key validation probe.\"}}}"
        val request = okhttp3.Request.Builder()
            .url(TYPESAFE_VALIDATION_URL)
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            keyTestClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> SetupLogic.KeyTestOutcome(
                        SetupLogic.KeyTestState.LIVE,
                        "TypeSafe accepted the key — it is live (one-noul probe).",
                    )
                    401 -> SetupLogic.KeyTestOutcome(
                        SetupLogic.KeyTestState.REJECTED,
                        "TypeSafe rejected the key (401 — not found or revoked). " +
                            "Enter a fresh key above and save, then test again.",
                    )
                    else -> SetupLogic.KeyTestOutcome(
                        SetupLogic.KeyTestState.ERROR,
                        "TypeSafe answered HTTP ${response.code} — try again.",
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
        SetupLogic.CHECK_TYPESAFE_KEY -> "TypeSafe key"
        SetupLogic.CHECK_KEY_LIVE -> "API keys are live"
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

        /** The dual tester's untested line (both channels start here; a save resets its own). */
        const val UNTESTED_DETAIL =
            "Not tested yet — tap \"Test keys\" to validate both stored keys (a free " +
                "OpenRouter call + a minimal one-noul TypeSafe probe)."

        /** The TypeSafe validation endpoint (the vendor systemone route, run-1 transport matrix). */
        const val TYPESAFE_VALIDATION_URL = "https://api.typesafe.ai/v1/systemone"

        /** The validation probe's model (the vendor slug the vision route pins). */
        const val TYPESAFE_PROBE_MODEL = "jev-1.14.0"

        private const val POST_NOTIFICATIONS_REQUEST = 91
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
