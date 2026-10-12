package navigator.core.ladder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gate 10.2's :core half — the pure ladder types (Phase 10, vision 04 §1 v1.4.0):
 * validation matrix (schema/whitelist/caps/shape-vs-class), the done-condition checkers
 * over code-read evidence, and the pointer-advance law.
 */
class LadderTest {

    // -- construction invariants ---------------------------------------------------------

    @Test
    fun `a valid hand-built ladder constructs`() {
        val ladder = Ladder(
            summary = "Open the encyclopedia and read the world war 2 article.",
            subtasks = listOf(
                Subtask("Open the encyclopedia site", DoneCondition.UrlHost("en.wikipedia.org")),
                Subtask("Open the world war 2 article", DoneCondition.MarkerText("World War II")),
            ),
            taskClass = LadderTaskClass.READ,
        )
        assertEquals(2, ladder.subtasks.size)
        assertEquals("restructure-v1/gen2", ladder.versionTag("restructure-v1", 2))
    }

    @Test
    fun `blank summary, multiline summary, oversized summary fail loudly`() {
        fun ladderOf(summary: String) = Ladder(summary, listOf(Subtask("do", DoneCondition.UrlHost("example.com"))))

        assertFailsWith<IllegalArgumentException> { ladderOf("   ") }
        assertFailsWith<IllegalArgumentException> { ladderOf("two\nlines") }
        assertFailsWith<IllegalArgumentException> {
            ladderOf("x".repeat(LadderLimits.MAX_SUMMARY_CHARS + 1))
        }
    }

    @Test
    fun `empty subtask list and oversized subtask count fail loudly`() {
        assertFailsWith<IllegalArgumentException> {
            Ladder("s", emptyList())
        }
        val oversized = (1..LadderLimits.MAX_SUBTASKS + 1).map {
            Subtask("step $it", DoneCondition.UrlHost("example.com"))
        }
        assertFailsWith<IllegalArgumentException> {
            // MULTI needs a count condition to construct, so build a MULTI-shaped oversized list.
            val withCount = oversized.take(LadderLimits.MAX_SUBTASKS + 1).toMutableList()
            withCount[0] = Subtask("count targets", DoneCondition.CountReached("targets", 2))
            Ladder("s", withCount, LadderTaskClass.MULTI)
        }
    }

    @Test
    fun `blank instruction fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            Subtask("  ", DoneCondition.UrlHost("example.com"))
        }
    }

    @Test
    fun `taskClass claim must match the done-condition shapes`() {
        // MULTI claimed with no count-shaped condition: rejected.
        assertFailsWith<IllegalArgumentException> {
            Ladder(
                "s",
                listOf(Subtask("open", DoneCondition.UrlHost("example.com"))),
                LadderTaskClass.MULTI,
            )
        }
        // READ claimed WITH a count-shaped condition: rejected.
        assertFailsWith<IllegalArgumentException> {
            Ladder(
                "s",
                listOf(Subtask("follow", DoneCondition.CountReached("accounts", 5))),
                LadderTaskClass.READ,
            )
        }
        // MULTI with a count condition: accepted.
        Ladder(
            "s",
            listOf(Subtask("follow", DoneCondition.CountReached("accounts", 5))),
            LadderTaskClass.MULTI,
        )
        // Total-size cap: a bloated done-condition payload blows it (the per-field
        // validator names the field; the init cap is the binding one).
        val bigCondition = Subtask("mark", DoneCondition.MarkerText("x".repeat(LadderLimits.MAX_TOTAL_CHARS)))
        assertFailsWith<IllegalArgumentException> { Ladder("s", listOf(bigCondition)) }
    }

    // -- LadderValidator ------------------------------------------------------------------

    @Test
    fun `validator accepts a well-formed ladder`() {
        val ladder = Ladder(
            "Follow five nature accounts.",
            listOf(Subtask("follow five", DoneCondition.CountReached("accounts followed", 5)), Subtask(
                "stop",
                DoneCondition.MarkerText("all done"),
            )),
            LadderTaskClass.MULTI,
        )
        assertEquals(ladder, LadderValidator.validate(ladder))
    }

    @Test
    fun `validator rejects opinion conditions and malformed fields with a loud list`() {
        // An opinion ("looks right") cannot be a DoneCondition at all — the whitelist's
        // whole point; the validator's loud matrix covers the fields it CAN see.
        val bad = Ladder(
            "s",
            listOf(
                Subtask("open", DoneCondition.UrlHost("not a host/with slash")),
                Subtask("mark", DoneCondition.MarkerText("  ")),
                Subtask("elem", DoneCondition.ElementPresent(" ")),
                Subtask("count", DoneCondition.CountReached("accounts", 0)),
                Subtask("count2", DoneCondition.CountReached("accounts", 1000)),
            ),
            LadderTaskClass.MULTI,
        )
        val e = assertFailsWith<LadderValidationException> { LadderValidator.validate(bad) }
        val message = e.message ?: ""
        assertTrue(message.contains("urlHost"), message)
        assertTrue(message.contains("markerText"), message)
        assertTrue(message.contains("elementPresent"), message)
        assertTrue(message.contains("count n=0"), message)
        assertTrue(message.contains("count n=1000"), message)
        assertTrue(message.contains("nothing navigates"), message)
    }

    // -- DoneConditionChecker --------------------------------------------------------------

    private fun evidence(
        url: String? = null,
        title: String = "",
        pageText: List<String> = emptyList(),
        candidateLabels: List<String> = emptyList(),
        counters: Map<String, Int> = emptyMap(),
    ) = DoneConditionChecker.Evidence(url, title, pageText, candidateLabels, counters)

    @Test
    fun `urlHost verifies by code from the URL bar with scheme port www insensitivity`() {
        val c = DoneCondition.UrlHost("en.wikipedia.org")
        assertTrue(DoneConditionChecker.satisfied(c, evidence(url = "https://en.wikipedia.org/wiki/World_War_II")))
        assertTrue(DoneConditionChecker.satisfied(c, evidence(url = "en.wikipedia.org/wiki/X")))
        assertTrue(DoneConditionChecker.satisfied(c, evidence(url = "https://www.en.wikipedia.org:80/wiki/X")))
        assertFalse(DoneConditionChecker.satisfied(c, evidence(url = "https://de.wikipedia.org/wiki/X")))
        assertFalse(DoneConditionChecker.satisfied(c, evidence(url = null)))
        assertFalse(DoneConditionChecker.satisfied(c, evidence(url = "hidden")))
    }

    @Test
    fun `markerText verifies literal page containment case-insensitively`() {
        val c = DoneCondition.MarkerText("Mediterranean cuisine")
        assertTrue(DoneConditionChecker.satisfied(c, evidence(pageText = listOf("[n3] \"Mediterranean cuisine classics\""))))
        assertTrue(DoneConditionChecker.satisfied(c, evidence(title = "MEDITERRANEAN CUISINE - Home")))
        assertFalse(DoneConditionChecker.satisfied(c, evidence(pageText = listOf("nothing relevant"))))
        assertFalse(DoneConditionChecker.satisfied(c, evidence()))
    }

    @Test
    fun `elementPresent verifies against enumerable candidate labels only`() {
        val c = DoneCondition.ElementPresent("Subscribe button")
        // Both containment directions: the LLM describes "Subscribe button", the page
        // label is "Subscribe" (reverse run), and the verbose label carries the run too.
        assertTrue(DoneConditionChecker.satisfied(c, evidence(candidateLabels = listOf("Subscribe"))))
        assertTrue(DoneConditionChecker.satisfied(c, evidence(candidateLabels = listOf("the subscribe button widget"))))
        assertFalse(DoneConditionChecker.satisfied(c, evidence(candidateLabels = listOf("Unsubscribe"))))
        assertFalse(DoneConditionChecker.satisfied(c, evidence(candidateLabels = listOf("Follow"))))
        // Page prose that is NOT a candidate never satisfies elementPresent.
        assertFalse(DoneConditionChecker.satisfied(c, evidence(pageText = listOf("Subscribe now!"))))
    }

    @Test
    fun `count conditions track working memory and advance only at n`() {
        val c = DoneCondition.CountReached("accounts followed", 5)
        val at3 = evidence(counters = mapOf(DoneConditionChecker.WORKING_MEMORY_KEY to 3))
        val at5 = evidence(counters = mapOf(DoneConditionChecker.WORKING_MEMORY_KEY to 5))
        val at6 = evidence(counters = mapOf("accounts followed" to 6))
        assertFalse(DoneConditionChecker.satisfied(c, at3), "'3 of 5 followed' must NOT advance")
        assertTrue(DoneConditionChecker.satisfied(c, at5), "advances only at 5")
        assertTrue(DoneConditionChecker.satisfied(c, at6))
        // A named counter for the condition's target wins over the shared working memory.
        assertTrue(
            DoneConditionChecker.satisfied(
                c,
                evidence(counters = mapOf(DoneConditionChecker.WORKING_MEMORY_KEY to 1, "accounts followed" to 5)),
            ),
        )
        // Missing working memory fails the condition (never defaults true).
        assertFalse(DoneConditionChecker.satisfied(c, evidence()))
    }

    @Test
    fun `guard strength - URL identities and the code-owned count are STRONG, content proxies are WEAK`() {
        // v1.7.0 (Phase 10C, doc 04 §3): STRONG = evidence that is NOT a page-content
        // proxy — URL identity (urlHost / urlContains) and the code-owned working-memory
        // count; WEAK = the content-reading shapes (markerText / elementPresent) that the
        // on-device battery showed silently accepting already-true conditions.
        assertEquals(
            DoneConditionChecker.GuardStrength.STRONG,
            DoneConditionChecker.guardStrength(DoneCondition.UrlHost("example.com")),
        )
        assertEquals(
            DoneConditionChecker.GuardStrength.STRONG,
            DoneConditionChecker.guardStrength(DoneCondition.UrlContains("example.com/pricing")),
        )
        assertEquals(
            DoneConditionChecker.GuardStrength.WEAK,
            DoneConditionChecker.guardStrength(DoneCondition.MarkerText("x")),
        )
        assertEquals(
            DoneConditionChecker.GuardStrength.WEAK,
            DoneConditionChecker.guardStrength(DoneCondition.ElementPresent("x")),
        )
        assertEquals(
            DoneConditionChecker.GuardStrength.STRONG,
            DoneConditionChecker.guardStrength(DoneCondition.CountReached("t", 3)),
        )
    }

    @Test
    fun `renders are one-line plain words`() {
        assertEquals("the URL host is en.wikipedia.org", DoneConditionChecker.render(DoneCondition.UrlHost("en.wikipedia.org")))
        assertEquals("the page contains 'done'", DoneConditionChecker.render(DoneCondition.MarkerText("done")))
        assertEquals(
            "an element matching 'Subscribe' is present",
            DoneConditionChecker.render(DoneCondition.ElementPresent("Subscribe")),
        )
        assertEquals("accounts followed >= 5", DoneConditionChecker.render(DoneCondition.CountReached("accounts followed", 5)))
    }

    // -- SubtaskPointer ---------------------------------------------------------------------

    private fun threeStepLadder() = Ladder(
        "s",
        listOf(
            Subtask("one", DoneCondition.UrlHost("one.example.com")),
            Subtask("two", DoneCondition.MarkerText("two")),
            Subtask("three", DoneCondition.ElementPresent("final")),
        ),
    )

    @Test
    fun `pointer starts at the first subtask and advances only one step at a time`() {
        val pointer = SubtaskPointer(threeStepLadder())
        assertEquals(1, pointer.activeOrdinal)
        assertEquals(0, pointer.completedCount)
        assertFalse(pointer.isFinal)
        pointer.advance()
        assertEquals(2, pointer.activeOrdinal)
        assertEquals("two", pointer.active.instruction)
        assertEquals(1, pointer.completedCount)
        assertFalse(pointer.isFinal)
        pointer.advance()
        assertTrue(pointer.isFinal)
        assertEquals(2, pointer.completedCount)
        assertFailsWith<IllegalStateException> { pointer.advance() }
    }

    @Test
    fun `completed subtasks ride the pointer in order`() {
        val ladder = threeStepLadder()
        val pointer = SubtaskPointer(ladder)
        pointer.advance()
        assertEquals(listOf("one"), pointer.completed().map { it.instruction })
    }

    // -- the URL-identity shape (v1.7.0, Phase 10C) -------------------------------------

    @Test
    fun `urlContains validates - a host-or-path fragment is required`() {
        val ok = Ladder(
            "Open the dinner category.",
            listOf(Subtask("open allrecipes.com/recipes/dinner", DoneCondition.UrlContains("allrecipes.com/recipes/dinner"))),
        )
        assertEquals(ok, LadderValidator.validate(ok))

        val blank = Ladder(
            "Open the page.",
            listOf(Subtask("open the page", DoneCondition.UrlContains("  "))),
        )
        val e1 = assertFailsWith<LadderValidationException> { LadderValidator.validate(blank) }
        assertTrue(e1.message!!.contains("urlContains"), e1.message!!)

        val whitespace = Ladder(
            "Open the page.",
            listOf(Subtask("open the page", DoneCondition.UrlContains("two words"))),
        )
        val e2 = assertFailsWith<LadderValidationException> { LadderValidator.validate(whitespace) }
        assertTrue(e2.message!!.contains("whitespace"), e2.message!!)

        val notAFragment = Ladder(
            "Open the page.",
            listOf(Subtask("open the page", DoneCondition.UrlContains("recipes"))),
        )
        val e3 = assertFailsWith<LadderValidationException> { LadderValidator.validate(notAFragment) }
        assertTrue(e3.message!!.contains("URL fragment"), e3.message!!)
    }

    @Test
    fun `urlContains matches the URL path - normalization keeps the path, strips scheme-www-port`() {
        val evidence = DoneConditionChecker.Evidence(url = "https://www.Allrecipes.com/recipes/17562/dinner/?page=2")
        assertTrue(
            DoneConditionChecker.satisfied(DoneCondition.UrlContains("allrecipes.com/recipes/17562/dinner"), evidence),
            "the path identity matches under scheme/www/query normalization (trailing slash trimmed)",
        )
        assertFalse(
            DoneConditionChecker.satisfied(DoneCondition.UrlContains("allrecipes.com/recipes/lunch"), evidence),
            "a different path never satisfies",
        )
        assertTrue(
            DoneConditionChecker.satisfied(DoneCondition.UrlContains("/dinner"), evidence),
            "a bare path fragment matches too",
        )
        assertFalse(
            DoneConditionChecker.satisfied(DoneCondition.UrlContains("m0.test/page"), evidence),
            "a different host never satisfies",
        )
        assertFalse(
            DoneConditionChecker.satisfied(DoneCondition.UrlContains("allrecipes.com/recipes"), DoneConditionChecker.Evidence(url = null)),
            "an unreadable URL never satisfies",
        )
    }


    @Test
    fun `same site family - apex, subdomain and same-brand ccTLD reconcile, strangers never do`() {
        assertTrue(DoneConditionChecker.sameSiteFamily("bbc.co.uk", "bbc.com"), "the observed bbc redirect")
        assertTrue(DoneConditionChecker.sameSiteFamily("wikipedia.org", "en.wikipedia.org"), "subdomain alias")
        assertTrue(DoneConditionChecker.sameSiteFamily("www.example.com", "example.com"), "www alias")
        assertTrue(DoneConditionChecker.sameSiteFamily("example.com", "example.com"), "identity")
        assertFalse(DoneConditionChecker.sameSiteFamily("example.com", "other.com"), "strangers never reconcile")
        assertFalse(
            DoneConditionChecker.sameSiteFamily("news.example.com", "news.other.com"),
            "same leading label but a shared suffix is NOT a family (the ccTLD rule needs differing suffixes)",
        )
        assertFalse(DoneConditionChecker.sameSiteFamily("", "example.com"), "blank never matches")
    }

}
