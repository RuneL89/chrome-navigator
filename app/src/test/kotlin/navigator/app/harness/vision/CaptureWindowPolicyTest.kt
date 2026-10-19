package navigator.app.harness.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PHASE_VEXP5 WI1: the pure window-capture decision ([CaptureWindowPolicy]) — model
 * screenshots are the BROWSER WINDOW's surface, so the service's window list must
 * resolve to exactly one lawful capture target. $0, no Android types, no device.
 */
class CaptureWindowPolicyTest {

    private fun candidate(
        id: Int,
        packageName: String? = "com.android.chrome",
        isFocused: Boolean = false,
        isActive: Boolean = false,
        isApplicationWindow: Boolean = true,
        left: Int = 0,
        top: Int = 0,
        right: Int = 1000,
        bottom: Int = 2000,
    ) = CaptureWindowPolicy.Candidate(
        id = id,
        packageName = packageName,
        isFocused = isFocused,
        isActive = isActive,
        isApplicationWindow = isApplicationWindow,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

    @Test
    fun `the focused allowlisted application window wins`() {
        val target = CaptureWindowPolicy.select(
            listOf(
                candidate(id = 1, isActive = true),
                candidate(id = 2, isFocused = true, left = 0, top = 100),
                candidate(id = 3),
            ),
        )
        assertEquals(2, target?.id, "focus beats activity and list order")
        assertEquals("com.android.chrome", target?.packageName)
        assertEquals(CaptureWindowPolicy.Target(2, "com.android.chrome", 0, 100, 1000, 2000), target)
    }

    @Test
    fun `without focus the active window wins - then list order`() {
        val active = CaptureWindowPolicy.select(
            listOf(candidate(id = 1), candidate(id = 2, isActive = true), candidate(id = 3)),
        )
        assertEquals(2, active?.id, "the active window beats list order")

        val first = CaptureWindowPolicy.select(listOf(candidate(id = 1), candidate(id = 2)))
        assertEquals(1, first?.id, "neither focused nor active = topmost-first list order")
    }

    @Test
    fun `non-browser non-application and empty windows are never lawful`() {
        val target = CaptureWindowPolicy.select(
            listOf(
                // The service's own live-log overlay: an application-package name would
                // pass the allowlist, but an overlay is not an APPLICATION window.
                candidate(id = 1, packageName = "com.android.chrome", isApplicationWindow = false, isFocused = true),
                candidate(id = 2, packageName = "com.example.other", isFocused = true),
                candidate(id = 3, packageName = null, isFocused = true),
                candidate(id = 4, right = 0, bottom = 0, isFocused = true), // zero area
                candidate(id = 5, left = 500, right = 500, isFocused = true), // zero width
                candidate(id = 6, isActive = true), // the lawful one
            ),
        )
        assertEquals(6, target?.id, "only the allowlisted, positive-area application window survives")
    }

    @Test
    fun `no lawful candidate selects nothing - the display fallback`() {
        assertNull(CaptureWindowPolicy.select(emptyList()))
        assertNull(
            CaptureWindowPolicy.select(
                listOf(
                    candidate(id = 1, packageName = "com.example.other", isFocused = true),
                    candidate(id = 2, isApplicationWindow = false, isFocused = true),
                ),
            ),
        )
    }
}
