package navigator.app.loop

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The 2026-10-07 S25+ UAT defect fix: the harness Debug presenters must bring the
 * harness activity to the front when the overlay permission is ABSENT (an
 * activity-local dialog behind a stopped activity is never seen — the completion card
 * triggered and timed out unseen), and must keep the overlay window when it IS granted.
 */
class GatePresenterFrontPathTest {

    @Test
    fun `no overlay permission means the harness must come to the front`() {
        assertTrue(shouldBringToFront(overlayGranted = false))
    }

    @Test
    fun `granted overlay permission keeps the overlay window (no switch)`() {
        assertFalse(shouldBringToFront(overlayGranted = true))
    }
}
