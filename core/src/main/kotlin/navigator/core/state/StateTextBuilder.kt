package navigator.core.state

import navigator.core.STATE_FORMAT_VERSION
import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateList
import navigator.core.laya.Tokenizer

/**
 * Builds the canonical state text v2 (vision 03 §4 — law; STATE_FORMAT_VERSION = 2).
 * One canonical format, dual-view budgets — canonical ≤960 tokens here, the on-device
 * laya view ≤319 derived from this text by [LayaStateView]. Sections, in order:
 *
 * ```
 * GOAL: <goal text>
 * URL: <url or hidden>
 * TITLE: <page title>
 * PLAN: <subgoal, one app-authored line each — omitted when no plan exists>
 * TASK PROGRESS: <working-memory line(s) — omitted when no tracker exists>
 * HISTORY: <digest of prior steps this task, ≤3 lines>
 * UNTRUSTED PAGE CONTENT:
 * <content excerpt — bounded page prose, deterministically selected>
 * <bucketed candidate lines — serialized nodes, bucket headers>
 * ```
 *
 * Laws enforced here:
 * - Third-party page text — BOTH the content excerpt and the candidate labels — appears
 *   ONLY inside the `UNTRUSTED PAGE CONTENT` block (ART-4): goal, url, title, plan,
 *   progress, and history lines are app-authored, rendered verbatim (whitespace-flattened,
 *   one line each) and never mixed with page text. PLAN lines derive from the writer
 *   LLM's proposal but are re-authored by code — flattened, bounded, one line each;
 *   they are context, never instructions (the gate reads actions, not plans).
 * - Oversized trees are compressed deterministically in code FIRST (vision 03 §4):
 *   whole candidates are dropped lowest-rank-first within buckets ([Candidate.RANKING]);
 *   if still over, the content excerpt shrinks head-anchored — whole excerpt lines drop
 *   from the tail, keeping the ranking's head — never a corrupted mid-line cut. Every
 *   drop is recorded; `compressedBy = "code"` when anything was dropped (the cloud
 *   page-compressor LLM role that could rewrite the untrusted block is Phase 10's —
 *   the seam is logged here as the compressedBy vocabulary's `llm` half, never called).
 * - Never-exceed: if even an empty content block cannot fit the budget the builder fails
 *   loudly — an oversized state text is never emitted.
 * - Secrets never appear in any section (vision 03 §4, doc 06 §2a redaction law): the
 *   caller passes the credentials supplied so far this task in [build]'s `secrets`.
 *   The law splits by block (the 2026-10-07 post-injection deadlock fix): in the
 *   UNTRUSTED PAGE CONTENT block — where a successfully injected secret may
 *   legitimately appear as page content (live Chrome carries the typed value in the
 *   field's a11y text, so every post-injection observation reads it back) — every
 *   occurrence is REPLACED with [CREDENTIAL_REDACTED] (doc 05 §2's render): the secret
 *   never appears, which IS the law, and the run survives its post-injection state
 *   builds. In the app-authored sections (GOAL/URL/TITLE/PLAN/TASK PROGRESS/HISTORY)
 *   a secret is still a LOUD error — an upstream leak (e.g. the secret rode into
 *   HISTORY) is a real bug and must fail, never be masked silently.
 *
 * The ≤960-token count is measured with the real laya tokenizer port (vision 05 §6),
 * not an approximation.
 */
class StateTextBuilder(private val tokenizer: Tokenizer = Tokenizer.loadDefault()) {

    /** One built state text plus its truncation/compression record (for the DecisionLog). */
    data class StateText(
        val text: String,
        val formatVersion: Int,
        /** Token count of [text] under the laya tokenizer (≤ [TOKEN_BUDGET]). */
        val tokenCount: Int,
        /** `null` when nothing was dropped, `"code"` after deterministic in-code compression. */
        val compressedBy: String?,
        /** Candidate ids dropped by compression, lowest-rank first. */
        val droppedCandidateIds: List<String>,
        /** Excerpt lines dropped by head-anchored shrinking (Phase 08H; tail first). */
        val droppedExcerptLines: Int = 0,
    )

    /**
     * Renders the state text for one step.
     *
     * @param goal the task goal (app-authored; newlines flattened)
     * @param url the URL-bar read, or the literal `hidden` when the toolbar is hidden
     *   (vision 03 §1; app-authored; newlines flattened)
     * @param title the page title (app-authored line; newlines flattened)
     * @param history digest lines of prior steps this task — at most the last
     *   [MAX_HISTORY_LINES] are kept, each flattened to one app-authored line
     * @param candidates this step's deterministic candidate list
     * @param excerpt the bounded non-candidate page prose ([ContentExtractor]'s product);
     *   rendered inside the untrusted block ahead of the candidate lines, shrunk
     *   head-anchored when the budget demands it
     * @param plan the (optional) plan source's subgoals, re-authored by code — flattened,
     *   bounded to [MAX_PLAN_LINES] lines, one app-authored `PLAN:` line each; null omits
     *   the section (vision 03 §4: the section exists only when a plan exists)
     * @param progress the (optional) tracker's working-memory lines (done/pending counts
     *   + recent, the 08G [navigator.app.loop.TargetTracker.progressLines] shape minus
     *   its section prefix), bounded to [MAX_PROGRESS_LINES] app-authored lines; null
     *   omits the section
     * @param secrets the credential values supplied via gate cards so far this task
     *   (doc 06 §2a; empty for every non-credential task — the default keeps every
     *   non-credential call site unchanged). A secret in the untrusted page block
     *   (candidate labels, content excerpt) is MASKED to [CREDENTIAL_REDACTED] — the
     *   lawful post-injection render; a secret in an app-authored section fails LOUDLY
     *   (an upstream leak), never a silent truncation.
     */
    fun build(
        goal: String,
        url: String,
        title: String,
        history: List<String>,
        candidates: CandidateList,
        excerpt: List<String> = emptyList(),
        plan: List<String>? = null,
        progress: List<String>? = null,
        secrets: List<String> = emptyList(),
    ): StateText {
        val redactions = secrets.filter { it.isNotEmpty() }
        val appLines = buildList {
            add("GOAL: " + flatten(goal))
            add("URL: " + flatten(url))
            add("TITLE: " + flatten(title))
            plan?.take(MAX_PLAN_LINES)?.forEach { subgoal -> add("PLAN: " + flatten(subgoal)) }
            progress?.take(MAX_PROGRESS_LINES)?.forEach { line -> add("TASK PROGRESS: " + flatten(line)) }
            val digest = history.takeLast(MAX_HISTORY_LINES).map(::flatten)
            addAll(digest.map { "HISTORY: $it" })
            if (digest.isEmpty()) add("HISTORY: (none)")
        }
        // App-authored sections are the loop's own text (GOAL/URL/TITLE/PLAN/TASK
        // PROGRESS/HISTORY): a sanctioned secret here rode upstream into app state — a
        // real leak — so it fails LOUDLY; only the untrusted block below masks (the
        // 2026-10-07 post-injection deadlock fix). The offending line itself is never
        // echoed into the error (it carries the secret); the section prefix is constant.
        for (secret in redactions) {
            val offender = appLines.firstOrNull { it.contains(secret) }
            if (offender != null) {
                throw IllegalStateException(
                    "state text app-authored section (${offender.substringBefore(':')}) would " +
                        "carry a credential-secret value (vision 03 §4 / doc 06 §2a redaction " +
                        "law) — an upstream leak, never a page-side echo; refusing to emit, " +
                        "and this is a loud error, never a silent truncation or a mask",
                )
            }
        }
        // Untrusted-block redaction (doc 05 §2's render): every occurrence of a
        // sanctioned secret in page content becomes [credential-redacted]. Applied at
        // line level so compression counts the emitted (masked) text.
        fun mask(line: String): String =
            redactions.fold(line) { acc, secret -> acc.replace(secret, CREDENTIAL_REDACTED) }
        val excerptLines = excerpt.map(::flatten).filter { it.isNotEmpty() }.map(::mask)

        val ranked = candidates.bucketed.values.flatten().sortedWith(Candidate.RANKING)
        val kept = ArrayDeque(ranked)
        val droppedCandidates = mutableListOf<Candidate>()
        var shownExcerpt = excerptLines
        val droppedExcerpt = mutableListOf<String>()
        var text = render(appLines, shownExcerpt, candidates.bucketed, ::mask)
        while (tokenizer.count(text) > TOKEN_BUDGET) {
            when {
                // Stage 1 (vision 03 §4): drop lowest-rank candidates first.
                kept.isNotEmpty() -> {
                    droppedCandidates += kept.removeLast()
                }
                // Stage 2: shrink the content excerpt head-anchored — its own
                // deterministic ranking keeps the head; whole lines drop from the tail.
                shownExcerpt.isNotEmpty() -> {
                    droppedExcerpt += shownExcerpt.last()
                    shownExcerpt = shownExcerpt.dropLast(1)
                }
                else -> throw IllegalStateException(
                    "state text is ${tokenizer.count(text)} tokens, over the $TOKEN_BUDGET-token " +
                        "canonical budget (vision 03 §4) even with an empty UNTRUSTED PAGE CONTENT " +
                        "block — the app-authored lines alone do not fit; refusing to emit an " +
                        "oversized state",
                )
            }
            text = render(appLines, shownExcerpt, groupByBucket(kept), ::mask)
        }
        // Emission-boundary invariant (doc 06 §2a via vision 03 §4): after the
        // app-authored loud check and the untrusted-block masking no sanctioned secret
        // can remain — if one does, either the masking invariant broke or the secret
        // equals a structural constant (e.g. the untrusted marker itself), which cannot
        // be masked without corrupting the format. Either way: LOUD, never emitted.
        for (secret in redactions) {
            check(!text.contains(secret)) {
                "state text still carries a credential-secret value after redaction " +
                    "(vision 03 §4 / doc 06 §2a redaction law) — the masking invariant " +
                    "broke; refusing to emit, never a silent truncation or a mask"
            }
        }
        return StateText(
            text = text,
            formatVersion = STATE_FORMAT_VERSION,
            tokenCount = tokenizer.count(text),
            compressedBy = if (droppedCandidates.isEmpty() && droppedExcerpt.isEmpty()) null else "code",
            droppedCandidateIds = droppedCandidates.map { it.id },
            droppedExcerptLines = droppedExcerpt.size,
        )
    }

    /**
     * The exact v2 template: app-authored header lines, the untrusted-block marker, the
     * content-excerpt lines, then bucket headers (taxonomy order, vision 03 §3) with
     * serialized candidate lines (serializer line discipline, vision 03 §2). Candidate
     * lines ride the caller's [mask] — the excerpt is masked upstream, the candidates
     * here, so every untrusted line is covered (ids and coords included).
     */
    private fun render(
        appLines: List<String>,
        excerptLines: List<String>,
        bucketed: Map<Bucket, List<Candidate>>,
        mask: (String) -> String,
    ): String = buildString {
        appLines.forEach { append(it).append('\n') }
        append(UNTRUSTED_HEADER).append('\n')
        excerptLines.forEach { line -> append(line).append('\n') }
        for (bucket in Bucket.entries) {
            val candidates = bucketed[bucket].orEmpty()
            if (candidates.isEmpty()) continue
            append(bucket.header).append('\n')
            candidates.forEach { candidate -> append(mask(candidate.line())).append('\n') }
        }
    }

    private fun groupByBucket(candidates: List<Candidate>): Map<Bucket, List<Candidate>> =
        candidates.groupBy { it.bucket }.mapValues { (_, list) -> list.sortedWith(Candidate.RANKING) }

    /** One serialized node line — TreeSerializer's discipline (vision 03 §2). */
    private fun Candidate.line(): String = buildString {
        append('[').append(id).append(']').append(" \"").append(label).append('"')
        if (kinds.isNotEmpty()) append(' ').append(kinds.joinToString(" "))
        append(" (").append(cx).append(',').append(cy).append(')')
    }

    companion object {

        /**
         * Vision 03 §4 v2.1.0: the CANONICAL state-text budget (dual-view law — "its
         * budget is owned by the product (content-aware navigation and exit calibration),
         * not by any single checkpoint's context window"). The on-device view's ≤319
         * budget is [LayaStateView]'s, derived from the checkpoint's marker ceilings.
         */
        const val TOKEN_BUDGET = 960

        /** Vision 03 §4: history digest is at most 3 lines. */
        const val MAX_HISTORY_LINES = 3

        /** Vision 03 §4: PLAN lines are re-authored by code — flattened AND bounded. */
        const val MAX_PLAN_LINES = 8

        /** TASK PROGRESS lines are bounded too (counts + recent; the tracker emits ≤7). */
        const val MAX_PROGRESS_LINES = 8

        /** The untrusted block marker (ART-4 boundary — page text lives only below it). */
        const val UNTRUSTED_HEADER = "UNTRUSTED PAGE CONTENT:"

        /**
         * The sanctioned-secret render (doc 05 §2): a known credential value found in
         * the untrusted page block is replaced with this marker — the secret never
         * appears in any section (vision 03 §4 / doc 06 §2a).
         */
        const val CREDENTIAL_REDACTED = "[credential-redacted]"

        /** Bucket header lines: the taxonomy's serial names (the shadow's vocabulary). */
        private val Bucket.header: String
            get() = when (this) {
                Bucket.NAV_LINKS -> "nav-links:"
                Bucket.FORM_FIELDS -> "form-fields:"
                Bucket.BUTTONS -> "buttons:"
                Bucket.SCROLL -> "scroll:"
                Bucket.BACK -> "back:"
                Bucket.FINISH -> "finish:"
            }

        /**
         * App-authored input discipline: control characters (newline/CR/tab) become plain
         * spaces and whitespace runs collapse — one line each, never multi-line injection.
         */
        private fun flatten(input: String): String =
            input.replace(CONTROL_CHARS, " ").trim().replace(WHITESPACE_RUNS, " ")

        private val CONTROL_CHARS = Regex("[\\n\\r\\t]")
        private val WHITESPACE_RUNS = Regex(" {2,}")
    }
}
