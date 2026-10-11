package navigator.app.overlay

/**
 * The corner-log LADDER BLOCK formatter (Phase 10, phase doc §2.4 — the owner directive;
 * **subtask-NAME lines per the owner design interview 2026-10-11**): the ladder lives in
 * the bottom-right live log as its header block ABOVE the streaming step lines:
 *
 * ```
 * GOAL: <the LLM's one-sentence rewrite>
 * re-plan 2
 * ▶ [ ]  Open the World War II article
 *   [x]  Reach the Wikipedia site
 * ```
 *
 * Each subtask line shows the subtask's OWN NAME (its instruction, flattened and capped
 * at [MAX_SUBTASK_CHARS] — the corner block stays readable), on a stable two-column
 * rail: a 2-char lead (`▶ ` active / two spaces idle) + the 3-char state mark
 * (`[x]` verified IS-DONE / `[ ]` not yet) + a 2-space gap + the name. The ACTIVE
 * subtask is visually distinguishable (the `▶` lead — its color spans are
 * presentation-only in LiveLogOverlay; the TEXT is the law this formatter owns). A
 * re-plan re-renders the block with the new ladder's summary + subtasks and the
 * generation count shown ("re-plan 2"). Pure JVM, deterministic, byte-stable — Gate
 * 10.2 pins it (pointer advance + re-plan regeneration). The block is a PINNED HEADER:
 * the latest-N slice law applies to the step lines below it, never to these lines.
 */
object LadderBlock {

    /** The summary line's prefix (the LLM's one-sentence rewrite of the user's goal). */
    const val GOAL_PREFIX = "GOAL: "

    /** Verified-IS-DONE mark. */
    const val DONE_MARK = "[x]"

    /** Not-yet mark. */
    const val PENDING_MARK = "[ ]"

    /** The ACTIVE subtask's lead marker. */
    const val ACTIVE_MARK = "▶ "

    /** The idle lead: two spaces keep every state mark in one aligned column. */
    const val IDLE_MARK = "  "

    /** The re-plan line (generation > 1 only), rendered under the GOAL line. */
    const val REPLAN_PREFIX = "re-plan "

    /** The GOAL summary is capped so the corner block stays readable (chars, ART-5). */
    const val MAX_SUMMARY_CHARS = 120

    /** The subtask NAME cap (chars, ART-5): long instructions never blow up the block. */
    const val MAX_SUBTASK_CHARS = 40

    /**
     * The name's readable width per rendered line at corner size (chars, ART-5):
     * longer names WRAP at a word boundary here, the continuation indent-aligned
     * under the rail ([NAME_INDENT]) — a plain TextView would wrap at the margin and
     * break the marker column (measured 2026-10-11).
     */
    const val NAME_LINE_CHARS = 26

    /** The state mark's position in a rendered subtask line (after the 2-char lead). */
    const val MARK_START = 2

    /** The state mark's width (`[x]` / `[ ]`). */
    const val MARK_LENGTH = 3

    /** The gap between the state mark and the name. */
    private const val NAME_GAP = "  "

    /** The continuation-line indent: the rail's width (lead + mark + gap). */
    internal const val NAME_INDENT = "       "

    /**
     * The header state: the summary, one name per subtask (its instruction — a blank
     * name lawfully degrades to the numbered label), the done flags, the active index,
     * and the ladder generation (1 = the initial restructure).
     */
    data class State(
        val summary: String,
        val instructions: List<String>,
        val doneFlags: List<Boolean>,
        val activeIndex: Int,
        val generation: Int = 1,
    )

    /**
     * Renders the block lines, in order: the GOAL line, the re-plan line (generation
     * > 1), then one `▶ [ ]  <name>` / `  [x]  <name>` line per subtask with the active
     * lead marking the current one. A name longer than [NAME_LINE_CHARS] wraps to at
     * most TWO lines — the continuation indent-aligned under the rail.
     */
    fun render(state: State): List<String> {
        val lines = mutableListOf(GOAL_PREFIX + flatten(state.summary, MAX_SUMMARY_CHARS))
        if (state.generation > 1) lines.add(REPLAN_PREFIX + state.generation)
        state.doneFlags.forEachIndexed { index, done ->
            val lead = if (index == state.activeIndex) ACTIVE_MARK else IDLE_MARK
            val mark = if (done) DONE_MARK else PENDING_MARK
            val name = state.instructions.getOrNull(index)
                ?.takeIf { it.isNotBlank() }
                ?.let { flatten(it, MAX_SUBTASK_CHARS) }
                ?: subtaskLabel(index)
            val (first, continuation) = wrapName(name)
            lines.add("$lead$mark$NAME_GAP$first")
            if (continuation != null) lines.add(NAME_INDENT + continuation)
        }
        return lines
    }

    /**
     * Deterministic word-boundary wrap of a capped name into at most two rows: the
     * second row is hard-capped at [NAME_LINE_CHARS] with the explicit truncation
     * marker. A single unbroken over-long word falls back to a hard split.
     */
    internal fun wrapName(name: String): Pair<String, String?> {
        if (name.length <= NAME_LINE_CHARS) return name to null
        val splitAt = name.lastIndexOf(' ', startIndex = NAME_LINE_CHARS)
        val first = if (splitAt > 0) name.take(splitAt) else name.take(NAME_LINE_CHARS)
        var rest = if (splitAt > 0) name.substring(splitAt + 1) else name.substring(NAME_LINE_CHARS)
        if (rest.length > NAME_LINE_CHARS) rest = rest.take(NAME_LINE_CHARS) + "..."
        return first to rest
    }

    /** `Sub Task N` — the label the BLANK-name degradation falls back to (1-based). */
    fun subtaskLabel(index: Int): String = "Sub Task ${index + 1}"

    /**
     * Whether [line] is a rendered SUBTASK line (the marks column): a lead + a `[`-state
     * mark. The terminal task-state line (`▶ task …`) shares the lead but never matches —
     * its char at [MARK_START] is not `[`.
     */
    fun isSubtaskLine(line: String): Boolean =
        (line.startsWith(ACTIVE_MARK) || line.startsWith(IDLE_MARK)) &&
            line.length > MARK_START && line[MARK_START] == '['

    /**
     * Whether [line] is a ladder-block line (the presentation layer's parse for color
     * spans — the text is never touched).
     */
    fun isLadderLine(line: String): Boolean =
        line.startsWith(GOAL_PREFIX) ||
            line.startsWith(REPLAN_PREFIX) ||
            isSubtaskLine(line)

    /** One-line display discipline: control chars become spaces, runs collapse, capped. */
    private fun flatten(input: String, cap: Int): String =
        input.replace(Regex("[\\n\\r\\t]"), " ").trim().replace(Regex(" {2,}"), " ")
            .let { if (it.length > cap) it.take(cap) + "..." else it }
}
