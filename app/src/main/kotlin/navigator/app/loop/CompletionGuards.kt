package navigator.app.loop

import navigator.core.log.TriageClass

/**
 * The code completion guards (vision 04 §3 — law, ART-4): per-triage-class state
 * machines over what CODE observed and executed — never over what the engine reports.
 * Goal-achieved requires the engine's finish choice AND verification through the exit
 * policy ([ExitPolicy]); the guard level is the code-evidence half of every path.
 *
 * Guard levels (vision 04 §3 v1.3.0):
 * - [GuardLevel.NONE] — the class predicate does not hold; no exit path can accept.
 * - [GuardLevel.WEAK] — the per-triage-class contentful predicates: lookup/research
 *   (a non-empty content region read after results loaded), form-fill (submission
 *   observed), action (post-action state observed, [ActionCompletionGuard]),
 *   action-multi (tracker drained, [ActionMultiCompletionGuard]).
 * - [GuardLevel.STRONG] — deterministic target verification: the triage-extracted
 *   [TargetEntity] is matched in the page **title or URL** (exact/fuzzy code match,
 *   no model) — page identity, not a content mention: a mention is not arrival, and a
 *   content-only match never yields STRONG (v1.3.0, owner-ratified after the Test-2′
 *   false GOAL_ACHIEVED on a Related-Articles mention). STRONG is a strict superset of
 *   WEAK: the class predicate must hold too (an entity named on the task's first page
 *   is not completion evidence).
 *
 * A content region is [CONTENT_MIN_TEXT_NODES] or more non-interactive text nodes in
 * one observation (code-countable "the page has body text", vision 04 §3's
 * "non-empty content region").
 */
class CompletionGuards(
    triageClass: TriageClass,
    /** The triage-extracted target entity (null when extraction found none — STRONG
     *  can never fire; WEAK is the ceiling). Extracted once at triage, code-only. */
    private val entity: TargetEntity? = null,
    /** The task working memory for action-multi (vision 04 §1, Phase 08G). REQUIRED
     *  for the ACTION_MULTI class — the Phase-8D vacuous pending=0 interim is replaced:
     *  the WEAK predicate is the REAL tracker's drained state and can fail. */
    private val tracker: TargetTracker? = null,
) {

    private val kind: TriageClass = triageClass

    init {
        require(triageClass != TriageClass.ACTION_MULTI || tracker != null) {
            "action-multi completion guards require the real TargetTracker (vision 04 §1) — " +
                "the vacuous pending=0 interim is gone; a finish attempt with pending targets " +
                "must be refusable"
        }
        require(tracker == null || triageClass == TriageClass.ACTION_MULTI) {
            "a TargetTracker is only meaningful for action-multi guards, was $triageClass"
        }
    }

    /** url -> highest content-text-node count observed there, in first-seen order. */
    private val pages = LinkedHashMap<String, Int>()
    private var firstUrl: String? = null
    private var fieldFilled = false
    private var submittedAfterFill = false

    /** The `action` class's predicate (Phase 08G named guard; vision 04 §3). */
    private val actionGuard = ActionCompletionGuard()

    /** The `action-multi` class's predicate over the REAL tracker (Phase 08G). */
    private val multiGuard: ActionMultiCompletionGuard? = tracker?.let { ActionMultiCompletionGuard(it) }

    /**
     * Seeds the task's start page URL. Call once per OBSERVE (before EXECUTE) — the
     * first observation, not the first post-step page, is the task's origin.
     */
    fun seed(url: String) {
        if (firstUrl == null) firstUrl = url
    }

    /**
     * Records one post-step observation plus what the executor actually did. Call once
     * per executed step, in task order (post-observation, after EXECUTE).
     *
     * @param url the page URL after the step (as read from the URL bar)
     * @param contentTextNodes non-interactive text nodes counted in that observation
     * @param textTyped true when a TYPE action executed successfully this step
     * @param submitExecuted true when a SUBMIT action executed successfully this step
     * @param actionExecuted true when any action executed successfully this step (the
     *   `action` class's post-action-state evidence; defaulted false for the callers
     *   that predate the action classes)
     */
    fun record(
        url: String,
        contentTextNodes: Int,
        textTyped: Boolean,
        submitExecuted: Boolean,
        actionExecuted: Boolean = false,
    ) {
        pages[url] = maxOf(pages[url] ?: 0, contentTextNodes)
        if (textTyped) fieldFilled = true
        if (submitExecuted && fieldFilled) submittedAfterFill = true
        if (actionExecuted) actionGuard.recordActionExecuted()
    }

    /** Whether the task's class-specific completion preconditions hold (the WEAK level). */
    fun satisfied(): Boolean = when (kind) {
        TriageClass.LOOKUP -> readContentfulPages().any { it != firstUrl }
        TriageClass.RESEARCH_MULTI_STEP -> readContentfulPages().size >= 2
        TriageClass.FORM_FILL -> fieldFilled && submittedAfterFill
        TriageClass.ACTION -> actionGuard.satisfied
        TriageClass.ACTION_MULTI -> multiGuard!!.satisfied
    }

    /**
     * The class-specific detail for a refused finish (the refusal must say WHY the
     * guard is NONE — vision 04 §3's evidence law). Null when the predicate holds or
     * the class has no tracker-backed count to name.
     */
    fun refusalDetail(): String? = when (kind) {
        TriageClass.ACTION_MULTI -> multiGuard?.takeIf { !it.satisfied }?.refusalDetail()
        else -> null
    }

    /**
     * The guard level for one finish verification over the CURRENT page (vision 04 §3
     * v1.3.0): NONE when the class predicate fails; STRONG when the extracted entity is
     * deterministically matched in the page TITLE or URL (identity, never a content
     * mention); WEAK otherwise. Call after [record] for the same observation so the
     * current page is in the state.
     */
    fun levelAt(title: String, url: String): GuardLevel = when {
        !satisfied() -> GuardLevel.NONE
        entity != null && TargetEntity.matchesIn(entity, title, url) -> GuardLevel.STRONG
        else -> GuardLevel.WEAK
    }

    /** The extracted entity's text, for logs/propositions (null when none was found). */
    val targetEntity: TargetEntity? get() = entity

    /** Human-readable state for the step log / run report. */
    fun describe(): String = when (kind) {
        TriageClass.LOOKUP ->
            "lookup: contentful pages read=${readContentfulPages().size} " +
                "(needs one beyond the first page)"
        TriageClass.RESEARCH_MULTI_STEP ->
            "research: distinct contentful pages=${readContentfulPages().size} (needs 2)"
        TriageClass.FORM_FILL ->
            "form-fill: fieldFilled=$fieldFilled submittedAfterFill=$submittedAfterFill"
        TriageClass.ACTION -> actionGuard.describe()
        TriageClass.ACTION_MULTI -> "action-multi: ${multiGuard!!.describe()}"
    } + entity.let { e -> if (e == null) "" else "; entity=\"${e.text}\"" }

    private fun readContentfulPages(): List<String> =
        pages.filterValues { it >= CONTENT_MIN_TEXT_NODES }.keys.toList()

    companion object {
        /** A "non-empty content region": >= 3 non-interactive, non-blank text nodes. */
        const val CONTENT_MIN_TEXT_NODES = 3
    }
}

/**
 * The `action` class's WEAK predicate (vision 04 §3, Phase 08G named guard): a
 * post-action state observed — exactly one successful action executed, recorded after
 * its post-step observation (code evidence, never the engine's self-report, ART-4).
 */
class ActionCompletionGuard {

    /** True once a step recorded an executed action (the post-action observation). */
    var satisfied: Boolean = false
        private set

    /** Called by [CompletionGuards.record] when an action executed successfully. */
    fun recordActionExecuted() {
        satisfied = true
    }

    fun describe(): String =
        "action: post-action state observed=$satisfied (needs one executed action)"
}

/**
 * The `action-multi` class's WEAK predicate (vision 04 §3, Phase 08G named guard): the
 * tracker DRAINED — none pending. Skipped rows count as resolved (the owner's gate
 * refusal is authoritative for that row); processed rows are the operation applied.
 * Finish is IMPOSSIBLE while targets are pending, and the refusal names the count.
 */
class ActionMultiCompletionGuard(private val tracker: TargetTracker) {

    /** None pending — processed and skipped rows are both resolved. */
    val satisfied: Boolean get() = tracker.drained()

    /** Targets still pending (the refusal's count). */
    fun pendingCount(): Int = tracker.pendingCount()

    /** Why a finish is refused right now: the pending count, named. */
    fun refusalDetail(): String =
        "${tracker.pendingCount()} target(s) still pending (drained=false)"

    fun describe(): String = tracker.summaryLine()
}

/** Guard evidence levels (vision 04 §3 v1.2.0); NONE never reaches the log (an exit
 *  always carries WEAK or STRONG — a failing guard refuses every path). */
enum class GuardLevel { NONE, WEAK, STRONG }

/**
 * The triage-extracted target entity (vision 04 §3 v1.2.0): the goal's named target,
 * found by DETERMINISTIC code — quoted spans, "article/page X" cue patterns, then the
 * goal's URL (UAT W1: last path segment, `_`/`-` as spaces; host/domain fallback), then
 * capitalized multi-word runs. No site names, no model, no per-goal hardcoding.
 *
 * Matching (the STRONG guard, vision 04 §3 v1.3.0): the entity matched in the page
 * TITLE or URL only — case-insensitive exact word-boundary match OR a token overlap
 * >= [STRONG_OVERLAP] of the entity's tokens — both deterministic; [STRONG_OVERLAP] is
 * the law's ">= 0.9 token-overlap fuzzy" bound. URL matching normalizes `_`/`-` and
 * path separators to spaces first (`wiki/Tarte_Tatin` carries the entity's tokens).
 * Content lines are NEVER consulted: a body-text mention (a Related-Articles card, a
 * "see also" link) is not arrival.
 */
data class TargetEntity(val text: String) {

    /** Normalized tokens of the entity (lowercase, punctuation-stripped, deduplicated). */
    val tokens: List<String> = normalizeTokens(text)

    init {
        require(text.isNotBlank()) { "TargetEntity text must not be blank" }
        require(tokens.isNotEmpty()) { "TargetEntity '$text' normalized to no tokens" }
    }

    companion object {

        /** The law's fuzzy bound (vision 04 §3): >= 0.9 of the entity's tokens must be
         *  present in the page text for a fuzzy STRONG match. */
        const val STRONG_OVERLAP = 0.9

        /** Quoted spans win: the goal names its target verbatim ("Tarte Tatin"). */
        private val QUOTED = Regex("[\"“”]([^\"“”]{2,80})[\"“”]")

        /** Explicit cue ("the article titled X", "page about X") — >= 1 capitalized word. */
        private val CUE_EXPLICIT =
            Regex("""\b(?:article|page|entry|post|video|recipe|profile)\s+(?:titled|named|called|about)\s+([A-Z][\w'’-]*(?:\s+[A-Z][\w'’-]*)*)""")

        /** Bare cue ("article Tarte Tatin") — needs >= 2 capitalized words to count. */
        private val CUE_BARE =
            Regex("""\b(?:article|page|entry|post|video|recipe|profile)\s+([A-Z][\w'’-]*(?:\s+[A-Z][\w'’-]+)+)""")

        /** One word of a capitalized run (first letter uppercase; trailing punctuation
         *  is stripped by the extractor, never inside a word). */
        private val CAPITALIZED_WORD = Regex("""[A-Z][\w'’-]*""")

        /** The goal's explicit http(s) URL ("open https://en.wikipedia.org/wiki/…") —
         *  ColdStart.GOAL_URL's pinned shape, local copy (this file stays pure). */
        private val GOAL_URL = Regex("""https?://[^\s"'<>]+""")

        /** A bare domain in the goal ("open example.com") — ColdStart.GOAL_DOMAIN's
         *  shape (common generic TLDs only), so the fallback never grabs arbitrary
         *  dotted text. */
        private val GOAL_DOMAIN = Regex(
            """(?:^|[\s"'])(([A-Za-z0-9][A-Za-z0-9-]*(?:\.[A-Za-z0-9][A-Za-z0-9-]*)*)\.(com|net|org|edu|gov|io|co)(/[^\s"'<>]*)?)(?=$|[\s"'.!?,:;])""",
        )

        /** A URL path segment shorter than this (after `_`/`-` -> spaces) falls back to
         *  the host ("…/a" carries no identity). */
        private const val SHORT_SEGMENT_MIN = 3

        /** Sentence-boundary marker for the capitalized-run fallback: a run may not
         *  include the goal's first word (sentence-start capitalization is not evidence). */
        fun extract(goal: String): TargetEntity? {
            val trimmed = goal.trim()
            if (trimmed.isEmpty()) return null
            QUOTED.find(trimmed)?.let { match ->
                return quoted(match.groupValues[1])
            }
            CUE_EXPLICIT.find(trimmed)?.let { match ->
                return quoted(match.groupValues[1])
            }
            CUE_BARE.find(trimmed)?.let { match ->
                return quoted(match.groupValues[1])
            }
            urlEntity(trimmed)?.let { return it }
            return capitalizedRun(trimmed)?.let { TargetEntity(it) }
        }

        /**
         * The URL-goal entity (UAT 9.1 W1, evidence-backed: the owner's
         * "open https://en.wikipedia.org/wiki/World_War_II" run extracted NO entity, so
         * STRONG could never fire and the run could not formally complete). The URL's
         * LAST non-empty path segment with `_`/`-` read as spaces ("World_War_II" ->
         * "World War II"); the host (www. stripped, port stripped) when the path is
         * empty/short; the bare domain for domain-only goals ("open example.com").
         * Runs AFTER the quoted/cue patterns (an explicitly named target wins over a
         * URL path) and BEFORE the capitalized-run fallback.
         */
        private fun urlEntity(goal: String): TargetEntity? {
            GOAL_URL.find(goal)?.let { match ->
                val url = match.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':')
                val afterScheme = url.substringAfter("://")
                val host = afterScheme.substringBefore('/').substringBefore(':').removePrefix("www.")
                val path = afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
                val segment = path.split('/').filter { it.isNotEmpty() }.lastOrNull()
                if (segment != null) {
                    val words = segment.replace('_', ' ').replace('-', ' ').trim()
                    if (words.length >= SHORT_SEGMENT_MIN) return quoted(words)
                }
                return quoted(host)
            }
            GOAL_DOMAIN.find(goal)?.let { match ->
                val host = match.groupValues[1].substringBefore('/')
                    .trimEnd('.', ',', ')', ']', '!', '?', ';', ':')
                    .removePrefix("www.")
                return quoted(host)
            }
            return null
        }

        /** Normalizes and validates one candidate span; null when it normalizes away. */
        private fun quoted(span: String): TargetEntity? {
            val cleaned = span.trim().trimEnd('.', ',', '!', '?', ';', ':')
            if (cleaned.isEmpty()) return null
            return runCatching { TargetEntity(cleaned) }.getOrNull()
        }

        /** The first capitalized multi-word run after the goal's first word. */
        private fun capitalizedRun(goal: String): String? {
            val words = goal.split(whitespace).filter { it.isNotEmpty() }
            var index = 1 // skip word 0: sentence-start capitalization is not evidence
            while (index < words.size - 1) { // a run needs the current word AND the next
                val first = words[index].trimEnd(*TRAILING_PUNCT)
                if (CAPITALIZED_WORD.matches(first)) {
                    val run = mutableListOf(first)
                    var cursor = index + 1
                    while (cursor < words.size) {
                        val next = words[cursor].trimEnd(*TRAILING_PUNCT)
                        if (!CAPITALIZED_WORD.matches(next)) break
                        run.add(next)
                        cursor += 1
                    }
                    if (run.size >= 2) return run.joinToString(" ")
                }
                index += 1
            }
            return null
        }

        private val whitespace = Regex("""\s+""")
        private val TRAILING_PUNCT = charArrayOf('.', ',', '!', '?', ';', ':', '"', '”')

        /**
         * STRONG matching (deterministic, vision 04 §3 v1.3.0): the entity matched in
         * the page TITLE or URL — exact word-boundary match or >= [STRONG_OVERLAP]
         * token overlap. Content is never consulted: a mention is not arrival.
         */
        fun matchesIn(entity: TargetEntity, title: String, url: String): Boolean {
            if (matchesText(entity, title)) return true
            return matchesText(entity, normalizeUrl(url))
        }

        /** One text target: exact word-boundary match (the entity's token sequence as a
         *  consecutive run of the text's tokens) or the fuzzy token-overlap bound. */
        fun matchesText(entity: TargetEntity, text: String): Boolean {
            if (text.isBlank()) return false
            val textTokens = tokensOf(text)
            if (textTokens.isEmpty()) return false
            if (containsConsecutiveRun(textTokens, entity.tokens)) return true
            val textSet = textTokens.toSet()
            val entitySet = entity.tokens.toSet()
            if (entitySet.isEmpty() || textSet.isEmpty()) return false
            val overlap = entitySet.count { it in textSet }.toDouble() / entitySet.size
            return overlap >= STRONG_OVERLAP
        }

        /** The entity's tokens appearing back-to-back in the text's token list
         *  (word-boundary-safe: "tart" never matches inside "start" or "tartefillin"). */
        private fun containsConsecutiveRun(textTokens: List<String>, entityTokens: List<String>): Boolean {
            if (entityTokens.isEmpty() || textTokens.size < entityTokens.size) return false
            var start = 0
            while (start <= textTokens.size - entityTokens.size) {
                if (textTokens.subList(start, start + entityTokens.size) == entityTokens) return true
                start += 1
            }
            return false
        }

        /** URL matching space: `_`/`-` and separators become token boundaries. */
        private fun normalizeUrl(url: String): String =
            url.replace('_', ' ').replace('-', ' ').replace('/', ' ').replace('.', ' ')

        /** Lowercase, punctuation → spaces, collapse whitespace (both sides of a match). */
        private fun normalize(text: String): String =
            text.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim()

        private fun normalizeTokens(text: String): List<String> =
            normalize(text).split(' ').filter { it.isNotBlank() }.distinct()

        private fun tokensOf(text: String): List<String> =
            normalize(text).split(' ').filter { it.isNotBlank() }
    }
}
