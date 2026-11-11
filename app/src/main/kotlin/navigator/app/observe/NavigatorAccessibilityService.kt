package navigator.app.observe

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

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

    /**
     * The Phase-9 product overlay host (all overlay windows are added from THIS
     * service's WindowManager — no SYSTEM_ALERT_WINDOW grant). Null when the service is
     * not connected; torn down (every window removed) in [onDestroy].
     */
    @Volatile
    var overlayManager: navigator.app.overlay.OverlayManager? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Phase 9: no window is added until a surface is shown; teardown in [onDestroy].
        overlayManager = navigator.app.overlay.OverlayManager(this)
    }

    override fun onDestroy() {
        // No leaked windows: every overlay surface is removed here (phase doc §2.1).
        overlayManager?.teardown()
        overlayManager = null
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
        val snapshot = toSnapshot(root, depth = 1, browserPackage = root.packageName?.toString()) ?: NodeSnapshot()
        val serialized = TreeSerializer.serialize(snapshot)
        val url = UrlBarReader.read(A11yNodeView(root))
        return Observation(serialized.lines, url, serialized.nodes)
    }

    /**
     * One live observation for the Phase-6 loop: the same fresh walk as [observe] plus the page-content
     * node count — the number of nodes under the first WebView-classed node (Chromium's
     * web-content subtree root, present in Chrome stable and the webview shell alike).
     * The unreadable-tree rule (vision 03 §5, "one undifferentiated node") must judge
     * the PAGE, not the page plus the browser toolbar; when no WebView node exists the
     * whole meaningful tree counts. One walk, one consistent triple. [windowPackage] is
     * the ACTIVE window's package (2026-10-08 pre-UAT2 RC1) — the readiness signal: a
     * readable tree alone is not readiness, because Chrome's tree stays readable while
     * Chrome sits in the background (the focus-first injection then lands nowhere).
     * Phase 10B added [LoopObservation.title] — the ACTIVE allowlisted window's title,
     * read through the service's `windows` list (the IME-probe precedent) with the
     * root-application-window fallback; the pure decision is [WindowTitles] and the
     * title feeds the state TITLE, the v3 PAGE IDENTITY line, marker evidence, and the
     * answer extraction.
     */
    fun observeForLoop(): LoopObservation {
        val activePackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        val title = readActiveWindowTitle()
        val root = freshChromeRoot()
            ?: return LoopObservation(UrlBarReader.Url.Unavailable, emptyList(), emptyList(), 0, title, activePackage)
        val snapshot = toSnapshot(root, depth = 1, browserPackage = root.packageName?.toString()) ?: NodeSnapshot()
        val serialized = TreeSerializer.serialize(snapshot)
        val url = UrlBarReader.read(A11yNodeView(root))
        return LoopObservation(url, serialized.lines, serialized.nodes, countPageNodes(snapshot), title, activePackage)
    }

    /**
     * The ACTIVE allowlisted window's title: the service's `windows` list (the IME-probe
     * precedent) mapped onto the pure [WindowTitles] decision — focused, then active,
     * then the root-application-window fallback. "" when nothing lawful exists.
     */
    private fun readActiveWindowTitle(): String =
        WindowTitles.lawfulTitle(
            windows.orEmpty().mapNotNull { window ->
                val pkg = window.root?.packageName?.toString() ?: return@mapNotNull null
                val title = try {
                    window.title?.toString().orEmpty()
                } catch (t: Exception) {
                    "" // some window kinds throw on getTitle(); they are simply untitled
                }
                WindowTitles.Candidate(
                    packageName = pkg,
                    title = title,
                    isFocused = window.isFocused,
                    isActive = window.isActive,
                    isApplicationWindow = window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                )
            },
        )

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
     * over wrong coordinates. A node in the omnibox/URL-bar subtree is marked
     * browser-chrome (2026-10-08 pre-UAT2 RC2): the gate excludes it from credential
     * consideration — the marker propagates to the whole subtree.
     */
    internal fun toSnapshot(
        node: AccessibilityNodeInfo,
        depth: Int,
        containerLabel: String? = null,
        browserPackage: String? = null,
    ): NodeSnapshot? {
        if (!node.refresh()) return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val visible = node.isVisibleToUser
        val ownText = node.text?.toString().orEmpty()
        val ownDesc = node.contentDescription?.toString().orEmpty()
        // The nearest LABELED ancestor for unlabeled controls (v1.7.0, Phase 10C).
        val ownLabel = ownText.takeIf { it.isNotBlank() } ?: ownDesc.takeIf { it.isNotBlank() }
        val childContext = ownLabel ?: containerLabel
        val children = buildList {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    toSnapshot(child, depth + 1, childContext, browserPackage)?.let { add(it) }
                }
            }
        }
        // Browser chrome = ANY Chrome-owned UI (v1.7.0, Phase 10C): the
        // whole `<browserPackage>:id/` namespace (toolbar, tab strip, menus) plus the
        // URL-bar ids; web-content nodes carry no browser view-id.
        val chromeById = browserPackage != null &&
            node.viewIdResourceName?.startsWith("$browserPackage:id/") == true
        return NodeSnapshot(
            text = ownText,
            desc = ownDesc,
            className = node.className?.toString().orEmpty(),
            clickable = node.isClickable,
            editable = node.isEditable || isEditableClass(node.className?.toString()),
            scrollable = node.isScrollable,
            isPassword = node.isPassword,
            visible = visible,
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
            browserChrome = chromeById ||
                node.viewIdResourceName in UrlBarReader.URL_BAR_VIEW_IDS ||
                children.any { it.browserChrome },
            hint = node.hintText?.toString().orEmpty(),
            context = containerLabel.orEmpty(),
        )
    }

    /**
     * Editable-by-ROLE (v1.7.0, Phase 10C — doc 03 §2): the platform's `isEditable` OR the
     * EditText class (Chrome's webview bridge exposes some HTML inputs — the observed
     * unlabeled search boxes — as plain `android.widget.EditText` with `isEditable=false`).
     */
    private fun isEditableClass(className: String?): Boolean =
        className?.equals("android.widget.EditText", ignoreCase = true) == true

    /**
     * Adapts a live node onto the URL-bar strategy's fakeable node view. INTERNAL since
     * PHASE_18 CN-02: the snapshotter's service identity edge reuses the same adapter
     * for its fresh URL read (one adapter, one read law) — no behavior change.
     */
    internal class A11yNodeView(private val node: AccessibilityNodeInfo) : UrlBarReader.NodeView {
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
         * Chrome stable (Phase-1 capture harness only). Delegates to the pure
         * [BrowserPackages.ALLOWLIST] (RC1: the focus-gating decisions are JVM-pinned
         * against the same list).
         */
        val PACKAGE_ALLOWLIST: Set<String> get() = BrowserPackages.ALLOWLIST

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
 *  node count the unreadable-tree rule needs (see [NavigatorAccessibilityService.observeForLoop])
 *  and the ACTIVE window's package — empty when there is none — the RC1 readiness
 *  signal (readiness = usable tree AND the browser being the active window). Phase 10B
 *  added [title]: the ACTIVE allowlisted window's title ([WindowTitles]' pure decision;
 *  "" when none) feeding the state TITLE, the v3 PAGE IDENTITY line, marker evidence,
 *  and extraction. */
data class LoopObservation(
    val url: UrlBarReader.Url,
    val lines: List<String>,
    val nodes: List<TreeSerializer.Emitted>,
    val pageNodeCount: Int,
    val title: String = "",
    val windowPackage: String = "",
)
