package navigator.app.harness.vision

import navigator.core.enumerate.ObservedNode

/**
 * PHASE_VEXP8 — the PRODUCT-PAGE PURCHASE-CONTROL detector (pure JVM, boolean only).
 *
 * The run-7 live defect (`task-1792427054326`): the Celsius product page stalled for
 * steps 11–23 on scroll_down/scroll_up while the sticky add-to-cart CTA sat in the
 * accessibility tree, visible the whole time. The loop's scroll-churn backstop needs to
 * know whether the CURRENT observation offers a purchase control — this object answers
 * exactly that and NEVER clicks. It is a pure predicate so the decision layer can use it
 * without any Android type or side effect.
 *
 * Law:
 * - a node must be CLICKABLE and NOT part of the browser chrome (an omnibox suggestion
 *   can never be a purchase control);
 * - the node's visible [ObservedNode.text], content description [ObservedNode.desc] or
 *   placeholder [ObservedNode.hint] must CONTAIN one of the CTA phrases as a consecutive
 *   run of normalized tokens (lowercased, non-letter/digit characters collapsed) — never
 *   a raw substring match ("Edit cart" or "Add a note" must not match);
 * - "sticky/fixed bottom bar" is not a geometry heuristic: an on-screen clickable node
 *   with the CTA text is a purchase control wherever it sits (the phase doc's non-goal
 *   forbids a broader sticky/scroll heuristic).
 *
 * THE PHRASES (the phase doc's vocabulary, one entry per supported storefront wording):
 * add to cart / add to basket / add to bag / lisää ostoskoriin / lisää koriin /
 * ostoskoriin / in den warenkorb / ajouter au panier / añadir al carrito /
 * lägg i varukorgen / buy now.
 */
object PurchaseControl {

    /** Non-letter/digit runs collapse to a single space (initialized BEFORE [PHRASES] uses it). */
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")

    /** The CTA vocabulary as normalized token runs. */
    private val PHRASES: List<List<String>> = listOf(
        "add to cart",
        "add to basket",
        "add to bag",
        "lisää ostoskoriin",
        "lisää koriin",
        "ostoskoriin",
        "in den warenkorb",
        "ajouter au panier",
        "añadir al carrito",
        "lägg i varukorgen",
        "buy now",
    ).map(::tokens)

    /** Whether the observation shows at least one clickable, non-chrome purchase CTA. */
    fun hasVisible(nodes: List<ObservedNode>): Boolean = nodes.any { node ->
        node.clickable && !node.browserChrome && listOf(node.text, node.desc, node.hint).any(::matches)
    }

    /** One label field matches when it contains a CTA phrase as a token run. */
    private fun matches(label: String): Boolean {
        val labelTokens = tokens(label)
        if (labelTokens.isEmpty()) return false
        return PHRASES.any { phrase -> containsRun(labelTokens, phrase) }
    }

    /** Lowercased letter/digit tokens — Unicode-aware, so Finnish/German/French accents survive. */
    private fun tokens(text: String): List<String> =
        text.lowercase().replace(NON_WORD, " ").split(' ').filter { it.isNotBlank() }

    /** Whether [needle] appears as a CONSECUTIVE run inside [text] (never a substring). */
    private fun containsRun(text: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || text.size < needle.size) return false
        var start = 0
        while (start <= text.size - needle.size) {
            if (text.subList(start, start + needle.size) == needle) return true
            start += 1
        }
        return false
    }
}
