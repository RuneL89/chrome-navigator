package navigator.app.loop

/**
 * Cold-start (vision 04 §1 — law): before TRIAGE and the first OBSERVE, deterministic
 * CODE decides whether the browser must be opened at the goal-derived start URL —
 * when the browser is absent, or present on an UNRELATED page. NO engine is ever
 * consulted for opening the browser: this file takes no `DecisionEngine` and runs
 * before the loop's first engine call. The derivation and the decision are logged.
 *
 * The start URL is derived from the goal text by code helpers — an explicit URL, then
 * a bare domain — falling back to the caller's scripted start URL when the goal names
 * nothing. No site names are hardcoded anywhere (vision 01 non-goals): hosts come from
 * the goal (or the harness goal's own start URL), never from this code.
 *
 * The "unrelated page" judgment is CODE: the current URL's host vs the derived start
 * URL's host — never a model call. Pure JVM; the Android edges ([BrowserProbe],
 * [BrowserLauncher]) sit behind interfaces so Gate 8D.2 runs Robolectric-free.
 */

/** The deterministic start-URL derivation for one goal. */
data class DerivedStart(
    val url: String,
    /** GOAL_URL | GOAL_DOMAIN | SCRIPTED_START — logged provenance of the derivation. */
    val source: String,
)

/** The browser's observed state at cold-start time (null = the browser is absent). */
data class BrowserState(
    /** The allowlisted browser package, when one is installed. */
    val packageName: String,
    /**
     * The browser's current URL as code can read it, or null when it is hidden,
     * unreadable, or the browser is not showing a page — an unreadable current page
     * cannot prove same-site, so the decision treats it as unrelated.
     */
    val url: String?,
)

/** What cold-start decided (and why — the reason is logged verbatim). */
sealed interface ColdStartDecision {
    /** Fire the VIEW intent for the derived start URL. */
    data class Launch(val reason: String, val url: String) : ColdStartDecision

    /** Browser already at the derived start site (or nothing to derive) — no intent. */
    data class NoLaunch(val reason: String) : ColdStartDecision
}

/** The logged cold-start record (rides the step log / run report). */
data class ColdStartRecord(
    /** The derivation provenance line, when a start URL was derived. */
    val derivation: String?,
    /** LAUNCH | NO_LAUNCH. */
    val decision: String,
    /** The reason string of the decision. */
    val reason: String,
    /** The step-log lines cold-start produced, in order. */
    val lines: List<String>,
)

/**
 * The probe edge: the browser's current state, or null when no allowlisted browser is
 * installed/observable (Chrome absent). Implemented Android-side over the observation
 * service; fakes in tests.
 */
fun interface BrowserProbe {
    fun probe(): BrowserState?
}

/**
 * The launcher edge: fire the platform VIEW intent for [url] (one call, logged by the
 * caller). Implemented Android-side with `ACTION_VIEW`; fakes in tests.
 */
fun interface BrowserLauncher {
    fun launch(url: String)
}

object ColdStart {

    /** Explicit URL in the goal text ("book a table at https://…"). */
    private val GOAL_URL = Regex("""https?://[^\s"'<>]+""")

    /** A bare domain in the goal text ("… on example.com"); common generic TLDs only. */
    private val GOAL_DOMAIN = Regex(
        """(?:^|[\s"'])(([A-Za-z0-9][A-Za-z0-9-]*(?:\.[A-Za-z0-9][A-Za-z0-9-]*)*)\.(com|net|org|edu|gov|io|co)(/[^\s"'<>]*)?)(?=$|[\s"'.!?,:;])""",
    )

    private val COMMON_TLDS = setOf("com", "net", "org", "edu", "gov", "io", "co")

    /**
     * Derives the goal's start URL (deterministic, in priority order): an explicit
     * http(s) URL in the goal; else a bare domain in the goal; else the caller's
     * scripted start URL; null when neither exists (nothing to open — logged).
     */
    fun derive(goal: String, scriptedStartUrl: String?): DerivedStart? {
        GOAL_URL.find(goal)?.let {
            return DerivedStart(it.value.trimEnd('.', ',', ')', ']'), "GOAL_URL")
        }
        GOAL_DOMAIN.find(goal)?.let {
            // Group 2 = the domain's labels sans TLD, group 3 = the TLD, group 4 = path.
            val host = "${it.groupValues[2]}.${it.groupValues[3]}".lowercase()
            if (it.groupValues[3].lowercase() in COMMON_TLDS) {
                return DerivedStart("https://$host${it.groupValues[4]}", "GOAL_DOMAIN")
            }
        }
        if (!scriptedStartUrl.isNullOrBlank()) {
            return DerivedStart(scriptedStartUrl, "SCRIPTED_START")
        }
        return null
    }

    /**
     * The cold-start decision (vision 04 §1): absent browser → launch; browser already
     * on the derived start host → no-op; anything else (unrelated page, unreadable
     * URL) → launch. Nothing here consults an engine.
     */
    fun decide(derived: DerivedStart?, browser: BrowserState?): ColdStartDecision {
        if (derived == null) {
            return ColdStartDecision.NoLaunch("no start URL is derivable from the goal")
        }
        if (browser == null) {
            return ColdStartDecision.Launch("browser absent", derived.url)
        }
        val current = browser.url
        if (current != null && sameHost(current, derived.url)) {
            return ColdStartDecision.NoLaunch(
                "browser ${browser.packageName} is already on the derived start host",
            )
        }
        return ColdStartDecision.Launch(
            if (current == null) {
                "browser ${browser.packageName} is present but not on a readable page"
            } else {
                "browser ${browser.packageName} is on an unrelated page"
            },
            derived.url,
        )
    }

    /**
     * One cold-start run: derive, probe, decide, fire. Returns the record whose lines
     * the caller logs (the derivation and the decision are ALWAYS logged).
     */
    fun run(
        goal: String,
        scriptedStartUrl: String?,
        probe: BrowserProbe,
        launcher: BrowserLauncher,
    ): ColdStartRecord {
        val derived = derive(goal, scriptedStartUrl)
        val lines = mutableListOf<String>()
        if (derived == null) {
            lines.add("cold-start: no start URL derivable from the goal - no VIEW intent")
            return ColdStartRecord(null, "NO_LAUNCH", "no start URL is derivable from the goal", lines)
        }
        lines.add("cold-start: start URL ${derived.url} (derived ${derived.source})")
        val browser = probe.probe()
        return when (val decision = decide(derived, browser)) {
            is ColdStartDecision.Launch -> {
                lines.add("cold-start: firing VIEW intent for ${decision.url} (${decision.reason})")
                launcher.launch(decision.url)
                ColdStartRecord(lines.first(), "LAUNCH", decision.reason, lines)
            }
            is ColdStartDecision.NoLaunch -> {
                lines.add("cold-start: no VIEW intent (${decision.reason})")
                ColdStartRecord(lines.first(), "NO_LAUNCH", decision.reason, lines)
            }
        }
    }

    /**
     * The code-only "same site" judgment: host equality of the current page and the
     * derived start URL (scheme-, port-, and leading-`www.`-insensitive; case-folded).
     * A null/blank current URL is never same-site.
     */
    fun sameHost(currentUrl: String, startUrl: String): Boolean {
        val current = hostOf(currentUrl) ?: return false
        val start = hostOf(startUrl) ?: return false
        return current == start
    }

    /** Lenient host extraction: no URI parser, just deterministic string surgery. */
    private fun hostOf(url: String): String? {
        var rest = url.trim().lowercase()
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd >= 0) rest = rest.substring(schemeEnd + 3)
        rest = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (rest.contains('@')) rest = rest.substringAfter('@')
        rest = rest.substringBefore(':') // port
        if (rest.startsWith("www.")) rest = rest.removePrefix("www.")
        return rest.takeIf { it.isNotBlank() && it.contains('.') }
    }
}
