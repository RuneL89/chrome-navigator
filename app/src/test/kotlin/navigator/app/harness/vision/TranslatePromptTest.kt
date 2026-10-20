package navigator.app.harness.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PHASE_15 UAT repair round 2 — the pure proposal matcher over the run-13 Attempt-2
 * descriptions: the proposer's Translate acceptance is recognized; the cookie consent,
 * the dismiss/settings controls and a page's generic "Translate" link are not.
 */
class TranslatePromptTest {

    private val box = VisionPrompts.BoxFractions(top = 0.12, bottom = 0.16, left = 0.77, right = 0.92)

    private fun proposal(
        description: String,
        action: VisionPrompts.ProposalAction = VisionPrompts.ProposalAction.TAP,
        box: VisionPrompts.BoxFractions? = this.box,
    ) = VisionPrompts.Proposal(description, action, null, box, 0.9)

    @Test
    fun `the run 032 Translate proposal is accepted over the cookie consent`() {
        val proposals = listOf(
            proposal(
                "tap \"Translate\" link-style button in the Google Translate prompt bar at the " +
                    "top of the page (Finnish to English), right side of the bar",
            ),
            proposal(
                "tap \"Hyväksy\" orange button in the cookie consent banner at the bottom of the " +
                    "screen, right side",
            ),
        )
        assertEquals(0, TranslatePrompt.acceptIndex(proposals))
    }

    @Test
    fun `the run 035 description with the settings-gear context still matches the Translate control`() {
        val proposals = listOf(
            proposal(
                "tap \"Translate\" link-button in the 'Translate page? Finnish to English' bar at " +
                    "the top of the screen, right of the settings gear",
            ),
        )
        assertEquals(0, TranslatePrompt.acceptIndex(proposals))
    }

    @Test
    fun `dismiss and settings controls are never accepted`() {
        listOf(
            "tap \"Not now\" button in the Google Translate prompt bar at the top of the page",
            "tap \"No thanks\" button in the Translate page? Finnish to English bar",
            "tap \"Close\" X in the translate bar at the top",
            "tap \"Settings\" gear in the Translate page? bar at the top right",
        ).forEach { description ->
            assertNull(TranslatePrompt.acceptIndex(listOf(proposal(description))), description)
        }
    }

    @Test
    fun `a generic page Translate link without the prompt-bar context is not accepted`() {
        assertNull(
            TranslatePrompt.acceptIndex(
                listOf(proposal("tap \"Translate\" navigation link in the site footer")),
            ),
        )
    }

    @Test
    fun `a proposal without a usable box or not a TAP is not accepted`() {
        assertNull(
            TranslatePrompt.acceptIndex(
                listOf(proposal("tap \"Translate\" button in the Google Translate prompt bar", box = null)),
            ),
        )
        assertNull(
            TranslatePrompt.acceptIndex(
                listOf(
                    proposal(
                        "tap \"Translate\" button in the Google Translate prompt bar",
                        action = VisionPrompts.ProposalAction.TYPE,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `no proposals means no acceptance`() {
        assertNull(TranslatePrompt.acceptIndex(emptyList()))
    }
}
