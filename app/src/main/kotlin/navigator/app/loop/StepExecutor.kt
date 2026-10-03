package navigator.app.loop

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The execution edge of the loop (vision 04 §2 step 5): gesture dispatch tap/scroll/back
 * at node-map coordinates, text entry focus-first SET_TEXT with the clipboard-paste
 * fallback (vision 03 §1). Pure interface — the Android implementation wraps
 * [AccessibilityService.dispatchGesture]; JVM tests fake it (Gate 6.1 is Robolectric-free).
 */
interface ActionExecutor {
    /** Taps the center of the target's bounds. Returns false when dispatch failed. */
    fun tap(x: Int, y: Int): Boolean

    /** Swipes a vertical scroll centered on (x, y). Returns false when dispatch failed. */
    fun scroll(x: Int, y: Int, down: Boolean): Boolean

    /** Performs the system back action. Returns false when it failed. */
    fun back(): Boolean

    /**
     * Text entry (vision 03 §1): tap to focus the editable node at (x, y), then
     * ACTION_SET_TEXT; if the field rejects SET_TEXT, clipboard + ACTION_PASTE fallback.
     * Returns false when neither path succeeded.
     */
    fun enterText(x: Int, y: Int, text: String): Boolean
}

/**
 * The generative-LLM phrasing hook (vision 04 §2 step 5, locked decision #4) stubbed
 * deterministically until Phase 10: returns the first double-quoted span of the goal
 * ("type THIS"), else the goal itself, flattened and capped. One deterministic answer,
 * zero cloud calls — the loop's contract (the hook returns the text to type) is already
 * the Phase-10 shape, so swapping the stub for the LLM call changes nothing else.
 */
fun phraseStub(goal: String, field: String): String {
    val quoted = Regex("\"([^\"]{1,200})\"").find(goal)?.groupValues?.get(1)
    val flat = (quoted ?: goal).replace(Regex("[\\n\\r\\t]"), " ").trim()
    return flat.take(MAX_PHRASE_LENGTH)
}

private const val MAX_PHRASE_LENGTH = 80

/**
 * The real executor: gestures through the service's [dispatchGesture]; text entry by
 * fresh root walk to the editable node containing the point, focus-first SET_TEXT with
 * the clipboard-paste fallback. Fresh reads only (vision 03 §1) — no node caching.
 */
class GestureExecutor(private val service: AccessibilityService) : ActionExecutor {

    override fun tap(x: Int, y: Int): Boolean = dispatchStroke(x, y, x, y, TAP_DURATION_MS)

    override fun scroll(x: Int, y: Int, down: Boolean): Boolean {
        val dy = SCROLL_DISTANCE_PX
        return if (down) dispatchStroke(x, y + dy / 2, x, y - dy / 2, SCROLL_DURATION_MS)
        else dispatchStroke(x, y - dy / 2, x, y + dy / 2, SCROLL_DURATION_MS)
    }

    override fun back(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    override fun enterText(x: Int, y: Int, text: String): Boolean {
        val field = editableNodeAt(x, y) ?: return false
        // Focus-first sequence (vision 03 §1): refresh -> FOCUS (+CLICK) -> SET_TEXT.
        if (!field.refresh()) return false
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // Paste fallback: clipboard + ACTION_PASTE for fields that reject SET_TEXT.
        val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(PASTE_CLIP_LABEL, text))
        return field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /** Dispatches one stroke; false on a failed dispatch (repeated errors feed the stuck detector). */
    private fun dispatchStroke(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs),
            )
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    /**
     * The editable node containing (x, y), from ONE fresh root walk. A failed refresh
     * drops the subtree — honest sparseness over wrong targets (Phase 1 convention).
     */
    private fun editableNodeAt(x: Int, y: Int): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findEditable(root, x, y)
    }

    private fun findEditable(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        if (!node.refresh()) return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.contains(x, y)) {
            if (node.isEditable) return node
            // Chrome exposes the text entry target as the editable child; if the hit node
            // itself is not editable keep walking its subtree (web fields nest).
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findEditable(child, x, y)?.let { return it }
            }
        }
        return null
    }

    companion object {
        private const val TAP_DURATION_MS = 50L
        private const val SCROLL_DURATION_MS = 250L
        private const val SCROLL_DISTANCE_PX = 400
        private const val PASTE_CLIP_LABEL = "navigator-entry"
    }
}
