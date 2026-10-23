package navigator.core.ladder

import navigator.core.ladder.GoalFidelity.GoalClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PHASE_16 Gate 16.1 — the goal-fidelity law (pure): a ladder is bound to the GOAL, not to
 * its own plan. The corpus cases are the fixtures:
 *
 * - R3b: a purchase goal whose final subtask is a REVIEW (the empty-checkout false
 *   completion) is refused.
 * - R4: an account-switch goal without the target account token, or a playback goal whose
 *   final condition is not a playback marker, is refused.
 * - R1 ("add to cart") is NOT a purchase; R2 ("find … press the latest video") matches no
 *   class — both are untouched.
 */
class GoalFidelityTest {

    private val purchaseGoal =
        "buy SoundMagic E11C with delivery to koivuviita 7 B 5. " +
            "Total budget including delivery: 60 euros"

    private fun ladder(
        vararg subtasks: Subtask,
        taskClass: LadderTaskClass = LadderTaskClass.ACTION,
    ) = Ladder(summary = "plan", subtasks = subtasks.toList(), taskClass = taskClass)

    private fun sub(instruction: String, done: DoneCondition) = Subtask(instruction, done)

    // -- classification ----------------------------------------------------------------

    @Test
    fun `the corpus goals classify as expected`() {
        assertTrue(GoalClass.PURCHASE in GoalFidelity.classify(GoalFidelity.normalize(purchaseGoal)))
        assertTrue(
            GoalClass.ACCOUNT_SWITCH in GoalFidelity.classify(
                GoalFidelity.normalize(
                    "open youtube.com and switch the active user channel to MrAwesome. " +
                        "Then find the Food Wishes channel from my subscriptions and play the " +
                        "latest posted video",
                ),
            ),
        )
        assertTrue(
            GoalClass.PLAYBACK in GoalFidelity.classify(
                GoalFidelity.normalize("play the latest posted video"),
            ),
        )
        // R1 ("add to cart") is not a purchase; R2 ("find … press") matches no class.
        assertEquals(
            emptySet(),
            GoalFidelity.classify(
                GoalFidelity.normalize(
                    "open verkkokauppa.com and add two cases of lemon lime celsius energy " +
                        "drink to the shopping cart",
                ),
            ),
        )
        assertEquals(
            emptySet(),
            GoalFidelity.classify(
                GoalFidelity.normalize(
                    "find the wwe channel on rumble.com and press the latest video in Live tab",
                ),
            ),
        )
    }

    // -- purchase ----------------------------------------------------------------------

    @Test
    fun `the R3b review final is refused - a purchase must end on the order confirmation`() {
        val verdict = GoalFidelity.check(
            purchaseGoal,
            ladder(
                sub("Open the store", DoneCondition.ElementPresent("the store page")),
                sub(
                    "Review the order summary without placing the order yet",
                    DoneCondition.ElementPresent(
                        "order summary listing SoundMagic E11C with total including delivery",
                    ),
                ),
            ),
        )
        assertFalse(verdict.ok)
        assertTrue(
            verdict.violations.any { it.contains("order-confirmation") || it.contains("confirm the ORDER") },
            verdict.violations.toString(),
        )
    }

    @Test
    fun `a purchase ladder ending on the confirmation with every parameter is accepted`() {
        val verdict = GoalFidelity.check(
            purchaseGoal,
            ladder(
                sub(
                    "Search Google for SoundMagic E11C and open its product page from the results",
                    DoneCondition.UrlContains("google.com/search?q=soundmagic"),
                ),
                sub(
                    "Place the order to koivuviita 7 B 5 within the 60 euro budget",
                    DoneCondition.MarkerText("Kiitos tilauksestasi"),
                ),
            ),
        )
        assertTrue(verdict.ok, verdict.violations.toString())
    }

    @Test
    fun `a site-agnostic purchase with a store-first ladder is refused - search-first`() {
        val verdict = GoalFidelity.check(
            purchaseGoal,
            ladder(
                sub(
                    "Open the store and find the product",
                    DoneCondition.UrlHost("cdon.fi"),
                ),
                sub(
                    "Place the order to koivuviita 7 B 5 within the 60 euro budget",
                    DoneCondition.MarkerText("Kiitos tilauksestasi"),
                ),
            ),
        )
        assertFalse(verdict.ok)
        assertTrue(
            verdict.violations.any { it.contains("site-first") },
            verdict.violations.toString(),
        )
    }

    @Test
    fun `a goal that names a site is never asked to search first`() {
        val verdict = GoalFidelity.check(
            "open cdon.fi and buy SoundMagic E11C with delivery to koivuviita 7 B 5 for 60 euros",
            ladder(
                sub(
                    "Open the store and find the product",
                    DoneCondition.UrlHost("cdon.fi"),
                ),
                sub(
                    "Place the order to koivuviita 7 b 5 within the 60 euro budget",
                    DoneCondition.MarkerText("Kiitos tilauksestasi"),
                ),
            ),
        )
        assertTrue(
            verdict.violations.none { it.contains("site-first") },
            verdict.violations.toString(),
        )
    }

    @Test
    fun `a missing delivery address is refused naming it`() {
        val verdict = GoalFidelity.check(
            purchaseGoal,
            ladder(
                sub(
                    "Place the order within the 60 euro budget",
                    DoneCondition.MarkerText("Order confirmed"),
                ),
            ),
        )
        assertFalse(verdict.ok)
        assertTrue(
            verdict.violations.any { it.contains("koivuviita 7 b 5") },
            verdict.violations.toString(),
        )
    }

    @Test
    fun `a missing budget is refused naming it`() {
        val verdict = GoalFidelity.check(
            purchaseGoal,
            ladder(
                sub(
                    "Place the order to koivuviita 7 B 5",
                    DoneCondition.MarkerText("Order confirmed"),
                ),
            ),
        )
        assertFalse(verdict.ok)
        assertTrue(
            verdict.violations.any { it.contains("'60'") },
            verdict.violations.toString(),
        )
    }

    @Test
    fun `a missing quantity is refused naming it`() {
        val goal = "buy two cases of Celsius lemon lime with delivery to koivuviita 7 B 5 " +
            "for 60 euros"
        val verdict = GoalFidelity.check(
            goal,
            ladder(
                sub(
                    "Place the order to koivuviita 7 B 5 within 60 euros",
                    DoneCondition.MarkerText("Order confirmed"),
                ),
            ),
        )
        assertFalse(verdict.ok)
        assertTrue(
            verdict.violations.any { it.contains("quantity 'two'") },
            verdict.violations.toString(),
        )
    }

    @Test
    fun `an euro-first budget is extracted`() {
        assertEquals(
            "60",
            GoalFidelity.budgetNumber("buy a SoundMagic E11C for €60 with delivery to koivuviita 7 B 5"),
        )
    }

    // -- account switch / playback -----------------------------------------------------

    private val switchGoal =
        "open youtube.com and switch the active user channel to MrAwesome. " +
            "Then find the Food Wishes channel from my subscriptions and play the latest posted video"

    @Test
    fun `an account switch without the target token is refused naming it`() {
        val verdict = GoalFidelity.check(
            switchGoal,
            ladder(
                sub("Open YouTube", DoneCondition.ElementPresent("the YouTube home page")),
                sub("Play the latest Food Wishes video", DoneCondition.ElementPresent("the video player")),
            ),
        )
        assertFalse(verdict.ok)
        assertTrue(
            verdict.violations.any { it.contains("mrawesome") },
            verdict.violations.toString(),
        )
    }

    @Test
    fun `the R4 ladder with the account token and a playback final is accepted`() {
        val verdict = GoalFidelity.check(
            switchGoal,
            ladder(
                sub(
                    "Switch the active user channel to MrAwesome",
                    DoneCondition.ElementPresent("the MrAwesome channel is active"),
                ),
                sub(
                    "Play the latest Food Wishes video",
                    DoneCondition.ElementPresent("the video player is playing the Food Wishes video"),
                ),
            ),
        )
        assertTrue(verdict.ok, verdict.violations.toString())
    }

    @Test
    fun `a playback goal whose final is not a playback marker is refused`() {
        val verdict = GoalFidelity.check(
            "play the latest Food Wishes video",
            ladder(
                sub("Open the Food Wishes video", DoneCondition.ElementPresent("the video page is open")),
            ),
        )
        assertFalse(verdict.ok)
        assertTrue(
            verdict.violations.any { it.contains("playback") },
            verdict.violations.toString(),
        )
    }

    // -- report ------------------------------------------------------------------------

    @Test
    fun `a report goal needs an answerExtracted condition`() {
        val goal = "find out the top headline and report it"
        val missing = GoalFidelity.check(
            goal,
            ladder(
                sub(
                    "Search Google for today's top headline",
                    DoneCondition.UrlContains("google.com/search?q=headline"),
                ),
                sub("Open the news page", DoneCondition.MarkerText("headline")),
                taskClass = LadderTaskClass.READ,
            ),
        )
        assertFalse(missing.ok)
        assertTrue(missing.violations.any { it.contains("answerExtracted") })

        val present = GoalFidelity.check(
            goal,
            ladder(
                sub(
                    "Search Google for today's top headline",
                    DoneCondition.UrlContains("google.com/search?q=headline"),
                ),
                sub("Open the news page", DoneCondition.MarkerText("headline")),
                sub("Report the headline", DoneCondition.AnswerExtracted),
                taskClass = LadderTaskClass.READ,
            ),
        )
        assertTrue(present.ok, present.violations.toString())
    }

    // -- unknown -----------------------------------------------------------------------

    @Test
    fun `an unknown goal class is untouched`() {
        val verdict = GoalFidelity.check(
            "open verkkokauppa.com and add two cases of lemon lime celsius energy drink to the " +
                "shopping cart",
            ladder(
                sub("Open the store", DoneCondition.UrlHost("verkkokauppa.com")),
                sub(
                    "Add the product to the cart",
                    DoneCondition.MarkerText("2 products, total"),
                ),
            ),
        )
        assertTrue(verdict.classes.isEmpty())
        assertTrue(verdict.ok, verdict.violations.toString())
    }
}
