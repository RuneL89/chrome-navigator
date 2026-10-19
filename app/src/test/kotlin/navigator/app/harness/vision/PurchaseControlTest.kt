package navigator.app.harness.vision

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import navigator.core.enumerate.ObservedNode

/**
 * PHASE_VEXP8 WI2 — the pure purchase-control detector ($0, no Android): clickable,
 * non-browser-chrome nodes whose text/desc/hint carries a CTA phrase as a normalized
 * token run. Boolean only — the detector NEVER clicks.
 */
class PurchaseControlTest {

    private fun node(
        id: String = "n1",
        text: String = "",
        desc: String = "",
        hint: String = "",
        clickable: Boolean = true,
        browserChrome: Boolean = false,
        left: Int = 0,
        top: Int = 0,
        right: Int = 100,
        bottom: Int = 50,
    ) = ObservedNode(
        id = id, text = text, desc = desc, clickable = clickable, editable = false,
        scrollable = false, left = left, top = top, right = right, bottom = bottom,
        className = "android.widget.Button", sourceOrder = 0, browserChrome = browserChrome,
        hint = hint,
    )

    @Test
    fun `a clickable Lisaa ostoskoriin control matches`() {
        assertTrue(PurchaseControl.hasVisible(listOf(node(text = "Lisää ostoskoriin"))))
        // The case/space/punctuation normalization is token-based, never a raw substring.
        assertTrue(PurchaseControl.hasVisible(listOf(node(text = "  LISÄÄ   OSTOSKORIIN  "))))
        assertTrue(PurchaseControl.hasVisible(listOf(node(text = "Add to cart", desc = ""))))
    }

    @Test
    fun `a non-clickable match does not count`() {
        assertFalse(PurchaseControl.hasVisible(listOf(node(text = "Add to cart", clickable = false))))
        // A content description or placeholder does not rescue a non-clickable node either.
        assertFalse(
            PurchaseControl.hasVisible(
                listOf(node(desc = "Add to basket", clickable = false)),
            ),
        )
        assertFalse(
            PurchaseControl.hasVisible(
                listOf(node(hint = "Lisää koriin", clickable = false)),
            ),
        )
    }

    @Test
    fun `browser chrome never counts`() {
        assertFalse(PurchaseControl.hasVisible(listOf(node(text = "Add to cart", browserChrome = true))))
        assertFalse(PurchaseControl.hasVisible(listOf(node(desc = "Buy now", browserChrome = true))))
    }

    @Test
    fun `unrelated controls do not match`() {
        listOf("Add a note", "Edit cart", "cart", "Continue", "Buy later", "adds to cart").forEach { text ->
            assertFalse(PurchaseControl.hasVisible(listOf(node(text = text))), "'$text' must not match")
        }
    }

    @Test
    fun `the sticky bottom control is found among other nodes`() {
        // The run-7 shape: a sticky/fixed bottom bar among ordinary page nodes. No
        // geometry heuristic — the clickable CTA node wherever it sits is enough.
        val nodes = listOf(
            node("n1", text = "Celsius Lemon Lime 24-pack", left = 0, top = 100, right = 1000, bottom = 300),
            node("n2", text = "Search", left = 0, top = 0, right = 200, bottom = 80),
            node(
                "n3", text = "Lisää ostoskoriin", clickable = true,
                left = 0, top = 2200, right = 1000, bottom = 2300,
            ),
        )
        assertTrue(PurchaseControl.hasVisible(nodes))
        // The CTA via desc/hint only (a text-less sticky bar) is found too.
        assertTrue(
            PurchaseControl.hasVisible(
                listOf(node("n4", desc = "add to basket", left = 0, top = 2200, right = 1000, bottom = 2300)),
            ),
        )
        assertFalse(PurchaseControl.hasVisible(listOf(nodes[0], nodes[1])))
    }
}
