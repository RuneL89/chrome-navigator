package navigator.core.ladder

/**
 * The code-side IS-DONE checker (vision 04 §1/§3 v1.4.0 — law, ART-4): verifies a
 * subtask's [DoneCondition] against CODE-READ facts only. The ladder's own claim of
 * completion is never evidence; neither is the engine's noul alone — a subtask is done
 * only when the engine noul check passes AND this checker holds (the loop owns the
 * pairing; the checker owns the code half).
 *
 * Evidence is what the loop already reads deterministically: the URL-bar text, the page
 * title, the serialized page lines + the content excerpt (all page text — untrusted
 * input, matched as data), the candidate labels, and the code-owned working memory.
 * Pure JVM, deterministic, no I/O.
 */
object DoneConditionChecker {

    /**
     * The working-memory key the loop populates with the multi-target tracker's
     * PROCESSED count: a `count(target, n)` condition with no per-target named counter
     * reads this (all count-shaped conditions on one ladder share the tracker's count —
     * the tracker IS the task working memory, vision 04 §1 v1.4.0).
     */
    const val WORKING_MEMORY_KEY = "processed"

    /** The code-read facts one verification judges (all optional — a missing fact fails the condition, never defaults true). */
    data class Evidence(
        /** The URL-bar read (null = unreadable — a urlHost condition can never verify). */
        val url: String? = null,
        val title: String = "",
        /** Page text lines (serialized labels + content excerpt) — untrusted, matched as data. */
        val pageText: List<String> = emptyList(),
        /** The step's enumerable candidate labels ([ElementPresent]'s matching scope). */
        val candidateLabels: List<String> = emptyList(),
        /** Working memory: named counters ([CountReached] reads its target, else [WORKING_MEMORY_KEY]). */
        val counters: Map<String, Int> = emptyMap(),
        /**
         * The code-owned extraction record (v1.7.0, Phase 10C): answers PRODUCED for the
         * active report subtask ([AnswerExtracted] reads this — never page content).
         */
        val answersProduced: Int = 0,
    )

    /** Whether [condition] is satisfied by [evidence] — deterministic, side-effect-free. */
    fun satisfied(condition: DoneCondition, evidence: Evidence): Boolean = when (condition) {
        is DoneCondition.UrlHost -> {
            val current = hostOf(evidence.url) ?: return false
            current == normalizeHost(condition.host)
        }

        is DoneCondition.UrlContains -> {
            // v1.7.0 (Phase 10C): the URL-identity shape for a different page on the SAME
            // host. Both sides normalize identically (scheme/userinfo/port/www./trailing
            // slash stripped, lowercased), then the URL must CONTAIN the segment as data.
            val current = normalizeUrl(evidence.url) ?: return false
            val segment = normalizeUrl(condition.segment) ?: return false
            segment.isNotEmpty() && current.contains(segment) &&
                // Post-UAT repair 2 (doc 04 §1 v1.8.2): the landing coherence — being ON the
                // page the segment names requires the page's own identity to agree. A site's
                // "Not Found" page keeps the requested URL (so the substring reads TRUE)
                // while its title belongs to the error page: the battery's allrecipes run
                // reported GOAL_ACHIEVED on a 404 through exactly that gap. A non-blank title
                // sharing no token with the segment's LAST path slug refuses; a blank title
                // is no evidence (coheres — conservative-on-no-information).
                coheresWithPathSlug(condition.segment, evidence.title)
        }

        is DoneCondition.MarkerText -> {
            val marker = condition.text.trim().lowercase()
            if (marker.isEmpty()) return false
            evidence.title.lowercase().contains(marker) ||
                evidence.pageText.any { it.lowercase().contains(marker) }
        }

        is DoneCondition.ElementPresent ->
            evidence.candidateLabels.any { matchesDescription(it, condition.description) }

        is DoneCondition.AnswerExtracted -> evidence.answersProduced >= 1

        is DoneCondition.CountReached -> {
            val count = evidence.counters[condition.target]
                ?: evidence.counters[WORKING_MEMORY_KEY]
                ?: 0
            count >= condition.n
        }
    }

    /**
     * The guard level a satisfied condition earns (the exit law's code-evidence half,
     * vision 04 §3 v1.7.0): STRONG for the evidence that is NOT a page-content proxy —
     * `urlHost`/`urlContains` are PAGE IDENTITY (URL identity), and `count` /
     * `answerExtracted` read CODE-OWNED state (the target tracker's processed count; the
     * loop's extraction record — deterministic code state, never a page mention). WEAK for the content-reading shapes
     * (`markerText`/`elementPresent`): the on-device battery showed these silently
     * accepting conditions already true where the subtask started. An unsatisfied
     * condition is NONE (the caller maps it; the checker only speaks for satisfied ones).
     */
    fun guardStrength(condition: DoneCondition): GuardStrength = when (condition) {
        is DoneCondition.UrlHost -> GuardStrength.STRONG
        is DoneCondition.UrlContains -> GuardStrength.STRONG
        is DoneCondition.CountReached -> GuardStrength.STRONG
        is DoneCondition.AnswerExtracted -> GuardStrength.STRONG
        else -> GuardStrength.WEAK
    }

    enum class GuardStrength { WEAK, STRONG }

    /** One-line plain-words render (the state text's ACTIVE SUBTASK section, cards, logs). */
    fun render(condition: DoneCondition): String = when (condition) {
        is DoneCondition.UrlHost -> "the URL host is ${condition.host}"
        is DoneCondition.UrlContains -> "the address contains '${condition.segment}'"
        is DoneCondition.MarkerText -> "the page contains '${condition.text}'"
        is DoneCondition.ElementPresent -> "an element matching '${condition.description}' is present"
        is DoneCondition.CountReached -> "${condition.target} >= ${condition.n}"
        is DoneCondition.AnswerExtracted -> "the answer has been produced"
    }

    /**
     * The deterministic candidate-label matcher ([ElementPresent]): the description's
     * token run appearing in the label, OR the label's token run appearing in the
     * description (the LLM describes "Subscribe button"; the page label is "Subscribe"
     * — both directions are deterministic full-token containment, never substrings), OR
     * >= 0.9 token overlap — the same deterministic discipline as the STRONG identity
     * matcher (no model, no fuzzier).
     */
    fun matchesDescription(label: String, description: String): Boolean {
        val labelTokens = tokens(label)
        val descTokens = tokens(description)
        if (descTokens.isEmpty() || labelTokens.isEmpty()) return false
        if (containsConsecutiveRun(labelTokens, descTokens)) return true
        if (containsConsecutiveRun(descTokens, labelTokens)) return true
        val labelSet = labelTokens.toSet()
        val overlap = descTokens.toSet().count { it in labelSet }.toDouble() / descTokens.toSet().size
        return overlap >= STRONG_OVERLAP
    }

    /** The law's fuzzy bound (vision 04 §3: ">= 0.9 token-overlap"). */
    private const val STRONG_OVERLAP = 0.9

    private fun tokens(text: String): List<String> =
        text.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .split(' ').filter { it.isNotBlank() }

    private fun containsConsecutiveRun(textTokens: List<String>, needle: List<String>): Boolean {
        if (textTokens.size < needle.size) return false
        var start = 0
        while (start <= textTokens.size - needle.size) {
            if (textTokens.subList(start, start + needle.size) == needle) return true
            start += 1
        }
        return false
    }

    /**
     * Lenient host extraction (the cold-start discipline): scheme/userinfo/port/www.
     * stripped, lowercased; null for blank/non-host text (an unreadable URL never
     * satisfies a urlHost condition).
     */
    internal fun hostOf(url: String?): String? {
        if (url == null) return null
        var rest = url.trim().lowercase()
        if (rest.isEmpty()) return null
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd >= 0) rest = rest.substring(schemeEnd + 3)
        rest = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (rest.contains('@')) rest = rest.substringAfter('@')
        rest = rest.substringBefore(':') // port
        if (rest.startsWith("www.")) rest = rest.removePrefix("www.")
        return rest.takeIf { it.isNotBlank() && it.contains('.') }
    }

    private fun normalizeHost(host: String): String =
        host.trim().lowercase().removePrefix("www.").trimEnd('.')

    /**
     * The landing coherence (post-UAT repair 2, doc 04 §1 v1.8.2): a `urlContains`
     * segment that carries a PATH names a specific page; the landed page's TITLE must
     * share at least one token with that path's LAST slug. No path (a bare host segment)
     * has nothing to verify; a blank title is no evidence and coheres; a non-blank title
     * with zero overlap refuses (the site's "Not Found" page shape). Pure, deterministic.
     */
    fun coheresWithPathSlug(segment: String, landedTitle: String): Boolean {
        val path = segment.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val slug = path.split('/').lastOrNull { it.isNotBlank() } ?: return true
        if (landedTitle.isBlank()) return true
        val slugTokens = NON_SLUG_SPLIT.split(slug.lowercase()).filter { it.isNotEmpty() }
        if (slugTokens.isEmpty()) return true
        val titleTokens = NON_SLUG_SPLIT.split(landedTitle.lowercase()).toSet()
        return slugTokens.any { it in titleTokens }
    }

    private val NON_SLUG_SPLIT = Regex("[^a-z0-9]+")

    /**
     * Whether two hosts belong to ONE SITE FAMILY (Phase 10C, doc 04 §1 v1.7.0): the
     * landing-host reconciliation amends a `urlHost` condition to the host a code
     * navigation actually reached when the difference is a benign alias — the same host,
     * a subdomain/apex pair (wikipedia.org / en.wikipedia.org), or a same-brand
     * ccTLD/apex pair (the observed bbc.co.uk → bbc.com redirect). A genuinely different
     * site (example.com vs other.com) never reconciles.
     */
    fun sameSiteFamily(conditionHost: String, landedHost: String): Boolean {
        val x = normalizeUrl(conditionHost)?.substringBefore('/') ?: return false
        val y = normalizeUrl(landedHost)?.substringBefore('/') ?: return false
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        if (x.endsWith(".$y") || y.endsWith(".$x")) return true
        // ccTLD/apex family: the same brand label with different TLD suffixes (bbc.com /
        // bbc.co.uk) — never for two hosts sharing a suffix (news.example.com / news.other.com).
        return x.substringBefore('.') == y.substringBefore('.') &&
            x.substringAfterLast('.') != y.substringAfterLast('.')
    }

    /**
     * The URL-identity normalization shared by BOTH sides of a [DoneCondition.UrlContains]
     * match (v1.7.0, Phase 10C): scheme, userinfo, port and a leading `www.` stripped,
     * trailing slashes trimmed, lowercased — the PATH survives, because the path is the
     * identity that distinguishes two pages on one host. Null for blank input (an
     * unreadable URL never satisfies a URL condition).
     */
    fun normalizeUrl(url: String?): String? {
        if (url == null) return null
        var rest = url.trim().lowercase()
        if (rest.isEmpty()) return null
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd >= 0) rest = rest.substring(schemeEnd + 3)
        rest = rest.substringBefore('#')
        if (rest.contains('@')) rest = rest.substringAfter('@')
        val slashIdx = rest.indexOf('/')
        val authority = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
        val path = if (slashIdx >= 0) rest.substring(slashIdx) else ""
        val hostPart = authority.substringBefore(':').removePrefix("www.")
        return (hostPart + path).trimEnd('/').takeIf { it.isNotEmpty() }
    }
}
