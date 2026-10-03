package navigator.core.gate

import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The confirmation-card payload (vision 06 §2): plain-words content the UI renders
 * verbatim. NO UI code lives here — these tests pin only the payload contract: class
 * name in plain words, site + URL, target field/button, one-line consequence, and the
 * typed value NEVER appears on the card (secrets never render on the overlay).
 */
class GateCardTest {

    private fun candidate(id: String, label: String) = Candidate(
        id = id, label = label, kinds = listOf("tap"), cx = 0, cy = 0,
        bucket = Bucket.BUTTONS, source = CandidateSource.A11Y, sourceOrder = 0, synthetic = false,
    )

    @Test
    fun `card carries class label, site, url, target, and a one-line consequence`() {
        val card = GateCard.build(
            HClass.H2_PURCHASE_CONFIRM,
            GateAction(candidate("buy-now", "Buy now"), ActionKind.TAP),
            PageContext("https://shop.deals-mega.example/product/super-widget-99"),
        )
        assertEquals("Purchase confirmation", card.classLabel)
        assertEquals("shop.deals-mega.example", card.site)
        assertEquals("https://shop.deals-mega.example/product/super-widget-99", card.url)
        assertEquals("\"Buy now\" (id buy-now, tap)", card.target)
        assertEquals("Will confirm a purchase on shop.deals-mega.example.", card.consequenceLine)
    }

    @Test
    fun `the typed value never renders on the card`() {
        val card = GateCard.build(
            HClass.H8_CREDENTIAL_TYPING,
            GateAction(candidate("pw", "Password"), ActionKind.TYPE, textToType = "super-secret-42"),
            PageContext("https://blog.example.com/post"),
        )
        val rendered = listOf(card.classLabel, card.site, card.url, card.target, card.consequenceLine)
        rendered.forEach { field -> assertFalse("super-secret-42" in field, "secret leaked into card field: $field") }
        assertEquals("Will type a credential into a field on blog.example.com.", card.consequenceLine)
    }

    @Test
    fun `every class produces a plain-words consequence naming the site`() {
        val action = GateAction(candidate("x", "Target"), ActionKind.TAP)
        val context = PageContext("https://www.login.example.net/signin")
        for (hclass in HClass.entries) {
            val card = GateCard.build(hclass, action, context)
            assertEquals(hclass.plainLabel, card.classLabel)
            assertTrue(card.consequenceLine.isNotBlank(), "$hclass: blank consequence")
            assertTrue("login.example.net" in card.consequenceLine, "$hclass: consequence must name the site")
            assertFalse(card.consequenceLine.contains("\n"), "$hclass: consequence is ONE line")
        }
    }
}
