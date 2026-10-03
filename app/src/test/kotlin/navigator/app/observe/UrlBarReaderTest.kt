package navigator.app.observe

import navigator.app.observe.UrlBarReader.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Gate 1.2 (JVM): URL-bar strategy over fake node trees — the abstraction is faked,
 * not the real Android API. Four paths: id found, id missing → heuristic fallback,
 * toolbar hidden, no Chrome window.
 */
class UrlBarReaderTest {

    private class FakeNode(
        override val viewId: String? = null,
        override val text: String? = null,
        override val className: String? = null,
        override val isEditable: Boolean = false,
        override val children: List<FakeNode> = emptyList(),
    ) : UrlBarReader.NodeView

    // -- 1. happy path: id found -------------------------------------------------------

    @Test
    fun `id-found url_bar wins`() {
        val root = FakeNode(
            className = "android.view.View",
            children = listOf(
                FakeNode(
                    viewId = "com.android.chrome:id/toolbar",
                    children = listOf(
                        FakeNode(
                            viewId = "com.android.chrome:id/url_bar",
                            text = "google.com", // display mode: elided, no scheme — logged as-is
                            className = "android.widget.TextView",
                        ),
                    ),
                ),
                FakeNode(className = "android.view.View"),
            ),
        )
        val url = UrlBarReader.read(root)
        assertIs<Url.Value>(url)
        assertEquals("google.com", url.text)
    }

    @Test
    fun `edit-mode url_bar carries the full url`() {
        val root = FakeNode(
            children = listOf(
                FakeNode(
                    viewId = "com.android.chrome:id/url_bar",
                    text = "https://www.allrecipes.com/recipe/231539/apple-pie/",
                    className = "android.widget.EditText",
                    isEditable = true,
                ),
            ),
        )
        val url = UrlBarReader.read(root)
        assertEquals(Url.Value("https://www.allrecipes.com/recipe/231539/apple-pie/"), url)
    }

    // -- 2. id missing: heuristic fallback finds the toolbar editable -------------------

    @Test
    fun `id-missing falls back to first editable text node`() {
        val root = FakeNode( // Chrome version drift: no com.android.chrome:id/url_bar anywhere
            children = listOf(
                FakeNode(className = "android.widget.TextView", text = "Some label"),
                FakeNode(
                    viewId = "com.android.chrome:id/toolbar",
                    children = listOf(
                        FakeNode(className = "android.widget.ImageButton"),
                        FakeNode(
                            className = "android.widget.EditText",
                            text = "bbc.com/news",
                            isEditable = true,
                        ),
                    ),
                ),
            ),
        )
        val url = UrlBarReader.read(root)
        assertEquals(Url.Value("bbc.com/news"), url)
    }

    @Test
    fun `blank url_bar text falls through to the heuristic`() {
        val root = FakeNode(
            children = listOf(
                FakeNode(viewId = "com.android.chrome:id/url_bar", text = ""),
                FakeNode(className = "android.widget.EditText", text = "example.com", isEditable = true),
            ),
        )
        val url = UrlBarReader.read(root)
        assertEquals(Url.Value("example.com"), url)
    }

    // -- 3. toolbar hidden ---------------------------------------------------------------

    @Test
    fun `toolbar scrolled away with no editable anywhere is Hidden`() {
        val root = FakeNode(
            children = listOf(
                FakeNode(className = "android.widget.TextView", text = "Article body"),
                FakeNode(className = "android.widget.Button", text = "Share"),
            ),
        )
        assertEquals(Url.Hidden, UrlBarReader.read(root))
    }

    // -- 4. no Chrome window ----------------------------------------------------------------

    @Test
    fun `no readable root is Unavailable`() {
        assertEquals(Url.Unavailable, UrlBarReader.read(null))
    }

    // -- purity: never stale, never cached ------------------------------------------------

    @Test
    fun `repeated reads over the same tree give the same answer and no state is held`() {
        val root = FakeNode(children = listOf(FakeNode(text = "a.com", isEditable = true)))
        assertEquals(UrlBarReader.read(root), UrlBarReader.read(root))
    }
}
