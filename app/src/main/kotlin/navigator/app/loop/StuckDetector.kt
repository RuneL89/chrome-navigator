package navigator.app.loop

/**
 * StuckDetector (vision 04 §4 — law): near-verbatim, deterministic reimplementation of
 * PokeClaw's design (attributed; ART-10), model-agnostic.
 *
 * - Window: the last [WINDOW] steps.
 * - Signals — any [SIGNALS_TO_TRIP] of the five trip an escalation:
 *   1. same action 3x,
 *   2. screen hash unchanged 3x,
 *   3. zero text diff 3x,
 *   4. high repetition score over the window,
 *   5. repeated error outcomes (3x).
 * - Levels (escalation is monotone while the screen keeps not changing; any step that
 *   DOES change the screen resets the escalation — the agent made progress):
 *   L1 hint (a line injected into the HISTORY section of the next state text),
 *   L2 strategy switch (the engine chooses from the recovery set
 *   `back · scroll · rephrase · give-up` — the engine picks, code triggers),
 *   L3 auto-kill. The Phase-10 rescue-coach call is absent here, so L3 kills straight
 *   away (the loop handles the coach's absence per the phase's non-scope).
 */
class StuckDetector(private val windowSize: Int = WINDOW) {

    init {
        require(windowSize >= 3) { "stuck window must be >= 3 steps, was $windowSize" }
    }

    /** One step's observable footprint. */
    data class Footprint(
        /** Deterministic key of the executed action (`kind:candidateId`); null when no gesture ran. */
        val actionKey: String?,
        /** Hash of the post-step screen (serialized lines). */
        val screenHash: Int,
        /** Post-step serialized tree (for the zero-text-diff signal). */
        val textLines: List<String>,
        /** True when the step ended in an error outcome. */
        val isError: Boolean,
    )

    data class Assessment(
        /** 1 = hint, 2 = strategy switch, 3 = auto-kill. */
        val level: Int,
        /** The tripped signal names (>= [SIGNALS_TO_TRIP]). */
        val signals: List<String>,
    )

    private val window = ArrayDeque<Footprint>()
    private var stuckCount = 0

    /** Records one step. A step that changed the screen resets the escalation counter. */
    fun record(footprint: Footprint) {
        val previous = window.lastOrNull()
        if (previous != null && previous.screenHash != footprint.screenHash) {
            stuckCount = 0 // progress: the screen moved — fresh escalation budget
        }
        window.addLast(footprint)
        while (window.size > windowSize) window.removeFirst()
    }

    /**
     * Assesses the window after the last recorded step. Returns null while fewer than
     * [SIGNALS_TO_TRIP] signals trip; otherwise the next escalation level.
     */
    fun assess(): Assessment? {
        val tripped = buildList {
            if (sameActionTripped()) add("same-action-3x")
            if (hashUnchangedTripped()) add("screen-hash-unchanged-3x")
            if (zeroTextDiffTripped()) add("zero-text-diff-3x")
            if (highRepetitionTripped()) add("high-repetition")
            if (repeatedErrorTripped()) add("repeated-error")
        }
        if (tripped.size < SIGNALS_TO_TRIP) return null
        stuckCount += 1
        return Assessment(minOf(stuckCount, MAX_LEVEL), tripped)
    }

    /** The last assessment's level (0 when never stuck) — L2 arms the recovery prompt. */
    val lastLevel: Int get() = if (stuckCount == 0) 0 else minOf(stuckCount, MAX_LEVEL)

    // -- signals ---------------------------------------------------------------------

    private fun last(n: Int): List<Footprint> = window.takeLast(n)

    private fun sameActionTripped(): Boolean =
        window.size >= 3 && last(3).all { it.actionKey != null && it.actionKey == last(3).first().actionKey }

    private fun hashUnchangedTripped(): Boolean =
        window.size >= 3 && last(3).all { it.screenHash == last(3).first().screenHash }

    private fun zeroTextDiffTripped(): Boolean =
        window.size >= 3 && last(3).all { it.textLines == last(3).first().textLines }

    /** High repetition: over a >=5-step window at most half the actions were distinct. */
    private fun highRepetitionTripped(): Boolean {
        val actions = window.mapNotNull { it.actionKey }
        if (actions.size < 5) return false
        return actions.distinct().size * 2 <= actions.size
    }

    private fun repeatedErrorTripped(): Boolean =
        window.size >= 3 && last(3).all { it.isError }

    companion object {
        /** Vision 04 §4: the rolling window. */
        const val WINDOW = 8

        /** Signals needed to trip an escalation (any 3 of the five). */
        const val SIGNALS_TO_TRIP = 3

        /** Highest escalation level (L3 = auto-kill). */
        const val MAX_LEVEL = 3

        /** The L1 hint line injected into HISTORY (app-authored line, ART-4-safe). */
        const val HINT =
            "STUCK HINT: you have repeated actions without progress - " +
                "stop repeating, try a different candidate or section of the page"
    }
}
