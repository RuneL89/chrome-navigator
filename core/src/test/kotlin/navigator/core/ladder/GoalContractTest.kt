package navigator.core.ladder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PHASE_18 Stage 18.C CN-05 — the immutable goal contract (doc 04 §9.4), the plan's
 * test cases. Pure JVM; $0; deterministic (no model, no keys).
 *
 * PHASE_18 NOTE: this pins the CN-05 TARGET behavior just landed (Stage 18.C); the doc
 * 04 §9.4 items it maps to are 9.4 (the immutable goal contract) itself.
 */
class GoalContractTest {

    private fun ladderOf(vararg instructions: String, urlHost: String? = null): Ladder {
        val subtasks = instructions.mapIndexed { index, instruction ->
            val done = if (index == instructions.size - 1 && urlHost != null) {
                DoneCondition.UrlHost(urlHost ?: "fixture.test")
            } else {
                DoneCondition.UrlContains("step-${index + 1}")
            }
            Subtask(instruction, done)
        }
        return Ladder("fixture ladder", subtasks)
    }

    // -- 1. a replan that changes the quantity is rejected --------------------------------

    @Test
    fun `a replan that changes two cases to one item is rejected`() {
        val goal = "add two cases of the drink to the cart"
        val contract = GoalContract.fromGoal(goal)
        val quantity = contract.parameters.filterIsInstance<GoalParameter.Quantity>().single()
        assertEquals(2, quantity.count)
        assertEquals("cases", quantity.unit)

        // The re-planned ladder drops the quantity (one item, no unit binding).
        val weakened = ladderOf("add one item of the drink to the cart")
        val violations = contract.validateReplan(weakened)
        assertTrue(violations.isNotEmpty(), "the weakened quantity must be refused: $violations")
        assertTrue(violations.single().contains("Quantity parameter '2 cases'"))
    }

    // -- 2. a replan that drops the budget or the address is rejected ---------------------

    @Test
    fun `a replan that drops the stated budget is rejected`() {
        val goal = "buy the lamp for 60 € with delivery to koivuviita 7 B 5"
        val contract = GoalContract.fromGoal(goal)
        org.junit.jupiter.api.Assertions.assertTrue(contract.parameters.any { it is GoalParameter.Budget }, "the budget parsed")
        assertTrue(contract.parameters.any { it is GoalParameter.Address }, "the address parsed")

        val weakened = ladderOf("buy the lamp") // no budget, no address
        val violations = contract.validateReplan(weakened)
        assertEquals(2, violations.size, "both stated parameters unbound: $violations")
    }

    // -- 3. different wording with identical parameters is accepted ------------------------

    @Test
    fun `a reworded plan that still binds every parameter is accepted`() {
        val goal = "add two cases of the drink to the cart"
        val contract = GoalContract.fromGoal(goal)
        // A reworded strategy: different verb, same parsed value and unit.
        val reworded = ladderOf("put 2 cases of the drink into the cart")
        assertEquals(emptyList<String>(), contract.validateReplan(reworded))
    }

    // -- 4. an unknown goal class carries no fabricated class rules ------------------------

    @Test
    fun `an unknown goal class has no parameters and binds nothing`() {
        val goal = "browse the news for a while"
        val contract = GoalContract.fromGoal(goal)
        assertTrue(contract.classes.isEmpty(), "no fabricated class")
        assertTrue(contract.parameters.isEmpty(), "no fabricated parameter")
        val ladder = ladderOf("browse the news for a while")
        assertEquals(emptyList<String>(), contract.validateLadder(ladder))
    }

    // -- 5. the named site rides the contract -----------------------------------------------

    @Test
    fun `a goal naming a site carries the NamedSite parameter`() {
        val goal = "open verkkokauppa.com and buy the lamp"
        val contract = GoalContract.fromGoal(goal)
        assertTrue(contract.parameters.any { it is GoalParameter.NamedSite })
    }

    // -- 6. the contract records the ambiguous requirement (the 18.D precursor) ------------

    @Test
    fun `a switch goal with no parseable target records the unmet requirement`() {
        val contract = GoalContract.fromGoal("switch my account somehow")
        assertTrue(
            contract.unmetRequirements.any { it.contains("target account") },
            "the ambiguity is recorded, never guessed: ${contract.unmetRequirements}",
        )
    }

    // -- 7. no secrets: the contract is data only -------------------------------------------

    @Test
    fun `the contract carries no credential-shaped field`() {
        val contract = GoalContract.fromGoal("log in and buy two cases of the drink")
        val rendered = contract.parameters.joinToString { it.rendered } + contract.goalText
        assertTrue(!rendered.contains("password"), "no credential field exists on the contract")
    }

    // -- the address binding: verbatim tokens in a subtask ----------------------------------

    @Test
    fun `the address binds by its own tokens in a subtask instruction`() {
        val goal = "buy the lamp with delivery to koivuviita 7 B 5"
        val contract = GoalContract.fromGoal(goal)
        val address = contract.parameters.filterIsInstance<GoalParameter.Address>().single()
        assertEquals("koivuviita 7 b 5", address.bindingTokens.joinToString(" "))

        val bound = ladderOf("order the lamp with delivery to koivuviita 7 b 5")
        assertEquals(emptyList<String>(), contract.validateLadder(bound))
        val unbound = ladderOf("order the lamp with delivery to another address entirely")
        assertTrue(contract.validateLadder(unbound).isNotEmpty(), "a different address does not bind")
    }
}
