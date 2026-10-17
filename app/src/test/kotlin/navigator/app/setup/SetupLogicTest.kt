package navigator.app.setup

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gate F (Phase 9, phase doc §2.2) — the setup wizard's PURE state derivation:
 * the a11y-enabled check string parsing, the manufacturer branching (Samsung-only
 * battery step; the emulator completes green), the key presence semantics, the
 * informational model check (never blocks), and the all-required-green summary.
 * Pure JVM — no Android types.
 */
class SetupLogicTest {

    // -- the a11y-enabled check -------------------------------------------------------

    @Test
    fun `the enabled-services setting parses colon-separated component entries`() {
        val entry = "com.android.chrome/com.android.chrome.Main:navigator.app/navigator.app.observe.NavigatorAccessibilityService"
        assertTrue(SetupLogic.isAccessibilityServiceEnabled(entry, "navigator.app"))
        assertTrue(
            SetupLogic.isAccessibilityServiceEnabled(
                "navigator.app/navigator.app.observe.NavigatorAccessibilityService",
                "navigator.app",
            ),
        )
    }

    @Test
    fun `another app's entry alone never enables`() {
        assertFalse(
            SetupLogic.isAccessibilityServiceEnabled(
                "com.other.app/com.other.app.Service",
                "navigator.app",
            ),
        )
        assertFalse(SetupLogic.isAccessibilityServiceEnabled(null, "navigator.app"))
        assertFalse(SetupLogic.isAccessibilityServiceEnabled("", "navigator.app"))
        assertFalse(
            SetupLogic.isAccessibilityServiceEnabled("null", "navigator.app"),
            "the emulator's fresh-install value is the literal string 'null'",
        )
    }

    @Test
    fun `matching is package-based so a re-install keeps the check green`() {
        assertTrue(
            SetupLogic.isAccessibilityServiceEnabled(
                "navigator.app/some.other.Class",
                "navigator.app",
            ),
        )
    }

    // -- the Samsung battery branching ------------------------------------------------

    @Test
    fun `the battery step is required only on Samsung`() {
        assertTrue(SetupLogic.samsungBatteryStepRequired("samsung"))
        assertTrue(SetupLogic.samsungBatteryStepRequired("SAMSUNG"))
        assertTrue(SetupLogic.samsungBatteryStepRequired(" Samsung "))
        assertFalse(SetupLogic.samsungBatteryStepRequired("Google"))
        assertFalse(SetupLogic.samsungBatteryStepRequired("generic"))
    }

    @Test
    fun `non-Samsung devices pass with an info line - the emulator completes green`() {
        val state = SetupLogic.batteryCheck("Google", acknowledged = false)
        assertEquals(SetupLogic.Status.INFO, state.status)
        assertFalse(state.required, "the non-Samsung info line must not gate the summary")
    }

    @Test
    fun `on Samsung the step is required and turns green on acknowledgement`() {
        val red = SetupLogic.batteryCheck("samsung", acknowledged = false)
        assertEquals(SetupLogic.Status.RED, red.status)
        assertTrue(red.required)
        val green = SetupLogic.batteryCheck("samsung", acknowledged = true)
        assertEquals(SetupLogic.Status.GREEN, green.status)
    }

    // -- the key + model checks and the summary ----------------------------------------

    @Test
    fun `the key check is presence-only and required`() {
        val green = SetupLogic.keyCheck(true)
        assertEquals(SetupLogic.Status.GREEN, green.status)
        assertTrue(green.required)
        val red = SetupLogic.keyCheck(false)
        assertEquals(SetupLogic.Status.RED, red.status)
        assertTrue(red.required)
    }

    // -- UAT 9.2 revision R4: the battery acknowledgement's persistence contract -----

    @Test
    fun `the battery-acknowledgement persistence keys are stable and non-blank`() {
        assertTrue(SetupLogic.BATTERY_ACK_PREFS_FILE.isNotBlank())
        assertTrue(SetupLogic.BATTERY_ACK_PREF_KEY.isNotBlank())
        assertEquals("navigator-setup", SetupLogic.BATTERY_ACK_PREFS_FILE)
        assertEquals("battery-acknowledged", SetupLogic.BATTERY_ACK_PREF_KEY)
    }

    @Test
    fun `the persisted acknowledgement restores the battery step green on Samsung`() {
        // The persisted boolean feeds straight back into the same pure derivation the
        // wizard renders — a restart with the flag set shows the step still GREEN.
        val restored = SetupLogic.batteryCheck("samsung", acknowledged = true)
        assertEquals(SetupLogic.Status.GREEN, restored.status)
        assertTrue(SetupLogic.allRequiredGreen(
            listOf(
                SetupLogic.accessibilityCheck(true),
                restored,
                SetupLogic.keyCheck(true),
                SetupLogic.modelCheck(false),
            ),
        ))
    }

    // -- UAT 9.2 revision R5: the fixed saved-key indicator ---------------------------

    @Test
    fun `the saved-key indicator is a FIXED dot string - never derived from the value`() {
        // Redaction law: presence only — the exact constant, independent of any input.
        assertEquals("••••••••", SetupLogic.SAVED_KEY_INDICATOR)
        assertEquals(8, SetupLogic.SAVED_KEY_INDICATOR.length)
    }

    @Test
    fun `the saved key detail shows only the fixed dots and names the replace path`() {
        val saved = SetupLogic.keyCheck(true)
        assertTrue(saved.detail.contains(SetupLogic.SAVED_KEY_INDICATOR))
        assertTrue(saved.detail.contains("replaces"))
        assertFalse(saved.detail.contains("sk-"), "no key-material shape may ever render")
    }

    @Test
    fun `the model check is informational and never gates - Jev is primary`() {
        val present = SetupLogic.modelCheck(true)
        assertEquals(SetupLogic.Status.INFO, present.status)
        assertFalse(present.required)
        val absent = SetupLogic.modelCheck(false)
        assertEquals(SetupLogic.Status.INFO, absent.status)
        assertFalse(absent.required)
        assertTrue(absent.detail.contains("not required"))
    }

    @Test
    fun `the live-key check maps every test state - untested and testing are info, live green, rejected and error red`() {
        // 2026-10-17 hotel iteration, check 5: the on-demand live-key card's pure mapping.
        val untested = SetupLogic.keyLiveCheck(
            SetupLogic.KeyTestState.UNTESTED,
            "Not tested yet — tap \"Test key\" to validate the stored key against " +
                "OpenRouter (a free call; no tokens are spent).",
        )
        assertEquals(SetupLogic.Status.INFO, untested.status)
        assertFalse(untested.required, "an untested (or offline) key never blocks the wizard")
        assertTrue(untested.detail.contains("Test key"))

        val testing = SetupLogic.keyLiveCheck(
            SetupLogic.KeyTestState.TESTING,
            "Testing the key against OpenRouter…",
        )
        assertEquals(SetupLogic.Status.INFO, testing.status)

        val live = SetupLogic.keyLiveCheck(
            SetupLogic.KeyTestState.LIVE,
            "OpenRouter accepted the key — it is live.",
        )
        assertEquals(SetupLogic.Status.GREEN, live.status)
        assertFalse(live.required)

        val rejected = SetupLogic.keyLiveCheck(
            SetupLogic.KeyTestState.REJECTED,
            "OpenRouter rejected the key (401 — not found or revoked). " +
                "Enter a fresh key above and save, then test again.",
        )
        assertEquals(SetupLogic.Status.RED, rejected.status)
        assertTrue(rejected.detail.contains("401"), "the rejection names the failure class")
        assertTrue(rejected.detail.contains("fresh key"), "the recovery path is named")

        val error = SetupLogic.keyLiveCheck(
            SetupLogic.KeyTestState.ERROR,
            "Network error (IOException) — check the connection and try again.",
        )
        assertEquals(SetupLogic.Status.RED, error.status)
    }

    @Test
    fun `the live-key check never gates the summary - offline owners complete the wizard`() {
        val wizard = listOf(
            SetupLogic.accessibilityCheck(true),
            SetupLogic.batteryCheck("Google", acknowledged = false),
            SetupLogic.keyCheck(true),
            SetupLogic.keyLiveCheck(
                SetupLogic.KeyTestState.UNTESTED,
                "Not tested yet — tap \"Test key\" to validate the stored key against " +
                    "OpenRouter (a free call; no tokens are spent).",
            ),
            SetupLogic.modelCheck(false),
        )
        assertTrue(
            SetupLogic.allRequiredGreen(wizard),
            "an untested live-key card must not block the all-green summary",
        )
    }

    @Test
    fun `the live-key card id and titles are stable`() {
        assertEquals("keyLive", SetupLogic.CHECK_KEY_LIVE)
    }

    @Test
    fun `the summary is all-green only when every required check is green`() {
        val emulatorWizard = listOf(
            SetupLogic.accessibilityCheck(true),
            SetupLogic.batteryCheck("Google", acknowledged = false),
            SetupLogic.keyCheck(true),
            SetupLogic.modelCheck(false),
        )
        assertTrue(SetupLogic.allRequiredGreen(emulatorWizard), "the emulator can complete green")

        val samsungWizardPending = listOf(
            SetupLogic.accessibilityCheck(true),
            SetupLogic.batteryCheck("samsung", acknowledged = false),
            SetupLogic.keyCheck(true),
            SetupLogic.modelCheck(false),
        )
        assertFalse(SetupLogic.allRequiredGreen(samsungWizardPending))

        val samsungWizardDone = samsungWizardPending.map {
            if (it.id == SetupLogic.CHECK_BATTERY) {
                SetupLogic.batteryCheck("samsung", acknowledged = true)
            } else {
                it
            }
        }
        assertTrue(SetupLogic.allRequiredGreen(samsungWizardDone))

        val noA11y = emulatorWizard.map {
            if (it.id == SetupLogic.CHECK_ACCESSIBILITY) SetupLogic.accessibilityCheck(false) else it
        }
        assertFalse(SetupLogic.allRequiredGreen(noA11y), "a red a11y check blocks the summary")

        val noKey = emulatorWizard.map {
            if (it.id == SetupLogic.CHECK_KEY) SetupLogic.keyCheck(false) else it
        }
        assertFalse(SetupLogic.allRequiredGreen(noKey))
    }
}
