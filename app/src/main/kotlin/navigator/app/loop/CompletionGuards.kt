package navigator.app.loop

import navigator.core.log.TriageClass

/**
 * The code completion guards (vision 04 §3 — law, ART-4): per-triage-class state
 * machines over what CODE observed and executed — never over what the engine reports.
 * Goal-achieved requires the engine's noul in the high band AND the guard satisfied;
 * a self-report alone is refused.
 *
 * Guard predicates per class:
 * - `lookup` (search-and-read): a non-empty content region was read on a page OTHER
 *   than the task's first page (generalizes PokeClaw's `InAppSearchGuard` — results
 *   must actually have loaded and been read).
 * - `research-multi-step`: at least two distinct content-bearing pages were read.
 * - `form-fill`: a text entry succeeded AND a submit executed afterwards.
 *
 * A content region is [CONTENT_MIN_TEXT_NODES] or more non-interactive text nodes in
 * one observation (code-countable "the page has body text", vision 04 §3's
 * "non-empty content region").
 */
class CompletionGuards(triageClass: TriageClass) {

    private val kind: TriageClass = triageClass

    /** url -> highest content-text-node count observed there, in first-seen order. */
    private val pages = LinkedHashMap<String, Int>()
    private var firstUrl: String? = null
    private var fieldFilled = false
    private var submittedAfterFill = false

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
     */
    fun record(url: String, contentTextNodes: Int, textTyped: Boolean, submitExecuted: Boolean) {
        pages[url] = maxOf(pages[url] ?: 0, contentTextNodes)
        if (textTyped) fieldFilled = true
        if (submitExecuted && fieldFilled) submittedAfterFill = true
    }

    /** Whether the task's class-specific completion preconditions hold. */
    fun satisfied(): Boolean = when (kind) {
        TriageClass.LOOKUP -> readContentfulPages().any { it != firstUrl }
        TriageClass.RESEARCH_MULTI_STEP -> readContentfulPages().size >= 2
        TriageClass.FORM_FILL -> fieldFilled && submittedAfterFill
    }

    /** Human-readable state for the step log / run report. */
    fun describe(): String = when (kind) {
        TriageClass.LOOKUP ->
            "lookup: contentful pages read=${readContentfulPages().size} " +
                "(needs one beyond the first page)"
        TriageClass.RESEARCH_MULTI_STEP ->
            "research: distinct contentful pages=${readContentfulPages().size} (needs 2)"
        TriageClass.FORM_FILL ->
            "form-fill: fieldFilled=$fieldFilled submittedAfterFill=$submittedAfterFill"
    }

    private fun readContentfulPages(): List<String> =
        pages.filterValues { it >= CONTENT_MIN_TEXT_NODES }.keys.toList()

    companion object {
        /** A "non-empty content region": >= 3 non-interactive, non-blank text nodes. */
        const val CONTENT_MIN_TEXT_NODES = 3
    }
}
