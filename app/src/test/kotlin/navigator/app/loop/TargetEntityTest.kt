package navigator.app.loop

import navigator.core.log.TriageClass
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 8J.2 + 8J.6 (Guard 2.0) — triage-time target-entity extraction (deterministic
 * code, NO site names hardcoded) and the WEAK/STRONG guard levels (vision 04 §3
 * v1.3.0): STRONG = a deterministic IDENTITY match in the page TITLE or URL only
 * (exact word-boundary or >= 0.9 token overlap) on top of the WEAK class predicate; a
 * content-only mention NEVER yields STRONG ("a mention is not arrival" — the Test-2′
 * false GOAL_ACHIEVED on a Related-Articles mention is pinned here as law).
 */
class TargetEntityTest {

    // -- extraction -----------------------------------------------------------------

    @Test
    fun `a quoted span wins`() {
        assertEquals("Tarte Tatin", TargetEntity.extract("Open the \"Tarte Tatin\" page on Wikipedia")?.text)
    }

    @Test
    fun `the article-titled cue extracts the entity`() {
        assertEquals("Apple Pie", TargetEntity.extract("find the article titled Apple Pie and read it")?.text)
        assertEquals("Ada Lovelace", TargetEntity.extract("open the page about Ada Lovelace")?.text)
    }

    @Test
    fun `the bare article cue extracts the entity`() {
        assertEquals("Tarte Tatin", TargetEntity.extract("Open Wikipedia article Tarte Tatin.")?.text)
    }

    @Test
    fun `a capitalized multi-word run is the entity fallback`() {
        assertEquals("Apple Pie Recipe", TargetEntity.extract("Find the Apple Pie Recipe and read it")?.text)
    }

    @Test
    fun `the goal's first word is never part of a run (sentence-start capitalization)`() {
        val entity = TargetEntity.extract("Open Wikipedia article Tarte Tatin.")
        assertTrue(entity?.text != "Open Wikipedia", "the run must not include word 0: ${entity?.text}")
    }

    @Test
    fun `a single capitalized word is not an entity`() {
        // a lone proper noun (a site name) is not a target entity: runs need >= 2 words
        assertNull(TargetEntity.extract("browse Wikipedia and read what it says about Tatin"))
    }

    @Test
    fun `no entity in a goal without a proper noun`() {
        assertNull(TargetEntity.extract("check the weather and tell me if it rains"))
        assertNull(TargetEntity.extract(""))
    }

    @Test
    fun `the specimen's goal extracts Tarte Tatin`() {
        val entity = TargetEntity.extract("Open Wikipedia article Tarte Tatin.")
        assertEquals("Tarte Tatin", entity?.text)
        assertEquals(listOf("tarte", "tatin"), entity?.tokens)
    }

    // -- matching (STRONG) ------------------------------------------------------------

    private val entity = TargetEntity("Tarte Tatin")

    @Test
    fun `exact match in the title`() {
        assertTrue(TargetEntity.matchesText(entity, "Tarte Tatin - Wikipedia"))
    }

    @Test
    fun `case-insensitive match with punctuation noise`() {
        assertTrue(TargetEntity.matchesText(entity, "TARTE TATIN! The classic French dessert."))
    }

    @Test
    fun `url match - separators become token boundaries`() {
        assertTrue(TargetEntity.matchesIn(entity, title = "", url = "https://en.wikipedia.org/wiki/Tarte_Tatin"))
    }

    @Test
    fun `a content-only mention NEVER matches - a mention is not arrival`() {
        // The Test-2' false positive (vision 04 §3 v1.3.0): the entity named in body
        // content (a Related-Articles card) while title and URL name another article.
        val mentionLines = listOf("Related Articles", "Tarte Tatin: the French upside-down apple tart")
        assertFalse(
            TargetEntity.matchesIn(entity, title = "Classic Apple Pie Recipe", url = "https://www.allrecipes.com/recipe/15896/classic-apple-pie"),
            "content is never consulted: title/URL name a different article",
        )
        // the mention itself is a real text match in isolation - it is the SURFACE that is wrong
        assertTrue(TargetEntity.matchesText(entity, mentionLines.last()))
        assertFalse(TargetEntity.matchesIn(entity, title = "", url = ""), "no identity surface, no STRONG")
    }

    @Test
    fun `a substring inside a longer word never matches (word-boundary discipline)`() {
        // "tarte tatin" vs "tartefillinstallations" — the tokens are NOT equal
        assertFalse(TargetEntity.matchesText(entity, "tartefillinstallations"))
        val tart = TargetEntity("Tart")
        assertFalse(TargetEntity.matchesText(tart, "starting the engine"), "'tart' must not match inside 'starting'")
        assertFalse(TargetEntity.matchesText(tart, "starter motor history"))
    }

    @Test
    fun `the Tart decoy page never fires STRONG for Tarte Tatin`() {
        assertFalse(TargetEntity.matchesIn(entity, title = "Tart - Wikipedia", url = "https://en.wikipedia.org/wiki/Tart"))
    }

    @Test
    fun `fuzzy match fires at exactly the 0-9 token overlap`() {
        // 10 entity tokens, 9 present in the page -> 0.9 >= 0.9 -> STRONG
        val long = TargetEntity("Alpha Bravo Charlie Delta Echo Foxtrot Golf Hotel India Juliet")
        val pageWithNine = "Alpha Bravo Charlie Delta Echo Foxtrot Golf Hotel India Kilo"
        assertTrue(TargetEntity.matchesText(long, pageWithNine))
        // 8 of 11 -> 0.727 < 0.9 -> no fuzzy match
        val longer = TargetEntity("Alpha Bravo Charlie Delta Echo Foxtrot Golf Hotel India Juliet Kilo Lima")
        assertFalse(TargetEntity.matchesText(longer, pageWithNine))
    }

    // -- guard levels (the 8J.6 matrix, vision 04 §3 v1.3.0) ----------------------------

    private fun lookupGuard(withEntity: TargetEntity?): CompletionGuards {
        val guards = CompletionGuards(TriageClass.LOOKUP, withEntity)
        guards.seed("http://m0.test/start")
        return guards
    }

    @Test
    fun `NONE - the class predicate fails even when the entity matches`() {
        // the search RESULTS page names the entity, but no contentful page beyond the
        // first was read: the guard is NONE and every exit path stays closed
        val guards = CompletionGuards(TriageClass.LOOKUP, entity)
        guards.seed("http://m0.test/start")
        guards.record("http://m0.test/start", contentTextNodes = 9, textTyped = false, submitExecuted = false)
        assertEquals(GuardLevel.NONE, guards.levelAt("Tarte Tatin - Wikipedia", "https://en.wikipedia.org/wiki/Tarte_Tatin"))
    }

    @Test
    fun `WEAK - the class predicate holds without an entity match`() {
        val guards = lookupGuard(entity)
        guards.record("http://m0.test/other", contentTextNodes = 5, textTyped = false, submitExecuted = false)
        assertEquals(GuardLevel.WEAK, guards.levelAt("Some other page", "http://m0.test/other"))
    }

    @Test
    fun `WEAK is the ceiling when no entity was extracted`() {
        val guards = lookupGuard(null)
        guards.record("http://m0.test/other", contentTextNodes = 5, textTyped = false, submitExecuted = false)
        assertEquals(GuardLevel.WEAK, guards.levelAt("Tarte Tatin", "http://m0.test/tarte-tatin"))
    }

    @Test
    fun `STRONG - predicate holds AND the identity matched in the TITLE`() {
        val guards = lookupGuard(entity)
        guards.record("http://m0.test/other", contentTextNodes = 5, textTyped = false, submitExecuted = false)
        assertEquals(
            GuardLevel.STRONG,
            guards.levelAt("Tarte Tatin - Wikipedia", "https://en.wikipedia.org/wiki/Tarte_Tatin"),
        )
    }

    @Test
    fun `STRONG fires from the URL alone when the title is blank`() {
        val guards = lookupGuard(entity)
        guards.record("http://m0.test/other", contentTextNodes = 5, textTyped = false, submitExecuted = false)
        assertEquals(GuardLevel.STRONG, guards.levelAt("", "https://en.wikipedia.org/wiki/Tarte_Tatin"))
    }

    @Test
    fun `mention-only - entity in content, absent from title AND URL - is WEAK, never STRONG (8J-6 matrix)`() {
        // The gate-8J.6 matrix row: a page that MENTIONS the target in body content
        // (e.g. a Related-Articles card) while title/URL name something else.
        val guards = lookupGuard(entity)
        guards.record("http://m0.test/other", contentTextNodes = 5, textTyped = false, submitExecuted = false)
        assertEquals(
            GuardLevel.WEAK,
            guards.levelAt("Classic Apple Pie Recipe", "https://www.allrecipes.com/recipe/15896/classic-apple-pie"),
            "a content-only match never yields STRONG (a mention is not arrival)",
        )
    }
}
