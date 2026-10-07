package navigator.app.loop

import navigator.core.log.TriageClass
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate 8J.2 + 8J.7 (AnchoredNoul, vision 04 §3): the goal-achieved proposition is
 * code-constructed per goal, anchored to the extracted entity, and versioned. v2 (Phase
 * 8J Round 2, v1.3.0): every proposition carries the code-derived PAGE IDENTITY line —
 * the current page's title/URL excerpt — so the engine judges ARRIVAL against page
 * identity, not content vibes. Generic "is the goal achieved?" phrasing is forbidden.
 */
class AnchoredNoulTest {

    private val entity = TargetEntity("Tarte Tatin")
    private val title = "Tarte Tatin - Wikipedia"
    private val url = "https://en.wikipedia.org/wiki/Tarte_Tatin"

    @Test
    fun `the version constant is 2 (the Round-2 PAGE IDENTITY templates)`() {
        assertEquals(2, AnchoredNoul.ANCHORED_NOUL_VERSION)
    }

    @Test
    fun `lookup propositions name the entity and carry the PAGE IDENTITY line`() {
        val proposition = AnchoredNoul.proposition("Open Wikipedia article Tarte Tatin.", entity, TriageClass.LOOKUP, title, url)
        assertTrue(proposition.contains("Tarte Tatin"))
        assertTrue(proposition.contains("displayed"))
        assertTrue(proposition.contains("PAGE IDENTITY"), "v2 judges arrival against page identity: $proposition")
        assertTrue(proposition.contains(title), "the title excerpt rides the identity line: $proposition")
        assertTrue(proposition.contains(url), "the URL excerpt rides the identity line: $proposition")
        // the question name is unchanged (stub scripts and wire law key on it)
        assertEquals(
            NavigationLoop.GOAL_ACHIEVED_QUESTION,
            AnchoredNoul.question("goal", entity, TriageClass.LOOKUP, title, url).name,
        )
    }

    @Test
    fun `research and form-fill propositions are class-anchored and identity-anchored`() {
        val research = AnchoredNoul.proposition("Research Tarte Tatin", entity, TriageClass.RESEARCH_MULTI_STEP, title, url)
        assertTrue(research.contains("pages read"))
        assertTrue(research.contains("PAGE IDENTITY"))
        val form = AnchoredNoul.proposition("Fill the Tarte Tatin order form", entity, TriageClass.FORM_FILL, title, url)
        assertTrue(form.contains("submitted"))
        assertTrue(form.contains("PAGE IDENTITY"))
    }

    @Test
    fun `action and action-multi propositions are class-anchored and identity-anchored (Phase 8D)`() {
        val action = AnchoredNoul.proposition("Book a table for two", entity, TriageClass.ACTION, title, url)
        assertTrue(action.contains("carried out"), "the operation, not content vibes: $action")
        assertTrue(action.contains("PAGE IDENTITY"))
        val multi = AnchoredNoul.proposition(
            "apply the operation to every target in the set",
            null,
            TriageClass.ACTION_MULTI,
            title,
            url,
        )
        assertTrue(multi.contains("targets"), "the set of discovered targets: $multi")
        assertTrue(multi.contains("PAGE IDENTITY"))
    }

    @Test
    fun `without an extracted entity the proposition quotes the goal and still pins page identity`() {
        val goal = "check the weather in one specific town"
        val proposition = AnchoredNoul.proposition(goal, null, TriageClass.LOOKUP, "A weather page", "http://m0.test/weather")
        assertTrue(proposition.contains(goal), "the goal text anchors the proposition: $proposition")
        assertTrue(proposition.contains("PAGE IDENTITY"))
        assertTrue(proposition.contains("A weather page"))
        assertTrue(
            proposition != "The goal \"$goal\" is fully achieved on the current page.",
            "the pre-amendment generic phrasing is forbidden",
        )
    }

    @Test
    fun `the identity line excerpts overlong titles and urls deterministically`() {
        val longTitle = "T".repeat(300)
        val proposition = AnchoredNoul.proposition("goal", entity, TriageClass.LOOKUP, longTitle, "http://m0.test/x")
        assertTrue(!proposition.contains("T".repeat(200)), "the title is excerpted, not dumped: $proposition")
        assertTrue(proposition.contains(AnchoredNoul.excerpt(longTitle)))
        assertEquals(AnchoredNoul.EXCERPT_MAX + 3, AnchoredNoul.excerpt(longTitle).length)
        assertEquals("short", AnchoredNoul.excerpt("  short  "), "trimmed verbatim when under the cap")
    }

    @Test
    fun `the proposition is deterministic - same inputs, same bytes`() {
        val first = AnchoredNoul.proposition("Open Wikipedia article Tarte Tatin.", entity, TriageClass.LOOKUP, title, url)
        val second = AnchoredNoul.proposition("Open Wikipedia article Tarte Tatin.", entity, TriageClass.LOOKUP, title, url)
        assertEquals(first, second)
    }

    @Test
    fun `a different page identity changes the proposition (the engine judges THIS page)`() {
        val onTarget = AnchoredNoul.proposition("Open Wikipedia article Tarte Tatin.", entity, TriageClass.LOOKUP, title, url)
        val onDecoy = AnchoredNoul.proposition("Open Wikipedia article Tarte Tatin.", entity, TriageClass.LOOKUP, "Classic Apple Pie Recipe", "https://www.allrecipes.com/recipe/15896/classic-apple-pie")
        assertTrue(onTarget != onDecoy, "page identity is a question input, not decoration")
    }
}
