package navigator.app.harness.vision

import navigator.core.enumerate.ObservedNode

/**
 * The EXPERIMENT's pure grounding math (branch-only, JVM-tested): Box answers arrive as
 * fractions of the image's edges; actions happen in SCREEN pixels; the a11y nodes carry
 * screen-space bounds. Pure functions, no Android types beyond the core node model.
 */
object VisionMath {

    /** A screen-space rectangle. */
    data class RectPx(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
    }

    /**
     * Maps a fractional Box answer onto screen pixels via the ORIGINAL screen dims (the
     * encoded image is a uniform downscale, so fractions are identical in both spaces).
     * Clamps to the screen and orders the edges defensively (the API guarantees
     * left<right / top<bottom, but a model can wobble).
     */
    fun boxToScreenRect(
        left: Double, top: Double, right: Double, bottom: Double,
        screenWidth: Int, screenHeight: Int,
    ): RectPx {
        require(screenWidth > 0 && screenHeight > 0) { "screen dims must be positive: ${screenWidth}x$screenHeight" }
        val l = (left.coerceIn(0.0, 1.0) * screenWidth).toInt()
        val r = (right.coerceIn(0.0, 1.0) * screenWidth).toInt()
        val t = (top.coerceIn(0.0, 1.0) * screenHeight).toInt()
        val b = (bottom.coerceIn(0.0, 1.0) * screenHeight).toInt()
        return RectPx(minOf(l, r), minOf(t, b), maxOf(l, r), maxOf(t, b))
    }

    /** Intersection-over-union of two screen rects; 0.0 when either is empty. */
    fun iou(a: RectPx, b: RectPx): Double {
        val interW = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val interH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (interW <= 0 || interH <= 0) return 0.0
        val inter = interW.toDouble() * interH
        val union = a.width.toDouble() * a.height + b.width.toDouble() * b.height - inter
        return if (union <= 0.0) 0.0 else inter / union
    }

    /** The node is actionable from the experiment's perspective: interactive and page content. */
    fun actionable(node: ObservedNode): Boolean =
        !node.browserChrome && (node.clickable || node.editable || node.scrollable)

    private fun nodeRect(node: ObservedNode) = RectPx(node.left, node.top, node.right, node.bottom)

    /**
     * The best-overlap actionable node for a grounded box: max IoU, with a tie-break
     * preferring the node whose center sits inside the box (a huge container overlapping
     * a small button reads lower IoU but the button is the target — the center-inside
     * bonus handles the common nesting case).
     */
    fun bestNodeMatch(nodes: List<ObservedNode>, box: RectPx): Pair<ObservedNode, Double>? =
        nodes.asSequence()
            .filter { actionable(it) }
            .map { node ->
                val i = iou(box, nodeRect(node))
                val bonus = if (box.left <= node.centerX && node.centerX <= box.right &&
                    box.top <= node.centerY && node.centerY <= box.bottom
                ) 0.05 else 0.0
                node to (i + bonus)
            }
            .maxByOrNull { it.second }
            ?.let { (node, score) -> node to iou(box, nodeRect(node)) }

    /**
     * The editable node containing a screen point — the TYPE raw-coordinate arm's safety
     * check (the fourth live-test repair: a box that grounds no a11y node must never fire
     * a doomed `enterText` at an inert spot). Smallest-area node wins (the most specific
     * field when bounds nest); browser chrome is INCLUDED — the address bar is a
     * legitimate type target and the a11y layer marks it as chrome.
     */
    fun editableNodeAt(nodes: List<ObservedNode>, x: Int, y: Int): ObservedNode? =
        nodes.asSequence()
            .filter { it.editable }
            .filter { x in it.left..it.right && y in it.top..it.bottom }
            .minByOrNull { area(it) }

    /**
     * The editable node nearest a grounded NON-editable target — the sixth live-test
     * repair (`task-1792398381736`): a label match can ground the address bar's
     * CONTAINER while the real EditText is a CHILD with different bounds, so a TYPE
     * aimed at the container's centre finds no editable field and churns
     * NO_FIELD_AT_POINT. Preference:
     *
     * 1. an editable whose bounds CONTAIN the target centre within a bounded pixel
     *    tolerance, smallest area (the most specific field when bounds nest);
     * 2. else the maximum-IoU editable against the target's rect, accepted only at
     *    [MIN_EDITABLE_IOU] — a container and its child overlap substantially by
     *    construction.
     *
     * Browser chrome is INCLUDED (the address bar is a legitimate type target).
     */
    fun nearestEditableTo(nodes: List<ObservedNode>, target: ObservedNode): ObservedNode? {
        val editables = nodes.filter { it.editable }
        if (editables.isEmpty()) return null
        editables.filter { containsWithTolerance(it, target.centerX, target.centerY) }
            .minByOrNull { area(it) }
            ?.let { return it }
        val targetRect = nodeRect(target)
        return editables.asSequence()
            .map { it to iou(targetRect, nodeRect(it)) }
            .filter { (_, overlap) -> overlap >= MIN_EDITABLE_IOU }
            .maxByOrNull { (_, overlap) -> overlap }
            ?.first
    }

    /** A bounded tolerance for the point-containment preference (the child edge may sit just off the centre). */
    private const val EDITABLE_CONTAIN_TOLERANCE_PX = 24

    /** The editable-overlap floor for the [nearestEditableTo] fallback. */
    private const val MIN_EDITABLE_IOU = 0.1

    private fun containsWithTolerance(node: ObservedNode, x: Int, y: Int): Boolean =
        x >= node.left - EDITABLE_CONTAIN_TOLERANCE_PX &&
            x <= node.right + EDITABLE_CONTAIN_TOLERANCE_PX &&
            y >= node.top - EDITABLE_CONTAIN_TOLERANCE_PX &&
            y <= node.bottom + EDITABLE_CONTAIN_TOLERANCE_PX

    private fun area(node: ObservedNode): Long =
        (node.right - node.left).toLong().coerceAtLeast(0) *
            (node.bottom - node.top).toLong().coerceAtLeast(0)

    /**
     * The no-screenshot degrade: match a proposal's description to a node by word-set
     * overlap over its text/description label. Returns the best node scoring at least
     * [MIN_LABEL_SCORE], else null.
     */
    fun bestLabelMatch(nodes: List<ObservedNode>, description: String): ObservedNode? {
        val wanted = words(description)
        if (wanted.isEmpty()) return null
        return nodes.asSequence()
            .filter { actionable(it) }
            .map { node -> node to overlap(wanted, words(node.text) + words(node.desc)) }
            .maxByOrNull { it.second }
            ?.takeIf { (node, score) -> score >= MIN_LABEL_SCORE && (node.text.isNotBlank() || node.desc.isNotBlank()) }
            ?.first
    }

    private const val MIN_LABEL_SCORE = 0.34

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("\\W+")).filter { it.length > 1 }.toSet()

    private fun overlap(wanted: Set<String>, have: Set<String>): Double {
        if (wanted.isEmpty() || have.isEmpty()) return 0.0
        return wanted.intersect(have).size.toDouble() / wanted.size.coerceAtMost(have.size)
    }
}
