package navigator.app.harness.vision

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * D2 (live defect, 2026-10-19): the loop's own corner live log was visible in its
 * screenshots and got proposed/targeted ("the X close button of the NAVIGATOR
 * overlay"). The capture paints the log's screen rect solid black BEFORE encoding and
 * keeps the frame FULL SIZE, so box fractions stay valid.
 *
 * Runs the real bitmap pixel paths under Robolectric's native graphics (the prefs
 * round-trip test's Robolectric precedent; the pure mapping helper needs no Android).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisionCaptureExclusionTest {

    @Test
    fun `imageExclusionRect maps a screen rect onto the encoded frame`() {
        // A 200x400 screen downscaled to 100x200: the live log at screen
        // (100,300)-(150,350) maps to image (50,150)-(75,175).
        assertEquals(
            VisionMath.RectPx(50, 150, 75, 175),
            VisionCapture.imageExclusionRect(
                bounds = VisionMath.RectPx(100, 300, 150, 350),
                sourceWidth = 200,
                sourceHeight = 400,
                imageWidth = 100,
                imageHeight = 200,
            ),
        )
    }

    @Test
    fun `imageExclusionRect clamps to the frame and refuses an empty overlap`() {
        // Straddling the bottom-right corner clamps to the image edges.
        assertEquals(
            VisionMath.RectPx(90, 190, 100, 200),
            VisionCapture.imageExclusionRect(
                bounds = VisionMath.RectPx(180, 380, 260, 460),
                sourceWidth = 200,
                sourceHeight = 400,
                imageWidth = 100,
                imageHeight = 200,
            ),
        )
        // Fully off-frame = nothing to paint.
        assertNull(
            VisionCapture.imageExclusionRect(
                bounds = VisionMath.RectPx(300, 500, 400, 600),
                sourceWidth = 200,
                sourceHeight = 400,
                imageWidth = 100,
                imageHeight = 200,
            ),
        )
        // Degenerate dims = nothing to paint (never a crash).
        assertNull(
            VisionCapture.imageExclusionRect(
                bounds = VisionMath.RectPx(1, 1, 2, 2),
                sourceWidth = 0,
                sourceHeight = 400,
                imageWidth = 100,
                imageHeight = 200,
            ),
        )
    }

    @Test
    fun `applyExclusion paints exactly the rect solid black and leaves the rest untouched`() {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)

        VisionCapture.applyExclusion(bitmap, VisionMath.RectPx(5, 6, 12, 14))

        // Every pixel inside the rect is black (corners + interior).
        assertEquals(Color.BLACK, bitmap.getPixel(5, 6))
        assertEquals(Color.BLACK, bitmap.getPixel(11, 6))
        assertEquals(Color.BLACK, bitmap.getPixel(5, 13))
        assertEquals(Color.BLACK, bitmap.getPixel(11, 13))
        assertEquals(Color.BLACK, bitmap.getPixel(8, 10))
        // Every adjacent pixel outside stays untouched.
        assertEquals(Color.WHITE, bitmap.getPixel(4, 6), "left of the rect")
        assertEquals(Color.WHITE, bitmap.getPixel(12, 6), "right of the rect")
        assertEquals(Color.WHITE, bitmap.getPixel(5, 5), "above the rect")
        assertEquals(Color.WHITE, bitmap.getPixel(11, 14), "below the rect")
        assertEquals(Color.WHITE, bitmap.getPixel(0, 0), "far corner")
    }

    @Test
    fun `applyExclusion is a no-op for an empty or fully out-of-frame rect`() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)

        VisionCapture.applyExclusion(bitmap, VisionMath.RectPx(5, 5, 5, 8)) // zero width
        VisionCapture.applyExclusion(bitmap, VisionMath.RectPx(20, 20, 30, 30)) // off-frame
        assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
        assertEquals(Color.WHITE, bitmap.getPixel(9, 9))
    }
}
