package navigator.core.ladder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V2 S2 FR-25 (AC-S2.3) — the weakened-re-plan refusal law: the goal contract is
 * IMMUTABLE; a re-plan is validated against the ORIGINAL contract, and a re-plan that
 * drops or weakens an explicit parameter (quantity, budget, delivery address, target
 * account) is refused, while a reworded-but-equal re-plan binds and passes. Nothing
 * ever rebuilds the contract from the re-plan's text.
 */
class GoalContractWeakenRefusalTest {

    private val purchaseGoal = "buy two cases of red bull on verkkokauppa.com with a 60 euro budget and deliver to koivuviita 7 b 5"

    private fun purchaseContract(): GoalContract = GoalContract.fromGoal(purchaseGoal)

    private fun baseLadder(): Ladder = Ladder(
        summary = "buy the red bull",
        subtasks = listOf(
            Subtask(
                "open verkkokauppa.com",
                DoneCondition.UrlHost("verkkokauppa.com"),
            ),
            Subtask(
                "search verkkokauppa for red bull",
                DoneCondition.ElementPresent("a search result for red bull"),
            ),
            Subtask(
                "add two cases of red bull to the cart for 60 euros and order to koivuviita 7 b 5",
                DoneCondition.MarkerText("tilausvahvistus"),
            ),
        ),
        taskClass = LadderTaskClass.ACTION,
    )

    private fun replanOf(vararg subtasks: Subtask): Ladder =
        Ladder("re-plan", subtasks.toList(), LadderTaskClass.ACTION)

    // -- the weakened shapes are refused ----------------------------------------------------

    @Test
    fun `a re-plan that drops the budget is refused`() {
        val contract = purchaseContract()
        assertTrue(contract.parameters.any { it is GoalParameter.Budget }, "the goal parses a budget")
        val weakened = replanOf(
            Subtask("open verkkokauppa.com", DoneCondition.UrlHost("verkkokauppa.com")),
            Subtask(
                "add two cases of red bull to the cart and order to koivuviita 7 b 5",
                DoneCondition.MarkerText("tilausvahvistus"),
            ),
        )
        val violations = contract.validateReplan(weakened)
        assertTrue(
            violations.any { it.contains("budget", ignoreCase = true) || it.contains("60", ignoreCase = true) },
            "the dropped budget is named: $violations",
        )
    }

    @Test
    fun `a re-plan that drops the delivery address is refused`() {
        val contract = purchaseContract()
        val weakened = replanOf(
            Subtask("open verkkokauppa.com", DoneCondition.UrlHost("verkkokauppa.com")),
            Subtask(
                "add two cases of red bull to the cart for 60 euros",
                DoneCondition.MarkerText("tilausvahvistus"),
            ),
        )
        val violations = contract.validateReplan(weakened)
        assertTrue(
            violations.any { it.contains("koivuviita") || it.contains("address", ignoreCase = true) },
            "the dropped address is named: $violations",
        )
    }

    @Test
    fun `a re-plan that weakens the quantity is refused`() {
        val contract = purchaseContract()
        assertTrue(contract.parameters.any { it is GoalParameter.Quantity }, "the goal parses a quantity")
        val weakened = replanOf(
            Subtask("open verkkokauppa.com", DoneCondition.UrlHost("verkkokauppa.com")),
            Subtask(
                "add one case of red bull to the cart for 60 euros and order to koivuviita 7 b 5",
                DoneCondition.MarkerText("tilausvahvistus"),
            ),
        )
        val violations = contract.validateReplan(weakened)
        assertTrue(
            violations.isNotEmpty(),
            "one case is not the goal's two cases: $violations",
        )
    }

    @Test
    fun `a re-plan that drops the target account is refused`() {
        val contract = GoalContract.fromGoal("switch my youtube account to brand channel and play the latest video")
        assertTrue(contract.parameters.any { it is GoalParameter.Account }, "the goal parses an account")
        val weakened = Ladder(
            "re-plan",
            listOf(
                Subtask("open youtube.com", DoneCondition.UrlHost("youtube.com")),
                Subtask("play the latest video", DoneCondition.MarkerText("playing")),
            ),
            LadderTaskClass.ACTION,
        )
        val violations = contract.validateReplan(weakened)
        assertTrue(
            violations.any { it.contains("brand", ignoreCase = true) || it.contains("account", ignoreCase = true) },
            "the dropped account is named: $violations",
        )
    }

    @Test
    fun `the weakened re-plan is refused LOUDLY at install - the exception carries the parameter`() {
        val contract = purchaseContract()
        val weakened = replanOf(
            Subtask("open verkkokauppa.com", DoneCondition.UrlHost("verkkokauppa.com")),
            Subtask(
                "add red bull to the cart",
                DoneCondition.MarkerText("tilausvahvistus"),
            ),
        )
        val e = assertFailsWith<LadderValidationException> {
            contract.validateReplan(weakened).let { violations ->
                if (violations.isNotEmpty()) {
                    throw LadderValidationException(violations.joinToString("; "))
                }
            }
        }
        assertTrue(e.message!!.contains("60"), "the budget parameter rides the refusal")
    }

    // -- the equal-but-reworded shape passes ---------------------------------------------------

    @Test
    fun `a reworded-but-equal re-plan binds every parameter and passes`() {
        val contract = purchaseContract()
        val reworded = replanOf(
            Subtask("verkkokauppa.com is open", DoneCondition.UrlHost("verkkokauppa.com")),
            Subtask(
                "the red bull search results are showing",
                DoneCondition.ElementPresent("a search result for red bull"),
            ),
            Subtask(
                "the cart holds two cases red bull costing 60 euro and the order delivers to koivuviita 7 b 5",
                DoneCondition.MarkerText("tilausvahvistus"),
            ),
        )
        val violations = contract.validateReplan(reworded)
        assertEquals(emptyList(), violations, "a reworded-but-equal plan binds: $violations")
    }

    @Test
    fun `the ORIGINAL install law agrees with the re-plan law on the same ladder`() {
        val contract = purchaseContract()
        val ladder = baseLadder()
        assertEquals(contract.validateInstall(ladder), contract.validateReplan(ladder))
    }

    // -- the account-switch goal's re-plan law ---------------------------------------------------

    @Test
    fun `a re-plan for an account-switch goal that renames the target is refused`() {
        val contract = GoalContract.fromGoal("switch my youtube account to brand channel and play the latest video")
        val renamed = Ladder(
            "re-plan",
            listOf(
                Subtask("open youtube.com", DoneCondition.UrlHost("youtube.com")),
                Subtask("switch the account to some other channel", DoneCondition.MarkerText("channel")),
                Subtask("play the latest video", DoneCondition.MarkerText("playing")),
            ),
            LadderTaskClass.ACTION,
        )
        val violations = contract.validateReplan(renamed)
        assertTrue(violations.isNotEmpty(), "some other channel is not the goal's brand channel: $violations")
    }
}
