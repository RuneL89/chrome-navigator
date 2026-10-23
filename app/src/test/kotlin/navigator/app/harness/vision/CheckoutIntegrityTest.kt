package navigator.app.harness.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import navigator.app.loop.StepObservation
import navigator.core.enumerate.ObservedNode

/**
 * PHASE_17 Gate 17.0.3 — the checkout integrity checks (pure): the browser-chrome
 * off-page detector (the R3 tab-switcher loss), the checkout-state recorder's token
 * vocabulary, and the REAL order-control detector (never "continue to payment").
 */
class CheckoutIntegrityTest {

    private fun node(
        id: String,
        text: String,
        clickable: Boolean = true,
        browserChrome: Boolean = false,
        editable: Boolean = false,
    ) = ObservedNode(
        id = id, text = text, desc = "", clickable = clickable, editable = editable,
        scrollable = false, left = 100, top = 200, right = 300, bottom = 260,
        className = "android.widget.Button", sourceOrder = 0, browserChrome = browserChrome,
    )

    private fun obs(
        url: String,
        title: String,
        nodes: List<ObservedNode>,
        windowPackage: String = "com.android.chrome",
    ) = StepObservation(
        url = url, title = title, lines = listOf("lines"), nodes = nodes,
        windowPackage = windowPackage,
    )

    private val checkoutPage = obs(
        "https://cdon.fi/ostoskori/kassa",
        "Kassa | CDON",
        listOf(
            node("n1", "Etunimi", clickable = false, editable = true),
            node("n2", "Katuosoite", clickable = false, editable = true),
            node("n3", "Jatka maksamaan"),
            node("n4", "43,92 €", clickable = false),
        ),
    )

    // -- the off-page detector ----------------------------------------------------------

    @Test
    fun `a working checkout page is never off-page`() {
        assertEquals(null, ChromeUi.offPage(checkoutPage))
    }

    @Test
    fun `the tab switcher shape is off-page`() {
        val tabSwitcher = obs(
            "",
            "Tab Switcher",
            listOf(
                node("n1", "Kassa | CDON", browserChrome = true),
                node("n2", "New tab", browserChrome = true),
                node("n3", "Close all", browserChrome = true),
            ),
        )
        val verdict = ChromeUi.offPage(tabSwitcher)
        assertTrue(verdict != null, "the tab switcher must be off-page")
        assertTrue(verdict!!.reason.contains("chrome"), verdict.reason)
    }

    @Test
    fun `a new-tab page is off-page`() {
        val newTab = obs(
            "about:blank",
            "New tab",
            listOf(node("n1", "Search or type URL", browserChrome = true)),
        )
        assertTrue(ChromeUi.offPage(newTab) != null, "a new tab is off-page")
    }

    @Test
    fun `a native app front is off-page`() {
        val app = obs(
            "https://m.youtube.com/",
            "YouTube",
            listOf(node("n1", "Home"), node("n2", "Shorts"), node("n3", "Subscriptions")),
            windowPackage = "com.google.android.youtube",
        )
        val verdict = ChromeUi.offPage(app)
        assertTrue(verdict != null, "a non-browser front is off-page")
        assertTrue(verdict!!.reason.contains("not a browser"), verdict.reason)
    }

    @Test
    fun `a slow-loading store page is NOT off-page (conservative)`() {
        // A thin tree with a REAL store URL and title: the detector must not misfire on
        // a page mid-load (the negative test the closeout required).
        val loading = obs(
            "https://cdon.fi/ostoskori/kassa",
            "",
            listOf(node("n1", "")),
        )
        assertEquals(null, ChromeUi.offPage(loading))
    }

    // -- the checkout vocabulary --------------------------------------------------------

    @Test
    fun `the checkout tokens recognize the corpus shapes`() {
        assertTrue(checkoutPage.let { VisionLoopProbe.looksLikeCheckout(it) })
        val englishCheckout = obs(
            "https://shop.test/checkout",
            "Checkout — Shop",
            listOf(node("n1", "Place order")),
        )
        assertTrue(VisionLoopProbe.looksLikeCheckout(englishCheckout))
        val productPage = obs(
            "https://shop.test/product/celsius",
            "Celsius 24-pack",
            listOf(node("n1", "Add to cart")),
        )
        assertFalse(VisionLoopProbe.looksLikeCheckout(productPage))
    }

    // -- the order control --------------------------------------------------------------

    @Test
    fun `the real order control is detected - continue to payment is NOT it`() {
        assertTrue(PurchaseControl.orderControlVisible(listOf(node("n1", "Place order"))))
        assertTrue(PurchaseControl.orderControlVisible(listOf(node("n2", "Vahvista tilaus"))))
        assertTrue(PurchaseControl.orderControlVisible(listOf(node("n3", "Pay now"))))
        // The R3 trap: "Jatka maksamaan" (continue to payment) is NOT the order control.
        assertFalse(PurchaseControl.orderControlVisible(listOf(node("n4", "Jatka maksamaan"))))
        assertFalse(PurchaseControl.orderControlVisible(listOf(node("n5", "Continue to payment"))))
        assertFalse(PurchaseControl.orderControlVisible(listOf(node("n6", "Proceed to checkout"))))
        // Browser chrome is excluded; a non-clickable label never counts.
        assertFalse(PurchaseControl.orderControlVisible(listOf(node("n7", "Place order", browserChrome = true))))
        assertFalse(PurchaseControl.orderControlVisible(listOf(node("n8", "Place order", clickable = false))))
        // "Edit the order" must not match "place the order" (token-run, not substring).
        assertFalse(PurchaseControl.orderControlVisible(listOf(node("n9", "Edit the order"))))
    }
}
