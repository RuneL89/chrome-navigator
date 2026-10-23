package navigator.app.harness.vision

/**
 * PHASE_17 Stage 0.3 — shared normalized-token helpers for the checkout integrity checks
 * (pure, deterministic; the same token discipline as [PurchaseControl]).
 */
internal object GoalTokens {

    /** Non-letter/digit runs collapse to a single space. */
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")

    /** Lowercased letter/digit tokens — Unicode-aware (Finnish/German/French survive). */
    fun normalize(text: String): List<String> =
        text.lowercase().replace(NON_WORD, " ").split(' ').filter { it.isNotBlank() }

    /** Whether [needle] appears as a CONSECUTIVE run inside [text] (never a substring). */
    fun containsRun(text: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || text.size < needle.size) return false
        var start = 0
        while (start <= text.size - needle.size) {
            if (text.subList(start, start + needle.size) == needle) return true
            start += 1
        }
        return false
    }
}
