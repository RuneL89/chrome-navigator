package navigator.app.overlay

/**
 * The corner-log LADDER BLOCK formatter (Phase 10, phase doc §2.4 — the owner directive,
 * 2026-10-09): the ladder lives in the bottom-right live log as its header block ABOVE
 * the streaming step lines, in exactly this shape:
 *
 * ```
 * GOAL: <the LLM's one-sentence rewrite>
 * Sub Task 1 [x]
 * Sub Task 2 [ ]
 * ```
 *
 * `[x]` = verified IS-DONE, `[ ]` = not yet; the ACTIVE subtask is visually
 * distinguishable (the `▶` marker prefix — its color spans are presentation-only in
 * LiveLogOverlay; the TEXT is the law this formatter owns). A re-plan re-renders the
 * block with the new ladder's summary + subtasks and the generation count shown
 * ("re-plan 2"). Pure JVM, deterministic, byte-stable — Gate 10.2 pins it (pointer
 * advance + re-plan regeneration). The block is a PINNED HEADER: the latest-N slice law
 * applies to the step lines below it, never to these lines.
 */
object LadderBlock {

    /** The summary line's prefix (the LLM's one-sentence rewrite of the user's goal). */
    const val GOAL_PREFIX = "GOAL: "

    /** Verified-IS-DONE mark. */
    const val DONE_MARK = "[x]"

    /** Not-yet mark. */
    const val PENDING_MARK = "[ ]"

    /** The ACTIVE subtask's distinguishing marker. */
    const val ACTIVE_MARK = "▶ "

    /** The re-plan line (generation > 1 only), rendered under the GOAL line. */
    const val REPLAN_PREFIX = "re-plan "

    /** The GOAL summary is capped so the corner block stays readable (chars, ART-5). */
    const val MAX_SUMMARY_CHARS = 120

    /**
     * The header state: the summary, one instruction per subtask (instructions inform
     * the CARD display, not the block), the done flags, the active index, and the ladder
     * generation (1 = the initial restructure).
     */
    data class State(
        val summary: String,
        val doneFlags: List<Boolean>,
        val activeIndex: Int,
        val generation: Int = 1,
    )

    /**
     * Renders the block lines, in order: the GOAL line, the re-plan line (generation
     * > 1), then one `Sub Task N [x]` line per subtask with the active one marked.
     */
    fun render(state: State): List<String> {
        val lines = mutableListOf(GOAL_PREFIX + flatten(state.summary))
        if (state.generation > 1) lines.add(REPLAN_PREFIX + state.generation)
        state.doneFlags.forEachIndexed { index, done ->
            val mark = if (done) DONE_MARK else PENDING_MARK
            val prefix = if (index == state.activeIndex) ACTIVE_MARK else ""
            lines.add("$prefix${subtaskLabel(index)} $mark")
        }
        return lines
    }

    /** `Sub Task N` — the phase doc's label (1-based). */
    fun subtaskLabel(index: Int): String = "Sub Task ${index + 1}"

    /**
     * Whether [line] is a ladder-block line (the presentation layer's parse for color
     * spans — the text is never touched).
     */
    fun isLadderLine(line: String): Boolean =
        line.startsWith(GOAL_PREFIX) ||
            line.startsWith(REPLAN_PREFIX) ||
            line.endsWith(DONE_MARK) ||
            line.endsWith(PENDING_MARK)

    /** One-line display discipline: control chars become spaces, runs collapse, capped. */
    private fun flatten(input: String): String =
        input.replace(Regex("[\\n\\r\\t]"), " ").trim().replace(Regex(" {2,}"), " ")
            .let { if (it.length > MAX_SUMMARY_CHARS) it.take(MAX_SUMMARY_CHARS) + "..." else it }
}
