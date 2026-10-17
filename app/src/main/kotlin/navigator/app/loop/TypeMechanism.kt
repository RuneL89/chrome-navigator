package navigator.app.loop

/**
 * The fill mechanism-rotation policy (doc 04 §1 v1.8.4 — the hotel-iteration run-3
 * repair; owner picks solutions 1+2+3). PURE and JVM-pinned — the SetupLogic /
 * LiveLogRecovery precedent: no Android types import here, so the whole matrix runs in
 * `:app`'s JVM tests. The LOOP owns the per-(ACTIVE subtask, candidate) memory (class
 * fields — the run() method-size discipline); this object owns the LAW:
 *
 * - The mechanism ladder per (active subtask, candidate): SET_TEXT -> PASTE ->
 *   TAP_AND_YIELD -> exhausted. An attempt on the same candidate must NEVER repeat a
 *   mechanism that already failed on it while the page hash is unchanged; a page change
 *   or a subtask advance invalidates the memory (new candidates start fresh).
 * - The executor's internal chain stays ONE composite attempt: SET_TEXT leg, then (on a
 *   rejection or an accepted-but-dropped write) the PASTE leg. The cause sink reports
 *   which legs failed ([legsFailed]) and the policy records them; the next attempt moves
 *   down the ladder — so the run-3 shape (five identical set-text+paste errors) becomes
 *   at most one set-text+paste attempt, one yield, one loud exhaustion.
 * - TAP_AND_YIELD is the third leg: ONLY the activation (a real tap, no text), then the
 *   loop yields to the page — a picker layer's own input becomes a fresh candidate on
 *   the next DECIDE. It consumes a step; it counts NO text-entry failure.
 * - Exhausted, the fill fails LOUDLY with a truthful mechanical cause
 *   ([exhaustedCause]) — vocabulary the informed re-plan classifies as INPUT_MECHANISM,
 *   so the writer changes the ROUTE instead of re-phrasing the subtask.
 *
 * Generalization law: no host, site, label, or language-specific string anywhere — a
 * travel/e-commerce combobox is an instance of "a type-resistant control", never a
 * design target.
 */
object TypeMechanism {

    /** One text-entry mechanism — a leg of the per-candidate ladder. */
    enum class Mechanism {
        /** ACTION_SET_TEXT on the focused field (the first leg). */
        SET_TEXT,

        /** Clipboard + ACTION_PASTE (the Phase-6 fallback leg). */
        PASTE,

        /** Activation only (a real tap), then the loop yields to the page (the third leg). */
        TAP_AND_YIELD,
    }

    /** What the loop should do with this candidate's next fill attempt. */
    sealed interface Plan {
        /**
         * Run the typing attempt. [leg] is the next unfailed TEXT mechanism (the ladder's
         * answer); the executor's composite set-text -> paste chain is the attempt's body —
         * the loop's decision is just "type", the leg is the law's position in the ladder.
         */
        data class Type(val leg: Mechanism) : Plan

        /** Activate the candidate (mechanism 3) and yield to the page. */
        object Yield : Plan

        /** Every mechanism failed on this candidate: the truthful exhaustion cause is ready. */
        data class Exhausted(val cause: String) : Plan
    }

    /**
     * The next leg for [failed] — the mechanisms already failed on this (active subtask,
     * candidate) while the page hash held. The ladder order: SET_TEXT -> PASTE ->
     * TAP_AND_YIELD -> exhausted.
     */
    fun nextLeg(failed: Collection<Mechanism>): Plan = when {
        Mechanism.SET_TEXT !in failed -> Plan.Type(Mechanism.SET_TEXT)
        Mechanism.PASTE !in failed -> Plan.Type(Mechanism.PASTE)
        Mechanism.TAP_AND_YIELD !in failed -> Plan.Yield
        else -> Plan.Exhausted(exhaustedCause(failed))
    }

    /**
     * The legs a finished attempt consumed, read from the executor's reported cause
     * sequence (the sink may receive several causes in sequence — each failed stage).
     * Pre-mechanism failures (wrong window, no field, a stale node) consume no leg: the
     * attempt never reached the field's text entry, so nothing may be recorded.
     */
    fun legsFailed(causes: List<TextEntryFailure>): List<Mechanism> = causes.mapNotNull { legOf(it) }

    private fun legOf(cause: TextEntryFailure): Mechanism? = when (cause) {
        TextEntryFailure.SET_TEXT_REJECTED -> Mechanism.SET_TEXT
        // CLIPBOARD_UNAVAILABLE: the paste leg is unusable — never repeat it.
        // TEXT_NOT_LANDED: only ever emitted after an accepted-and-dropped paste.
        TextEntryFailure.PASTE_FAILED,
        TextEntryFailure.CLIPBOARD_UNAVAILABLE,
        TextEntryFailure.TEXT_NOT_LANDED,
        -> Mechanism.PASTE

        TextEntryFailure.WRONG_WINDOW,
        TextEntryFailure.NO_FIELD_AT_POINT,
        TextEntryFailure.REFRESH_FAILED,
        -> null
    }

    /**
     * The truthful exhaustion cause — the text mechanisms that failed, in ladder order.
     * The vocabulary INPUT_MECHANISM classification keys on ("mechanically", "set text",
     * "paste", "no mechanism"). Lowercase log style, no site names.
     */
    fun exhaustedCause(failed: Collection<Mechanism>): String {
        val names = listOf(Mechanism.SET_TEXT to "set text", Mechanism.PASTE to "paste")
            .filter { it.first in failed }
            .joinToString(", ") { it.second }
            .ifBlank { "every mechanism" }
        return "typing into this field failed mechanically ($names) - no mechanism left"
    }

    /**
     * The attempt's step-log line: the truthful mechanism chain, lowercase, no site
     * names — e.g. `fill: set-text rejected, paste rejected (paste failed) - n31`. A
     * rescued attempt names the rescue (`…, paste landed`) with no failure parenthetical
     * — the rescue IS the terminal fact.
     */
    fun attemptLine(candidateId: String, causes: List<TextEntryFailure>, landed: Boolean): String {
        val chain = causes.joinToString(", ") { chainPhrase(it) } + if (landed) ", paste landed" else ""
        val tail = if (!landed) causes.lastOrNull()?.let { " (${terminalPhrase(it)})" }.orEmpty() else ""
        return "fill: $chain$tail - $candidateId"
    }

    /** The chain phrase for a history digest line (no terminal parenthetical). */
    fun chainPhrase(causes: List<TextEntryFailure>, landed: Boolean): String =
        causes.joinToString(", ") { chainPhrase(it) } + if (landed) ", paste landed" else ""

    /** The yield leg's step-log line: `fill: activating n31 and yielding to the page (mechanism 3)`. */
    fun yieldLine(candidateId: String): String =
        "fill: activating $candidateId and yielding to the page (mechanism 3)"

    private fun chainPhrase(cause: TextEntryFailure): String = when (cause) {
        TextEntryFailure.SET_TEXT_REJECTED -> "set-text rejected"
        TextEntryFailure.PASTE_FAILED -> "paste rejected"
        TextEntryFailure.CLIPBOARD_UNAVAILABLE -> "no clipboard"
        TextEntryFailure.TEXT_NOT_LANDED -> "the text did not land"
        TextEntryFailure.WRONG_WINDOW -> "the window was not the browser"
        TextEntryFailure.NO_FIELD_AT_POINT -> "no field at the point"
        TextEntryFailure.REFRESH_FAILED -> "the field went stale"
    }

    private fun terminalPhrase(cause: TextEntryFailure): String = when (cause) {
        TextEntryFailure.SET_TEXT_REJECTED -> "set text failed"
        TextEntryFailure.PASTE_FAILED -> "paste failed"
        TextEntryFailure.CLIPBOARD_UNAVAILABLE -> "clipboard unavailable"
        TextEntryFailure.TEXT_NOT_LANDED -> "text did not land"
        TextEntryFailure.WRONG_WINDOW -> "wrong window"
        TextEntryFailure.NO_FIELD_AT_POINT -> "no field at point"
        TextEntryFailure.REFRESH_FAILED -> "refresh failed"
    }
}
