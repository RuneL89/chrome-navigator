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

    /** One site-entry plan: the mechanism plus the resolved URL (never null for VIEW_INTENT). */
    data class Plan(val mechanism: Mechanism, val url: String, /** Derivation provenance. */ val source: String)

    /**
     * The plan for opening [subtaskInstruction] under [goal], or null when this is NOT a
     * site-entry subtask (the done-condition must be `urlHost`) or no URL resolves.
     * Deterministic: same inputs, same plan.
     */
    fun plan(goal: String, subtaskInstruction: String, doneCondition: navigator.core.ladder.DoneCondition): Plan? {
        if (doneCondition !is navigator.core.ladder.DoneCondition.UrlHost) return null
        resolveFromText(goal, subtaskInstruction)?.let {
            return Plan(Mechanism.VIEW_INTENT, it, "GOAL_URL")
        }
        // The done-condition's own host: the writer validated it as a bare host, so it
        // is the lawful resolution of last resort (still goal text, still no hardcoding).
        if (doneCondition.host.isNotBlank() && doneCondition.host.contains('.')) {
            return Plan(Mechanism.VIEW_INTENT, "https://${doneCondition.host}", "DONE_CONDITION_HOST")
        }
        return null
    }

    /**
     * The URL the search space (goal + subtask instruction) RESOLVES TO, shape-independent
     * (Phase 10C v1.7.0, doc 04 §1): an explicit http(s) URL first, then a bare domain with
     * a common TLD (optionally carrying its path). Null when nothing resolves. The
     * condition-honesty gate uses this to ask whether a subtask NAMES a page to arrive at,
     * and — when the named page differs from the page in front of us — to derive a
     * `urlContains` URL identity for it. Pure; the derivation order mirrors [plan].
     */
    fun resolveFromText(goal: String, subtaskInstruction: String): String? {
        val searchSpace = "$goal \n $subtaskInstruction"
        ColdStartGoalUrl.find(searchSpace)?.let {
            return it.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':')
        }
        ColdStartGoalDomain.find(searchSpace)?.let { match ->
            val tld = match.groupValues[3].lowercase()
            if (tld in COMMON_TLDS) {
                val host = "${match.groupValues[2]}.${match.groupValues[3]}".lowercase()
                return "https://$host${match.groupValues[4]}"
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
