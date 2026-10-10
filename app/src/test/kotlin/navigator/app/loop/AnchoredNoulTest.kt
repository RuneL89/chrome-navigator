package navigator.app.loop

import navigator.core.ladder.DoneCondition
import navigator.core.log.TriageClass
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate 8J.2 + 8J.7 (AnchoredNoul, vision 04 §3): the goal-achieved proposition is
 * code-constructed per goal, anchored to the extracted entity, and versioned. v2 (Phase
 * 8J Round 2, v1.3.0): every proposition carries the code-derived PAGE IDENTITY line —
 * the current page's title/URL excerpt — so the engine judges ARRIVAL against page
 * identity, not content vibes. v3 (Phase 10B, v1.6.0): SUBTASK propositions are ATOMIC —
 * one crisp sentence per done-condition shape (the phase-doc §2.3 exact forms are
 * fixture law), each + the PAGE IDENTITY line; the task-level (non-ladder) fallback
 * keeps the single-sentence goal-anchored forms. Generic "is the goal achieved?"
 * phrasing is forbidden.
 */
class AnchoredNoulTest {

    private val entity = TargetEntity("Tarte Tatin")
    private val title = "Tarte Tatin - Wikipedia"
    private val url = "https://en.wikipedia.org/wiki/Tarte_Tatin"

    @Test
    fun `the version constant is 3 (the v1_6_0 atomic templates)`() {
        assertEquals(3, AnchoredNoul.ANCHORED_NOUL_VERSION)
    }

    @Test
    fun `the atomic subtask propositions are the exact per-shape forms (fixture law)`() {
        assertEquals(
            "The page's address is on the host 'en.wikipedia.org'.",
            AnchoredNoul.atomicProposition(DoneCondition.UrlHost("en.wikipedia.org")),
        )
        assertEquals(
            "The text 'Mediterranean cuisine' appears on the page.",
            AnchoredNoul.atomicProposition(DoneCondition.MarkerText("Mediterranean cuisine")),
        )
        assertEquals(
            "A control matching 'Unsubscribe from Cooking Classics' is present on the page.",
            AnchoredNoul.atomicProposition(DoneCondition.ElementPresent("Unsubscribe from Cooking Classics")),
        )
        assertEquals(
            "5 of the counted 'accounts followed' are complete.",
            AnchoredNoul.atomicProposition(DoneCondition.CountReached("accounts followed", 5)),
        )
    }

    @Test
    fun `the subtask question is ONE atomic sentence plus the PAGE IDENTITY line`() {
        val question = AnchoredNoul.subtaskQuestion(
            done = DoneCondition.UrlHost("en.wikipedia.org"),
            pageTitle = title,
            pageUrl = url,
        )
        assertEquals(
            "The page's address is on the host 'en.wikipedia.org'.\n" +
                "PAGE IDENTITY: the currently displayed page's title is \"$title\" " +
                "and its address is $url.",
            question.proposition,
            "atomic: no instruction text, no finality clause, no compound sentence",
        )
        assertEquals(
            NavigationLoop.GOAL_ACHIEVED_QUESTION,
            question.name,
            "the question name is unchanged (stub scripts and wire law key on it)",
        )
    }

    @Test
    fun `the atomic proposition flattens and bounds the condition payload`() {
        val question = AnchoredNoul.subtaskQuestion(
            done = DoneCondition.MarkerText("a marker\nwith control\tcharacters " + "x".repeat(200)),
            pageTitle = title,
            pageUrl = url,
        )
        val firstLine = question.proposition.split("\n").first()
        assertTrue(!firstLine.contains("\n".repeat(1) + "with"), "control chars flattened: $firstLine")
        assertTrue(
            firstLine.length < 200,
            "the payload is excerpt-bounded, not dumped: ${firstLine.length} chars",
        )
    }

    @Test
    fun `lookup propositions name the entity and carry the PAGE IDENTITY line (the task-level fallback stays one sentence)`() {
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
        val q1 = AnchoredNoul.subtaskQuestion(DoneCondition.UrlHost("en.wikipedia.org"), title, url)
        val q2 = AnchoredNoul.subtaskQuestion(DoneCondition.UrlHost("en.wikipedia.org"), title, url)
        assertEquals(q1, q2)
    }

    @Test
    fun `a different page identity changes the proposition (the engine judges THIS page)`() {
        val onTarget = AnchoredNoul.proposition("Open Wikipedia article Tarte Tatin.", entity, TriageClass.LOOKUP, title, url)
        val onDecoy = AnchoredNoul.proposition("Open Wikipedia article Tarte Tatin.", entity, TriageClass.LOOKUP, "Classic Apple Pie Recipe", "https://www.allrecipes.com/recipe/15896/classic-apple-pie")
        assertTrue(onTarget != onDecoy, "page identity is a question input, not decoration")
    }
}
