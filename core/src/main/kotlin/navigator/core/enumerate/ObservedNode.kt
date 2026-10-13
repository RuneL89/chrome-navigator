package navigator.core.enumerate

import navigator.core.fixtures.A11yDump
import navigator.core.fixtures.A11yNode

/**
 * One observed interactive node — the pure-JVM input to [CandidateEnumerator]. This is
 * `:core`'s minimal view of the observation layer's output (Phase 2 works over the
 * `A11yDump` fixture model); no Android types cross the ART-2 boundary.
 *
 * @param id stable node id within the dump (candidate ids inherit it)
 * @param text visible text (may be blank)
 * @param desc content description (may be blank)
 * @param clickable whether the node is clickable
 * @param editable whether the node accepts text entry
 * @param scrollable whether the node is a scrollable container (a scrollability signal,
 *   not a page candidate of its own — scroll actions are synthetic, vision 03 §3)
 * @param left/top/right/bottom on-screen bounds in pixels
 * @param className Android class name (drives the link-vs-button split)
 * @param sourceOrder the node's index in the deterministic a11y read order (ART-6) —
 *   the third-order tiebreak in the candidate ranking
 * @param isPassword the reader-captured secret-field flag
 *   (`AccessibilityNodeInfo.isPassword`) — the primary H8/H3 signal (2026-10-08
 *   pre-UAT2 defect: live Chrome exposes secret fields as plain EditText carrying the
 *   typed content, so wording/mask heuristics alone leave the gate dark); the gate
 *   consumes it through `FieldDescriptor.fromNode`, the state text carries it as the
 *   `password` kind marker
 * @param browserChrome browser-UI marker (2026-10-08 pre-UAT2 RC2): the node belongs
 *   to the browser chrome (the omnibox/URL-bar subtree), not the page content — the
 *   gate excludes such nodes from credential consideration (the URL bar can never be
 *   a credential target) while they stay ordinary ungated candidates. Committed
 *   fixtures predate the flag and decode false (page-only trees).
 */
data class ObservedNode(
    val id: String,
    val text: String,
    val desc: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val className: String,
    val sourceOrder: Int,
    val isPassword: Boolean = false,
    val browserChrome: Boolean = false,
    /**
     * The field's PLACEHOLDER (v1.7.0, Phase 10C): an unlabeled editable control still
     * names itself (the battery: an unlabeled in-page search box was invisible to the
     * form-fields slate). Blank when the platform exposes none.
     */
    val hint: String = "",
    /**
     * The CONTROL'S CONTAINER relation (v1.7.0, Phase 10C): the nearest LABELED ancestor's
     * text when the node itself carries none — a text-less button is described as
     * "button inside '<container>'" instead of being dropped for lacking a label.
     */
    val context: String = "",
) {
    /** Center X — the same `(left + right) / 2` convention the serializer emits. */
    val centerX: Int get() = (left + right) / 2

    /** Center Y. */
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * Maps an `A11yDump`'s nodes (deterministic read order, ART-6) into [ObservedNode]s.
 * List index is the source order; bounds parse strictly — a malformed bounds string
 * fails loudly (fixture loaders already validate the shape, this re-checks the parse).
 */
fun A11yDump.observedNodes(): List<ObservedNode> = nodes.mapIndexed { index, node ->
    node.toObservedNode(index)
}

fun A11yNode.toObservedNode(sourceOrder: Int): ObservedNode {
    val parts = bounds.split(",")
    require(parts.size == 4) { "A11yNode '$id': bounds must have 4 ints, was \"$bounds\"" }
    val (l, t, r, b) = parts.map {
        it.toIntOrNull() ?: throw IllegalArgumentException(
            "A11yNode '$id': bounds component \"$it\" is not an int (bounds \"$bounds\")",
        )
    }
    return ObservedNode(
        id = id,
        text = text,
        desc = desc,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        left = l,
        top = t,
        right = r,
        bottom = b,
        className = className,
        sourceOrder = sourceOrder,
        isPassword = isPassword,
        // Phase 10D Stage 3 (the verifier's note): the fixture-recorded LABEL SOURCES ride
        // the mapping too, so a captured dump's placeholder/container actually reaches the
        // enumerator in replay (the probe→replay loop is closed). Committed fixtures
        // predate the fields (all blank), so every existing replay is byte-identical.
        hint = hint,
        context = context,
    )
}
