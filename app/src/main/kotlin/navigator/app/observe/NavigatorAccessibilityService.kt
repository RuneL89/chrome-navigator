package navigator.app.observe

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The observation service (vision 03 §1, patterned after PokeClaw's
 * ClawAccessibilityService — see NOTICE). Phase 1 is read-only: it observes, it never
 * acts — no dispatchGesture calls here (gesture execution is Phase 6).
 *
 * Fresh-root policy: every [observe] re-queries the root, `refresh()`es it, and re-walks.
 * Nothing is cached across observations; the a11y tree is eventually consistent (Chrome
 * builds virtual nodes on demand and throttles events), so observations are pull-based.
 */
class NavigatorAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Events are subscribed to per the service config (window state/content/scroll) but
     * deliberately NOT accumulated: per vision 03 §1 the tree is eventually consistent and
     * events are throttled/collapsed, so state is only ever read fresh via [observe].
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 1: no-op. Bucketing/state-text arrives in Phase 2; actions in Phase 6.
    }

    override fun onInterrupt() {
        // No continuous work to interrupt in Phase 1.
    }

    /**
     * One live observation: serialized node lines + node→bounds map + URL bar read,
     * all from one fresh root walk. Never returns cached data.
     */
    fun observe(): Observation {
        val root = freshChromeRoot()
            ?: return Observation(emptyList(), UrlBarReader.Url.Unavailable, emptyList())
        val snapshot = toSnapshot(root, depth = 1) ?: NodeSnapshot()
        val serialized = TreeSerializer.serialize(snapshot)
        val url = UrlBarReader.read(A11yNodeView(root))
        return Observation(serialized.lines, url, serialized.nodes)
    }

    /**
     * Phase-6 loop observation: the same fresh walk as [observe] plus the page-content
     * node count — the number of nodes under the first WebView-classed node (Chromium's
     * web-content subtree root, present in Chrome stable and the webview shell alike).
     * The unreadable-tree rule (vision 03 §5, "one undifferentiated node") must judge
     * the PAGE, not the page plus the browser toolbar; when no WebView node exists the
     * whole meaningful tree counts. One walk, one consistent triple.
     */
    fun observeForLoop(): LoopObservation {
        val root = freshChromeRoot()
            ?: return LoopObservation(UrlBarReader.Url.Unavailable, emptyList(), emptyList(), 0)
        val snapshot = toSnapshot(root, depth = 1) ?: NodeSnapshot()
        val serialized = TreeSerializer.serialize(snapshot)
        val url = UrlBarReader.read(A11yNodeView(root))
        return LoopObservation(url, serialized.lines, serialized.nodes, countPageNodes(snapshot))
    }

    /** Descendant count under the first WebView-classed node; whole tree when absent. */
    private fun countPageNodes(root: NodeSnapshot): Int {
        val webview = findWebView(root)
        return if (webview != null) countDescendants(webview) - 1 else countDescendants(root)
    }

    private fun findWebView(node: NodeSnapshot): NodeSnapshot? {
        if (node.className.contains(WEBVIEW_CLASS_MARKER, ignoreCase = true)) return node
        for (child in node.children) findWebView(child)?.let { return it }
        return null
    }

    private fun countDescendants(node: NodeSnapshot): Int =
        1 + node.children.sumOf { countDescendants(it) }

    /** The Chrome root, freshly re-read: active window if Chrome's, else a Chrome window. */
    private fun freshChromeRoot(): AccessibilityNodeInfo? {
        // Refresh precedes every read (vision 03 §1). A failed refresh means a stale node.
        activeRootIfChrome()?.let { root ->
            if (root.refresh()) return root
        }
        // Active window belongs to another app (launcher, dialogs): fall back to the
        // topmost window whose package is on the allowlist, refreshed.
        for (window in windows.orEmpty()) {
            val root = window.root ?: continue
            if (root.packageName?.toString() in PACKAGE_ALLOWLIST && root.refresh()) return root
        }
        return null
    }

    private fun activeRootIfChrome(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return if (root.packageName?.toString() in PACKAGE_ALLOWLIST) root else null
    }

    /**
     * Maps a live node onto the JVM-pure snapshot model (no depth cap here — the pure
     * serializer applies the meaningful-depth accounting and indentation cap). Off-screen
     * nodes are skipped but their subtree is still walked (the reference pattern); a node
     * that fails to `refresh()` (stale) is dropped with its subtree — honest sparseness
     * over wrong coordinates.
     */
    internal fun toSnapshot(node: AccessibilityNodeInfo, depth: Int): NodeSnapshot? {
        if (!node.refresh()) return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val visible = node.isVisibleToUser
        val children = buildList {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    toSnapshot(child, depth + 1)?.let { add(it) }
                }
            }
        }
        return NodeSnapshot(
            text = node.text?.toString().orEmpty(),
            desc = node.contentDescription?.toString().orEmpty(),
            className = node.className?.toString().orEmpty(),
            clickable = node.isClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            visible = visible,
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
        )
    }

    /** Adapts a live node onto the URL-bar strategy's fakeable node view. */
    private class A11yNodeView(private val node: AccessibilityNodeInfo) : UrlBarReader.NodeView {
        override val viewId: String? get() = node.viewIdResourceName
        override val text: String? get() = node.text?.toString()
        override val className: String? get() = node.className?.toString()
        override val isEditable: Boolean get() = node.isEditable

        override val children: List<UrlBarReader.NodeView> by lazy {
            buildList {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { add(A11yNodeView(it)) }
                }
            }
        }

        // The platform's indexed lookup beats walking the whole tree binder-node by
        // binder-node; semantics match the default pre-order search.
        override fun findByViewId(id: String): UrlBarReader.NodeView? =
            node.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.let { A11yNodeView(it) }
            ?: super.findByViewId(id)
    }

    companion object {
        /**
         * Observation allowlist (vision 03 §1: Chrome stable only). The harness widens
         * this constant for test sites later — code-side filtering is authoritative
         * (the XML `packageNames` attribute only filters event delivery). The Chromium
         * webview-shell browser is on it for fixture capture on images that ship no
         * Chrome stable (Phase-1 capture harness only).
         */
        val PACKAGE_ALLOWLIST: Set<String> = setOf("com.android.chrome", "org.chromium.webview_shell")

        /** Class-name marker of Chromium's web-content subtree root. */
        private const val WEBVIEW_CLASS_MARKER = "WebView"

        /**
         * In-process handle for the debug capture harness (same process, single service).
         * Null when the service is not enabled by the user.
         */
        @Volatile
        var instance: NavigatorAccessibilityService? = null
            private set
    }
}

/** One live observation (Phase 1's product; `nodes` is Phase 2's bucketing input). */
data class Observation(
    val lines: List<String>,
    val url: UrlBarReader.Url,
    val nodes: List<TreeSerializer.Emitted>,
) {
    /** `nodeIndex → bounds` — the gesture-dispatch coordinate map (vision 03 §2). */
    val nodeBounds: Map<Int, Bounds> by lazy {
        nodes.associate { it.index to it.snapshot.bounds }
    }
}

/** One live observation for the Phase-6 loop: [Observation] plus the page-content
 *  node count the unreadable-tree rule needs (see [NavigatorAccessibilityService.observeForLoop]). */
data class LoopObservation(
    val url: UrlBarReader.Url,
    val lines: List<String>,
    val nodes: List<TreeSerializer.Emitted>,
    val pageNodeCount: Int,
)
