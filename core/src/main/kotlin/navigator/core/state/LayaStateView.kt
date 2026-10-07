package navigator.core.state

import navigator.core.laya.Tokenizer

/**
 * The laya state view (vision 03 §4 dual-view law, amended 2026-10-06; Phase 08H):
 * a pure, deterministic derivation of the canonical state text for every on-device
 * engine call and the on-device-lineage training export — zero train/serve skew per
 * view. `LAYA_STATE_VIEW_VERSION = 1`.
 *
 * - **Budget**: at most `maxLen − headMaxLen − 1` tokens (currently 512 − 192 − 1 = 319
 *   for the pinned 421M checkpoint). The FORMULA lives beside the marker ceilings
 *   (`MarkerBuilder.SequenceFormat.layaStateViewBudget`, ART-5) and re-derives when an
 *   eval-gated checkpoint change moves them; this derivation takes the resulting number
 *   as its `budgetTokens` parameter (`:core` never names a checkpoint).
 * - **Derivation**: same sections, same order as the canonical text (a strict parse of
 *   it — the export path holds only the stored canonical text, vision 05 §4 "the export
 *   derives the view through the same versioned builder, it never rewrites state
 *   text"). A canonical text that already fits returns byte-for-byte unchanged. An
 *   oversized one shrinks exactly like the builder's own compression, applied to the
 *   text: candidate lines drop from the tail of the candidate region (the canonical
 *   lists them deterministically, head-anchored), emptied bucket headers collapse, then
 *   the content-excerpt lines shrink head-anchored (tail first) — whole lines only,
 *   never a corrupted mid-line cut.
 * - **Loud failure**: a view that cannot fit its budget (the app-authored block alone
 *   exceeds it) fails LOUDLY — silent truncation at the marker layer is unlawful (it
 *   was the v1 shadow's hidden skew; vision 03 §4 amendment 2026-10-06).
 */
object LayaStateView {

    /** Version of the laya state-view derivation (ART-7's per-engine view companion). */
    const val LAYA_STATE_VIEW_VERSION: Int = 1

    /**
     * Derives the view of [canonical] under [tokenizer] counting, within [budgetTokens].
     * [budgetTokens] is the caller checkpoint's `maxLen − headMaxLen − 1` (ART-5 —
     * beside the marker ceilings).
     */
    fun derive(canonical: String, tokenizer: Tokenizer, budgetTokens: Int): String =
        deriveWith(canonical, budgetTokens) { tokenizer.count(it) }

    /**
     * The derivation with the token counter injected — the parity seam: the Python
     * reference mirror (`tools/laya_reference.py derive_state_view`) runs the identical
     * algorithm over any counter, so cross-language parity is proven byte-for-byte
     * independent of the tokenizer family.
     */
    fun deriveWith(canonical: String, budgetTokens: Int, count: (String) -> Int): String {
        if (count(canonical) <= budgetTokens) return canonical
        val lines = canonical.split("\n").dropLastWhile { it.isEmpty() }
        val headerIndex = lines.indexOf(StateTextBuilder.UNTRUSTED_HEADER)
        if (headerIndex < 0) {
            // No untrusted block: nothing lawful to shrink — the app lines alone are over.
            throw IllegalStateException(loudFail(count(canonical), budgetTokens))
        }
        val appLines = lines.take(headerIndex + 1) // includes the UNTRUSTED marker line
        val content = lines.drop(headerIndex + 1)

        // Split the content region: excerpt lines, then bucket sections (header + lines).
        val excerpt = content.takeWhile { it !in BUCKET_HEADERS }.toMutableList()
        val sections = mutableListOf<MutableList<String>>() // each: header, line, line, ...
        var current: MutableList<String>? = null
        for (line in content.drop(excerpt.size)) {
            if (line in BUCKET_HEADERS) {
                current = mutableListOf(line)
                sections += current
            } else {
                val section = current ?: continue // a candidate line with no header: keep in excerpt order
                section += line
            }
        }

        fun render(): String = buildString {
            appLines.forEach { append(it).append('\n') }
            excerpt.forEach { append(it).append('\n') }
            sections.forEach { section -> section.forEach { append(it).append('\n') } }
        }

        // Stage 1: drop candidate lines lowest-in-text first (the canonical text orders
        // them deterministically — the tail is the ranking's bottom); a bucket whose
        // lines are gone loses its header too.
        while (count(render()) > budgetTokens) {
            val last = sections.lastOrNull { it.size > 1 } ?: break
            if (last.size == 2) sections.removeLast() else last.removeAt(last.size - 1)
        }
        // Stage 2: shrink the content excerpt head-anchored — whole lines, tail first.
        while (count(render()) > budgetTokens && excerpt.isNotEmpty()) {
            excerpt.removeAt(excerpt.size - 1)
        }
        val view = render()
        if (count(view) > budgetTokens) throw IllegalStateException(loudFail(count(view), budgetTokens))
        return view
    }

    private fun loudFail(tokens: Int, budgetTokens: Int): String =
        "laya state view is $tokens tokens, over the $budgetTokens-token view budget " +
            "(vision 03 §4 dual-view law: maxLen - headMaxLen - 1 beside the marker ceilings, " +
            "ART-5) even with an empty UNTRUSTED PAGE CONTENT block — the app-authored lines " +
            "alone do not fit; refusing to derive an oversized view (silent marker-layer " +
            "truncation is unlawful, LAYA_STATE_VIEW_VERSION=$LAYA_STATE_VIEW_VERSION)"

    /** The six bucket header lines (taxonomy serial names — the shadow's vocabulary). */
    private val BUCKET_HEADERS: Set<String> = setOf(
        "nav-links:", "form-fields:", "buttons:", "scroll:", "back:", "finish:",
    )
}
