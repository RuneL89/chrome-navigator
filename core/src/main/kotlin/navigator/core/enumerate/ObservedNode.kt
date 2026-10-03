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
    )
}
