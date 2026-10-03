package navigator.core.decision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Gate 0.2 — ART banding law (vision 02 §2): low `< 0.30`, uncertain `[0.30, 0.70]`,
 * high `> 0.70`. Boundary values belong to UNCERTAIN.
 */
class BandsTest {

    @Test
    fun `boundaries 0_299 0_30 0_70 0_701`() {
        assertEquals(Band.LOW, band(0.299))
        assertEquals(Band.UNCERTAIN, band(0.30))
        assertEquals(Band.UNCERTAIN, band(0.70))
        assertEquals(Band.HIGH, band(0.701))
    }

    @Test
    fun `range extremes land in low and high`() {
        assertEquals(Band.LOW, band(0.0))
        assertEquals(Band.HIGH, band(1.0))
    }

    @Test
    fun `values strictly inside the uncertain band`() {
        assertEquals(Band.UNCERTAIN, band(0.5))
        assertEquals(Band.UNCERTAIN, band(0.301))
        assertEquals(Band.UNCERTAIN, band(0.699))
    }

    @Test
    fun `out of range probabilities fail loudly`() {
        assertFailsWith<IllegalArgumentException> { band(-0.001) }
        assertFailsWith<IllegalArgumentException> { band(1.001) }
        assertFailsWith<IllegalArgumentException> { band(Double.NaN) }
    }
}
