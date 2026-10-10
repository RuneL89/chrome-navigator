package navigator.core.state

import navigator.core.STATE_FORMAT_VERSION
import navigator.core.enumerate.Candidate
import navigator.core.laya.Tokenizer

/**
 * The cascade's per-stage state views (vision 03 §4 v2.3.0 — LAW; Phase 10B, doc 02 §3):
 * two additional lawful derivations of the same observation, built by the same versioned
 * builder family as the canonical text — the INTENT STATE VIEW feeding every stage-1
 * intent call and the TARGET STATE VIEW feeding each stage-2 score request. Both are
 * pure, deterministic, budget-checked with the REAL laya tokenizer (loud on overflow —
 * silent truncation stays unlawful), versioned [STAGE_VIEW_VERSION], and they NEVER
 * replace the canonical text as the logged/corpus record of the step.
 *
 * Lines (phase doc §2.2; the excerpt head lines and the assist block are page-derived —
 * the assist block is labeled UNTRUSTED by design, and the canonical text remains the
 * ART-4 record of the step):
 *
 * INTENT STATE VIEW (≤ [INTENT_VIEW_TOKEN_BUDGET] tokens):
 * ```
 * SUBTASK: <the active subtask's instruction>
 * DONE WHEN: <the rendered done-condition — the goal text on non-ladder runs>
 * PAGE: <host> — <title>
 * <up to MAX_INTENT_EXCERPT_LINES excerpt head lines>
 * ASSIST (untrusted): <the handshake assist text — only when a re-ask is in flight>
 * ```
 * TARGET STATE VIEW (≤ [TARGET_VIEW_TOKEN_BUDGET] tokens):
 * ```
 * SUBTASK: <the active subtask's instruction>
 * DONE WHEN: <the rendered done-condition>
 * <one bucket's candidate lines — browser-chrome EXCLUDED, deterministic order>
 * ```
 *
 * Deterministic-first compression mirrors the canonical builder: the intent view drops
 * the optional assist block first, then excerpt head lines tail-first; the target view
 * drops lowest-rank candidates whole — never a mid-line cut. `compressedBy = "code"`
 * records any drop.
 */
class StageViews(private val tokenizer: Tokenizer = Tokenizer.loadDefault()) {

    /** One built stage view plus its compression record. */
    data class StageView(
        val text: String,
        /** Which stage the view feeds. */
        val stage: Stage,
        val version: Int,
        val stateFormatVersion: Int,
        /** Token count of [text] under the laya tokenizer (≤ the stage's budget). */
        val tokenCount: Int,
        /** `null` when nothing was dropped, `"code"` after deterministic in-code compression. */
        val compressedBy: String?,
    )

    /** Which cascade stage a view feeds (doc 02 §3). */
    enum class Stage { INTENT, TARGET }

    /**
     * Builds the INTENT STATE VIEW for one stage-1 call. [assistText] (page material
     * re-described by the writer, doc 04 §2) rides ONLY when a handshake re-ask is in
     * flight, labeled `ASSIST (untrusted):` — never on a first ask.
     */
    fun intentView(
        subtask: String,
        doneWhen: String,
        host: String,
        title: String,
        excerpt: List<String> = emptyList(),
        assistText: String? = null,
    ): StageView {
        require(subtask.isNotBlank()) { "intent view: the subtask line must not be blank" }
        require(doneWhen.isNotBlank()) { "intent view: the done-when line must not be blank" }
        val head = listOf(
            "SUBTASK: ${flatten(subtask)}",
            "DONE WHEN: ${flatten(doneWhen)}",
            "PAGE: ${flatten(host)} — ${flatten(title)}",
        )
        val headLines = excerpt.map(::flatten).filter { it.isNotEmpty() }.take(MAX_INTENT_EXCERPT_LINES)
        var shownLines = headLines
        var assistLine = assistText?.let { "ASSIST (untrusted): ${flatten(it)}" }
        var assistDropped = false
        var dropped = 0
        var text = renderIntent(head, shownLines, assistLine)
        while (tokenizer.count(text) > INTENT_VIEW_TOKEN_BUDGET) {
            when {
                assistLine != null -> {
                    assistLine = null // the assist is the first thing to go: it is optional context
                    assistDropped = true
                }

                shownLines.isNotEmpty() -> {
                    dropped += 1
                    shownLines = shownLines.dropLast(1)
                }

                else -> throw IllegalStateException(
                    "intent state view is ${tokenizer.count(text)} tokens, over the " +
                        "$INTENT_VIEW_TOKEN_BUDGET-token budget (vision 03 §4 v2.3.0) even with " +
                        "no excerpt and no assist — refusing to emit an oversized stage view",
                )
            }
            text = renderIntent(head, shownLines, assistLine)
        }
        return StageView(
            text = text,
            stage = Stage.INTENT,
            version = STAGE_VIEW_VERSION,
            stateFormatVersion = STATE_FORMAT_VERSION,
            tokenCount = tokenizer.count(text),
            compressedBy = if (assistDropped || dropped > 0) "code" else null,
        )
    }

    /**
     * Builds the TARGET STATE VIEW for one stage-2 request: the subtask lines plus ONE
     * bucket's candidate lines in deterministic order, browser-chrome candidates
     * EXCLUDED here (URL needs are the open-site intent's code path — the exclusion is
     * enforced again at this boundary, never trusted to the caller). Compression drops
     * lowest-rank candidates whole ([Candidate.RANKING]); the head lines alone over
     * budget is LOUD.
     */
    fun targetView(
        subtask: String,
        doneWhen: String,
        slate: List<Candidate>,
    ): StageView {
        require(subtask.isNotBlank()) { "target view: the subtask line must not be blank" }
        require(doneWhen.isNotBlank()) { "target view: the done-when line must not be blank" }
        val head = listOf(
            "SUBTASK: ${flatten(subtask)}",
            "DONE WHEN: ${flatten(doneWhen)}",
        )
        val eligible = slate.filterNot { it.browserChrome }
        require(eligible.isNotEmpty()) {
            "target view: the slate is empty after the browser-chrome exclusion — " +
                "a target-bearing intent never draws its slate from browser UI (vision 03 §3)"
        }
        val ordered = eligible.sortedWith(Candidate.RANKING)
        val kept = ArrayDeque(ordered)
        var dropped = 0
        var text = renderTarget(head, kept)
        while (tokenizer.count(text) > TARGET_VIEW_TOKEN_BUDGET) {
            if (kept.isEmpty()) {
                throw IllegalStateException(
                    "target state view is ${tokenizer.count(text)} tokens, over the " +
                        "$TARGET_VIEW_TOKEN_BUDGET-token budget (vision 03 §4 v2.3.0) even with an " +
                        "empty slate — refusing to emit an oversized stage view",
                )
            }
            kept.removeLast()
            dropped += 1
            text = renderTarget(head, kept)
        }
        return StageView(
            text = text,
            stage = Stage.TARGET,
            version = STAGE_VIEW_VERSION,
            stateFormatVersion = STATE_FORMAT_VERSION,
            tokenCount = tokenizer.count(text),
            compressedBy = if (dropped > 0) "code" else null,
        )
    }

    /** The exact intent-view template. */
    private fun renderIntent(head: List<String>, excerptLines: List<String>, assistLine: String?): String =
        buildString {
            head.forEach { append(it).append('\n') }
            excerptLines.forEach { line -> append(line).append('\n') }
            assistLine?.let { append(it).append('\n') }
        }.removeSuffix("\n")

    /** The exact target-view template (serialized candidate lines — the serializer discipline). */
    private fun renderTarget(head: List<String>, slate: Collection<Candidate>): String = buildString {
        head.forEach { append(it).append('\n') }
        slate.forEach { candidate -> append(candidate.line()).append('\n') }
    }.removeSuffix("\n")

    companion object {

        /** The stage-view template version (vision 03 §4 v2.3.0). */
        const val STAGE_VIEW_VERSION = 1

        /** The INTENT STATE VIEW budget (≈100–120 tokens — vision 03 §4 v2.3.0, ART-5). */
        const val INTENT_VIEW_TOKEN_BUDGET = 120

        /** The TARGET STATE VIEW budget. */
        const val TARGET_VIEW_TOKEN_BUDGET = 200

        /** Excerpt head lines the intent view may carry (≤3 — the phase doc's cap). */
        const val MAX_INTENT_EXCERPT_LINES = 3

        /**
         * One serialized node line — TreeSerializer's discipline (vision 03 §2), the same
         * line shape the canonical builder renders.
         */
        fun Candidate.line(): String = buildString {
            append('[').append(id).append(']').append(" \"").append(label).append('"')
            if (kinds.isNotEmpty()) append(' ').append(kinds.joinToString(" "))
            append(" (").append(cx).append(',').append(cy).append(')')
        }

        /**
         * App-authored input discipline: control characters become plain spaces and
         * whitespace runs collapse — one line each, never multi-line injection (the
         * canonical builder's rule).
         */
        private fun flatten(input: String): String =
            input.replace(CONTROL_CHARS, " ").trim().replace(WHITESPACE_RUNS, " ")

        private val CONTROL_CHARS = Regex("[\\n\\r\\t]")
        private val WHITESPACE_RUNS = Regex(" {2,}")
    }
}
