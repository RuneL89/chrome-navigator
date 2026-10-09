package navigator.app.overlay

import navigator.app.overlay.LiveLogAnchor.Anchor.BOTTOM_RIGHT
import navigator.app.overlay.LiveLogAnchor.Anchor.TOP_RIGHT
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
