package navigator.app.observe

/**
 * JVM-pure snapshot of one accessibility node (vision 03 §2). The Android service maps
 * [android.view.accessibility.AccessibilityNodeInfo] trees onto this model; everything
 * downstream (serializer, URL-bar strategy, tests) stays free of Android types so it runs
 * in plain JVM unit tests without Robolectric.
 *
 * [isPassword] carries the platform's `AccessibilityNodeInfo.isPassword` — the one
 * explicit, reliable secret-field signal (2026-10-08 pre-UAT2 defect: live Chrome
 * exposes secret fields as plain EditText with the typed content as the text and no
 * label association, so text heuristics alone miss it).
 *
 * [browserChrome] marks the omnibox/URL-bar subtree (2026-10-08 pre-UAT2 RC2): the
 * browser's own UI, not page content — the gate excludes it from credential
 * consideration (the URL bar can never be a credential target).
 */
data class NodeSnapshot(
    val text: String = "",
    val desc: String = "",
    val className: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val isPassword: Boolean = false,
    val visible: Boolean = true,
    val bounds: Bounds = Bounds.ZERO,
    val children: List<NodeSnapshot> = emptyList(),
    val browserChrome: Boolean = false,
) {
    /** Meaningful-node filter (vision 03 §2, the PokeClaw-pattern filter). */
    val isMeaningful: Boolean
        get() = text.isNotBlank() || desc.isNotBlank() || clickable || editable || scrollable
}

/** On-screen bounds in the Android a11y convention: left, top, right, bottom (px, ints). */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    /** `"left,top,right,bottom"` — the Phase-0 fixture-schema flattening. */
    val asFixtureString: String get() = "$left,$top,$right,$bottom"

    companion object {
        val ZERO = Bounds(0, 0, 0, 0)
    }
}
