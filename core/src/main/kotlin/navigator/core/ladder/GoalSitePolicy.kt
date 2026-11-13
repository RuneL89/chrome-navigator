package navigator.core.ladder

/**
 * PHASE_18 Stage 18.D CN-14 (doc 04 §9.4/§9.9, ratified law) — the DETERMINISTIC
 * SITE-RESOLUTION POLICY consumed by plan validation. One policy, one law set; the
 * validation integration points are `GoalContract.validateInstall` (the initial
 * install) and the writer's error-fed retry on the ENTRY plan (the same violations
 * ride the correction) — this file owns the DECISIONS, never a second validation path.
 *
 * ENTRY-LAW SCOPING (measured against the Phase-16 strategy law): the site law is how
 * a run ENTERS its site. After entries have failed, the strategy law ("change the
 * APPROACH — a different site, route or control class") may lawfully route a re-plan
 * THROUGH a search, so the policy judges the entry plan only; `GoalFidelity`'s own
 * site-first rule keeps enforcing search-first for site-agnostic goals at EVERY
 * planning event, and the re-plan contract check stays the parameter binding.
 *
 * The laws (CN-14):
 * - **(a) A goal that names a site keeps it.** The named-site binding stays through
 *   site-entry: a search detour injected BEFORE the named site (a first subtask that
 *   is search-shaped AND binds a destination outside the named site's family) is
 *   refused — the owner already chose the source.
 * - **(b) A site-agnostic goal whose task requires choosing a source/store must START
 *   WITH SEARCH.** A store-first ladder (its first destination-binding subtask names a
 *   specific store host with no search shape) is refused. The `GoalFidelity` site-first
 *   rule (§4.2) is the broader writer-side law (every site-agnostic goal starts with a
 *   search); this policy carries the store-first half at the contract boundary — it is
 *   deliberately NARROWER than `GoalFidelity`'s rule, so the two can never disagree.
 * - **(c) The selected destination host is RECORDED in runtime state** once entered —
 *   a thin VisionLoop seam reads [destinationHostOf] and keeps the run record (the
 *   policy stays pure; the loop owns the record).
 * - **(d) A store must never be inferred solely from the page open at goal entry.**
 *   This is the site-agnostic law above as its enforcement: this policy reads ONLY the
 *   owner's goal text (via the immutable contract) and the plan's own text — it has no
 *   observation input, so a store derived from the page the browser happened to be on
 *   at entry cannot even be expressed. The landing history below records failures; it
 *   never promotes a page into a destination.
 * - **(e) An ambiguous consequential destination (cost/account-material) goes to the
 *   CN-15 clarify path**: the contract's `unmetRequirements` are the ambiguity record
 *   (doc 04 §9.4: a required value that is ambiguous or absent asks the owner, never a
 *   guess); [ambiguousRequirements] exposes them for the loop's clarification request.
 *
 * Landing / failed-entry history (CN-14 part 2): [LandingHistory] is the RUN-SCOPED
 * record of same-URL and failed-landing entries that SURVIVES plan installs (a re-plan
 * is a strategy change, not a new run). Re-evaluation uses the URL PATH and the page
 * CONTENT — never the page TITLE alone (a correct `/videos`-style path with a weak or
 * blank title IS a landing). A ban is not permanent: a BOUNDED fresh-evidence recheck
 * clears an old failure when the page's path/content now establishes the destination,
 * and the clearing is recorded with its evidence.
 *
 * Pure JVM: no Android types, no model calls (ART-2 — code owns the policy), no site
 * names hardcoded (the hosts come only from the goal and the plan's own text).
 */
object GoalSitePolicy {

    /** How the goal's source site resolves (CN-14 a/b). */
    enum class Resolution {
        /** The goal names the site — the named-site binding stays through site-entry. */
        NAMED_SITE,

        /** Site-agnostic + the task requires choosing a source/store — start with search. */
        SEARCH_FIRST,

        /** No store-choice requirement (the ordinary laws apply; no store-first rule). */
        OPEN,
    }

    /**
     * The resolution for [contract]'s goal: NAMED_SITE when the contract carries the
     * NamedSite parameter; SEARCH_FIRST when the goal is site-agnostic and its task
     * requires choosing a source/store (a commerce task — the store is chosen from
     * search results); OPEN otherwise (a read/report goal with no site names no store).
     */
    fun resolution(contract: GoalContract): Resolution = when {
        contract.site != null -> Resolution.NAMED_SITE
        GoalFidelity.GoalClass.PURCHASE in contract.classes -> Resolution.SEARCH_FIRST
        else -> Resolution.OPEN
    }

    /**
     * The plan-validation violations (CN-14 a/b) for the ENTRY plan. Composed into
     * `GoalContract.validateInstall` and the writer's error-fed retry on the entry plan
     * — never a forked validation. Empty = the ladder satisfies the site law.
     */
    fun checkLadder(contract: GoalContract, ladder: Ladder): List<String> {
        if (ladder.subtasks.isEmpty()) return emptyList()
        val first = ladder.subtasks.first()
        val firstDestination = destinationHostOf(first.instruction, first.done)
            ?: return emptyList() // the first subtask binds no destination — nothing to judge
        return when (resolution(contract)) {
            Resolution.NAMED_SITE -> {
                val named = contract.site!!.host
                // (a) no search detour: the first destination is search-shaped AND
                // outside the named site's family — the owner's site must be entered
                // first (the binding stays through site-entry).
                val host = DoneConditionChecker.normalizeUrl(firstDestination)?.substringBefore('/') ?: firstDestination
                val namedHost = DoneConditionChecker.normalizeUrl(named)?.substringBefore('/') ?: named
                val detour = isSearchShaped(first.instruction, first.done) &&
                    !DoneConditionChecker.sameSiteFamily(host, namedHost)
                if (detour) {
                    listOf(
                        "site policy: the goal names $named, so the plan must enter the named " +
                            "site first — the search detour through '$firstDestination' is refused " +
                            "(the named-site binding stays through site-entry)",
                    )
                } else {
                    emptyList()
                }
            }
            Resolution.SEARCH_FIRST -> {
                // (b) store-first refused: the first destination-binding subtask names a
                // specific store host with no search shape. The store comes only from the
                // owner's goal text or a search step — never from the page open at entry.
                if (isSearchShaped(first.instruction, first.done)) {
                    emptyList()
                } else {
                    listOf(
                        "site policy: the goal names no website, so the plan must START WITH " +
                            "SEARCH — navigating straight to '$firstDestination' infers a store " +
                            "the goal never named (a store-first ladder is refused; the store is " +
                            "chosen from the search results)",
                    )
                }
            }
            Resolution.OPEN -> emptyList()
        }
    }

    /**
     * The destination host a subtask BINDS, deterministically: the done-condition's
     * urlHost / a dotted urlContains segment, else a bare domain or URL host in the
     * instruction text. Null = the subtask binds no destination (the conservative read —
     * nothing is ever inferred from a page).
     */
    fun destinationHostOf(instruction: String, done: DoneCondition): String? {
        when (done) {
            is DoneCondition.UrlHost -> return done.host
            is DoneCondition.UrlContains -> {
                val segment = done.segment
                if (segment.contains('.')) return segment.substringBefore('/')
            }
            else -> {}
        }
        val lower = instruction.lowercase()
        GoalFidelity.URL_IN_TEXT.find(lower)?.let { return GoalContract.hostOfUrl(it.value) }
        GoalFidelity.DOMAIN_IN_TEXT.find(lower)?.let { return it.value }
        return null
    }

    /** Whether a subtask is SEARCH-shaped (the GoalFidelity search-first vocabulary). */
    private fun isSearchShaped(instruction: String, done: DoneCondition): Boolean =
        GoalFidelity.searchFirst(
            Ladder(
                summary = "probe",
                subtasks = listOf(Subtask(instruction, done)),
                taskClass = LadderTaskClass.READ,
            ),
        )

    /**
     * (e) The ambiguity record for the CN-15 clarify path: the contract's stated-but-
     * unmet requirements (a required value that is ambiguous or absent asks the owner —
     * doc 04 §9.4/§9.9). Empty when the goal is fully bound.
     */
    fun ambiguousRequirements(contract: GoalContract): List<String> = contract.unmetRequirements

    // -- the landing / failed-entry history (CN-14 part 2) --------------------------------

    /**
     * The RUN-SCOPED landing record: same-URL and failed-landing history that persists
     * ACROSS re-plans (the run keeps it; a plan install never resets it — a re-plan is a
     * strategy change, not a new run; [resetRun] is the only reset).
     *
     * Laws:
     * - [recordFailure] accumulates per URL (same-URL failures count together); at
     *   [banAfterFailures] consecutive failures the URL is BANNED — not re-opened.
     * - Re-evaluation evidence is the URL PATH and the page CONTENT, never the title
     *   alone ([RecheckEvidence]).
     * - [recheck] is the BOUNDED fresh-evidence recheck: at most [maxRechecksPerUrl]
     *   rechecks per banned URL per run; when the fresh evidence establishes the
     *   landing, the ban clears and the clearing is RECORDED ([clearEvents]).
     */
    class LandingHistory(
        private val banAfterFailures: Int = BAN_AFTER_FAILURES,
        private val maxRechecksPerUrl: Int = MAX_RECHECKS_PER_URL,
    ) {

        private class Entry(val url: String) {
            var failures = 0
            var banned = false
            var rechecks = 0
        }

        private val entries = mutableMapOf<String, Entry>()

        /** The recorded ban-clearing events ("what cleared it and on what evidence"). */
        val clearEvents = mutableListOf<String>()

        /** One failed landing / same-URL failure (the reason rides the caller's log). */
        fun recordFailure(url: String, reason: String) {
            val entry = entryFor(url)
            entry.failures += 1
            if (entry.failures >= this.banAfterFailures) {
                entry.banned = true
            }
        }

        /** Whether [url] is currently banned from re-entry. */
        fun isBanned(url: String): Boolean = entries[SiteEntryKey.key(url)]?.banned == true

        /** The accumulated failure count for [url] (the evidence/debug surfaces). */
        fun failures(url: String): Int = entries[SiteEntryKey.key(url)]?.failures ?: 0

        /**
         * The bounded fresh-evidence recheck of a banned landing: [evidence] carries the
         * FRESH page's path/content verdict (path + content — never the title alone).
         * When it establishes the landing, the ban clears, the clearing is recorded
         * with the evidence line, and true is returned; a non-establishing recheck
         * consumes one of the URL's bounded recheck attempts and returns false. A URL
         * that is not banned returns false (nothing to clear).
         */
        fun recheck(url: String, evidence: RecheckEvidence): String? {
            val entry = entries[SiteEntryKey.key(url)] ?: return null
            if (!entry.banned) return null
            if (entry.rechecks >= maxRechecksPerUrl) return null
            entry.rechecks += 1
            if (!evidence.establishes) return null
            entry.banned = false
            val line = "landing recheck: the ban on ${SiteEntryKey.key(url)} cleared - " +
                "${evidence.detail} (fresh evidence; the old failure is recorded, not repeated)"
            clearEvents.add(line)
            return line
        }

        /** The recheck attempts already spent on [url] (the bound's evidence surface). */
        fun rechecksSpent(url: String): Int = entries[SiteEntryKey.key(url)]?.rechecks ?: 0

        /** The currently banned URLs (the re-plan context's landing-history line). */
        fun bannedUrls(): List<String> = entries.values.filter { it.banned }.map { it.url }

        /** A new run: everything restarts (no landing history crosses runs). */
        fun resetRun() {
            entries.clear()
            clearEvents.clear()
        }

        private fun entryFor(url: String): Entry =
            entries.getOrPut(SiteEntryKey.key(url)) { Entry(SiteEntryKey.key(url)) }

        companion object {
            /** The failure count that bans a URL's re-entry (CN-14: bounded, small). */
            const val BAN_AFTER_FAILURES = 2

            /** The bounded fresh-evidence recheck attempts per banned URL per run. */
            const val MAX_RECHECKS_PER_URL = 2
        }
    }

    /**
     * The FRESH-EVIDENCE verdict a landing recheck consumes (CN-14: URL path + selected
     * tab + page CONTENT — never the page TITLE alone). The caller (the loop) computes
     * it from the current observation; the selected tab IS the observed surface in this
     * loop, so its path and content are the tab evidence.
     */
    data class RecheckEvidence(
        /** The URL PATH establishes the landing (e.g. the destination's /videos path). */
        val pathEstablishes: Boolean,
        /** The page CONTENT establishes the landing (identity tokens in the content lines). */
        val contentEstablishes: Boolean,
        /** The evidence line naming what established it (recorded when the ban clears). */
        val detail: String,
    ) {
        val establishes: Boolean get() = pathEstablishes || contentEstablishes
    }

    /**
     * Whether [observedUrl]'s PATH establishes the landing at [destinationUrl] (the
     * non-root path prefix rule — the same normalization discipline as the checker).
     */
    fun pathEstablishes(observedUrl: String, destinationUrl: String): Boolean {
        val observed = DoneConditionChecker.normalizeUrl(observedUrl) ?: return false
        val destination = DoneConditionChecker.normalizeUrl(destinationUrl) ?: return false
        val observedPath = observed.substringAfter('/', "")
        val destinationPath = destination.substringAfter('/', "")
        if (destinationPath.isEmpty()) return false
        return observedPath == destinationPath || observedPath.startsWith("$destinationPath/")
    }

    /**
     * Whether the page CONTENT (the content LINES — not the title) carries the
     * destination's identity tokens (the recorded title's significant tokens; the same
     * >2-char discipline as the recovery controller's tab identification).
     */
    fun contentEstablishes(contentLines: List<String>, destinationTitle: String): Boolean {
        val tokens = destinationTitle.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .split(' ')
            .filter { it.length > 2 }
        if (tokens.isEmpty()) return false
        val content = contentLines.joinToString(" ").lowercase()
        return tokens.all { it in content }
    }
}

/**
 * The landing-history URL key: scheme, case, and trailing-slash insensitive. Query
 * strings are RETAINED (the loop re-opens the identical recorded URL, so key equality
 * is exact today); if query-noise insensitivity is ever needed, normalize HERE ONLY —
 * never in the ban/clear records.
 */
private object SiteEntryKey {
    fun key(url: String): String =
        url.trim().lowercase().removePrefix("https://").removePrefix("http://").removeSuffix("/")
}
