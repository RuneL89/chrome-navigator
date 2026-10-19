package navigator.app.harness.vision

import navigator.app.observe.BrowserPackages

/**
 * The window-scoped capture decision (PHASE_VEXP5 WI1) — the PURE half of
 * [VisionCapture]'s window selection, JVM-testable without Android types (the
 * `BrowserPackages`/`LiveLogRecovery` precedent).
 *
 * The experiment's screenshots are the BROWSER WINDOW's surface, not the composited
 * display: the owner's corner live log, the soft keyboard and the system bars never
 * reach Haiku/Jev. The service enumerates its `windows` list (topmost-first), reads each
 * window's id/type/focus/activity/bounds and the root package, and this policy picks the
 * ONE lawful capture target:
 *
 * - the window's root package must be in the [BrowserPackages] allowlist;
 * - it must be an APPLICATION window (the service's own overlays are
 *   `TYPE_ACCESSIBILITY_OVERLAY` and are never lawful targets);
 * - its bounds must have POSITIVE area (a collapsed/zero window cannot be captured);
 * - preference: the FOCUSED window, then the ACTIVE window, then the first lawful
 *   candidate in list order (the platform orders `getWindows()` topmost-first).
 *
 * A null package (the window's root was unavailable — a null-safe platform edge) is
 * never lawful. Null return = no target: the caller falls back to the full-display
 * capture.
 */
object CaptureWindowPolicy {

    /** One enumerated window: the platform facts the decision needs. */
    data class Candidate(
        val id: Int,
        val packageName: String?,
        val isFocused: Boolean,
        val isActive: Boolean,
        val isApplicationWindow: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /** The chosen capture target: the window's identity + its screen-space bounds. */
    data class Target(
        val id: Int,
        val packageName: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /** The lawful capture target, or null (no allowlisted focused/active application window). */
    fun select(candidates: List<Candidate>): Target? {
        val lawful = candidates.filter {
            it.isApplicationWindow &&
                BrowserPackages.isBrowser(it.packageName) &&
                it.right > it.left &&
                it.bottom > it.top
        }
        val chosen = lawful.firstOrNull { it.isFocused }
            ?: lawful.firstOrNull { it.isActive }
            ?: lawful.firstOrNull()
            ?: return null
        return Target(
            id = chosen.id,
            packageName = chosen.packageName.orEmpty(),
            left = chosen.left,
            top = chosen.top,
            right = chosen.right,
            bottom = chosen.bottom,
        )
    }
}
