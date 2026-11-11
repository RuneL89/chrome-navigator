package navigator.app.loop

import navigator.core.enumerate.ObservedNode

/**
 * PHASE_18 Stage 18.B CN-03 — the execution request (doc 04 §9.2): everything the
 * pre-dispatch guard needs to re-verify the world FRESH, immediately before dispatch,
 * after model work and after any owner approval. Built from the step's decision-time
 * facts (the snapshot, the grounded target, the gate's approval); the guard resolves the
 * fresh counterpart at dispatch time and refuses on drift. NO TYPED TEXT ever enters a
 * request — the refusal evidence must stay secret-free (doc 06 §2a).
 *
 * Placement under `loop/` is deliberate (the plan's cross-engine contract): the types
 * are engine-agnostic; the vision loop consumes them this phase, the retained engine's
 * wiring changes nothing (Stage E's extraction consumes them later).
 *
 * @param snapshotId the [navigator.app.harness.vision.VisionSnapshot] the decision was
 *   made on ("" when the loop runs snapshotter-less — the snapshot-bound checks skip).
 * @param expectedBrowserPackage the ACTIVE package the snapshot saw; the guard requires
 *   the fresh ACTIVE root to name the SAME package (and always an allowlisted browser).
 * @param windowIdentity the snapshot's capture scope (bookkeeping/evidence).
 * @param navigationContext the snapshot's normalized navigation key; a fresh key that
 *   differs means the page navigated between decision and dispatch.
 * @param action the effective action kind.
 * @param x/y the dispatch coordinates when the action has a location (raw tap, node
 *   action, scroll start), null otherwise (back/submit).
 * @param target the fresh-target expectation built from the decision-time node: role
 *   (class), label/description, editable/clickable state, previous bounds. The
 *   observation-local node id is DELIBERATELY absent — ids are not durable identities
 *   across fresh reads (doc 04 §9.1); the match is semantic + bounds.
 * @param approvedRiskClass/approvedSite the gate's approved (class, site) key when an
 *   approval rode this action (a card approval, a per-task trust execution, or a card
 *   secret approval — doc 06 §2c binds ALL of them to their context); null = ungated.
 * @param recoveryReason the active-subtask reason a BACK serves (an arbitrary
 *   model-proposed back must leave the task context noticed, never silently).
 * @param frame the captured frame's screen-space bounds (origin + scope dims) — raw
 *   coordinates must sit inside the frame the models grounded against; null = no frame
 *   (the check skips honestly).
 * @param occluders the CURRENT occluder bounds (the live log + the soft keyboard) — raw
 *   touches at a covered point are refused (the behind-log law, doc 03 §2).
 */
data class ExecutionRequest(
    val snapshotId: String,
    val expectedBrowserPackage: String,
    val windowIdentity: String,
    val navigationContext: String,
    val action: GuardActionKind,
    val x: Int?,
    val y: Int?,
    val target: TargetExpectation?,
    val approvedRiskClass: String?,
    val approvedSite: String?,
    val recoveryReason: String?,
    val frame: FrameBounds?,
    val occluders: List<RectPxBox>,
) {
    /** Whether an owner approval (card, trust or secret) rides this request. */
    val gated: Boolean get() = approvedRiskClass != null && approvedSite != null
}

/** The effective action kinds the guard distinguishes. */
enum class GuardActionKind {
    /** ACTION_CLICK on the page node resolved at the point (the behind-log path). */
    NODE_CLICK,
    /** A raw gesture tap at a point. */
    RAW_TAP,
    /** Typed entry into a resolved editable field (SET_TEXT/paste under the executor). */
    TYPE_TEXT,
    /** The IME action on the fresh focused editable node. */
    SUBMIT_IME,
    /** The global back action. */
    GLOBAL_BACK,
    /** A raw scroll gesture from a start point. */
    SCROLL,
}

/** A plain screen-space box (the occluder/frame shape the guard consumes; JVM-pure). */
data class RectPxBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
}

/** The captured frame's screen-space scope (origin + dims), from a [VisionCapture]-shot. */
data class FrameBounds(val originX: Int, val originY: Int, val width: Int, val height: Int) {
    fun contains(x: Int, y: Int): Boolean =
        x in originX..(originX + width) && y in originY..(originY + height)
}

/**
 * The decision-time target, carried to the dispatch instant for a FRESH comparison.
 * Built from an [ObservedNode]; the id stays behind on purpose (semantic identity law).
 * [requireEditable]/[requireClickable] are the ROLE half: the fresh node must still be
 * the kind of control the action needs, not merely the same-looking rectangle.
 */
data class TargetExpectation(
    val className: String,
    val label: String,
    val description: String,
    val editable: Boolean,
    val clickable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** The action needs an editable fresh node (TYPE_TEXT). */
    val requireEditable: Boolean = false,
    /** The action needs a clickable-or-editable fresh node (node click). */
    val requireClickable: Boolean = false,
    /**
     * Resource-id when the observation layer captured one; the current reader does NOT
     * carry view ids, so this stays null today and the match leans on class+label+bounds
     * (recorded in the phase status deviations).
     */
    val viewId: String? = null,
) {
    companion object {
        /** From the decision-time node (id dropped; role flags default from the node). */
        fun from(node: ObservedNode): TargetExpectation = TargetExpectation(
            className = node.className,
            label = node.text,
            description = node.desc,
            editable = node.editable,
            clickable = node.clickable,
            left = node.left,
            top = node.top,
            right = node.right,
            bottom = node.bottom,
        )

        /** Role flags for a node-click expectation (the tappable rule). */
        fun forNodeClick(node: ObservedNode): TargetExpectation =
            from(node).copy(requireClickable = true)

        /** Role flags for a typed-entry expectation (the editable rule). */
        fun forType(node: ObservedNode): TargetExpectation =
            from(node).copy(requireEditable = true, clickable = node.clickable)
    }

    /**
     * The SEMANTIC identity match against a freshly resolved node: class equal, the
     * expected role held, the label/description equal (when expected non-blank), and
     * the fresh bounds compatible with the previous ones (overlap coefficient >= 0.5 —
     * small shifts pass, a moved-away control fails). The fresh node's id plays NO part.
     */
    fun matchesFresh(fresh: FreshTarget): Boolean {
        if (requireEditable && !fresh.editable) return false
        if (requireClickable && !(fresh.clickable || fresh.editable)) return false
        if (className.isNotBlank() && fresh.className.isNotBlank() &&
            className != fresh.className
        ) {
            return false
        }
        if (label.isNotBlank() && fresh.label.isNotBlank() &&
            label.trim() != fresh.label.trim()
        ) {
            return false
        }
        if (description.isNotBlank() && fresh.description.isNotBlank() &&
            description.trim() != fresh.description.trim()
        ) {
            return false
        }
        return boundsCompatible(fresh)
    }

    /** Overlap coefficient (intersection / smaller area) at the 0.5 floor. */
    private fun boundsCompatible(fresh: FreshTarget): Boolean {
        val interW = minOf(right, fresh.right) - maxOf(left, fresh.left)
        val interH = minOf(bottom, fresh.bottom) - maxOf(top, fresh.top)
        if (interW <= 0 || interH <= 0) return false
        val inter = interW.toLong() * interH.toLong()
        val own = (right - left).toLong() * (bottom - top).toLong()
        val theirs = (fresh.right - fresh.left).toLong() * (fresh.bottom - fresh.top).toLong()
        val smaller = minOf(own, theirs)
        if (smaller <= 0L) return false
        return inter * 2 >= smaller
    }
}
