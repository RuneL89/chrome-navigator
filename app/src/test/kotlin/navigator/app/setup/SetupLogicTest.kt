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
