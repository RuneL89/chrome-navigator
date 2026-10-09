package navigator.app.overlay

/**
 * The pure anchor decision for the live log's IME avoidance (UAT 9.1 revision R2
 * follow-up — the owner's mid-RUN S25+ screenshot: the AGENT's TYPE action raised the
 * IME and the bottom-right log floated over it). The log window is `FLAG_NOT_FOCUSABLE`,
 * so it NEVER receives IME insets, and the platform's IME window bounds proved
 * UNRELIABLE for geometry (measured on the API 35 emulator: the a11y IME window's
 * `getBoundsInScreen()` top sat ~294px BELOW the drawn keyboard's visual top — an
 * offset computed from it still overlapped the key rows). So the log uses the
 * dispatch's simplest-robust option: the service's window observation
 * (`AccessibilityService.getWindows()`, `TYPE_INPUT_METHOD`) supplies IME PRESENCE
 * only, and while an IME exists the log re-anchors to the TOP-right (never overlaps a
 * keyboard, any device, any IME); when it goes away the log restores to bottom-right.
 *
 * Only EDGES produce a decision — the same IME state again returns null, which is both
 * the hysteresis (a flickering IME cannot jitter the window) and the reason idle polls
 * do zero layout work (the Gate 9.4 receipt→rendered measurement stays truthful).
 *
 * Pure JVM — no Android types; pinned by `LiveLogAnchorTest`.
 */
internal object LiveLogAnchor {

    /** The log's screen anchor. */
    enum class Anchor { BOTTOM_RIGHT, TOP_RIGHT }

    /**
     * [current] — the anchor currently applied; [imeVisible] — whether the service's
     * window observation sees an IME window. Returns the anchor to move to, or null
     * when there is no edge (move nothing).
     */
    fun decide(current: Anchor, imeVisible: Boolean): Anchor? = when {
        imeVisible && current != Anchor.TOP_RIGHT -> Anchor.TOP_RIGHT
        !imeVisible && current != Anchor.BOTTOM_RIGHT -> Anchor.BOTTOM_RIGHT
        else -> null
    }
}
