package navigator.app.loop

/**
 * PHASE_18 Stage 18.B CN-03 — the CLOSED dispatch vocabulary (doc 04 §9.2): the guard's
 * typed verdicts. A refusal is EVIDENCE, not a dispatch — zero executor calls ride it —
 * and a refusal is not by itself a subtask failure: it routes to the loop's existing
 * retry/recovery semantics. Every detail string is evidence-grade: type names, node
 * roles and bounds — never typed text, never page credentials (doc 06 §2a).
 */
sealed interface ExecutionResult {

    /** The vocabulary name (rides step lines + evidence verbatim). */
    val label: String

    /** Evidence detail — secret-free by construction. */
    val detail: String

    /** Android accepted the guarded dispatch and the action returned true. */
    data class DispatchAccepted(override val detail: String = "dispatched") : ExecutionResult {
        override val label: String get() = "DISPATCH_ACCEPTED"
    }

    /** The guard passed but the platform/executor rejected the dispatch itself. */
    data class DispatchRejected(override val detail: String) : ExecutionResult {
        override val label: String get() = "DISPATCH_REJECTED"
    }

    /** The snapshot's context (navigation key) no longer matches the fresh read. */
    data class NotDispatchedStaleSnapshot(override val detail: String) : ExecutionResult {
        override val label: String get() = "NOT_DISPATCHED_STALE_SNAPSHOT"
    }

    /** The fresh ACTIVE window is not the expected production browser. */
    data class NotDispatchedWrongWindow(override val detail: String) : ExecutionResult {
        override val label: String get() = "NOT_DISPATCHED_WRONG_WINDOW"
    }

    /** The expected target is gone, changed role, moved out of bounds, or unresolvable. */
    data class NotDispatchedTargetChanged(override val detail: String) : ExecutionResult {
        override val label: String get() = "NOT_DISPATCHED_TARGET_CHANGED"
    }

    /** A raw touch would land under an occluder (the live log / the soft keyboard). */
    data class NotDispatchedOccluded(override val detail: String) : ExecutionResult {
        override val label: String get() = "NOT_DISPATCHED_OCCLUDED"
    }

    /** The fresh action is not the approved one — the approval is void, the action re-gates. */
    data class NotDispatchedRiskContextChanged(override val detail: String) : ExecutionResult {
        override val label: String get() = "NOT_DISPATCHED_RISK_CONTEXT_CHANGED"
    }
}
