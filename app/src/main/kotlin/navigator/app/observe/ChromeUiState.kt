package navigator.app.observe

/**
 * PHASE_18 Stage 18.D CN-12 (doc 04 §9.10, ratified law) — the GENERIC browser-UI state
 * vocabulary. The R3 vision run lost a live checkout because Chrome's own surfaces were
 * treated as page content: the tab switcher's tab cards and the New tab became proposal
 * targets and the purchase restarted from a search. Before any recovery (or any raw
 * action) the loop must know WHICH surface it is looking at.
 *
 * The states, and what recovery may do in each (decided by the loop's
 * `BrowserRecoveryController`, never here — this enum carries no logic):
 *
 * - [WEB_CONTENT] — the page the run is working. The ONLY state page-coordinate actions
 *   are lawfully aimed at.
 * - [OMNIBOX_OR_SEARCH] — the omnibox/search overlay owns the surface (the focused URL
 *   bar, suggestion rows). Chrome-owned: typing the query there is lawful, page taps are
 *   not (the R3 New-tab shape).
 * - [TAB_SWITCHER] — the tab grid. With a checkout milestone active this is THE
 *   off-page state of record (doc 06 §4): recover the EXISTING checkout tab, never a
 *   New tab, never a restart.
 * - [CHROME_MENU_OR_DIALOG] — a browser menu/sheet/dialog. Recovery dismisses it with a
 *   global action; page coordinates are never touched.
 * - [DOWNLOAD_OR_OTHER_CHROME_SURFACE] — the residual chrome-owned bucket (a download
 *   sheet, an infobar stack). Same discipline as the menu: no page coordinates.
 * - [NON_CHROME_FOREGROUND] — a non-browser package owns the foreground (the R4
 *   APP_ROUTING class). The browser never sees the touch.
 * - [UNKNOWN] — the observer cannot tell (a blank window package, a tree that carries
 *   neither page content nor a chrome surface). Conservative: no recovery decision is
 *   ever taken from UNKNOWN; the ordinary guards (occlusion, readiness, the pre-dispatch
 *   check) own the step.
 *
 * Pure vocabulary; JVM-pinnable; no Android types (the [ChromeUiStateDetector] derives
 * it from the observation).
 */
enum class ChromeUiState {
    WEB_CONTENT,
    OMNIBOX_OR_SEARCH,
    TAB_SWITCHER,
    CHROME_MENU_OR_DIALOG,
    DOWNLOAD_OR_OTHER_CHROME_SURFACE,
    NON_CHROME_FOREGROUND,
    UNKNOWN,
    ;

    /** The chrome-owned off-page states: recovery decides, page coordinates never fire. */
    val offPage: Boolean
        get() = this != WEB_CONTENT
}
