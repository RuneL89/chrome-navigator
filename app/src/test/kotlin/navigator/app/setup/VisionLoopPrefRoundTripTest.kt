package navigator.app.setup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PHASE_VEXP2 — the vision-loop preference's read/write round-trip (the engine
 * switch); PHASE_VEXP4 — the DEFAULT FLIP: the vision loop IS the app's engine, so
 * the plain SharedPreferences under the battery-ack file default ON
 * ([SetupLogic.VISION_LOOP_DEFAULT]) and an explicitly stored OFF is the owner's
 * legacy-engine fallback. Written on every wizard flip, read by the service at every
 * product run. Runs the real SharedPreferences on the JVM under Robolectric (the
 * DecisionLogDbTest precedent).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VisionLoopPrefRoundTripTest {

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences(SetupLogic.VISION_LOOP_PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().remove(SetupLogic.VISION_LOOP_PREF_KEY).commit()
    }

    @Test
    fun `the preference defaults ON and round-trips a flip`() {
        // Default ON (PHASE_VEXP4): the reader's fallback is the SetupLogic constant —
        // a fresh install with the key ABSENT runs the vision loop.
        val default = prefs.getBoolean(SetupLogic.VISION_LOOP_PREF_KEY, SetupLogic.VISION_LOOP_DEFAULT)
        assertTrue(default)
        assertTrue(SetupLogic.visionLoopToggleState(default, true, true).on)

        // An explicitly stored OFF still reads OFF — the owner's legacy-engine fallback
        // is honored, never overwritten by the default.
        prefs.edit().putBoolean(SetupLogic.VISION_LOOP_PREF_KEY, false).commit()
        val explicitOff = prefs.getBoolean(SetupLogic.VISION_LOOP_PREF_KEY, SetupLogic.VISION_LOOP_DEFAULT)
        assertFalse(explicitOff)
        assertFalse(SetupLogic.visionLoopToggleState(explicitOff, true, true).on)

        // The wizard's write path (SetupActivity.writeVisionLoopPref) is exactly this
        // edit; the service's read (NavigatorService.visionLoopToggled) is exactly the
        // get below — write ON, read back, the derivation flips.
        prefs.edit().putBoolean(SetupLogic.VISION_LOOP_PREF_KEY, true).commit()
        val stored = prefs.getBoolean(SetupLogic.VISION_LOOP_PREF_KEY, SetupLogic.VISION_LOOP_DEFAULT)
        assertTrue(stored)
        val state = SetupLogic.visionLoopToggleState(stored, openRouterPresent = true, typesafePresent = true)
        assertTrue(state.interactive)
        assertTrue(state.on)
    }
}
