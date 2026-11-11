package navigator.app.loop

import navigator.core.enumerate.ObservedNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PHASE_18 Stage 18.B CN-03 — the fresh pre-dispatch guard (doc 04 §9.2 + doc 06 §2c),
 * the plan's eight cases. Pure JVM over a fake [FreshContextReader]; $0, no device.
 *
 * PHASE_18 NOTE: this pins the CN-03 TARGET behavior just landed (Stage 18.B); the doc
 * 04 §9.2 items it maps to are 9.2 (the pre-dispatch guard) itself plus 9.1's
 * semantic-identity law.
 */
class PreDispatchGuardTest {

    private class FakeReader(
        var active: String = "com.android.chrome",
        var navKey: String? = "a.test",
        var focusedEditable: Boolean = false,
        var tappable: FreshTarget? = null,
        var editable: FreshTarget? = null,
    ) : FreshContextReader {
        override fun activePackage(): String = active
        override fun navigationKey(): String? = navKey
        override fun focusedEditable(): Boolean = focusedEditable
        override fun tappableAt(x: Int, y: Int): FreshTarget? = tappable
        override fun editableAt(x: Int, y: Int): FreshTarget? = editable
    }

    /** The decision-time expectation node (an [ObservedNode], as the loop builds it). */
    private fun expectedNode(
        className: String = "android.widget.Button",
        label: String = "Buy now",
        clickable: Boolean = true,
        editable: Boolean = false,
        left: Int = 100,
        top: Int = 500,
        right: Int = 400,
        bottom: Int = 600,
    ) = ObservedNode(
        id = "n7", text = label, desc = "", clickable = clickable, editable = editable,
        scrollable = false, left = left, top = top, right = right, bottom = bottom,
        className = className, sourceOrder = 7,
    )

    /** The fresh counterpart the guard re-resolves at dispatch time. */
    private fun freshNode(
        className: String = "android.widget.Button",
        label: String = "Buy now",
        clickable: Boolean = true,
        editable: Boolean = false,
        left: Int = 100,
        top: Int = 500,
        right: Int = 400,
        bottom: Int = 600,
    ) = FreshTarget(className, label, "", editable, clickable, left, top, right, bottom)

    private fun request(
        action: GuardActionKind,
        x: Int? = 250,
        y: Int? = 550,
        target: TargetExpectation? = null,
        gatedClass: String? = null,
        gatedSite: String? = null,
        navContext: String = "a.test",
        browser: String = "com.android.chrome",
        recovery: String? = null,
        frame: FrameBounds? = FrameBounds(0, 0, 1080, 2400),
        occluders: List<RectPxBox> = emptyList(),
    ) = ExecutionRequest(
        snapshotId = "snap-1",
        expectedBrowserPackage = browser,
        windowIdentity = "window:${browser}:id=9:bounds=0,0,1080,2400",
        navigationContext = navContext,
        action = action,
        x = x,
        y = y,
        target = target,
        approvedRiskClass = gatedClass,
        approvedSite = gatedSite,
        recoveryReason = recovery,
        frame = frame,
        occluders = occluders,
    )

    /** The refusal enum from a check (the verdict shape the loop consumes). */
    private fun refusalOf(verdict: PreDispatchGuard.Verdict): PreDispatchGuard.Refusal =
        (verdict as PreDispatchGuard.Verdict.Refuse).refusal

    private fun detailOf(verdict: PreDispatchGuard.Verdict): String =
        (verdict as PreDispatchGuard.Verdict.Refuse).detail

    // -- 1. another app becomes foreground after grounding: zero dispatch ------------
    @Test
    fun `another app foreground after grounding - zero dispatch (wrong window)`() {
        val verdict = PreDispatchGuard(FakeReader(active = "com.other.app"))
            .check(request(GuardActionKind.RAW_TAP))
        assertEquals(PreDispatchGuard.Refusal.WRONG_WINDOW, refusalOf(verdict))
        assertTrue(detailOf(verdict).contains("not an allowlisted browser"))
    }

    // -- 2. the browser visible in the window list but NOT active: zero raw dispatch --
    @Test
    fun `browser backgrounded - a foreign active window refuses the raw dispatch`() {
        // The window-list visibility is not what the guard reads: the ACTIVE root's
        // package is. A foreign active root (the browser backgrounded behind another
        // app) refuses even though the request names the browser as expected.
        val verdict = PreDispatchGuard(FakeReader(active = "com.android.systemui"))
            .check(request(GuardActionKind.RAW_TAP, browser = "com.android.chrome"))
        assertEquals(PreDispatchGuard.Refusal.WRONG_WINDOW, refusalOf(verdict))
    }

    // -- 3. the target disappears / changes role / moves out of bounds: zero dispatch -
    @Test
    fun `target gone at dispatch time - target changed`() {
        val verdict = PreDispatchGuard(FakeReader(tappable = null)).check(
            request(GuardActionKind.NODE_CLICK, target = TargetExpectation.forNodeClick(expectedNode())),
        )
        assertEquals(PreDispatchGuard.Refusal.TARGET_CHANGED, refusalOf(verdict))
    }

    @Test
    fun `target changes role - a button that turned into a read-only text refuses`() {
        val fresh = freshNode(clickable = false, editable = false, className = "android.widget.TextView")
        val verdict = PreDispatchGuard(FakeReader(tappable = fresh)).check(
            request(GuardActionKind.NODE_CLICK, target = TargetExpectation.forNodeClick(expectedNode())),
        )
        assertEquals(PreDispatchGuard.Refusal.TARGET_CHANGED, refusalOf(verdict))
    }

    @Test
    fun `target moves out of bounds - bounds-incompatible fresh node refuses`() {
        val fresh = freshNode(left = 800, top = 2000, right = 1000, bottom = 2100)
        val verdict = PreDispatchGuard(FakeReader(tappable = fresh)).check(
            request(GuardActionKind.NODE_CLICK, target = TargetExpectation.forNodeClick(expectedNode())),
        )
        assertEquals(PreDispatchGuard.Refusal.TARGET_CHANGED, refusalOf(verdict))
    }

    // -- 4. a fresh equivalent node with a changed observation-local id: may proceed --
    @Test
    fun `fresh equivalent node with a different id - proceeds on semantic identity`() {
        // The expectation carries NO id: a fresh node that matches class+label+bounds
        // (an ad-churn re-numbered tree) allows the dispatch.
        val verdict = PreDispatchGuard(FakeReader(tappable = freshNode())).check(
            request(GuardActionKind.NODE_CLICK, target = TargetExpectation.forNodeClick(expectedNode())),
        )
        assertEquals(PreDispatchGuard.Verdict.Allow, verdict)
    }

    // -- 5. the approval does not survive a changed target: re-gate -------------------
    @Test
    fun `approval bound to the old target - a changed fresh target voids it and re-gates`() {
        val fresh = freshNode(label = "Subscribe to newsletter")
        val verdict = PreDispatchGuard(FakeReader(tappable = fresh)).check(
            request(
                GuardActionKind.NODE_CLICK,
                target = TargetExpectation.forNodeClick(expectedNode()),
                gatedClass = "H2_PURCHASE_CONFIRMATION",
                gatedSite = "a.test",
            ),
        )
        assertEquals(PreDispatchGuard.Refusal.RISK_CONTEXT_CHANGED, refusalOf(verdict))
        assertTrue(detailOf(verdict).contains("is void and re-gates"))
    }

    @Test
    fun `approval bound to the old site - a drifted navigation voids it and re-gates`() {
        val verdict = PreDispatchGuard(FakeReader(navKey = "b.test")).check(
            request(
                GuardActionKind.RAW_TAP,
                gatedClass = "H2_PURCHASE_CONFIRMATION",
                gatedSite = "a.test",
            ),
        )
        assertEquals(PreDispatchGuard.Refusal.RISK_CONTEXT_CHANGED, refusalOf(verdict))
    }

    // -- 6. a covered point with no lawful node action: zero dispatch ------------------
    @Test
    fun `covered point - occluded refusal for a raw touch under the overlay`() {
        // A tappable node exists under the point; occlusion wins first (the behind-log
        // law's occlusion check precedes the target match).
        val verdict = PreDispatchGuard(FakeReader(tappable = freshNode())).check(
            request(
                GuardActionKind.RAW_TAP,
                x = 990,
                y = 2300,
                occluders = listOf(RectPxBox(900, 2200, 1080, 2400)),
            ),
        )
        assertEquals(PreDispatchGuard.Refusal.OCCLUDED, refusalOf(verdict))
    }

    // -- 7. a submit with no focused editable node: zero dispatch ---------------------
    @Test
    fun `submit without a focused editable node - zero dispatch`() {
        val verdict = PreDispatchGuard(FakeReader(focusedEditable = false)).check(
            request(GuardActionKind.SUBMIT_IME, x = null, y = null),
        )
        assertEquals(PreDispatchGuard.Refusal.TARGET_CHANGED, refusalOf(verdict))
    }

    @Test
    fun `submit with a focused editable node - allowed`() {
        val verdict = PreDispatchGuard(FakeReader(focusedEditable = true)).check(
            request(GuardActionKind.SUBMIT_IME, x = null, y = null),
        )
        assertEquals(PreDispatchGuard.Verdict.Allow, verdict)
    }

    // -- 8. evidence hygiene: refusals carry no typed text ----------------------------
    @Test
    fun `refusal evidence is secret-free by construction`() {
        // The request shape has NO text field; a drifted target's refusal names roles
        // and bounds only — whatever the card would have typed never enters evidence.
        val expectation = TargetExpectation.forType(expectedNode(editable = true))
        val fresh = freshNode(className = "android.widget.EditText", editable = false)
        val verdict = PreDispatchGuard(FakeReader(editable = fresh)).check(
            request(GuardActionKind.TYPE_TEXT, target = expectation),
        )
        assertEquals(PreDispatchGuard.Refusal.TARGET_CHANGED, refusalOf(verdict))
        assertTrue(!detailOf(verdict).contains("4111"))
    }

    // -- the back law: an unexplained context exit is refused --------------------------
    @Test
    fun `back without a recovery reason - refused as a risk-context change`() {
        val verdict = PreDispatchGuard(FakeReader()).check(
            request(GuardActionKind.GLOBAL_BACK, x = null, y = null),
        )
        assertEquals(PreDispatchGuard.Refusal.RISK_CONTEXT_CHANGED, refusalOf(verdict))
    }

    @Test
    fun `back with the active-subtask recovery reason - allowed`() {
        val verdict = PreDispatchGuard(FakeReader()).check(
            request(
                GuardActionKind.GLOBAL_BACK,
                x = null,
                y = null,
                recovery = "back serves the active subtask: read the headline",
            ),
        )
        assertEquals(PreDispatchGuard.Verdict.Allow, verdict)
    }

    // -- the stale-snapshot law: a drifted navigation key on an ungated action ---------
    @Test
    fun `navigation drift without an approval - stale snapshot, not a risk-context change`() {
        val verdict = PreDispatchGuard(FakeReader(navKey = "b.test")).check(
            request(GuardActionKind.RAW_TAP),
        )
        assertEquals(PreDispatchGuard.Refusal.STALE_SNAPSHOT, refusalOf(verdict))
    }
}
