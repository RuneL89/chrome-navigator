package navigator.app.loop

/**
 * Outcome-based site entry (Phase 10, vision 04 §1 v1.4.0): a subtask whose
 * done-condition is `urlHost(h)` and whose instruction names opening a site is satisfied
 * BY CODE through the best mechanism — a VIEW intent when the URL is resolved, URL-bar
 * entry otherwise. Deterministic + logged; this folds the old cold-start under the
 * ladder (the same derivation helpers, one decision: [plan]).
 *
 * Mechanism law:
 * - [Mechanism.VIEW_INTENT] — a full URL was resolved from the goal/subtask text (an
 *   explicit http(s) URL, or a bare domain the derivation can scheme): the browser is
 *   opened/navigated with the platform VIEW intent through the loop's existing
 *   [BrowserLauncher] seam (the cold-start mechanism, byte-for-byte the same edge).
 * - [Mechanism.URL_BAR_ENTRY] — no URL resolved (the instruction names only a host the
 *   derivation cannot trust, or nothing at all): the code types nothing and invents
 *   nothing — there is no lawful URL to enter, so the plan is null and the ENGINE
 *   navigates normally (the active subtask still scopes its objective; ART-2 intact).
 *   A resolvable host ALWAYS resolves (https://host), so URL-bar entry in v1 executes
 *   only through the engine — the code-authored typing of the omnibox adds a fragile
 *   gesture path with no verified win; the plan records the honest null instead.
 *
 * The plan chooser is PURE and JVM-pinned (Gate 10.2); execution + logging live in the
 * loop. No site names are hardcoded anywhere (vision 01 non-goals).
 */
object SiteEntry {

    /** The chosen mechanism (logged on the step log, vision 04 §1 "deterministic, logged"). */
    enum class Mechanism { VIEW_INTENT, URL_BAR_ENTRY }

    /** One text-derived URL plus its derivation provenance (Phase 10C). */
    data class Resolved(val url: String, val source: String)

    /** One site-entry plan: the mechanism plus the resolved URL (never null for VIEW_INTENT). */
    data class Plan(val mechanism: Mechanism, val url: String, /** Derivation provenance. */ val source: String)

    /**
     * The plan for opening [subtaskInstruction] under [goal], or null when this is NOT a
     * site-entry subtask or no URL resolves. Deterministic: same inputs, same plan.
     *
     * POST-UAT REPAIR 2 (doc 04 §1 v1.8.2, 2026-10-13): a `urlContains` identity whose
     * segment NAMES a page is the writer's arrival expressed as a substring (the 10C
     * honesty gate derives such identities itself) — it site-enters exactly like a
     * `urlHost` arrival. The live battery showed the engine choosing `open-site`
     * decisively (p 0.59–0.82) on such subtasks while the derivation refused, failing
     * steps whose destination the ladder itself named.
     *
     * **The naming guard (same amendment, the zero-false-completions bar):** the
     * destination must be named by the HUMAN-READABLE plan text ([goal] or
     * [subtaskInstruction]) — never by the machine condition alone. A condition is a
     * proxy: "the address contains 'google.com/search'" is the writer's shorthand for
     * "the results are showing", and CODE-NAVIGATING to the bare endpoint would satisfy
     * the letter of the condition without the user-visible outcome (no query, no
     * results) — a false completion. A destination the plan text itself names ("Open the
     * BBC News section page at bbc.com/news") is the arrival the writer meant.
     */
    fun plan(
        goal: String,
        subtaskInstruction: String,
        doneCondition: navigator.core.ladder.DoneCondition,
        currentUrl: String = "",
    ): Plan? = when (doneCondition) {
        is navigator.core.ladder.DoneCondition.UrlHost -> {
            resolveFromText(goal, subtaskInstruction)?.let {
                Plan(Mechanism.VIEW_INTENT, it.url, it.source)
            } ?: run {
                // The done-condition's own host: the writer validated it as a bare host, so
                // it is the lawful resolution of last resort (still goal text, still no
                // hardcoding).
                if (doneCondition.host.isNotBlank() && doneCondition.host.contains('.')) {
                    Plan(Mechanism.VIEW_INTENT, "https://${doneCondition.host}", "DONE_CONDITION_HOST")
                } else {
                    null
                }
            }
        }
        is navigator.core.ladder.DoneCondition.UrlContains ->
            urlFromIdentity(goal, subtaskInstruction, doneCondition.segment, currentUrl)?.let {
                Plan(Mechanism.VIEW_INTENT, it.url, it.source)
            }
        else -> null
    }

    /**
     * The URL a `urlContains` identity names, when the human plan text names it too
     * (v1.8.2): a full http(s) URL, or a bare host[/path] the derivation can scheme —
     * the segment must literally appear in the goal or the instruction text (the naming
     * guard). An origin-relative path ("/wiki/X") additionally resolves against the
     * CURRENT page's origin, lawful only when that host is one site family with the
     * goal's named host. Null otherwise — a phrase like "2 hrs ago" is never a URL, and
     * a condition-only destination never site-enters. Pure.
     */
    fun urlFromIdentity(
        goal: String,
        subtaskInstruction: String,
        rawSegment: String,
        currentUrl: String,
    ): Resolved? {
        val segment = rawSegment.trim().trim('"', '\'', '`')
        if (segment.isEmpty() || segment.any { it.isWhitespace() }) return null
        // The naming guard: the destination must be named by the plan's own words.
        val searchSpace = "$goal \n $subtaskInstruction"
        if (!searchSpace.contains(segment)) return null
        if (segment.startsWith("http://") || segment.startsWith("https://")) {
            return Resolved(segment, "DONE_CONDITION_URL")
        }
        if (segment.startsWith("/")) {
            val origin = ORIGIN.find(currentUrl.trim())?.value ?: return null
            val currentHost = hostOf(currentUrl) ?: return null
            val goalHost = resolveFromText(goal, "")?.url?.let(::hostOf) ?: return null
            if (!navigator.core.ladder.DoneConditionChecker.sameSiteFamily(goalHost, currentHost)) {
                return null
            }
            return Resolved("$origin$segment", "DONE_CONDITION_PATH")
        }
        val hostPart = segment.substringBefore('/').substringBefore('?').substringBefore('#')
        val lastLabel = hostPart.substringAfterLast('.', "")
        if (HOST_SHAPE.matches(hostPart) && lastLabel.length >= 2 && lastLabel.all { it.isLetter() }) {
            return Resolved("https://$segment", "DONE_CONDITION_DOMAIN")
        }
        return null
    }

    /** The normalized host of a page URL, or null. */
    private fun hostOf(url: String): String? =
        navigator.core.ladder.DoneConditionChecker.normalizeUrl(url)?.substringBefore('/')

    /** A hostname shape: labels of alphanumerics/hyphens joined by dots (no spaces). */
    private val HOST_SHAPE = Regex(
        "[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+",
    )
    private val ORIGIN = Regex("^https?://[^/\\s]+")

    /**
     * The URL the search space (goal + subtask instruction) RESOLVES TO, shape-independent
     * (Phase 10C v1.7.0, doc 04 §1): an explicit http(s) URL first, then a bare domain with
     * a common TLD (optionally carrying its path). Null when nothing resolves. The
     * condition-honesty gate uses this to ask whether a subtask NAMES a page to arrive at,
     * and — when the named page differs from the page in front of us — to derive a
     * `urlContains` URL identity for it. Pure; the derivation order mirrors [plan].
     */
    fun resolveFromText(goal: String, subtaskInstruction: String): Resolved? {
        val searchSpace = "$goal \n $subtaskInstruction"
        ColdStartGoalUrl.find(searchSpace)?.let {
            return Resolved(it.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':'), "GOAL_URL")
        }
        ColdStartGoalDomain.find(searchSpace)?.let { match ->
            val tld = match.groupValues[3].lowercase()
            if (tld in COMMON_TLDS) {
                val host = "${match.groupValues[2]}.${match.groupValues[3]}".lowercase()
                return Resolved("https://$host${match.groupValues[4]}", "GOAL_DOMAIN")
            }
        }
        return null
    }

    // The cold-start derivation's pinned shapes (local copies — this file stays pure and
    // the :app loop's ColdStart regexes are private).
    private val ColdStartGoalUrl = Regex("""https?://[^\s"'<>]+""")
    private val ColdStartGoalDomain = Regex(
        """(?:^|[\s"'])(([A-Za-z0-9][A-Za-z0-9-]*(?:\.[A-Za-z0-9][A-Za-z0-9-]*)*)\.(com|net|org|edu|gov|io|co)(/[^\s"'<>]*)?)(?=$|[\s"'.!?,:;])""",
    )
    private val COMMON_TLDS = setOf("com", "net", "org", "edu", "gov", "io", "co")
}
