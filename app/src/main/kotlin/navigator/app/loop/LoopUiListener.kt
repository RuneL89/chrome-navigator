package navigator.app.loop

import navigator.core.log.LoggedGateEvent
import navigator.core.log.serialNameOf

/**
 * The ADDITIVE product-UI listener (Phase 9, phase doc §2.1): the overlay surface's
 * live view of a run — thinking states, the plan display, the compact live-log lines,
 * and the terminal task state. The loop takes it as ONE nullable constructor parameter
 * (default null); a null listener leaves every existing path byte-identical, and every
 * callback is fire-and-forget — the listener can never steer the loop (the overlay is
 * presentation, not decision-making: ART-2 — every decision still routes through the
 * [DecisionEngine]; the owner's only loop-facing inputs ride the [GatePresenter] /
 * [CompletionCardPresenter] seams).
 *
 * Redaction law (doc 05 §2 / doc 06 §2a, binding here too): the listener may carry only
 * data already lawful for logs and rows. A candidate label is page text that can carry
 * a sanctioned post-injection echo, so the loop masks it at SOURCE through the same
 * redaction-key fold the step log uses ([StepFacts.label] never transits raw) — a
 * secret echo can therefore never reach the overlay through a label. The gate-card
 * payload (class/site/url/target/consequence) is card data rendered verbatim by the
 * card, not listener data.
 */
interface LoopUiListener {

    /**
     * An engine round-trip started (true) or ended (false) — the overlay's spinner
     * line (perf law b: within 1s of a decision round-trip). Fired around EVERY engine
     * consult (triage, per-step decide, recovery decide, the anchored noul judge, the
     * additive-engine consult), so the spinner is always truthful.
     */
    fun onThinking(active: Boolean)

    /**
     * The triage-time plan proposal arrived (Phase 08G [ProposedPlan]; provenance only,
     * ART-2 — context transparency on the cards, never an executor). The contract: the
     * loop fires whenever the plan source returned a NON-NULL proposal (no isEmpty
     * guard at the call site — but a [ProposedPlan] cannot carry empty subgoals, its
     * `init` fails loudly); [navigator.app.overlay.OverlayManager.updatePlan] nulls
     * empty lists so nothing ever renders for one.
     *
     * Phase 10: on LADDER runs this same callback carries the ACTIVE-SUBTASK display
     * (one line — the cards' PLAN section becomes the ACTIVE-SUBTASK display, phase doc
     * §2.4), fired at the initial restructure, on every pointer advance, and on every
     * re-plan.
     */
    fun onPlan(subgoals: List<String>)

    /**
     * The ladder block re-rendered (Phase 10, phase doc §2.4): the corner log's pinned
     * header ([navigator.app.overlay.LadderBlock.render]'s lines — the pure formatter
     * owns the text law). Fired at the initial restructure, on every pointer advance,
     * and on every re-plan. Default no-op (the additive law — existing paths
     * byte-identical).
     */
    fun onLadderBlock(lines: List<String>) {}

    /**
     * One logged navigation step's COMPACT product facts (the live-log line). Fired at
     * the LOG step; [StepFacts.label] is masked at source (see the interface KDoc).
     */
    fun onStep(facts: StepFacts)

    /** The task reached its one terminal state (vision 04 §3 exits). */
    fun onTaskState(state: TaskStateUpdate)

    /**
     * The run's recovery-row facts (2026-10-17 hotel iteration): the terminal's name and
     * whether ANY observation this run named an allowlisted browser as the ACTIVE window.
     * Fired once at the single finish() funnel. The corner log's recovery action row is
     * offered on browser-fixable refusals ONLY — the offer itself is the overlay edge's
     * PURE decision ([navigator.app.overlay.LiveLogRecovery]); this callback carries the
     * facts, never the decision (ART-2: fire-and-forget, it can never steer the loop).
     * Default no-op (the additive law — existing paths byte-identical).
     */
    fun onRecoveryOffer(terminal: String, everBrowserFront: Boolean) {}

    /** The compact product facts of one logged step (the live-log line's payload). */
    data class StepFacts(
        /** The loop's 0-based step index. */
        val stepIndex: Int,
        /** The acted action verb: tap / type / scroll / submit / back. */
        val verb: String,
        /**
         * The acted-on candidate label, ALREADY masked through the loop's
         * sanctioned-secret redaction fold ([TextSource.REDACTED] render) — masked at
         * source so a post-injection page echo can never reach the overlay.
         */
        val label: String,
        /** The primary's confidence for the chosen action; null when none. */
        val confidence: Double?,
        /** The gate marker: [UNGATED_MARKER] when the step ran ungated. */
        val gateMarker: String,
    )

    /** One terminal task state (vision 04 §3: exactly one terminal per task). */
    data class TaskStateUpdate(
        val terminal: TerminalState,
        /** The loop's own terminal detail line. */
        val detail: String,
    )

    companion object {
        /** The live-log gate marker of an ungated step (the phase doc's "gate: —"). */
        const val UNGATED_MARKER = "—"

        /**
         * The compact gate marker for the live-log line: "—" when the step ran
         * ungated, else the class + logged decision in plain words (e.g. "H9 approved");
         * a per-task trusted execution says so (doc 06 §2 `trustedFromTask`).
         */
        fun gateMarker(event: LoggedGateEvent?): String {
            if (event == null) return UNGATED_MARKER
            val hclass = serialNameOf(navigator.core.log.LoggedHClass.serializer(), event.hclass)
            val decision = event.decision.name.lowercase()
            return if (event.trustedFromTask) "$hclass $decision (trusted)" else "$hclass $decision"
        }

        /**
         * The compact live-log line (phase doc §2.1's shape:
         * "#12 tap 'Apple Pie — Allrecipes' · Jev 0.81 · gate: —" — rendered here
         * engine-agnostically: "#12 tap 'Apple Pie' · 0.81 · gate: —"). Confidence
         * renders to two decimals; a null confidence renders "—". Pure so the format is
         * JVM-pinnable (Gate F: compact-line formatting).
         */
        fun compactLine(facts: StepFacts): String {
            val confidence = facts.confidence
                ?.let { String.format(java.util.Locale.ROOT, "%.2f", it) }
                ?: UNGATED_MARKER
            return "#${facts.stepIndex} ${facts.verb} '${facts.label}' · $confidence · " +
                "gate: ${facts.gateMarker}"
        }

        /**
         * The terminal task-state line (the log's last line). USER_CANCEL renders the
         * owner-directive text "task CANCELLED — by owner" (the ⏹ control cancelled the
         * run — vision 04 §3's lawful owner exit); every other terminal renders the
         * loop's own "task <TERMINAL> — <detail>". Pure so the format is JVM-pinnable.
         */
        fun taskStateLine(state: TaskStateUpdate): String =
            if (state.terminal == TerminalState.USER_CANCEL) {
                "task CANCELLED — by owner"
            } else {
                "task ${state.terminal.name} — ${state.detail}"
            }
    }
}
