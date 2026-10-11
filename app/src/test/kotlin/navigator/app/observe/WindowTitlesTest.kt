package navigator.app.observe

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 10B (Gate 10B.4, the pure half): the page-title reader's lawful-window decision
 * — focused, then active, then the root-application-window fallback, allowlisted only,
 * "" when nothing lawful exists (the KD-5 webview-shell caveat).
 */
class WindowTitlesTest {

    private fun candidate(
        pkg: String,
        title: String,
        focused: Boolean = false,
        active: Boolean = false,
        application: Boolean = true,
    ) = WindowTitles.Candidate(
        packageName = pkg,
        title = title,
        isFocused = focused,
        isActive = active,
        isApplicationWindow = application,
    )

    @Test
    fun `the focused allowlisted window wins`() {
        val title = WindowTitles.lawfulTitle(
            listOf(
                candidate("com.android.launcher", "Launcher", focused = true),
                candidate("com.android.chrome", "Apple Pie Recipe - Allrecipes", active = true),
            ),
        )
        assertEquals("Apple Pie Recipe - Allrecipes", title, "the launcher is not allowlisted")
    }

    @Test
    fun `a focused NON-allowlisted window never steals the page identity`() {
        val title = WindowTitles.lawfulTitle(
            listOf(
                candidate("com.android.chrome", "Museum page"),
                candidate("com.android.systemui", "System UI", focused = true),
            ),
        )
        assertEquals("Museum page", title)
    }

    @Test
    fun `active is preferred over the root-application fallback`() {
        val title = WindowTitles.lawfulTitle(
            listOf(
                candidate("com.android.chrome", "Root window title"),
                candidate("com.android.chrome", "Active window title", active = true),
            ),
        )
        assertEquals("Active window title", title)
    }

    @Test
    fun `the root-application window is the last fallback - a blank focused window never wins`() {
        val title = WindowTitles.lawfulTitle(
            listOf(
                candidate("com.android.chrome", "", focused = true), // blank: not lawful
                candidate("com.android.chrome", "Root application title"),
            ),
        )
        assertEquals("Root application title", title)
    }

    @Test
    fun `a non-application window never serves the fallback`() {
        val title = WindowTitles.lawfulTitle(
            listOf(
                candidate("com.android.chrome", "An IME window", application = false),
            ),
        )
        assertEquals("", title)
    }

    @Test
    fun `no lawful window renders the empty title (the KD-5 caveat is honest input)`() {
        assertEquals("", WindowTitles.lawfulTitle(emptyList()))
        assertEquals(
            "",
            WindowTitles.lawfulTitle(listOf(candidate("com.android.chrome", ""))),
            "a blank title is a defect the downstream consumers render honestly",
        )
        assertEquals(
            "",
            WindowTitles.lawfulTitle(listOf(candidate("com.other.app", "Shell", focused = true))),
            "a non-allowlisted package never serves a title",
        )
    }

    @Test
    fun `the Chrome window prefix is stripped - the page's title is the identity`() {
        // Live-observed 2026-10-11: Chrome's window title is "Chrome: <page title>";
        // the prefix is browser chrome, never the page's identity.
        assertEquals(
            "Wikipedia, the free encyclopedia",
            WindowTitles.lawfulTitle(
                listOf(candidate("com.android.chrome", "Chrome: Wikipedia, the free encyclopedia", focused = true)),
            ),
        )
        // a title without the prefix is untouched; a bare "Chrome:" degrades to ""
        assertEquals(
            "Plain Title",
            WindowTitles.lawfulTitle(listOf(candidate("com.android.chrome", "Plain Title", focused = true))),
        )
        assertEquals("", WindowTitles.stripChromePrefix("Chrome:"))
        assertEquals("Chrome: two", WindowTitles.stripChromePrefix("Chrome: Chrome: two"))
    }
}
