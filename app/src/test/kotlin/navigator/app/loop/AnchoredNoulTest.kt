package navigator.app.loop

import navigator.core.log.TriageClass
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate 8J.2 (AnchoredNoul, vision 04 §3 v1.2.0): the goal-achieved proposition is
 * code-constructed per goal, anchored to the extracted entity, and versioned. Generic
 * "is the goal achieved?" phrasing is forbidden by law.
 */
class AnchoredNoulTest {

    private val entity = TargetEntity("Tarte Tatin")

    @Test
    fun `the version constant is 1 (the amendment's first template set)`() {
        assertEquals(1, AnchoredNoul.ANCHORED_NOUL_VERSION)
    }

    @Test
    fun `lookup propositions name the entity`() {
        val proposition = AnchoredNoul.proposition("Open Wikipedia article Tarte Tatin.", entity, TriageClass.LOOKUP)
        assertTrue(proposition.contains("Tarte Tatin"))
        assertTrue(proposition.contains("displayed"))
        // the question name is unchanged (stub scripts and wire law key on it)
        assertEquals(
            NavigationLoop.GOAL_ACHIEVED_QUESTION,
            AnchoredNoul.question("goal", entity, TriageClass.LOOKUP).name,
        )
    }

    @Test
    fun `research and form-fill propositions are class-anchored`() {
        val research = AnchoredNoul.proposition("Research Tarte Tatin", entity, TriageClass.RESEARCH_MULTI_STEP)
        assertTrue(research.contains("pages read"))
        val form = AnchoredNoul.proposition("Fill the Tarte Tatin order form", entity, TriageClass.FORM_FILL)
        assertTrue(form.contains("submitted"))
    }

    @Test
    fun `without an extracted entity the proposition quotes the goal (anchored, not generic)`() {
        val goal = "check the weather in one specific town"
        val proposition = AnchoredNoul.proposition(goal, null, TriageClass.LOOKUP)
        assertTrue(proposition.contains(goal), "the goal text anchors the proposition: $proposition")
        assertTrue(
            proposition != "The goal \"$goal\" is fully achieved on the current page.",
            "the pre-amendment generic phrasing is forbidden",
        )
    }

    @Test
    fun `the proposition is deterministic - same inputs, same bytes`() {
        val first = AnchoredNoul.proposition("Open Wikipedia article Tarte Tatin.", entity, TriageClass.LOOKUP)
        val second = AnchoredNoul.proposition("Open Wikipedia article Tarte Tatin.", entity, TriageClass.LOOKUP)
        assertEquals(first, second)
    }
}
