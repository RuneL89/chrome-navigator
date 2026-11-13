package navigator.app.loop

import navigator.app.observe.ChromeUiState
import navigator.core.ladder.DoneConditionChecker
import navigator.core.ladder.GoalFidelity

/**
 * PHASE_18 Stage 18.D CN-12 (doc 04 §9.10 + doc 06 §4, ratified law) — the PURE recovery
 * DECISIONS for off-page browser states. The R3 vision run's loss: a correct checkout was
 * abandoned when Chrome's tab switcher appeared — the engine treated the tab cards as
 * page content, opened a NEW tab, and proposed a full purchase restart. This controller
 * owns the decisions so the loop never has to improvise them:
 *
 * - the checkout-milestone progression (cart → checkout → payment review → order
 *   confirmation), derived deterministically from the observation's title/URL/control
 *   vocabulary — NO model calls (doc 04 §9.10: "navigation milestones record when cart,
 *   checkout, payment review and order confirmation were reached");
 * - the checkout-integrity law (doc 06 §4): once a checkout milestone is active, an
 *   off-page state is recovered by identifying and restoring the EXISTING checkout tab
 *   from fresh URL/title/tab evidence; "New tab" is never the recovery; shopping, cart
 *   entry and address entry are never restarted; the restored contents are revalidated
 *   BEFORE the run continues; an unidentifiable tab hands off to the owner (the CN-15
 *   terminal vocabulary lands with Stage 18.D part 2 — for now the handoff is this
 *   sealed [BrowserRecovery.OwnerHandoff] outcome the loop maps into its terminal set);
 * - the raw-action law: in an off-page state a page-coordinate touch is refused in code
 *   (the R3 defect's second half — acting on chrome UI as if it were the page).
 *
 * "Continue to payment" is NOT an order confirmation: the order-control vocabulary is
 * [GoalFidelity]'s CONFIRM_EVIDENCE (order-specific only, Stage 18.C) and
 * [navigator.app.harness.vision.PurchaseControl]s ORDER_PHRASES deliberately excludes
 * the payment-continuation controls — this controller REFERENCES those vocabularies and
 * never duplicates them.
 *
 * Pure JVM; no Android types; no model calls (code detects, models never authorize —
 * ART-2). The loop supplies the state ([ChromeUiStateDetector]) and maps the outcome.
 */
object BrowserRecoveryController {

    // -- the checkout-milestone progression (doc 04 §9.10) -----------------------------

    /** The navigation milestones a purchase passes through, in order. */
    enum class CheckoutMilestone {
        NONE, CART, CHECKOUT, PAYMENT_REVIEW, ORDER_CONFIRMATION,
        ;

        /** Monotonic progression: the highest milestone reached is the run's state of record. */
        fun atLeast(other: CheckoutMilestone): Boolean = ordinal >= other.ordinal
    }

    /** Non-letter/digit runs collapse to a single space ([GoalTokens] discipline). */
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")

    /**
     * The cart-surface vocabulary as normalized token runs (en + fi + de + fr + es — the
     * same multi-language discipline as the checkout runs; never a store name).
     */
    private val CART_TOKEN_RUNS: List<List<String>> = listOf(
        "cart", "shopping cart", "basket", "ostoskori", "ostoskoriin", "warenkorb",
        "panier", "carrito",
    ).map { tokens(it) }

    /** The checkout/payment surface vocabulary (the [navigator.app.harness.vision CHECKOUT] runs' checkout half). */
    private val CHECKOUT_TOKEN_RUNS: List<List<String>> = listOf(
        "checkout", "to checkout", "kassa", "jatka maksamaan", "payment", "zur kasse", "tilaus",
    ).map { tokens(it) }

    /**
     * The payment-REVIEW vocabulary (the summary/review step BEFORE the order control).
     * Deliberately distinct from [GoalFidelity] CONFIRM_EVIDENCE: "review your order" is
     * a checkout step, never a completed purchase (doc 06 §4).
     */
    private val PAYMENT_REVIEW_TOKEN_RUNS: List<List<String>> = listOf(
        "order review", "review order", "review your order", "order summary",
        "tilauksen yhteenveto", "tilauksen esikatselu", "bestellung prüfen",
    ).map { tokens(it) }

    private fun tokens(text: String): List<String> =
        text.lowercase().replace(NON_WORD, " ").split(' ').filter { it.isNotBlank() }

    private fun containsRun(text: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || text.size < needle.size) return false
        var start = 0
        while (start <= text.size - needle.size) {
            if (text.subList(start, start + needle.size) == needle) return true
            start += 1
        }
        return false
    }

    private fun anyRun(text: List<String>, runs: List<List<String>>): Boolean =
        runs.any { containsRun(text, it) }

    /**
     * The milestone THIS observation evidences (title + URL + visible page lines — the
     * same read universe the checkout-shape test uses). The ORDER check is
     * [GoalFidelity.evidencesOrderConfirmation] — the order-specific core vocabulary
     * ("continue to payment" and every cart/checkout/review shape are NOT in it), so the
     * progression can never jump to ORDER_CONFIRMATION on a payment-continuation control.
     */
    fun milestoneOf(obs: StepObservation): CheckoutMilestone {
        val text = tokens(
            obs.title + " " + obs.url + " " + obs.lines.take(20).joinToString(" "),
        )
        return when {
            GoalFidelity.evidencesOrderConfirmation(obs.title + " " + obs.lines.take(20).joinToString(" ")) ->
                CheckoutMilestone.ORDER_CONFIRMATION
            anyRun(text, PAYMENT_REVIEW_TOKEN_RUNS) -> CheckoutMilestone.PAYMENT_REVIEW
            anyRun(text, CHECKOUT_TOKEN_RUNS) -> CheckoutMilestone.CHECKOUT
            anyRun(text, CART_TOKEN_RUNS) -> CheckoutMilestone.CART
            else -> CheckoutMilestone.NONE
        }
    }

    // -- the off-page recovery decision -------------------------------------------------

    /**
     * One recovery decision (sealed — the loop maps it; no stringly-typed branches).
     * The CN-15 terminal mapping (a dedicated owner-handoff TERMINAL) lands with Stage
     * 18.D part 2; until then [OwnerHandoff] is the explicit outcome the loop refuses to
     * act past, never a silent continue.
     */
    sealed interface BrowserRecovery {
        /** The state needs no recovery (on-page, or nothing code may lawfully do). */
        data object None : BrowserRecovery

        /**
         * Restore the EXISTING checkout tab by re-opening its recorded URL (the code-owned
         * launcher seam). [reason] carries the identification evidence, never secrets.
         */
        data class RestoreCheckout(val url: String, val reason: String) : BrowserRecovery

        /**
         * The checkout tab cannot be identified safely from the fresh tab evidence →
         * OWNER HANDOFF (doc 06 §4). ZERO navigation follows from this outcome; the loop
         * maps it to the CN-15 terminal when that vocabulary lands (part 2).
         */
        data class OwnerHandoff(val reason: String) : BrowserRecovery

        /**
         * The recovered/restored checkout's contents differ from the milestone's record →
         * STOP AND REVERIFY (doc 06 §4: never continue on stale evidence).
         */
        data class StopAndReverify(val reason: String) : BrowserRecovery

        /**
         * A browser menu/dialog/surface during (or instead of) page work: escape with a
         * GLOBAL action (back) — never a page-coordinate touch.
         */
        data class EscapeBrowserUi(val reason: String) : BrowserRecovery
    }

    /**
     * The checkout-tab identification from FRESH tab evidence (the tab switcher's card
     * titles): a card identifies the checkout tab when its text carries the recorded
     * checkout identity (the recorded title's tokens, or the checkout URL's HOST). The
     * identity match is exact-and-bounded (token-run containment against the recorded
     * milestone identity), so a "New tab" card can never be the identification — the
     * controller has no new-tab ACTION to return at all (the doc 06 §4 law).
     *
     * @return the identification evidence line when EXACTLY ONE tab card matches; null
     *   when zero or multiple match (ambiguous → the caller hands off — never a guess).
     */
    fun identifyCheckoutTab(
        obs: StepObservation,
        checkoutUrl: String,
        checkoutTitle: String,
    ): String? {
        val host = DoneConditionChecker.normalizeUrl(checkoutUrl)
            ?.substringBefore('/')
            ?.removePrefix("www.")
            ?.lowercase()
            .orEmpty()
        val titleTokens = tokens(checkoutTitle).filter { it.length > 2 }
        val cards = obs.nodes.filter {
            it.browserChrome && it.clickable && it.text.isNotBlank()
        }
        val matches = cards.filter { card ->
            val cardTokens = tokens(card.text)
            (host.isNotEmpty() && containsRun(cardTokens, tokens(host))) ||
                (titleTokens.isNotEmpty() && titleTokens.all { it in cardTokens })
        }
        return when (matches.size) {
            1 -> "identified the checkout tab by its card '${matches.single().text.take(40)}'"
            else -> null // zero matches = lost; multiple = ambiguous; both are handoffs
        }
    }

    /**
     * The OFF-PAGE recovery decision. [checkoutUrl]/[checkoutTitle] are the recorded
     * checkout milestone (null = no checkout was reached — the ordinary off-page escape
     * applies). [activeConditionHolds] is the code-side quick check that the ACTIVE
     * subtask's condition already holds on THIS surface (the pre-existing guard: the
     * recovery must never fire past a satisfied condition).
     */
    fun onOffPage(
        state: ChromeUiState,
        obs: StepObservation,
        checkoutUrl: String?,
        checkoutTitle: String?,
        activeConditionHolds: Boolean,
    ): BrowserRecovery {
        // The satisfied condition wins: no recovery may run past it (the pre-existing law).
        if (activeConditionHolds) return BrowserRecovery.None
        val checkoutActive = checkoutUrl != null
        return when {
            // A non-browser foreground with a checkout milestone: restore the checkout
            // (the app took the URL — the R4 APP_ROUTING class; the launcher re-opens it).
            state == ChromeUiState.NON_CHROME_FOREGROUND && checkoutActive ->
                BrowserRecovery.RestoreCheckout(
                    checkoutUrl!!,
                    "a non-browser window took the foreground; restoring the checkout tab",
                )
            // Tab switcher / menu / other chrome surface WITH a checkout milestone:
            // identify and restore the EXISTING checkout tab from the fresh tab evidence;
            // ambiguous → owner handoff (never a New tab, never a restart).
            state == ChromeUiState.TAB_SWITCHER ||
                state == ChromeUiState.CHROME_MENU_OR_DIALOG ||
                state == ChromeUiState.DOWNLOAD_OR_OTHER_CHROME_SURFACE -> {
                if (!checkoutActive) {
                    BrowserRecovery.EscapeBrowserUi(
                        "${state.name.lowercase().replace('_', ' ')} with no checkout milestone - " +
                            "escaping the browser UI (global back), never page coordinates",
                    )
                } else {
                    val identification = identifyCheckoutTab(obs, checkoutUrl!!, checkoutTitle.orEmpty())
                    if (identification == null) {
                        BrowserRecovery.OwnerHandoff(
                            "the checkout tab cannot be identified safely from the tab evidence " +
                                "(zero or ambiguous card matches) - owner handoff, checkout integrity " +
                                "(doc 06 §4); no new tab, no restart",
                        )
                    } else {
                        BrowserRecovery.RestoreCheckout(checkoutUrl, identification)
                    }
                }
            }
            // The omnibox/search overlay: escape it (a global back dismisses; the URL is
            // recoverable afterwards) — never a suggestion-row tap (the R3 New-tab shape).
            state == ChromeUiState.OMNIBOX_OR_SEARCH ->
                BrowserRecovery.EscapeBrowserUi(
                    "the omnibox/search overlay owns the surface - escaping with a global back, " +
                        "never a suggestion tap",
                )
            else -> BrowserRecovery.None
        }
    }

    /**
     * The POST-RESTORE REVALIDATION (doc 06 §4: the restored contents are revalidated
     * before continuing). The fresh observation must still carry the checkout identity —
     * the readable URL's host matches the recorded checkout URL's host, or the page
     * identity (title) still carries the checkout title's tokens. Anything else is a
     * DIFFERENT page wearing the recovery: stop and reverify, never continue.
     */
    fun validateRestoredCheckout(
        fresh: StepObservation,
        checkoutUrl: String,
        checkoutTitle: String,
    ): BrowserRecovery {
        val freshUrl = fresh.url.takeUnless { it.isBlank() || it == "hidden" || it == "unavailable" }
        if (freshUrl != null) {
            val sameHost = DoneConditionChecker.sameSiteFamily(
                freshUrl,
                checkoutUrl,
            ) || hostsMatch(freshUrl, checkoutUrl)
            if (sameHost) return BrowserRecovery.None
        }
        val titleTokens = tokens(checkoutTitle).filter { it.length > 2 }
        if (titleTokens.isNotEmpty() && titleTokens.all { it in tokens(fresh.title) }) {
            return BrowserRecovery.None
        }
        return BrowserRecovery.StopAndReverify(
            "the restored surface does not carry the checkout identity (host/title mismatch) - " +
                "STOP AND REVERIFY, never continuing on stale evidence (doc 06 §4)",
        )
    }

    /** Exact-host equality (scheme/port/www-insensitive, the checker's normalization). */
    private fun hostsMatch(a: String, b: String): Boolean {
        val hostA = DoneConditionChecker.normalizeUrl(a)?.substringBefore('/')?.removePrefix("www.")
        val hostB = DoneConditionChecker.normalizeUrl(b)?.substringBefore('/')?.removePrefix("www.")
        return hostA != null && hostA == hostB
    }

    // -- the raw-action law --------------------------------------------------------------

    /**
     * Whether a dispatch in [state] may touch PAGE COORDINATES. The loop calls this
     * BEFORE grounding (a refused touch never pays for one): a non-null reason REFUSES
     * the action with zero executor calls and zero grounding calls.
     *
     * Law:
     * - [touchesPageCoordinates] false (back, the IME submit): always allowed — the
     *   global actions are the ESCAPE surface, never a page touch.
     * - WEB_CONTENT and UNKNOWN: allowed (UNKNOWN never decides — the ordinary guards
     *   own the step).
     * - OMNIBOX_OR_SEARCH: allowed — typing the query and picking suggestions IS the
     *   lawful search path (the chrome-owned surface; the R3 defect was acting on chrome
     *   UI AS page content, which the off-page states below refuse).
     * - TAB_SWITCHER / CHROME_MENU_OR_DIALOG / DOWNLOAD_OR_OTHER_CHROME_SURFACE: refused
     *   — no page-coordinate touch on a chrome-management surface.
     * - NON_CHROME_FOREGROUND: refused — the browser is not the front window; the touch
     *   would land in another app.
     */
    fun rawActionRefusal(state: ChromeUiState, touchesPageCoordinates: Boolean): String? {
        if (!touchesPageCoordinates) return null
        return when (state) {
            ChromeUiState.WEB_CONTENT,
            ChromeUiState.OMNIBOX_OR_SEARCH,
            ChromeUiState.UNKNOWN,
            -> null
            ChromeUiState.TAB_SWITCHER -> "the surface is the TAB SWITCHER - page-coordinate " +
                "actions are refused (checkout integrity: recover the existing tab, never act " +
                "on chrome UI as page content)"
            ChromeUiState.CHROME_MENU_OR_DIALOG -> "the surface is a CHROME MENU/DIALOG - " +
                "page-coordinate actions are refused (escape with a global action, never a " +
                "touch that would land on the menu)"
            ChromeUiState.DOWNLOAD_OR_OTHER_CHROME_SURFACE -> "the surface is another " +
                "CHROME-OWNED surface - page-coordinate actions are refused"
            ChromeUiState.NON_CHROME_FOREGROUND -> "the foreground is NOT the browser - " +
                "page-coordinate actions are refused (the touch would land in another app)"
        }
    }
}
