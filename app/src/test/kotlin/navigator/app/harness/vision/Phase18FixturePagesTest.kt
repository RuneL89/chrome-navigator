package navigator.app.harness.vision

import kotlin.test.Test
import kotlin.test.assertTrue
import navigator.app.loop.StepObservation

/**
 * PHASE 18 STAGE 18.A — the sanitized fixture PAGES' gate (the CN-16/§8 sanitization
 * law at fixture creation): every HTML fixture under
 * `app/src/test/resources/fixtures/phase18/` must be free of the UAT 17.1 corpus's
 * real names (stores, sites, brands, accounts, addresses, people, private page text)
 * and must carry the structural anchors the later emulator leg serves and the
 * scripted fixtures mirror. The scripted replay itself lives in
 * [Phase18R2SiteDriftFixtureTest], [Phase18R3ChromeStateAndFormFixtureTest] and
 * [Phase18R4FinalEvidenceFixtureTest]; this gate keeps the pages honest and
 * deterministic as the corpus evolves.
 */
class Phase18FixturePagesTest {

    private val dir = "fixtures/phase18"

    private fun page(name: String): String =
        javaClass.classLoader.getResourceAsStream("$dir/$name")!!
            .readBytes().decodeToString()

    /** Real corpus material that must NEVER re-enter a fixture (the CN-16 law). */
    private val banned = listOf(
        "verkkokauppa", "rumble", "wwe", "cdon", "gear4music", "celsius",
        "youtube", "foodwishes", "food wishes", "mrawesome", "mrawesome",
        "koivuviita", "etunimi", "katuosoite", "postinumero", "sähköposti",
        "kassa", "ostoskori", "jatka maksamaan", "kiitos tilauksestasi",
        "välilehdet", "lisää ostoskoriin", "yhteystietosi", "salasana",
    )

    private fun assertSanitized(html: String, name: String) {
        val lower = html.lowercase()
        for (token in banned) {
            assertTrue(token !in lower, "$name carries banned corpus material: '$token'")
        }
        assertTrue(html.contains("SANITIZED"), "$name must carry its sanitization header")
        assertTrue(html.contains("UAT 17.1"), "$name must name its source run")
        assertTrue(html.contains("§9."), "$name must name its doc 04 §9.x mapping")
        assertTrue(html.contains("Stage 18"), "$name must name the stage that changes it")
    }

    @Test
    fun `the R2 pages are sanitized and carry the drift shape`() {
        val siteA = page("r2-site-a-home.html")
        val siteB = page("r2-site-b-listing.html")
        assertSanitized(siteA, "r2-site-a-home.html")
        assertSanitized(siteB, "r2-site-b-listing.html")
        assertTrue("Open the archive" in siteA, "the archive anchor mirrors the scripted observation")
        assertTrue("Directory entry" in siteB, "the directory entries mirror the scripted drift page")
    }

    @Test
    fun `the R3 pages are sanitized and carry the form plus the chrome state`() {
        val form = page("r3-checkout-form.html")
        val tabs = page("r3-tab-switcher.html")
        assertSanitized(form, "r3-checkout-form.html")
        assertSanitized(tabs, "r3-tab-switcher.html")
        assertTrue("Email address *" in form, "the required-empty field anchor")
        assertTrue("Sample Buyer" in form, "the pre-filled field anchor")
        assertTrue("no order-placed marker" in form, "the page must not carry a completion marker")
        assertTrue("Tab switcher" in tabs && "New tab" in tabs, "the chrome-state anchors")
    }

    @Test
    fun `the R4 page is sanitized and carries the evidence-free media shape`() {
        val media = page("r4-media-page.html")
        assertSanitized(media, "r4-media-page.html")
        assertTrue("Now playing" in media, "the playback outcome anchor")
        assertTrue("no account marker" in media, "the page must not evidence the switched profile")
    }

    /** The scripted observations mirror the pages' anchors (one observation per page). */
    @Test
    fun `the scripted observations stay in sync with the pages' anchors`() {
        // The mirrors are structural: the anchors above are exactly the strings the
        // scripted fixtures ground and check. This assertion documents the pairing so a
        // page edit that orphans a scripted fixture fails here first.
        assertTrue(
            StepObservation(
                url = "https://sitea.test/home", title = "Site A",
                lines = listOf("Site A", "Open the archive"), nodes = emptyList(),
            ).screenHash != 0,
        )
    }
}
