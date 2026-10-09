package navigator.app.loop

import navigator.core.log.LoggedGateDecision
import navigator.core.log.LoggedHClass
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Gate F (Phase 9, phase doc §2.1) — the compact live-log line's pure formatting law:
 * the ungated marker is "—" (the phase doc's "gate: —"), a gated step names the class +
 * logged decision (per-task trust says so), the confidence renders to two decimals
 * (or "—" when absent), and the label renders VERBATIM — it arrives at the formatter
 * already masked at source (the loop's redaction fold), so the raw secret never
 * transits anywhere. Pure JVM; no Android types.
 */
class LoopUiListenerTest {

    private fun facts(
        label: String = "Apple Pie — Allrecipes",
        confidence: Double? = 0.81,
        gateMarker: String? = null,
        stepIndex: Int = 12,
        verb: String = "tap",
    ) = LoopUiListener.StepFacts(
        stepIndex = stepIndex,
        verb = verb,
        label = label,
        confidence = confidence,
        gateMarker = gateMarker ?: LoopUiListener.UNGATED_MARKER,
    )

    @Test
    fun `the compact line renders the phase-doc shape - ungated step`() {
        assertEquals(
            "#12 tap 'Apple Pie — Allrecipes' · 0.81 · gate: —",
            LoopUiListener.compactLine(facts()),
        )
    }

    @Test
    fun `the compact line renders the gate marker and the verb`() {
        assertEquals(
            "#3 submit 'Sign in' · 0.44 · gate: H9 approved (trusted)",
            LoopUiListener.compactLine(
                facts(verb = "submit", label = "Sign in", confidence = 0.44, gateMarker = "H9 approved (trusted)", stepIndex = 3),
            ),
        )
    }

    @Test
    fun `a null confidence renders the ungated marker in its slot`() {
        assertEquals(
            "#1 scroll 'scroll down' · — · gate: —",
            LoopUiListener.compactLine(facts(verb = "scroll", label = "scroll down", confidence = null, stepIndex = 1)),
        )
    }

    @Test
    fun `confidence renders to exactly two decimals`() {
        assertEquals(
            "#0 tap 'x' · 0.80 · gate: —",
            LoopUiListener.compactLine(facts(label = "x", confidence = 0.8, stepIndex = 0)),
        )
        assertEquals(
            "#0 tap 'x' · 1.00 · gate: —",
            LoopUiListener.compactLine(facts(label = "x", confidence = 0.999999, stepIndex = 0)),
        )
    }

    @Test
    fun `the masked label renders verbatim - masking happens at source, not in the formatter`() {
        assertEquals(
            "#2 type '[credential-redacted]' · 0.90 · gate: H8 approved",
            LoopUiListener.compactLine(
                facts(verb = "type", label = "[credential-redacted]", confidence = 0.90, gateMarker = "H8 approved", stepIndex = 2),
            ),
        )
    }

    @Test
    fun `gateMarker of a null event is the ungated marker`() {
        assertEquals(LoopUiListener.UNGATED_MARKER, LoopUiListener.gateMarker(null))
        assertEquals("—", LoopUiListener.UNGATED_MARKER)
    }

    @Test
    fun `gateMarker names the class and the logged decision`() {
        assertEquals(
            "H9 approved",
            LoopUiListener.gateMarker(
                navigator.core.log.LoggedGateEvent(LoggedHClass.H9, LoggedGateDecision.APPROVED, false),
            ),
        )
        assertEquals(
            "H8 denied",
            LoopUiListener.gateMarker(
                navigator.core.log.LoggedGateEvent(LoggedHClass.H8, LoggedGateDecision.DENIED, false),
            ),
        )
        assertEquals(
            "H3 timeout",
            LoopUiListener.gateMarker(
                navigator.core.log.LoggedGateEvent(LoggedHClass.H3, LoggedGateDecision.TIMEOUT, false),
            ),
        )
    }

    @Test
    fun `gateMarker says when the execution rode per-task trust`() {
        assertEquals(
            "H9 approved (trusted)",
            LoopUiListener.gateMarker(
                navigator.core.log.LoggedGateEvent(LoggedHClass.H9, LoggedGateDecision.APPROVED, true),
            ),
        )
    }

    // -- the terminal task-state line (UAT 9.1 close-out: the ⏹ control) --------------

    @Test
    fun `the owner-cancel terminal renders the by-owner line`() {
        assertEquals(
            "task CANCELLED — by owner",
            LoopUiListener.taskStateLine(
                LoopUiListener.TaskStateUpdate(
                    TerminalState.USER_CANCEL,
                    "cancelled before step 3",
                ),
            ),
        )
    }

    @Test
    fun `every other terminal renders the loop's own terminal and detail`() {
        assertEquals(
            "task GOAL_ACHIEVED — anchored HIGH",
            LoopUiListener.taskStateLine(
                LoopUiListener.TaskStateUpdate(TerminalState.GOAL_ACHIEVED, "anchored HIGH"),
            ),
        )
        assertEquals(
            "task BUDGET_EXHAUSTED — steps: 40",
            LoopUiListener.taskStateLine(
                LoopUiListener.TaskStateUpdate(TerminalState.BUDGET_EXHAUSTED, "steps: 40"),
            ),
        )
    }
}
