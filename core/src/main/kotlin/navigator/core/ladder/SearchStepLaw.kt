package navigator.core.ladder

/**
 * V2 S1 — the INTERMEDIATE-CONDITION law (FR-28, AC-S1.5): the search-oscillation fix.
 *
 * The live phone UAT (2026-11-13) surfaced the defect this law closes: the writer pinned
 * a SEARCH step's done-condition as a URL ("the results page is showing" → a
 * google.com/search URL condition). The step verified, then the loop's lawful NEXT click
 * — opening a result — navigated away, the condition went false, the prefix audit rolled
 * the step back, and the re-plan re-ran the same search. The task oscillated until the
 * owner stopped it.
 *
 * The law: a NON-FINAL step whose outcome is SEARCHING may not pin a URL condition
 * ([DoneCondition.UrlHost] / [DoneCondition.UrlContains]). A search step's work product
 * is the CONTENT it found — the results being on the page — not the address bar, which
 * the very next lawful click invalidates. The writer is told to pin content
 * (markerText / elementPresent / count / answerExtracted); this validator is the code
 * backstop, and the bounded error-fed retry re-asks with exactly this reason.
 *
 * Detection is deliberately fail-strict: any non-final step that reads as a search step
 * and carries a URL condition is refused, because the FIX is always lawful — a content
 * condition is never worse than the URL it replaces. False positives (a "Google Docs"
 * step, say) cost the writer one re-worded condition; false negatives re-open the
 * oscillation. Engine/verb vocabulary is token-matched, so "research" does not read as
 * "search".
 */
object SearchStepLaw {

    /** Search-engine tokens: an engine named in the instruction makes the step a search. */
    private val ENGINE_TOKENS = setOf(
        "google", "bing", "duckduckgo", "ecosia", "startpage", "yahoo", "qwant", "brave",
    )

    /** Search verbs (EN + FI); phrases are matched on the normalized text, words by token. */
    private val SEARCH_WORD_TOKENS = setOf("search", "etsi", "hae", "googled", "googling")
    private val SEARCH_PHRASES = listOf("search engine", "look up", "lookup", "haku", "results page", "results list")

    /**
     * Entry verbs: a step that LEADS with one is opening a site/page first. Such a step
     * may still search afterwards ("open shop.example and search the catalog") — that is
     * site entry, not search pinning.
     */
    private val ENTRY_VERBS = listOf("open", "go to", "goto", "visit", "load", "navigate to", "navigoi", "avaa")

    /**
     * True when [instruction] reads as a SEARCH step — an engine named, or a search verb.
     * Token-matched on normalized text ("research" is not "search").
     */
    fun isSearchStep(instruction: String): Boolean {
        val normalized = normalize(instruction)
        if (normalized.isBlank()) return false
        if (SEARCH_PHRASES.any { normalized.contains(it) }) return true
        val tokens = normalized.split(' ')
        if (tokens.any { it in SEARCH_WORD_TOKENS }) return true
        return tokens.any { it in ENGINE_TOKENS }
    }

    /**
     * The violation text when [subtask] breaks the law (a search step pinned by URL),
     * or null when the subtask is lawful against THIS law. The caller supplies the
     * finality context: only a non-final step can violate (the final step's URL identity
     * is the destination itself — nothing lawful navigates past it).
     *
     * The named-site ENTRY exception: a non-final search-shaped step whose condition is
     * [DoneCondition.UrlHost] — the instruction LEADS with an entry verb, the condition's
     * host is NAMED in the instruction, and that host is NOT a search engine — is site
     * entry ("open shop.example and search the catalog"), not search pinning: intra-site
     * navigation keeps the host true, so the condition cannot oscillate the way a results
     * URL does. [DoneCondition.UrlContains] is NEVER exempted — it pins a specific page,
     * exactly the shape the next lawful click invalidates.
     */
    fun urlConditionViolation(subtask: Subtask, isFinal: Boolean): String? {
        if (isFinal) return null
        val done = subtask.done
        val isUrl = done is DoneCondition.UrlHost || done is DoneCondition.UrlContains
        if (!isUrl) return null
        if (!isSearchStep(subtask.instruction)) return null
        if (done is DoneCondition.UrlHost) {
            val normalizedInstruction = normalize(subtask.instruction)
            val normalizedHost = normalize(done.host)
            val entryVerb = ENTRY_VERBS.any { normalizedInstruction.startsWith(it) }
            val hostNamed = normalizedHost.isNotBlank() && normalizedInstruction.contains(normalizedHost)
            val engineHost = normalizedHost.split(' ').any { it in ENGINE_TOKENS }
            if (entryVerb && hostNamed && !engineHost) return null
        }
        return (
            "a non-final SEARCH step may not carry a URL condition — the lawful next click " +
                "navigates away and re-invalidates the step (search oscillation); pin page " +
                "CONTENT instead (markerText / elementPresent / count / answerExtracted)"
            )
    }

    /** Lowercase; every non-letter/digit becomes a space; collapsed. Same family as GoalFidelity. */
    private fun normalize(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .replace(Regex("""\s+"""), " ").trim()
}
