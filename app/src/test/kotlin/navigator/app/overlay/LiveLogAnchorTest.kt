package navigator.app.overlay

import navigator.app.overlay.LiveLogAnchor.Anchor.BOTTOM_RIGHT
import navigator.app.overlay.LiveLogAnchor.Anchor.TOP_RIGHT
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live log's IME-avoidance anchor decision (UAT 9.1 revision R2 follow-up): while
 * the service's window observation sees an IME, the log anchors TOP-right; when it
 * goes away, back to bottom-right; any non-edge state moves NOTHING (hysteresis; idle
 * polls do zero layout work). Pure JVM — no Android types.
 */
class LiveLogAnchorTest {

    // -- rising edge: the IME appears --------------------------------------------------

    @Test
    fun `an IME while bottom-anchored moves the log to the top-right`() {
        assertEquals(TOP_RIGHT, LiveLogAnchor.decide(BOTTOM_RIGHT, imeVisible = true))
    }

    // -- falling edge: the IME goes away -------------------------------------------------

    @Test
    fun `no IME while top-anchored restores the bottom-right anchor`() {
        assertEquals(BOTTOM_RIGHT, LiveLogAnchor.decide(TOP_RIGHT, imeVisible = false))
    }

    // -- hysteresis: no edge = no move -----------------------------------------------------

    @Test
    fun `no IME while bottom-anchored moves nothing`() {
        assertNull(LiveLogAnchor.decide(BOTTOM_RIGHT, imeVisible = false))
    }

    @Test
    fun `a still-present IME while top-anchored moves nothing - no jitter`() {
        assertNull(LiveLogAnchor.decide(TOP_RIGHT, imeVisible = true))
    }

    // -- UAT 9.1 W2: the anchors clear the system bars ---------------------------------

    @Test
    fun `the bottom anchor rides the nav-bar inset plus the margin`() {
        val bars = LiveLogAnchor.SystemBars(navBarBottomPx = 63, statusBarTopPx = 66)
        assertEquals(63 + 21, LiveLogAnchor.yOffsetPx(BOTTOM_RIGHT, bars, marginPx = 21))
    }

    @Test
    fun `the top anchor rides the status-bar inset plus the margin`() {
        val bars = LiveLogAnchor.SystemBars(navBarBottomPx = 63, statusBarTopPx = 66)
        assertEquals(66 + 21, LiveLogAnchor.yOffsetPx(TOP_RIGHT, bars, marginPx = 21))
    }

    @Test
    fun `zero insets degrade to the bare margin - never negative`() {
        val bars = LiveLogAnchor.SystemBars(navBarBottomPx = 0, statusBarTopPx = 0)
        assertEquals(21, LiveLogAnchor.yOffsetPx(BOTTOM_RIGHT, bars, marginPx = 21))
        assertEquals(21, LiveLogAnchor.yOffsetPx(TOP_RIGHT, bars, marginPx = 21))
    }

    @Test
    fun `a rotation-sized nav bar changes the offset - re-read per application`() {
        val portrait = LiveLogAnchor.SystemBars(navBarBottomPx = 48, statusBarTopPx = 66)
        val landscape = LiveLogAnchor.SystemBars(navBarBottomPx = 63, statusBarTopPx = 27)
        // The overlay re-reads the insets per anchor application; the arithmetic tracks.
        assertTrue(
            LiveLogAnchor.yOffsetPx(BOTTOM_RIGHT, portrait, 21) !=
                LiveLogAnchor.yOffsetPx(BOTTOM_RIGHT, landscape, 21),
        )
    }
}
