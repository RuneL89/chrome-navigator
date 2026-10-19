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
 * switch): plain SharedPreferences under the battery-ack file, default OFF, written on
 * every wizard flip and read by the service at every product run. Runs the real
 * SharedPreferences on the JVM under Robolectric (the DecisionLogDbTest precedent).
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
    fun `the preference defaults OFF and round-trips a flip`() {
        // Default OFF: the reader's fallback, and the pure derivation reads it as off.
        val default = prefs.getBoolean(SetupLogic.VISION_LOOP_PREF_KEY, false)
        assertFalse(default)
        assertFalse(SetupLogic.visionLoopToggleState(default, true, true).on)

        // The wizard's write path (SetupActivity.writeVisionLoopPref) is exactly this
        // edit; the service's read (NavigatorService.visionLoopToggled) is exactly the
        // get below — write ON, read back, the derivation flips.
        prefs.edit().putBoolean(SetupLogic.VISION_LOOP_PREF_KEY, true).commit()
        val stored = prefs.getBoolean(SetupLogic.VISION_LOOP_PREF_KEY, false)
        assertTrue(stored)
        val state = SetupLogic.visionLoopToggleState(stored, openRouterPresent = true, typesafePresent = true)
        assertTrue(state.interactive)
        assertTrue(state.on)

        // Back OFF (the owner's lawful off flip).
        prefs.edit().putBoolean(SetupLogic.VISION_LOOP_PREF_KEY, false).commit()
        assertFalse(prefs.getBoolean(SetupLogic.VISION_LOOP_PREF_KEY, false))
    }
}
