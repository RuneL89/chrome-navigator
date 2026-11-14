package navigator.core.ladder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V2 S1 FR-28 (AC-S1.5) — the search-oscillation law. The live phone UAT defect: a
 * search step pinned by a URL condition re-invalidates itself on the lawful next click,
 * the prefix audit rolls it back, and the re-plan re-runs the same search forever.
 * The law: a NON-FINAL search step may not carry UrlHost/UrlContains; a content
 * condition is required. Enforced in [LadderValidator] — the one choke point both the
 * initial plan and every re-plan pass through.
 */
class SearchStepOscillationLawTest {

    private fun ladder(vararg subtasks: Subtask, taskClass: LadderTaskClass = LadderTaskClass.READ) =
        Ladder("test ladder", subtasks.toList(), taskClass)

    private fun validLadder(vararg subtasks: Subtask): Ladder =
        ladder(*subtasks).let { LadderValidator.validate(it) }

    // -- the detector -------------------------------------------------------------------

    @Test
    fun `search steps detected by engine token, search verb, and search phrases`() {
        assertTrue(SearchStepLaw.isSearchStep("Search google for 'kettle site:verkkokauppa.com'"))
        assertTrue(SearchStepLaw.isSearchStep("search verkkokauppa for kettles"))
        assertTrue(SearchStepLaw.isSearchStep("Open Bing and look up the article"))
        assertTrue(SearchStepLaw.isSearchStep("etsi halvin vedenkeitin"))
        assertTrue(SearchStepLaw.isSearchStep("the results page is showing"))
        assertTrue(SearchStepLaw.isSearchStep("google it"))
        assertTrue(SearchStepLaw.isSearchStep("use the site's search and haku for prices"))
    }

    @Test
    fun `non-search steps are not detected as search steps`() {
        assertFalse(SearchStepLaw.isSearchStep("Open verkkokauppa.com"))
        assertFalse(SearchStepLaw.isSearchStep("Open the encyclopedia site"))
        assertFalse(SearchStepLaw.isSearchStep("Open the kettle category page"))
        assertFalse(SearchStepLaw.isSearchStep("Add the cheapest kettle to the cart"))
        // word-boundary law: "research" is not "search"
        assertFalse(SearchStepLaw.isSearchStep("Research the article's references"))
    }

    // -- the validator rule -------------------------------------------------------------

    @Test
    fun `non-final search step with urlHost is refused`() {
        val e = assertFailsWith<LadderValidationException> {
            LadderValidator.validate(
                ladder(
                    Subtask("Search google for the kettle price", DoneCondition.UrlHost("google.com")),
                    Subtask("Open the verkkokauppa product page", DoneCondition.UrlContains("verkkokauppa.com/fi/product")),
                ),
            )
        }
        assertContains(e.message!!, "subtask 1")
        assertContains(e.message!!, "SEARCH step")
        assertContains(e.message!!, "URL condition")
    }

    @Test
    fun `non-final search step with urlContains is refused`() {
        val e = assertFailsWith<LadderValidationException> {
            LadderValidator.validate(
                ladder(
                    Subtask("search for the kettle prices", DoneCondition.UrlContains("google.com/search?q=kettle")),
                    Subtask("Open the verkkokauppa product page", DoneCondition.MarkerText("Vedenkeitin")),
                ),
            )
        }
        assertContains(e.message!!, "subtask 1")
        assertContains(e.message!!, "search oscillation")
    }

    @Test
    fun `site-internal search step with url condition is refused (the lawful product click leaves the results url)`() {
        assertFailsWith<LadderValidationException> {
            LadderValidator.validate(
                ladder(
                    Subtask("Open verkkokauppa.com", DoneCondition.UrlHost("verkkokauppa.com")),
                    Subtask("search verkkokauppa for kettles", DoneCondition.UrlContains("/fi/search?query=vedenkeitin")),
                    Subtask("Open the cheapest kettle's product page", DoneCondition.MarkerText("lisää ostoskoriin")),
                ),
            )
        }
    }

    @Test
    fun `a FINAL search step may carry a url condition (nothing lawful navigates past the final step)`() {
        val ladder = validLadder(
            Subtask("Open the search engine", DoneCondition.MarkerText("search results")),
            Subtask("report which engine answered", DoneCondition.UrlContains("google.com/search")),
        )
        assertTrue(ladder.subtasks[1].done is DoneCondition.UrlContains)
    }

    @Test
    fun `non-final search step with a CONTENT condition passes`() {
        val ladder = validLadder(
            Subtask(
                "search google for the verkkokauppa kettle listing",
                DoneCondition.ElementPresent("a result link to verkkokauppa.com"),
            ),
            Subtask("Open the verkkokauppa product page", DoneCondition.MarkerText("Vedenkeitin")),
        )
        assertEquals(2, ladder.subtasks.size)
    }

    @Test
    fun `non-final NON-search steps keep their url conditions (site entry and category pages are lawful)`() {
        val ladder = validLadder(
            Subtask("Open verkkokauppa.com", DoneCondition.UrlHost("verkkokauppa.com")),
            Subtask("Open the coffee machines category page", DoneCondition.UrlContains("/fi/kahvinkeittimet")),
            Subtask("Open the cheapest product page", DoneCondition.MarkerText("lisää ostoskoriin")),
        )
        assertTrue(ladder.subtasks[0].done is DoneCondition.UrlHost)
        assertTrue(ladder.subtasks[1].done is DoneCondition.UrlContains)
    }

    @Test
    fun `the named-site ENTRY exception - an entry-verb step naming its own host keeps urlHost`() {
        // Phase18SitePolicyTest's named-site ladder shape: "open <site> and search the
        // catalog" — site entry whose host stays true across intra-site navigation.
        val ladder = validLadder(
            Subtask(
                "open shop.example and search the catalog for SoundMagic E11C for 60 euros",
                DoneCondition.UrlHost("shop.example"),
            ),
            Subtask("buy the product to koivuviita 7 B 5 for 60 euros", DoneCondition.MarkerText("order placed")),
        )
        assertTrue(ladder.subtasks[0].done is DoneCondition.UrlHost)
    }

    @Test
    fun `an ENGINE host is never exempt - open google and search with urlHost is refused`() {
        assertFailsWith<LadderValidationException> {
            LadderValidator.validate(
                ladder(
                    Subtask("open google.com and search for kettles", DoneCondition.UrlHost("google.com")),
                    Subtask("open the cheapest product page", DoneCondition.MarkerText("lisää ostoskoriin")),
                ),
            )
        }
    }

    @Test
    fun `a host NOT named in the instruction is never exempt`() {
        assertFailsWith<LadderValidationException> {
            LadderValidator.validate(
                ladder(
                    Subtask("search google for kettles", DoneCondition.UrlHost("google.com")),
                    Subtask("open the cheapest product page", DoneCondition.MarkerText("lisää ostoskoriin")),
                ),
            )
        }
    }

    @Test
    fun `urlContains is never exempt even on an entry-verb step`() {
        assertFailsWith<LadderValidationException> {
            LadderValidator.validate(
                ladder(
                    Subtask(
                        "open shop.example and search the catalog",
                        DoneCondition.UrlContains("shop.example/search"),
                    ),
                    Subtask("open the cheapest product page", DoneCondition.MarkerText("lisää ostoskoriin")),
                ),
            )
        }
    }

    @Test
    fun `the violation fires on MIDDLE steps too, not only the first`() {
        val e = assertFailsWith<LadderValidationException> {
            LadderValidator.validate(
                ladder(
                    Subtask("Open the encyclopedia site", DoneCondition.UrlHost("en.wikipedia.org")),
                    Subtask("search the site for the tove jansson medal year", DoneCondition.UrlContains("Special:Search")),
                    Subtask("Open her article", DoneCondition.MarkerText("Hans Christian Andersen")),
                ),
            )
        }
        assertContains(e.message!!, "subtask 2")
    }

    // -- the law object directly ---------------------------------------------------------

    @Test
    fun `the law object returns null for lawful shapes and the reason text for violations`() {
        assertNull(
            SearchStepLaw.urlConditionViolation(
                Subtask("Open verkkokauppa.com", DoneCondition.UrlHost("verkkokauppa.com")),
                isFinal = false,
            ),
            "a non-search site-entry step is lawful",
        )
        assertNull(
            SearchStepLaw.urlConditionViolation(
                Subtask("search for the kettle", DoneCondition.UrlContains("google.com/search")),
                isFinal = true,
            ),
            "a FINAL search step is lawful",
        )
        assertNull(
            SearchStepLaw.urlConditionViolation(
                Subtask("search for the kettle", DoneCondition.MarkerText("results")),
                isFinal = false,
            ),
            "a search step pinned by CONTENT is lawful",
        )
        val violation = SearchStepLaw.urlConditionViolation(
            Subtask("search google for kettles", DoneCondition.UrlHost("google.com")),
            isFinal = false,
        )
        assertTrue(violation != null && violation.contains("oscillation"))
    }
}
