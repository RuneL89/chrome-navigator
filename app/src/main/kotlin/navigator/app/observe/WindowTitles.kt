package navigator.app.observe

/**
 * The page-title reader's PURE decision (Phase 10B, phase doc §2.8 / vision 04 §3
 * v1.6.0: "the observation layer reads the page title; a blank title is a defect, never
 * normal input"): which window's title is LAWFUL page identity. The service walks its
 * `windows` list (the IME-probe precedent — the same enumeration the live log's
 * IME-avoidance uses) and hands the candidates here; this object answers which title
 * feeds the loop.
 *
 * Preference order (deterministic in the platform's topmost-first window order):
 * 1. the FOCUSED window whose package is allowlisted, with a non-blank title;
 * 2. the ACTIVE allowlisted window with a non-blank title;
 * 3. the root-application-window fallback: the first allowlisted APPLICATION window
 *    with a non-blank title (a focused dialog over the browser must not steal the
 *    page's identity, and a browser whose focused window carries no title still has
 *    its root application window's).
 *
 * "" when nothing lawful exists (no allowlisted window, or none exposes a title — the
 * KD-5 webview-shell caveat): the state builder renders an empty TITLE and the v3
 * PAGE IDENTITY line carries the URL excerpt instead. Pure, JVM-pinnable — no Android
 * types cross this boundary.
 */
object WindowTitles {

    /** One window's title candidacy (the service's adapter shape — no Android types). */
    data class Candidate(
        val packageName: String,
        val title: String,
        val isFocused: Boolean,
        val isActive: Boolean,
        val isApplicationWindow: Boolean,
    )

    /**
     * The lawful page title of one observation, or "" when no allowlisted window
     * exposes one. [isBrowser] defaults to the observation allowlist's pure decision.
     */
    fun lawfulTitle(
        candidates: List<Candidate>,
        isBrowser: (String) -> Boolean = BrowserPackages::isBrowser,
    ): String {
        val titled = candidates.filter { isBrowser(it.packageName) && it.title.isNotBlank() }
        return titled.firstOrNull { it.isFocused }?.title
            ?: titled.firstOrNull { it.isActive }?.title
            ?: titled.firstOrNull { it.isApplicationWindow }?.title
            .orEmpty()
    }
}
