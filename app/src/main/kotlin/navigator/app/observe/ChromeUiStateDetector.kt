package navigator.app.observe

import navigator.app.loop.StepObservation

/**
 * PHASE_18 Stage 18.D CN-12 (doc 04 §9.10, ratified law) — the GENERIC browser-UI state
 * detector: pure, deterministic, one observation in, one [ChromeUiState] out. NEVER
 * navigates; the loop's recovery controller decides what an off-page state means.
 *
 * The R3 vision run's loss mechanism: Chrome's own surfaces were read as page content,
 * so the proposer targeted tab cards and the New tab while the checkout sat behind the
 * tab grid. This detector classifies the surface BEFORE any proposal acts.
 *
 * The signals, in the law's order (package, resource-ID shapes, node roles, URL
 * availability, page-root structure):
 *
 * - PACKAGE: a non-blank, non-allowlisted window package is [ChromeUiState.NON_CHROME_FOREGROUND]
 *   (the R4 APP_ROUTING class). A blank package (fakes, an unknown observer) never
 *   decides — the state falls through to the structural signals.
 * - RESOURCE-ID SHAPES: the observation's `browserChrome` flag IS the resource-ID signal
 *   — the service marks every node whose `viewIdResourceName` sits in the browser's own
 *   `<package>:id/` namespace, so the chrome-vs-content node split is ID-derived without
 *   this detector naming a single Chrome ID. No Chrome-specific resource ID is ever
 *   REQUIRED here (the law): [ChromeUiState.WEB_CONTENT] derives from page content alone.
 * - NODE ROLES: an EDITABLE browser-chrome node is the focused URL bar / search overlay
 *   — the omnibox refine. Clickable titled chrome nodes are the tab cards / menu rows.
 * - URL AVAILABILITY: a chrome-internal URL (blank / hidden / unavailable / about:blank /
 *   chrome:// / content://) is the tab-switcher and menu states' second signal — the
 *   conservative leg that keeps a SLOW-LOADING page (a readable store URL, a thin tree)
 *   out of the off-page states (the [navigator.app.harness.vision.ChromeUi] precedent:
 *   every off-page declaration needs a second signal to agree).
 * - PAGE-ROOT STRUCTURE: the content-node count (plus [StepObservation.pageNodeCount])
 *   against the chrome-node count — content present and not chrome-dominated is
 *   [ChromeUiState.WEB_CONTENT] (the emulator's webview shell classifies here: it has no
 *   tab switcher, and its page content needs no ID to be seen).
 *
 * Sub-state honesty: menu/dialog vs download/other is refined only by the structural
 * titled-node count; a finer split (which exact chrome surface) needs resource IDs this
 * pure model does not carry, so the residual bucket stays honest rather than guessing
 * from text (NO site text, NO language labels, NO single-store heuristics — the law).
 *
 * Pure JVM; no Android types; zero model calls (doc 04 §9.10: code detects).
 */
object ChromeUiStateDetector {

    /**
     * The minimum page-content nodes a working page carries (the [navigator.app.harness.vision.ChromeUi]
     * precedent: the R3 checkout carried dozens; a loading spinner carries none).
     */
    private const val MIN_CONTENT_NODES = 3

    /** The chrome-internal URL shapes: never a page the run can act on. */
    private val CHROME_URL_PREFIXES = listOf("about:blank", "chrome://", "content://", "about:!")

    /**
     * The minimum titled, clickable chrome nodes for the TAB-SWITCHER shape (the tab
     * grid shows the open tabs as titled cards; a single titled node is a dialog/sheet).
     */
    private const val TAB_CARD_MIN = 2

    /** The chrome-internal shape of the URL-bar read ([StepObservation.url]). */
    private fun urlIsChromeInternal(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.isBlank() || u == "hidden" || u == "unavailable" ||
            CHROME_URL_PREFIXES.any(u::startsWith)
    }

    /** The one entry point: classify the surface this observation shows. */
    fun detect(obs: StepObservation): ChromeUiState {
        val pkg = obs.windowPackage.trim()
        // (1) PACKAGE: a non-browser foreground is decisive (the browser never sees the touch).
        if (pkg.isNotEmpty() && !BrowserPackages.isBrowser(pkg)) {
            return ChromeUiState.NON_CHROME_FOREGROUND
        }
        val contentNodes = obs.nodes.count { !it.browserChrome && (it.text.isNotBlank() || it.desc.isNotBlank()) }
        val chromeNodes = obs.nodes.count { it.browserChrome }
        val chromeDominated = chromeNodes > 0 && chromeNodes >= contentNodes * 2
        val editableChrome = obs.nodes.any { it.browserChrome && it.editable }
        val titledCards = obs.nodes.count {
            it.browserChrome && it.clickable && (it.text.isNotBlank() || it.desc.isNotBlank())
        }
        val urlInternal = urlIsChromeInternal(obs.url)
        // (2) The chrome-owned surfaces: the chrome tree dominates and the URL agrees
        // (the second-signal law — a thin page with a readable URL is UNKNOWN, never a
        // declared chrome surface).
        if (chromeDominated && urlInternal) {
            return when {
                // (2a) NODE ROLES: the focused URL bar / search overlay owns the surface
                // (the New-tab page's search box and the omnibox suggestions alike).
                editableChrome -> ChromeUiState.OMNIBOX_OR_SEARCH
                // (2b) the tab grid: several titled chrome cards (tab titles + New tab).
                titledCards >= TAB_CARD_MIN -> ChromeUiState.TAB_SWITCHER
                // (2c) one titled chrome node: a dialog/sheet/menu-popover shape.
                titledCards == 1 -> ChromeUiState.CHROME_MENU_OR_DIALOG
                // (2d) the chrome-owned residual: download sheets, infobar stacks.
                else -> ChromeUiState.DOWNLOAD_OR_OTHER_CHROME_SURFACE
            }
        }
        // The omnibox refine when the tree is not chrome-dominated but the focused URL
        // bar sits over a too-thin page (the overlay owns the screen even with the page
        // half-attached).
        if (editableChrome && contentNodes < MIN_CONTENT_NODES) {
            return ChromeUiState.OMNIBOX_OR_SEARCH
        }
        // (3) PAGE-ROOT STRUCTURE: page content present and not chrome-dominated — the
        // WEB_CONTENT law (the webview shell needs no Chrome ID to get here).
        if (contentNodes >= MIN_CONTENT_NODES || (obs.pageNodeCount ?: 0) >= MIN_CONTENT_NODES) {
            return ChromeUiState.WEB_CONTENT
        }
        // (4) Nothing decides: conservative UNKNOWN (no recovery decision may be taken from it).
        return ChromeUiState.UNKNOWN
    }
}
