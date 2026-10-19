package navigator.app.harness.vision

import navigator.core.enumerate.ObservedNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PHASE_VEXP5 WI2/WI3: the pure occlusion + origin math — the covered-point guard
 * ([VisionMath.coveredAt]/[VisionMath.tappableNodeAt]/[VisionMath.uncoveredPoint]) and
 * the window-origin box mapping. $0, plain JVM.
 */
class VisionOcclusionTest {

    private fun node(
        id: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        clickable: Boolean = true,
        editable: Boolean = false,
        scrollable: Boolean = false,
        chrome: Boolean = false,
    ) = ObservedNode(
        id = id,
        text = id,
        desc = "",
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        className = "android.view.View",
        sourceOrder = 0,
        browserChrome = chrome,
    )

    @Test
    fun `coveredAt grows every rect by the margin`() {
        val log = VisionMath.RectPx(100, 100, 200, 200)
        assertTrue(VisionMath.coveredAt(listOf(log), 150, 150, margin = 16), "inside")
        assertTrue(VisionMath.coveredAt(listOf(log), 210, 150, margin = 16), "16px right of the edge is covered")
        assertTrue(VisionMath.coveredAt(listOf(log), 150, 216, margin = 16), "within the bottom margin")
        assertFalse(VisionMath.coveredAt(listOf(log), 217, 150, margin = 16), "past the margin")
        assertFalse(VisionMath.coveredAt(emptyList(), 150, 150, margin = 16), "no occluders = uncovered")
    }

    @Test
    fun `tappableNodeAt picks the smallest actionable node and includes browser chrome`() {
        val container = node("n1", left = 0, top = 0, right = 1000, bottom = 2000)
        val button = node("n2", left = 100, top = 400, right = 300, bottom = 500)
        val addressBar = node("n3", left = 100, top = 40, right = 900, bottom = 100, clickable = false, editable = true, chrome = true)
        val nodes = listOf(container, button, addressBar)

        assertEquals("n2", VisionMath.tappableNodeAt(nodes, 200, 450)?.id, "the smallest containing node wins")
        assertEquals(
            "n3",
            VisionMath.tappableNodeAt(nodes, 500, 70)?.id,
            "browser chrome is a lawful target (the address bar)",
        )
        assertNull(
            VisionMath.tappableNodeAt(nodes, 1500, 2500),
            "a point outside every node finds nothing",
        )
    }

    @Test
    fun `tappableNodeAt ignores inert and scroll-only nodes`() {
        val inert = node("n1", left = 0, top = 0, right = 1000, bottom = 2000, clickable = false)
        val scrollOnly = node(
            "n2", left = 100, top = 400, right = 300, bottom = 500,
            clickable = false, editable = false, scrollable = true,
        )
        assertNull(
            VisionMath.tappableNodeAt(listOf(inert, scrollOnly), 200, 450),
            "a scrollable-only node cannot be ACTION_CLICKed",
        )
    }

    @Test
    fun `uncoveredPoint walks the candidate order and returns null when everything is covered`() {
        val area = VisionMath.RectPx(0, 0, 1000, 2000)
        val centreCover = VisionMath.RectPx(400, 900, 600, 1100)
        assertEquals(
            500 to 666,
            VisionMath.uncoveredPoint(listOf(centreCover), area, margin = 16),
            "the upper third is the first uncovered candidate",
        )
        val upperThirdCover = VisionMath.RectPx(400, 600, 600, 700)
        assertEquals(
            333 to 1000,
            VisionMath.uncoveredPoint(listOf(centreCover, upperThirdCover), area, margin = 0),
            "the next uncovered candidate is the left third",
        )
        assertNull(
            VisionMath.uncoveredPoint(listOf(VisionMath.RectPx(0, 0, 1000, 2000)), area, margin = 16),
            "every candidate covered = NO lawful start (the review amendment: never a best-effort centre)",
        )
    }

    @Test
    fun `boxToScreenRect adds the scope origin and clamps fractions`() {
        // A window capture starting at (60, 300), 900x1600: fraction (0.5,0.5) is its centre.
        assertEquals(
            VisionMath.RectPx(510, 1100, 510, 1100),
            VisionMath.boxToScreenRect(0.5, 0.5, 0.5, 0.5, 900, 1600, originX = 60, originY = 300),
        )
        assertEquals(
            VisionMath.RectPx(60, 300, 960, 1900),
            VisionMath.boxToScreenRect(0.0, 0.0, 1.0, 1.0, 900, 1600, originX = 60, originY = 300),
        )
        // Out-of-range fractions clamp to the scope edges (the pre-VEXP5 law).
        assertEquals(
            VisionMath.RectPx(60, 300, 960, 1900),
            VisionMath.boxToScreenRect(-0.2, -1.0, 1.4, 2.0, 900, 1600, originX = 60, originY = 300),
        )
    }

    @Test
    fun `boxToScreenRect with a zero origin is the pre-VEXP5 mapping`() {
        assertEquals(
            VisionMath.boxToScreenRect(0.1, 0.2, 0.3, 0.4, 1000, 2000),
            VisionMath.boxToScreenRect(0.1, 0.2, 0.3, 0.4, 1000, 2000, originX = 0, originY = 0),
        )
        assertEquals(
            VisionMath.RectPx(100, 400, 300, 800),
            VisionMath.boxToScreenRect(0.1, 0.2, 0.3, 0.4, 1000, 2000, originX = 0, originY = 0),
        )
    }
}
