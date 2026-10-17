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
