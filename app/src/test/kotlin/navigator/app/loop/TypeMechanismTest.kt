package navigator.app.loop

import navigator.app.gen.Prompts
import navigator.core.ladder.ReplanContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mechanism-rotation law, pure (doc 04 §1 v1.8.4 — the hotel-iteration run-3 repair;
 * owner picks solutions 1+2+3). Pinned here:
 * - the rotation matrix (TypeMechanism.nextLeg): first attempt = set-text; the paste leg
 *   after a set-text failure; tap-and-yield after both text legs failed; exhausted after
 *   all three — with the truthful exhaustion cause;
 * - the leg mapping from the executor's reported cause sequence (a mechanism cause names
 *   its leg; a pre-mechanism failure consumes no leg — nothing may be recorded);
 * - the truthful step-log lines (lowercase, no site names — the run-3 shape
 *   `fill: set-text rejected, paste rejected (paste failed) - n31`);
 * - the cause classification: the mechanical cause strings classify INPUT_MECHANISM (a
 *   planning problem, not a wording problem) while every existing mapping is unchanged;
 * - the per-cause writer hint: the INPUT_MECHANISM guidance changes the ROUTE, never the
 *   wording.
 */
class TypeMechanismTest {

    private val setType = TypeMechanism.Mechanism.SET_TEXT
    private val paste = TypeMechanism.Mechanism.PASTE
    private val yield = TypeMechanism.Mechanism.TAP_AND_YIELD

    // -- the rotation matrix (pure) --------------------------------------------------------

    @Test
    fun `rotation matrix - set-text first, then the paste leg, then tap-and-yield, then exhausted`() {
        assertEquals(
            TypeMechanism.Plan.Type(setType),
            TypeMechanism.nextLeg(emptySet()),
            "the first attempt on a fresh candidate is the set-text leg",
        )
        assertEquals(
            TypeMechanism.Plan.Type(paste),
            TypeMechanism.nextLeg(listOf(setType)),
            "after a set-text failure the ladder moves to the paste leg",
        )
        assertEquals(
            TypeMechanism.Plan.Yield,
            TypeMechanism.nextLeg(listOf(setType, paste)),
            "after both text legs failed the third leg is tap-and-yield",
        )
        val exhausted = TypeMechanism.nextLeg(listOf(setType, paste, yield))
        assertTrue(exhausted is TypeMechanism.Plan.Exhausted, "all three legs failed -> exhausted")
        assertEquals(
            "typing into this field failed mechanically (set text, paste) - no mechanism left",
            (exhausted as TypeMechanism.Plan.Exhausted).cause,
            "the exhaustion cause is truthful and names the failed text mechanisms",
        )
    }

    @Test
    fun `rotation matrix - the order of the failed set never matters, only membership`() {
        assertEquals(
            TypeMechanism.Plan.Yield,
            TypeMechanism.nextLeg(listOf(paste, setType)),
        )
        assertEquals(
            TypeMechanism.Plan.Exhausted(
                "typing into this field failed mechanically (set text, paste) - no mechanism left",
            ),
            TypeMechanism.nextLeg(listOf(yield, setType, paste)),
        )
    }

    // -- the leg mapping from the executor's cause sequence ---------------------------------

    @Test
    fun `leg mapping - the run-3 chain records both text legs`() {
        assertEquals(
            listOf(setType, paste),
            TypeMechanism.legsFailed(listOf(TextEntryFailure.SET_TEXT_REJECTED, TextEntryFailure.PASTE_FAILED)),
            "set-text rejected then paste rejected = both legs consumed in one attempt",
        )
    }

    @Test
    fun `leg mapping - accepted-and-dropped paste and an unusable clipboard both consume the paste leg`() {
        assertEquals(
            listOf(paste),
            TypeMechanism.legsFailed(listOf(TextEntryFailure.TEXT_NOT_LANDED)),
            "TEXT_NOT_LANDED is only ever emitted after an accepted-and-dropped paste",
        )
        assertEquals(
            listOf(paste),
            TypeMechanism.legsFailed(listOf(TextEntryFailure.CLIPBOARD_UNAVAILABLE)),
            "no clipboard = the paste leg is unusable - never repeat it",
        )
    }

    @Test
    fun `leg mapping - pre-mechanism failures consume no leg`() {
        assertEquals(
            emptyList<TypeMechanism.Mechanism>(),
            TypeMechanism.legsFailed(listOf(TextEntryFailure.WRONG_WINDOW)),
        )
        assertEquals(
            emptyList<TypeMechanism.Mechanism>(),
            TypeMechanism.legsFailed(listOf(TextEntryFailure.NO_FIELD_AT_POINT)),
        )
        assertEquals(
            emptyList<TypeMechanism.Mechanism>(),
            TypeMechanism.legsFailed(listOf(TextEntryFailure.REFRESH_FAILED)),
        )
    }

    // -- the truthful step-log lines ---------------------------------------------------------

    @Test
    fun `attempt line - names the mechanism chain truthfully, lowercase, no site names`() {
        assertEquals(
            "fill: set-text rejected, paste rejected (paste failed) - n31",
            TypeMechanism.attemptLine(
                "n31",
                listOf(TextEntryFailure.SET_TEXT_REJECTED, TextEntryFailure.PASTE_FAILED),
                landed = false,
            ),
        )
    }

    @Test
    fun `attempt line - a rescued attempt names the rescue`() {
        assertEquals(
            "fill: set-text rejected, paste landed - n7",
            TypeMechanism.attemptLine("n7", listOf(TextEntryFailure.SET_TEXT_REJECTED), landed = true),
        )
    }

    @Test
    fun `yield line - the activation yield names mechanism 3`() {
        assertEquals(
            "fill: activating n31 and yielding to the page (mechanism 3)",
            TypeMechanism.yieldLine("n31"),
        )
    }

    @Test
    fun `chain phrase - the history digest form has no terminal parenthetical`() {
        assertEquals(
            "set-text rejected, paste rejected",
            TypeMechanism.chainPhrase(
                listOf(TextEntryFailure.SET_TEXT_REJECTED, TextEntryFailure.PASTE_FAILED),
                landed = false,
            ),
        )
    }

    // -- the cause classification (ReplanSupport.classify) ------------------------------------

    @Test
    fun `classification - the mechanical cause strings classify INPUT_MECHANISM`() {
        val mechanical = "typing into this field failed mechanically (set text, paste) - no mechanism left"
        assertEquals(ReplanContext.CauseClass.INPUT_MECHANISM, ReplanSupport.classify(mechanical))
        assertEquals(
            ReplanContext.CauseClass.INPUT_MECHANISM,
            ReplanSupport.classify("the field rejected set text"),
        )
        assertEquals(
            ReplanContext.CauseClass.INPUT_MECHANISM,
            ReplanSupport.classify("paste rejected by the control"),
        )
        assertEquals(
            ReplanContext.CauseClass.INPUT_MECHANISM,
            ReplanSupport.classify("no mechanism left for this field"),
        )
    }

    @Test
    fun `classification - every existing mapping is unchanged`() {
        assertEquals(
            ReplanContext.CauseClass.NO_GOOD_TARGET,
            ReplanSupport.classify("no good target on the form-fields slate (top 1.61 below the floor)"),
        )
        assertEquals(
            ReplanContext.CauseClass.EMPTY_SLATE,
            ReplanSupport.classify("the 'form-fields' slate was empty for 'fill-field'"),
        )
        assertEquals(
            ReplanContext.CauseClass.UNCERTAIN_AFTER_HANDSHAKE,
            ReplanSupport.classify("the cascade stayed uncertain after the handshake"),
        )
        assertEquals(
            ReplanContext.CauseClass.UNRESOLVABLE_ROUTE,
            ReplanSupport.classify("open-site could not resolve a URL to open"),
        )
        assertEquals(
            ReplanContext.CauseClass.NO_SCROLL_CANDIDATE,
            ReplanSupport.classify("the scroll intent found no scroll candidate"),
        )
        assertEquals(
            ReplanContext.CauseClass.STUCK_REPEATS,
            ReplanSupport.classify("still stuck after hint and strategy switch - L3 auto-kill"),
        )
        assertEquals(
            ReplanContext.CauseClass.ENGINE_GIVE_UP,
            ReplanSupport.classify("engine chose give-up from the recovery set"),
        )
        assertEquals(ReplanContext.CauseClass.OTHER, ReplanSupport.classify("something else went wrong"))
    }

    // -- the per-cause writer hint -------------------------------------------------------------

    @Test
    fun `writer hint - INPUT_MECHANISM changes the ROUTE, never the wording`() {
        val hint = assertNotNull(Prompts.causeHint(ReplanContext.CauseClass.INPUT_MECHANISM))
        assertTrue(
            hint.contains("change the ROUTE, not the wording"),
            "the hint must forbid a wording change: $hint",
        )
        assertTrue(
            hint.contains("activation step before typing") && hint.contains("waypoint page"),
            "the hint names the strategy levers: $hint",
        )
        // The hint stays site-agnostic: no host-shaped or language-specific token.
        assertTrue(!hint.contains(".com") && !hint.contains("http"), hint)
    }

    private fun assertNotNull(value: String?): String =
        requireNotNull(value) { "the INPUT_MECHANISM hint must exist (never null like OTHER)" }
}
