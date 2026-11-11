package navigator.app.harness.vision

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import navigator.app.loop.FreshContextReader
import navigator.app.loop.FreshTarget
import navigator.app.observe.BrowserPackages
import navigator.app.observe.UrlBarReader

/**
 * PHASE_18 Stage 18.B CN-03 — the production [FreshContextReader] over the bound a11y
 * service: every read is FRESH at the dispatch instant (vision 03 §1's fresh-root law —
 * `rootInActiveWindow` re-read, bounds re-read, nothing cached across calls).
 *
 * The active-package read waits BOUNDED for an allowlisted browser (the GestureExecutor
 * focus-gate precedent: an owner card's dismissal re-fronts the browser asynchronously,
 * so a single instantaneous read would race the re-front). The budget expires into the
 * last-seen package — the guard, not the reader, refuses on it.
 *
 * JVM tests fake [FreshContextReader] directly; this class is Android-side only.
 */
class VisionFreshReader(
    private val service: AccessibilityService,
    /** The bounded browser-wait budget (the focus-gate precedent's shape, tighter). */
    private val browserWaitMs: Long = 2_000,
    private val waitCadenceMs: Long = 250,
    private val wait: (Long) -> Unit = { Thread.sleep(it) },
) : FreshContextReader {

    override fun activePackage(): String {
        var last = ""
        val deadline = System.currentTimeMillis() + browserWaitMs
        while (true) {
            last = service.rootInActiveWindow?.packageName?.toString().orEmpty()
            if (BrowserPackages.isBrowser(last)) return last
            if (System.currentTimeMillis() >= deadline) return last
            wait(waitCadenceMs)
        }
    }

    override fun navigationKey(): String? {
        val root = service.rootInActiveWindow ?: return null
        val url = when (val read = UrlBarReader.read(UrlNodeView(root))) {
            is UrlBarReader.Url.Value -> read.text
            else -> return null
        }
        if (url.isBlank() || url == "hidden" || url == "unavailable") return null
        return navigationKey(url).ifBlank { null }
    }

    override fun focusedEditable(): Boolean {
        budget = WALK_BUDGET
        val root = service.rootInActiveWindow ?: return false
        return findNode(root) { it.isFocused && it.isEditable }
    }

    override fun tappableAt(x: Int, y: Int): FreshTarget? =
        smallestAt(x, y) { it.isClickable || it.isEditable }

    override fun editableAt(x: Int, y: Int): FreshTarget? =
        smallestAt(x, y) { it.isEditable }

    /** The smallest-area fresh node at the point passing [predicate], bounds re-read. */
    private fun smallestAt(x: Int, y: Int, predicate: (AccessibilityNodeInfo) -> Boolean): FreshTarget? {
        budget = WALK_BUDGET
        val root = service.rootInActiveWindow ?: return null
        var best: FreshTarget? = null
        walk(root) { node ->
            val b = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            if (!b.contains(x, y)) return@walk
            if (!predicate(node)) return@walk
            val target = FreshTarget(
                className = node.className?.toString().orEmpty(),
                label = node.text?.toString().orEmpty(),
                description = node.contentDescription?.toString().orEmpty(),
                editable = node.isEditable,
                clickable = node.isClickable,
                left = b.left,
                top = b.top,
                right = b.right,
                bottom = b.bottom,
            )
            val better = best == null || run {
                val t = best!!
                (b.width().toLong() * b.height()) <
                    (t.right - t.left).toLong() * (t.bottom - t.top).toLong()
            }
            if (better) best = target
        }
        return best
    }

    /** The per-read traversal budget (each public read resets it). */
    private var budget = WALK_BUDGET

    private fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        if (budget <= 0) return
        budget -= 1
        visit(node)
        for (i in 0 until node.childCount) walk(node.getChild(i) ?: continue, visit)
    }

    private fun findNode(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (budget <= 0) return false
        budget -= 1
        if (predicate(node)) return true
        for (i in 0 until node.childCount) {
            if (findNode(node.getChild(i) ?: continue, predicate)) return true
        }
        return false
    }

    companion object {
        /** The traversal budget per fresh read (the observation layer's scale). */
        const val WALK_BUDGET = 400
    }
}

/** The [UrlBarReader.NodeView] adapter over a live node (the service's own adapter shape). */
private class UrlNodeView(private val node: AccessibilityNodeInfo) : UrlBarReader.NodeView {
    override val viewId: String? get() = node.viewIdResourceName
    override val text: String? get() = node.text?.toString()
    override val className: String? get() = node.className?.toString()
    override val isEditable: Boolean get() = node.isEditable
    override val children: List<UrlBarReader.NodeView>
        get() = (0 until node.childCount).mapNotNull { i -> node.getChild(i)?.let { UrlNodeView(it) } }
}
