package navigator.app.observe

/**
 * Pure tree serializer (vision 03 §2, following the credited PokeClaw pattern — see
 * NOTICE): meaningful nodes only, one line each —
 *
 * ```
 *   [n17] "Apple Pie Recipe - Allre" tap (540,632)
 * ```
 *
 * - Text falls back to the content description when the text is blank; truncated at 40
 *   characters; control characters (newline/CR/tab) are flattened to spaces so one node is
 *   always exactly one line.
 * - Kinds come from node traits, in fixed order: `tap` (clickable), `edit` (editable),
 *   `scroll` (scrollable); a trait-less node (kept because of text only) has no kind segment.
 * - Depth: only MEANINGFUL ancestors consume depth; non-meaningful containers are
 *   transparent for it (web content stays reachable without burning depth on chrome).
 *   Depth is capped at 4 levels of two-space indentation on the emitted line. Invisible
 *   nodes are skipped but their subtree is still walked. Nothing else is dropped here —
 *   oversized trees are truncated later, deterministically, by Phase 2's ranking.
 * - Every emitted node carries a stable index (deterministic pre-order — ART-6: same tree
 *   in, same output out) and its center coordinates in the returned index→bounds map used
 *   later by gesture dispatch (Phase 6).
 *
 * No Android types here — Gate 1.1 runs this on the plain JVM.
 */
object TreeSerializer {

    const val MAX_TEXT_LENGTH = 40

    /** Indentation cap: four meaningful levels, two spaces each (vision 03 §2). */
    const val MAX_DEPTH = 4

    /** One emitted node: stable index, serialized line, and the raw traits (fixture capture). */
    data class Emitted(
        val index: Int,
        val line: String,
        val snapshot: NodeSnapshot,
    )

    /** Result of serializing one observation snapshot. */
    data class Serialized(
        val lines: List<String>,
        /** `nodeIndex → bounds` map (the gesture-dispatch coordinate map, vision 03 §2). */
        val nodeBounds: Map<Int, Bounds>,
        /** Emitted nodes in deterministic pre-order (input to Phase 2's bucket enumeration). */
        val nodes: List<Emitted>,
    )

    fun serialize(root: NodeSnapshot): Serialized {
        val nodes = mutableListOf<Emitted>()
        walk(root, depth = 0, nodes)
        val bounds = nodes.associate { it.index to it.snapshot.bounds }
        return Serialized(nodes.map { it.line }, bounds, nodes)
    }

    private fun walk(node: NodeSnapshot, depth: Int, out: MutableList<Emitted>) {
        if (!node.visible) {
            // A node being invisible does not mean its children are (scroll containers).
            for (child in node.children) walk(child, depth, out)
            return
        }
        if (node.isMeaningful) {
            val index = out.size
            val indent = INDENT_UNIT.repeat(minOf(depth, MAX_DEPTH))
            out += Emitted(index, indent + line(index, node), node)
        }
        val childDepth = if (node.isMeaningful) depth + 1 else depth
        for (child in node.children) walk(child, childDepth, out)
    }

    private fun line(index: Int, node: NodeSnapshot): String {
        val raw = node.text.ifBlank { node.desc }
        val flattened = raw.replace(CONTROL_CHARS, " ")
        val truncated =
            if (flattened.length <= MAX_TEXT_LENGTH) flattened else flattened.take(MAX_TEXT_LENGTH)
        val kinds = buildList {
            if (node.clickable) add("tap")
            if (node.editable) add("edit")
            if (node.scrollable) add("scroll")
        }
        val kindSegment = if (kinds.isEmpty()) "" else " " + kinds.joinToString(" ")
        return "[n$index] \"$truncated\"$kindSegment (${node.bounds.centerX},${node.bounds.centerY})"
    }

    private val CONTROL_CHARS = Regex("[\\n\\r\\t]")
    private const val INDENT_UNIT = "  "
}
