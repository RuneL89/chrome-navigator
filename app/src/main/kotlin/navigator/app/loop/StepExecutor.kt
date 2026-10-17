package navigator.app.loop

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import navigator.app.observe.BrowserPackages

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
     * Fires the platform's form-submission affordance (doc 04 §2 v1.8.2, post-UAT repair 2):
     * the IME action (the keyboard's Search/Go/Done key) dispatched on the FOCUSED editable
     * node of the active window. This is the `submit` intent's control-less execution — a
     * mobile search page's submit affordance IS the keyboard key, and there is no control
     * to tap. Never called on a gate-classified submission (the loop checks first).
     * Returns false when no editable field is focused.
     */
    fun submit(): Boolean

    /**
     * Text entry (vision 03 §1): tap to focus the editable node at (x, y), then
     * ACTION_SET_TEXT; if the field rejects SET_TEXT, clipboard + ACTION_PASTE fallback.
     * Returns false when neither path succeeded. Simple-fake variant: no cause reporting.
     */
    fun enterText(x: Int, y: Int, text: String): Boolean

    /**
     * Cause-reporting text entry (2026-10-08 pre-UAT2 RC1 — the injection FAILED 13ms
     * after a readiness pass that had validated readability, not focus, and a bare
     * `false` left the cause invisible). On failure the terminal cause is reported
     * through [cause] — the loop logs it on the execute step line as
     * `-> FAILED (CAUSE)`. The sink may receive several causes in sequence (each stage
     * that failed on the way down); the LAST reported one is the terminal cause.
     * Default delegates to the simple variant with a discarding sink, so existing JVM
     * fakes keep compiling unchanged.
     */
    fun enterText(x: Int, y: Int, text: String, cause: (TextEntryFailure) -> Unit): Boolean =
        enterText(x, y, text)
}

/**
 * Why a text-injection failed (2026-10-08 pre-UAT2 RC1 vocabulary). The loop renders
 * the terminal cause on the execute step line (`-> FAILED (WRONG_WINDOW)`). The loop's
 * mechanism rotation (doc 04 §1 v1.8.4) reads the MECHANISM causes — the ones that name
 * a failed typing leg ([SET_TEXT_REJECTED], [CLIPBOARD_UNAVAILABLE], [PASTE_FAILED],
 * [TEXT_NOT_LANDED]) — into its per-candidate ladder; the pre-mechanism causes
 * ([WRONG_WINDOW], [NO_FIELD_AT_POINT], [REFRESH_FAILED]) consume no leg.
 */
enum class TextEntryFailure {
    /** The active window's package never became an allowlisted browser (bounded wait). */
    WRONG_WINDOW,

    /** A browser window was active, but no editable node sits at the target point. */
    NO_FIELD_AT_POINT,

    /** The editable node at the point went stale (refresh failed) before the write. */
    REFRESH_FAILED,

    /**
     * The SET_TEXT leg failed: the field rejected ACTION_SET_TEXT, or accepted and
     * DROPPED it (the landed-text verification found the text absent — an action's
     * boolean is not proof; Chrome's a11y bridge can accept and drop). The paste
     * fallback follows either way.
     */
    SET_TEXT_REJECTED,

    /** No clipboard manager is reachable for the paste fallback. */
    CLIPBOARD_UNAVAILABLE,

    /** The paste fallback was attempted and rejected. */
    PASTE_FAILED,

    /**
     * The paste leg failed to deliver (2026-10-17 landed-text verification): ACTION_PASTE
     * was accepted but the field's text does not contain the entered phrase.
     */
    TEXT_NOT_LANDED,
}

/**
 * The pure window-gate decision for text entry (RC1): an ACTIVE root whose package is
 * an allowlisted browser may proceed (null); anything else — the harness fronted after
 * a no-overlay card, the launcher, no window at all — is [TextEntryFailure.WRONG_WINDOW].
 * Beside [BrowserPackages] so the JVM tests pin it; the Android wrapper stays thin.
 */
internal fun textEntryWindowCause(activePackage: String?): TextEntryFailure? =
    if (BrowserPackages.isBrowser(activePackage)) null else TextEntryFailure.WRONG_WINDOW

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

internal const val MAX_PHRASE_LENGTH = 80

/**
 * The real executor: gestures through the service's [dispatchGesture]; text entry by
 * fresh root walk to the editable node containing the point, focus-first SET_TEXT with
 * the clipboard-paste fallback. Fresh reads only (vision 03 §1) — no node caching.
 *
 * RC1 (2026-10-08): [enterText] waits — bounded — for the ACTIVE window to belong to an
 * allowlisted browser before it resolves the field. The no-overlay card path had left
 * the HARNESS focused; the fresh walk found no editable at the page-field coordinates
 * and the injection failed silently (a bare `false`). The wait re-reads
 * `rootInActiveWindow` on a short cadence until the browser is front or the ~5s budget
 * expires; the cause vocabulary above reports exactly what failed.
 */
class GestureExecutor(private val service: AccessibilityService) : ActionExecutor {

    override fun tap(x: Int, y: Int): Boolean = dispatchStroke(x, y, x, y, TAP_DURATION_MS)

    override fun scroll(x: Int, y: Int, down: Boolean): Boolean {
        val dy = SCROLL_DISTANCE_PX
        return if (down) dispatchStroke(x, y + dy / 2, x, y - dy / 2, SCROLL_DURATION_MS)
        else dispatchStroke(x, y - dy / 2, x, y + dy / 2, SCROLL_DURATION_MS)
    }

    override fun back(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    override fun submit(): Boolean {
        val focused = service.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        if (!focused.isEditable) return false
        return focused.performAction(
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id,
        )
    }

    override fun enterText(x: Int, y: Int, text: String): Boolean = enterText(x, y, text) { }

    override fun enterText(x: Int, y: Int, text: String, cause: (TextEntryFailure) -> Unit): Boolean {
        // RC1 focus gate: the ACTIVE window must be the browser before we resolve the
        // field. Bounded wait — after a no-overlay card the harness is still front and
        // the browser re-fronts asynchronously; a fixed sleep raced it live.
        val activePackage = awaitBrowserActivePackage()
        textEntryWindowCause(activePackage)?.let {
            cause(it)
            return false
        }
        val field = editableNodeAt(x, y) ?: run {
            cause(TextEntryFailure.NO_FIELD_AT_POINT)
            return false
        }
        // Focus-first sequence (vision 03 §1): refresh -> FOCUS (+CLICK) -> SET_TEXT.
        if (!field.refresh()) {
            cause(TextEntryFailure.REFRESH_FAILED)
            return false
        }
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        // Landed-text verification (2026-10-17, doc 04 §1 v1.8.4): an action's boolean is
        // NOT proof — Chrome's a11y bridge can accept and drop. SET_TEXT "succeeded" with
        // the text absent is an accepted-and-dropped leg: the same SET_TEXT_REJECTED
        // cause, the same paste fallback.
        if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) && textLanded(field, text)) {
            return true
        }
        cause(TextEntryFailure.SET_TEXT_REJECTED)
        // Paste fallback: clipboard + ACTION_PASTE for fields that reject SET_TEXT.
        val clipboard = service.getSystemService(ClipboardManager::class.java) ?: run {
            cause(TextEntryFailure.CLIPBOARD_UNAVAILABLE)
            return false
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(PASTE_CLIP_LABEL, text))
        val pasteAccepted = field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        // Hygiene (RC1): the secret lingers on the clipboard after a paste — clear it
        // whatever the paste's outcome (a rejected paste leaves the clip set too).
        runCatching { clipboard.clearPrimaryClip() }
        if (!pasteAccepted) {
            cause(TextEntryFailure.PASTE_FAILED)
            return false
        }
        // The paste was accepted — but accepted is not landed (the run-3 lesson: both
        // mechanisms "fired" five times while the field never held the text). Verify.
        if (textLanded(field, text)) return true
        cause(TextEntryFailure.TEXT_NOT_LANDED)
        return false
    }

    /**
     * Landed-text verification (2026-10-17, doc 04 §1 v1.8.4): refresh the field and read
     * its text; the entered text must be CONTAINED (not equal — autofill/formatting may
     * wrap it; the phrase is at most [MAX_PHRASE_LENGTH] chars). A failed refresh is a
     * failed verification — an unreadable field proves nothing.
     */
    private fun textLanded(field: AccessibilityNodeInfo, text: String): Boolean {
        if (!field.refresh()) return false
        return field.text?.toString()?.contains(text) == true
    }

    /**
     * The ACTIVE window's package, re-read until it names an allowlisted browser or the
     * bounded budget expires (then the last-seen package, so the caller reports
     * WRONG_WINDOW with the honest cause). Executor-internal mechanics: the DECISION is
     * the pure [textEntryWindowCause] beside [BrowserPackages] — JVM-pinned.
     */
    private fun awaitBrowserActivePackage(): String? {
        val deadline = SystemClock.elapsedRealtime() + BROWSER_WAIT_BUDGET_MS
        var lastSeen: String? = null
        while (true) {
            lastSeen = service.rootInActiveWindow?.packageName?.toString()
            if (textEntryWindowCause(lastSeen) == null) return lastSeen
            if (SystemClock.elapsedRealtime() >= deadline) return lastSeen
            SystemClock.sleep(BROWSER_WAIT_CADENCE_MS)
        }
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

        /**
         * The bounded active-browser wait for text entry (RC1): ~5s budget, 400ms
         * cadence. Executor-internal mechanics (the Android wrapper stays thin).
         */
        private const val BROWSER_WAIT_BUDGET_MS = 5_000L
        private const val BROWSER_WAIT_CADENCE_MS = 400L
    }
}
