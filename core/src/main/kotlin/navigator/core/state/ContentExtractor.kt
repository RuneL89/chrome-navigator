package navigator.core.state

import navigator.core.enumerate.ObservedNode

/**
 * The v2 content extraction (vision 03 §1 "Content extraction (v2)" + §4 CONTENT
 * section, Phase 08H): a bounded excerpt of the page's NON-CANDIDATE text — the prose
 * and headings a reader would read — deterministically selected and ordered. The
 * excerpt feeds the state text's `UNTRUSTED PAGE CONTENT` block (below the marker,
 * ART-4) and is the substrate for read/compare goals. Text only (ART-8): no
 * screenshots, no pixels, nothing but the observed node text.
 *
 * Laws:
 * - **Non-candidate scope**: only nodes that produce no page candidate and no synthetic
 *   action — not clickable, not editable, not a scrollable container — with non-blank
 *   visible text. Interactive labels stay in the candidate lines; the excerpt is the
 *   rest of the page.
 * - **Deterministic selection + order** (ART-6): a subsequence of the a11y read order
 *   (the input list order, which is [ObservedNode.sourceOrder] order); same tree in →
 *   same excerpt out, always. No wall clock, no randomness, no locale sensitivity.
 * - **Bounded**: at most [MAX_LINES] lines of at most [MAX_LINE_LENGTH] characters each
 *   (the serializer's debug-tree cap, vision 03 §2); whitespace flattened to one line
 *   per node; duplicates dropped keeping the FIRST occurrence (repeated headers/footers
 *   never eat the budget twice).
 * - Pure JVM data-in/data-out: no Android types, no I/O (`:core` boundary).
 */
object ContentExtractor {

    /** Bounded excerpt: at most this many lines survive (deterministic read-order head). */
    const val MAX_LINES: Int = 24

    /** Per-line cap (vision 03 §2 debug-tree discipline: 200 characters). */
    const val MAX_LINE_LENGTH: Int = 200

    /**
     * Extracts the bounded prose excerpt from one observation's nodes, in deterministic
     * read order. The input list order is the a11y read order (ART-6) — e.g.
     * `A11yDump.observedNodes()` or the live walk's emitted nodes; the extraction is a
     * deterministic subsequence of it.
     */
    fun extract(nodes: List<ObservedNode>): List<String> {
        val seen = HashSet<String>()
        val excerpt = ArrayList<String>(MAX_LINES)
        for (node in nodes) {
            if (excerpt.size >= MAX_LINES) break
            if (node.clickable || node.editable || node.scrollable) continue
            val line = flatten(node.text)
            if (line.isEmpty()) continue
            if (!seen.add(line)) continue
            excerpt += line
        }
        return excerpt
    }

    /**
     * App-authored input discipline (the state builder's rule): control characters
     * (newline/CR/tab) become plain spaces and whitespace runs collapse — one line per
     * node, never multi-line injection into the excerpt.
     */
    private fun flatten(input: String): String =
        input.replace(CONTROL_CHARS, " ").trim().replace(WHITESPACE_RUNS, " ").take(MAX_LINE_LENGTH)

    private val CONTROL_CHARS = Regex("[\\n\\r\\t]")
    private val WHITESPACE_RUNS = Regex(" {2,}")
}
