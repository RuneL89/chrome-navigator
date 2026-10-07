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

    // ---- the credential-secret card variant (vision 06 §2a, Phase 08F) ----

    @Test
    fun `credential-typing classes on a TYPE action carry the masked-input variant`() {
        val context = PageContext("https://blog.example.com/signin")
        for (hclass in CREDENTIAL_TYPING_CLASSES) {
            val card = GateCard.build(
                hclass,
                GateAction(candidate("pw", "Password"), ActionKind.TYPE),
                context,
            )
            assertTrue(
                card.credentialTyping,
                "$hclass typing must build the credential card variant (doc 06 §2a)",
            )
        }
    }

    @Test
    fun `submissions and non-credential classes ride the ordinary card`() {
        val context = PageContext("https://blog.example.com/signin")
        // A SUBMIT of the very same login form is submission, not typing.
        assertFalse(
            GateCard.build(HClass.H4_LOGIN, GateAction(candidate("go", "Sign in"), ActionKind.SUBMIT), context)
                .credentialTyping,
            "the masked input is only for typing actions",
        )
        // Non-credential classes never carry the variant, even when typing.
        assertFalse(
            GateCard.build(HClass.H2_PURCHASE_CONFIRM, GateAction(candidate("q", "Search"), ActionKind.TYPE), context)
                .credentialTyping,
        )
        assertFalse(
            GateCard.build(HClass.H9_PERSISTENT_MUTATION, GateAction(candidate("c", "Save"), ActionKind.TYPE), context)
                .credentialTyping,
        )
    }
}
