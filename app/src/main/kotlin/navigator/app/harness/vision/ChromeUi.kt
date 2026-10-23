package navigator.app.harness.vision

import navigator.app.loop.StepObservation
import navigator.app.observe.BrowserPackages

/**
 * PHASE_17 Stage 0.3 — the OFF-PAGE / BROWSER-CHROME detector (pure, deterministic).
 *
 * The R3 vision run (`task-1792674073530`): the "Jatka maksamaan" tap opened Chrome's TAB
 * SWITCHER instead of the payment page. The engine kept proposing inside Chrome's own UI —
 * the "Kassa | CDON" tab card, then the New tab — and the checkout was lost. This object
 * answers exactly one question: is the CURRENT observation still on the page the run was
 * working, or has the browser dropped to its own chrome (the tab switcher, a New-tab page,
 * an empty omnibox state)?
 *
 * It is a PREDICATE only — it never navigates. The loop decides the recovery.
 *
 * The off-page shapes (all deterministic over the observation):
 * - the front window is not an allowlisted browser (a native app took the URL — the R4
 *   tree's APP_ROUTING class; the a11y openSiteStep already fails this loudly);
 * - the title is a known chrome-UI title (the tab switcher / tab grid in the shipped
 *   languages) while the page offers no content nodes;
 * - the URL is `about:blank`/`chrome://`-shaped (a New-tab page, not a store page);
 * - the content subtree is empty while clickable BROWSER-CHROME nodes dominate (the tab
 *   cards are chrome nodes; a store checkout always carries content text).
 */
object ChromeUi {

    /**
     * The browser chrome's own page titles (lowercase fragments; the shipped UI languages
     * first). A title match alone never declares off-page — the content check must agree.
     */
    private val CHROME_TITLES = listOf(
        "tab switcher", "tab overview", "all tabs", "new tab", "untitled",
        "välilehdet", "uusi välilehti", "tabellen", "neuer tab", "onglets",
    )

    /** The chrome-internal URL shapes: never a store page. */
    private val CHROME_URL_PREFIXES = listOf("about:blank", "chrome://", "content://", "about:!")

    /** The minimum content nodes a working page carries (the R3 checkout had dozens). */
    private const val MIN_CONTENT_NODES = 3

    /** Why the observation looks like browser chrome (or null when it does not). */
    data class OffPage(val reason: String)

    /**
     * Whether [obs] is a browser-chrome surface (the tab switcher, a New tab, a native
     * app, a chrome:// page) rather than the page the run was working. Conservative:
     * every signal needs a second signal to agree, so a slow-loading store page (a thin
     * tree, a stale title) is NOT declared off-page.
     */
    fun offPage(obs: StepObservation): OffPage? {
        val frontIsBrowser = BrowserPackages.isBrowser(obs.windowPackage)
        if (!frontIsBrowser) {
            return OffPage("the front window is not a browser (${obs.windowPackage.ifBlank { "unknown" }})")
        }
        val url = obs.url.trim().lowercase()
        val chromeUrl = url.isBlank() || url == "hidden" || url == "unavailable" ||
            CHROME_URL_PREFIXES.any { url.startsWith(it) }
        val title = obs.title.trim().lowercase()
        val chromeTitle = title.isBlank() || CHROME_TITLES.any(title::contains)
        val contentNodes = obs.nodes.count { !it.browserChrome && (it.text.isNotBlank() || it.desc.isNotBlank()) }
        val thinContent = contentNodes < MIN_CONTENT_NODES
        val chromeNodes = obs.nodes.count { it.browserChrome }
        val chromeDominated = chromeNodes > 0 && chromeNodes >= contentNodes * 2
        // A blank URL with a real store title (or vice versa) is a slow page, not chrome:
        // a BLANK title alone needs the URL to also be chrome-shaped (the conservative leg).
        val urlRealStore = !chromeUrl && url.contains(".")
        return when {
            chromeUrl && thinContent ->
                OffPage("the URL is a chrome/new-tab shape and the page offers no content")
            title.isBlank() && urlRealStore -> null
            chromeTitle && thinContent ->
                OffPage("the title is browser chrome (\"${obs.title.take(40)}\") and the page offers no content")
            chromeTitle && chromeDominated ->
                OffPage("the title is browser chrome and the tree is dominated by chrome nodes")
            else -> null
        }
    }
}
