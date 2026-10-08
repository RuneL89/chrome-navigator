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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import navigator.app.overlay.NavigatorService

/**
 * The guided setup wizard (phase doc §2.2): four re-runnable checks with state and
 * deep-link actions —
 *  1. accessibility-service enable (with the Android 13+ "Allow restricted settings"
 *     path explained — a sideloaded a11y app is restricted after install);
 *  2. Samsung battery whitelist (TEXT-GUIDED — no programmatic whitelist on stock
 *     Android is lawful; non-Samsung passes with an info line, so the wizard completes
 *     green on the emulator);
 *  3. OpenRouter key entry ([OpenRouterKeyStore]; presence check only — never renders
 *     the key back; ART-8 local-only);
 *  4. model-download status (informational; eval-only since the 2026-10-06 owner
 *     demotion — Jev is primary, never blocks).
 * A summary line reports the all-required-green state. The screen also STARTS the
 * [NavigatorService] (a lawful FOREGROUND start — the FGS background-start law) and
 * triggers the goal-input overlay for the notification's "New task" action.
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
        setContent { SetupScreen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_GOAL_INPUT, false)) {
            startNavigatorService(showGoalInput = true)
        }
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
        var batteryAcknowledged by remember { mutableStateOf(false) }
        var keyInput by remember { mutableStateOf("") }
        var keySaved by remember { mutableStateOf(false) }

        fun refresh() {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            val modelPresent = runCatching {
                assets.list(LAYA_ASSET_DIR)?.contains(LAYA_ASSET_FILE) == true
            }.getOrDefault(false)
            checks.clear()
            checks.add(SetupLogic.accessibilityCheck(
                SetupLogic.isAccessibilityServiceEnabled(enabledServices, packageName),
            ))
            checks.add(SetupLogic.batteryCheck(Build.MANUFACTURER, batteryAcknowledged))
            checks.add(SetupLogic.keyCheck(OpenRouterKeyStore.has(this)))
            checks.add(SetupLogic.modelCheck(modelPresent))
        }

        LaunchedEffect(batteryAcknowledged, keySaved) { refresh() }

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Navigator setup", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Complete the required checks, then start Navigator. Everything stays " +
                        "on this device.",
                    style = MaterialTheme.typography.bodySmall,
                )

                checks.forEach { check ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${statusGlyph(check.status)} ${check.id.replaceFirstChar { it.uppercase() }}",
                            style = MaterialTheme.typography.titleMedium,
                            color = when (check.status) {
                                SetupLogic.Status.GREEN -> Color(0xFF1B7A2F)
                                SetupLogic.Status.RED -> Color(0xFFB3261E)
                                SetupLogic.Status.INFO -> Color(0xFF555555)
                            },
                        )
                        Text(check.detail, style = MaterialTheme.typography.bodySmall)

                        when (check.id) {
                            SetupLogic.CHECK_ACCESSIBILITY -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    runCatching {
                                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    }
                                }) { Text("Open Accessibility settings") }
                                OutlinedButton(onClick = {
                                    runCatching {
                                        startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:$packageName"),
                                            ),
                                        )
                                    }
                                }) { Text("App info (allow restricted settings)") }
                            }

                            SetupLogic.CHECK_BATTERY -> if (SetupLogic.samsungBatteryStepRequired(Build.MANUFACTURER)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(onClick = {
                                        runCatching {
                                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                        }
                                    }) { Text("Open Battery settings") }
                                    Checkbox(
                                        checked = batteryAcknowledged,
                                        onCheckedChange = { batteryAcknowledged = it },
                                    )
                                    Text("Done (Never sleeping + Unrestricted)")
                                }
                            }

                            SetupLogic.CHECK_KEY -> Column {
                                OutlinedTextField(
                                    value = keyInput,
                                    onValueChange = { keyInput = it },
                                    label = { Text("OpenRouter key") },
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
                                            keySaved = !keySaved // re-trigger the LaunchedEffect
                                        }
                                    },
                                    enabled = keyInput.isNotBlank(),
                                ) { Text("Save key") }
                            }
                        }
                    }
                }

                val allGreen = SetupLogic.allRequiredGreen(checks)
                Text(
                    text = if (allGreen) {
                        "Setup complete — all required checks green."
                    } else {
                        "Setup incomplete — finish the required checks above."
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (allGreen) Color(0xFF1B7A2F) else Color(0xFFB3261E),
                )

                Button(onClick = { startNavigatorService(showGoalInput = false) }) {
                    Text(if (allGreen) "Start Navigator" else "Start Navigator anyway")
                }
                Button(onClick = { startNavigatorService(showGoalInput = true) }) {
                    Text("New task (goal overlay)")
                }
            }
        }
    }

    private fun statusGlyph(status: SetupLogic.Status): String = when (status) {
        SetupLogic.Status.GREEN -> "[OK]"
        SetupLogic.Status.RED -> "[!!]"
        SetupLogic.Status.INFO -> "[i]"
    }

    companion object {
        /** The "New task" notification action lands here (then opens the goal overlay). */
        const val EXTRA_SHOW_GOAL_INPUT = "showGoalInput"

        private const val POST_NOTIFICATIONS_REQUEST = 91
        private const val LAYA_ASSET_DIR = "laya"
        private const val LAYA_ASSET_FILE = "laya-int8.onnx"
    }
}
