package navigator.app.loop

import navigator.app.observe.BrowserPackages

/**
 * A freshly resolved target node, read from the CURRENT tree at dispatch time. Semantic
 * identity fields only — no observation-local id survives a fresh read (doc 04 §9.1).
 */
data class FreshTarget(
    val className: String,
    val label: String,
    val description: String,
    val editable: Boolean,
    val clickable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    /** Valid fresh bounds: a real, positive-area rectangle. */
    val hasValidBounds: Boolean get() = right > left && bottom > top
}

/**
 * The FRESH reads the guard performs at dispatch time (the Android implementation wraps
 * the a11y service; JVM tests fake it). Every method reads the CURRENT state — no cache,
 * no node reuse (vision 03 §1's fresh-root law at the dispatch instant).
 */
interface FreshContextReader {
    /**
     * The ACTIVE window's package (`rootInActiveWindow` — NEVER the window list: Chrome
     * visible-but-backgrounded must refuse). The production implementation waits
     * BOUNDED for an allowlisted browser (the GestureExecutor focus-gate precedent:
     * an owner card's dismissal re-fronts the browser asynchronously) and returns the
     * last-seen package when the budget expires.
     */
    fun activePackage(): String

    /** The fresh navigation key (the URL bar's host); null = unreadable (check skips). */
    fun navigationKey(): String?

    /** A focused EDITABLE node exists in the active window (the submit's fresh target). */
    fun focusedEditable(): Boolean

    /** The smallest clickable-or-editable fresh node containing the point (null = none). */
    fun tappableAt(x: Int, y: Int): FreshTarget?

    /** The editable fresh node containing the point (null = none). */
    fun editableAt(x: Int, y: Int): FreshTarget?
}

/**
 * PHASE_18 Stage 18.B CN-03 — the fresh pre-dispatch guard (doc 04 §9.2): AFTER model
 * work and AFTER any owner approval, IMMEDIATELY before dispatch, code re-verifies —
 * fresh — the expected browser foreground, the target re-resolved with its expected role
 * and valid bounds, no occlusion for raw touches, and — for a gated action — that the
 * fresh action is STILL the approved one. A changed site, target or classification voids
 * the approval (`NOT_DISPATCHED_RISK_CONTEXT_CHANGED`, doc 06 §2c) and the fresh action
 * re-gates through the loop's ordinary next-step machinery. The decision is PURE over
 * [FreshContextReader]; the dispatch primitive stays the caller's executor (the
 * GestureExecutor is never bypassed — GuardedActionExecutor delegates to it on Allow).
 */
class PreDispatchGuard(private val reader: FreshContextReader) {

    /** The pure verdict: allow the dispatch, or refuse with the typed reason + evidence. */
    sealed interface Verdict {
        data object Allow : Verdict
        data class Refuse(val refusal: Refusal, val detail: String) : Verdict
    }

    /** The refusal reasons (the NOT_DISPATCHED_* half of the closed vocabulary). */
    enum class Refusal { STALE_SNAPSHOT, WRONG_WINDOW, TARGET_CHANGED, OCCLUDED, RISK_CONTEXT_CHANGED }

    /**
     * The occlusion margin for raw touches (the behind-log law's margin — the same 16px
     * the vision loop's covered-point guard uses).
     */
    companion object {
        const val OCCLUSION_MARGIN_PX = 16
    }

    fun check(request: ExecutionRequest): Verdict {
        // 1. The fresh ACTIVE window must be the expected PRODUCTION browser. A browser
        //    visible in the window list but not active has a foreign/absent active root.
        val active = reader.activePackage()
        if (!BrowserPackages.isBrowser(active)) {
            return refuse(
                Refusal.WRONG_WINDOW,
                "active window package '${active.take(40)}' is not an allowlisted browser",
            )
        }
        if (request.expectedBrowserPackage.isNotBlank() &&
            request.expectedBrowserPackage != active
        ) {
            return refuse(
                Refusal.WRONG_WINDOW,
                "active window package '${active.take(40)}' is not the expected " +
                    "'${request.expectedBrowserPackage}'",
            )
        }
        // 2. The navigation context: a drifted key means the page navigated between the
        //    snapshot and dispatch. With an approval attached this voids the approval
        //    first (doc 06 §2c — the (class, site) key no longer names this site).
        val freshKey = reader.navigationKey()
        if (request.navigationContext.isNotBlank() && freshKey != null && freshKey.isNotBlank()) {
            if (request.navigationContext != freshKey) {
                return if (request.gated) {
                    refuse(
                        Refusal.RISK_CONTEXT_CHANGED,
                        "the approved site '${request.approvedSite}' no longer matches the " +
                            "fresh site '$freshKey' - the approval is void and the fresh action re-gates",
                    )
                } else {
                    refuse(
                        Refusal.STALE_SNAPSHOT,
                        "the page navigated after the snapshot (context '${request.navigationContext}' " +
                            "-> fresh '$freshKey')",
                    )
                }
            }
        }
        // 3. The action-specific fresh checks.
        return when (request.action) {
            GuardActionKind.NODE_CLICK -> checkNodeAction(request, request.x, request.y)
            GuardActionKind.RAW_TAP -> checkRawTouch(request, request.x, request.y)
            GuardActionKind.TYPE_TEXT -> {
                val x = request.x ?: return refuse(Refusal.TARGET_CHANGED, "typed entry carries no coordinates")
                val y = request.y ?: return refuse(Refusal.TARGET_CHANGED, "typed entry carries no coordinates")
                val fresh = reader.editableAt(x, y)
                    ?: return refuse(Refusal.TARGET_CHANGED, "no editable field at ($x,$y) at dispatch time")
                checkExpectation(request, fresh)
            }
            GuardActionKind.SUBMIT_IME ->
                if (reader.focusedEditable()) Verdict.Allow
                else refuse(Refusal.TARGET_CHANGED, "no focused editable node at dispatch time - the IME submit has no target")
            GuardActionKind.GLOBAL_BACK ->
                if (request.recoveryReason.isNullOrBlank()) {
                    refuse(
                        Refusal.RISK_CONTEXT_CHANGED,
                        "back requested without an active-subtask recovery reason - an unexplained " +
                            "context exit is refused",
                    )
                } else {
                    Verdict.Allow
                }
            GuardActionKind.SCROLL -> checkRawTouch(request, request.x, request.y)
        }
    }

    /** Node actions re-resolve the target fresh, require its role and valid bounds. */
    private fun checkNodeAction(request: ExecutionRequest, x: Int?, y: Int?): Verdict {
        val px = x ?: return refuse(Refusal.TARGET_CHANGED, "node action carries no coordinates")
        val py = y ?: return refuse(Refusal.TARGET_CHANGED, "node action carries no coordinates")
        val fresh = reader.tappableAt(px, py)
            ?: return refuse(Refusal.TARGET_CHANGED, "no clickable/editable node at ($px,$py) at dispatch time")
        if (!fresh.hasValidBounds) {
            return refuse(Refusal.TARGET_CHANGED, "the fresh node at ($px,$py) carries invalid bounds")
        }
        return checkExpectation(request, fresh)
    }

    /** Raw touches require fresh coordinates inside the captured frame and no occlusion. */
    private fun checkRawTouch(request: ExecutionRequest, x: Int?, y: Int?): Verdict {
        val px = x ?: return refuse(Refusal.TARGET_CHANGED, "raw dispatch carries no coordinates")
        val py = y ?: return refuse(Refusal.TARGET_CHANGED, "raw dispatch carries no coordinates")
        // Coordinates must sit inside the frame the models grounded against (when there
        // is one; the no-frame text-only degrade skips this honestly).
        request.frame?.let { frame ->
            if (!frame.contains(px, py)) {
                return refuse(Refusal.TARGET_CHANGED, "point ($px,$py) is outside the captured frame")
            }
        }
        val margin = OCCLUSION_MARGIN_PX
        val covered = request.occluders.any { occ ->
            px >= occ.left - margin && px <= occ.right + margin &&
                py >= occ.top - margin && py <= occ.bottom + margin
        }
        if (covered) {
            return refuse(Refusal.OCCLUDED, "point ($px,$py) sits under an overlay or the keyboard")
        }
        // Target bounds where a target exists: an expectation riding a raw touch (the
        // grounded raw arm) must still resolve to a compatible live node.
        val expectation = request.target
        if (expectation != null) {
            val fresh = reader.tappableAt(px, py)
                ?: return refuse(Refusal.TARGET_CHANGED, "no clickable/editable node at ($px,$py) at dispatch time")
            return checkExpectation(request, fresh)
        }
        return Verdict.Allow
    }

    /** The expectation match; a drift voids an attached approval (doc 06 §2c). */
    private fun checkExpectation(request: ExecutionRequest, fresh: FreshTarget): Verdict {
        val expectation = request.target
            ?: return Verdict.Allow // no expectation to compare — context checks carried the weight
        if (expectation.matchesFresh(fresh)) return Verdict.Allow
        return if (request.gated) {
            refuse(
                Refusal.RISK_CONTEXT_CHANGED,
                "the fresh target at (${fresh.left},${fresh.top},${fresh.right},${fresh.bottom}) does not " +
                    "match the approved target (class '${expectation.className.take(40)}') - the approval " +
                    "for (${request.approvedRiskClass} on ${request.approvedSite}) is void and re-gates",
            )
        } else {
            refuse(
                Refusal.TARGET_CHANGED,
                "the fresh node at (${fresh.left},${fresh.top},${fresh.right},${fresh.bottom}) does not " +
                    "match the expected target (class '${expectation.className.take(40)}', " +
                    "expected bounds (${expectation.left},${expectation.top},${expectation.right},${expectation.bottom}))",
            )
        }
    }

    private fun refuse(refusal: Refusal, detail: String): Verdict = Verdict.Refuse(refusal, detail)
}
