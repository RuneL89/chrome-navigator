package navigator.app.harness.vision

/**
 * PHASE_15 UAT repair round 2 (run 13 Attempt 2) — the TRANSLATE-ACCEPTANCE matcher over
 * the proposer's proposals. Pure; it NEVER clicks.
 *
 * The measured failure: at the product-page steps the Haiku proposer DID offer the
 * Translate control (`b0` = "Translate" link button in the Google Translate prompt bar,
 * Finnish to English), but the Jev `pick` chose the cookie-consent button (call-032:
 * `p1` 0.88 vs the Translate 0.02) or `none` (calls 035/038: 0.91/0.87). The pick cannot
 * choose a control the proposer did not offer; when it is offered, this matcher lets the
 * loop select it deterministically before any task action.
 *
 * Law:
 * - TAP only, with a usable screenshot box — the image-path override. The Chrome
 *   translate bar is browser chrome and is not in the a11y state; its location comes
 *   from the proposer's screenshot box.
 * - The CONTROL's own label is the description's LEADING segment (before the first
 *   comma or parenthesis) and it must be the translate acceptance. A quoted context
 *   mention later in the string is never the control: run 14 false-positived on a
 *   search-field description that quoted the "Translate page?" bar as context after its
 *   own leading label ("Search edit field in the store header …").
 * - A prompt-bar context is required ("bar"/"prompt"/"Translate page?"/"Finnish to
 *   English"/"Google Translate"), so a page's generic "Translate" navigation link never
 *   matches.
 * - A dismiss/settings control is never an acceptance — and context words are not the
 *   control: the run-035 description "right of the settings gear" must still match.
 */
object TranslatePrompt {

    /** The control label / description must carry one of these acceptance words. */
    private val ACCEPT_LABEL = Regex("translate|google translate|finnish to english")

    /** The prompt-bar context that distinguishes the bar from a page's generic link. */
    private val PROMPT_CONTEXT =
        Regex("bar|prompt|translate this page|translate page|translate to english|google translate|finnish to english")

    /** The control's own label is a dismissal/settings control, never an acceptance. */
    private val DISMISS_LABEL =
        Regex("not now|no thanks|dismiss|close|settings|gear|never translate|do not translate|don't translate")

    /** The first proposal that accepts the visible translate-to-English prompt, or null. */
    fun acceptIndex(proposals: List<VisionPrompts.Proposal>): Int? =
        proposals.indexOfFirst(::isAccept).takeIf { it >= 0 }

    /** Whether one proposal is the translate acceptance (TAP + box + leading label + bar context). */
    fun isAccept(proposal: VisionPrompts.Proposal): Boolean {
        if (proposal.action != VisionPrompts.ProposalAction.TAP) return false
        if (proposal.box == null) return false
        // KD-13 (run 14): the control is the LEADING label, never a context quote later
        // in the description. The proposer's convention is "<label> <context>"; the first
        // comma/parenthesis ends the control's own phrase.
        val leading = proposal.description
            .substringBefore(',')
            .substringBefore('(')
            .lowercase()
        if (DISMISS_LABEL.containsMatchIn(leading)) return false
        if (!ACCEPT_LABEL.containsMatchIn(leading)) return false
        return PROMPT_CONTEXT.containsMatchIn(proposal.description.lowercase())
    }
}
