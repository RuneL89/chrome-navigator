package navigator.app.observe

/**
 * URL-bar reading strategy (vision 03 §1). First try
 * `findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")`; if the id is
 * missing (Chrome version drift), fall back to the first editable text node in the tree
 * (the toolbar subtree's input). Returns a sealed [Url] — never a stale value: callers
 * re-read on every observation (fresh-root policy) and this function holds no state.
 */
object UrlBarReader {

    /** Chrome's URL-bar resource id (stable channel; fallback covers version drift). */
    const val URL_BAR_VIEW_ID = "com.android.chrome:id/url_bar"

    /**
     * The harness's loopback browser (WebView Browser Tester) exposes its address bar
     * under this id. It is BROWSER UI exactly like Chrome's omnibox — never page
     * content — so it joins [URL_BAR_VIEW_IDS] for the id-first read AND the
     * browser-chrome marking (the Phase-10B gate-105 finding: unmarked, it leaked into
     * form-field stage-2 slates on the emulator).
     */
    const val WEBVIEW_SHELL_URL_FIELD_VIEW_ID = "org.chromium.webview_shell:id/url_field"

    /**
     * Every allowlisted browser's URL-bar view id. The browser-chrome marking and the
     * id-first read consume this set; the versions are of the BROWSER's UI, not page
     * content, in each case.
     */
    val URL_BAR_VIEW_IDS: List<String> = listOf(URL_BAR_VIEW_ID, WEBVIEW_SHELL_URL_FIELD_VIEW_ID)

    sealed interface Url {
        /** Live URL as displayed (display mode shows Chrome's elided form; logged as-is). */
        data class Value(val text: String) : Url

        /** Toolbar hidden (scrolled away) — record `hidden`, never a stale value. */
        data object Hidden : Url

        /** No Chrome window to read (Chrome not foreground / tree unavailable). */
        data object Unavailable : Url
    }

    /**
     * Minimal read-only view of an a11y node. The Android service adapts
     * [android.view.accessibility.AccessibilityNodeInfo] onto this; tests fake it.
     */
    interface NodeView {
        /** Fully-qualified view id, e.g. `com.android.chrome:id/url_bar` (null if none). */
        val viewId: String?
        val text: String?
        val className: String?
        val isEditable: Boolean
        /** Pre-ordered children (deterministic walk order — ART-6). */
        val children: List<NodeView>

        /** `findAccessibilityNodeInfosByViewId` over this abstraction (pre-order default). */
        fun findByViewId(id: String): NodeView? {
            if (viewId == id) return this
            for (child in children) child.findByViewId(id)?.let { return it }
            return null
        }
    }

    /**
     * @param root the fresh Chrome root node, or null when no Chrome window is readable.
     */
    fun read(root: NodeView?): Url {
        if (root == null) return Url.Unavailable

        for (viewId in URL_BAR_VIEW_IDS) {
            root.findByViewId(viewId)?.let { node ->
                val text = node.text
                if (!text.isNullOrBlank()) return Url.Value(text)
                // url_bar node present but blank (transient render state): fall through
                // to the heuristic rather than recording an empty URL.
            }
        }
        firstEditable(root)?.let { node ->
            val text = node.text
            if (!text.isNullOrBlank()) return Url.Value(text)
        }
        return Url.Hidden
    }

    /** Version-tolerant fallback: first editable text node (EditText-classed or editable). */
    private fun firstEditable(root: NodeView): NodeView? {
        if (root.isEditable || root.className == EDIT_TEXT_CLASS) return root
        for (child in root.children) firstEditable(child)?.let { return it }
        return null
    }

    private const val EDIT_TEXT_CLASS = "android.widget.EditText"
}
