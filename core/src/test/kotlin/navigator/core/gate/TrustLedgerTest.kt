package navigator.core.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gate 4.2: the trust state machine (isolation). Every rule of vision 06 §2 asserted:
 * no trust -> card; approve -> execute + grant (class, site) trust; same-class
 * same-site auto-execute; same-class OTHER-site still gates; deny -> skip; double deny
 * (DENY or TIMEOUT) of the same (class, site) in one task -> clean ABORT_TASK; trust
 * never crosses tasks. Illegal transitions are rejected loudly.
 */
class TrustLedgerTest {

    private fun gate(): TaskGate = TaskGate()

    private fun request(hclass: HClass = HClass.H2_PURCHASE_CONFIRM, site: String = "shop.example") =
        GateEvent.REQUEST(hclass, site)

    // -- the core cycle ------------------------------------------------------------------

    @Test
    fun `no trust opens the gate with a confirmation ask`() {
        val decision = gate().decide(request())
        assertEquals(GateDecision.ASK, decision)
    }

    @Test
    fun `approval executes once and grants per-task trust for that class and site`() {
        val g = gate()
        g.decide(request(HClass.H1_PAYMENT, "pay.example"))
        val approved = g.decide(GateEvent.APPROVE)
        assertEquals(GateDecision.EXECUTE(trustedFromTask = false), approved)
    }

    @Test
    fun `trusted class+site auto-executes without asking (trustedFromTask=true)`() {
        val g = gate()
        g.decide(request(HClass.H2_PURCHASE_CONFIRM, "shop.example"))
        g.decide(GateEvent.APPROVE)
        val again = g.decide(request(HClass.H2_PURCHASE_CONFIRM, "shop.example"))
        assertEquals(GateDecision.EXECUTE(trustedFromTask = true), again)
    }

    @Test
    fun `trust never crosses sites - class approved on site A still gates on site B`() {
        val g = gate()
        g.decide(request(HClass.H2_PURCHASE_CONFIRM, "shop-a.example"))
        g.decide(GateEvent.APPROVE)
        assertEquals(GateDecision.ASK, g.decide(request(HClass.H2_PURCHASE_CONFIRM, "shop-b.example")))
    }

    @Test
    fun `trust never crosses classes on the same site`() {
        val g = gate()
        g.decide(request(HClass.H2_PURCHASE_CONFIRM, "shop.example"))
        g.decide(GateEvent.APPROVE)
        assertEquals(GateDecision.ASK, g.decide(request(HClass.H1_PAYMENT, "shop.example")))
        // resolve the pending H1 card (denied) before the next class asks
        assertEquals(GateDecision.SKIP, g.decide(GateEvent.DENY))
        assertEquals(GateDecision.ASK, g.decide(request(HClass.H8_CREDENTIAL_TYPING, "shop.example")))
    }

    // -- deny / timeout ------------------------------------------------------------------

    @Test
    fun `deny skips the action and the task continues (a second ask is allowed)`() {
        val g = gate()
        g.decide(request())
        assertEquals(GateDecision.SKIP, g.decide(GateEvent.DENY))
        // the loop re-decides on the next step; the same action may be asked once more
        assertEquals(GateDecision.ASK, g.decide(request()))
    }

    @Test
    fun `same class+site denied twice in one task aborts the task`() {
        val g = gate()
        g.decide(request())
        assertEquals(GateDecision.SKIP, g.decide(GateEvent.DENY))
        g.decide(request())
        assertEquals(GateDecision.ABORT_TASK, g.decide(GateEvent.DENY))
    }

    @Test
    fun `two denials of DIFFERENT class+site keys never abort`() {
        val g = gate()
        g.decide(request(HClass.H2_PURCHASE_CONFIRM, "shop.example"))
        assertEquals(GateDecision.SKIP, g.decide(GateEvent.DENY))
        g.decide(request(HClass.H7_DOWNLOAD, "shop.example"))
        assertEquals(GateDecision.SKIP, g.decide(GateEvent.DENY))
        // both keys are still one refusal each: a third, distinct request still asks
        assertEquals(GateDecision.ASK, g.decide(request(HClass.H6_UPLOAD, "shop.example")))
    }

    @Test
    fun `timeout is decided exactly like deny (30s expiry is a refusal)`() {
        val g = gate()
        g.decide(request())
        assertEquals(GateDecision.SKIP, g.decide(GateEvent.TIMEOUT))
        g.decide(request())
        assertEquals(GateDecision.ABORT_TASK, g.decide(GateEvent.TIMEOUT))
    }

    @Test
    fun `deny then timeout on the same key also aborts (timeouts count as denials)`() {
        val g = gate()
        g.decide(request())
        assertEquals(GateDecision.SKIP, g.decide(GateEvent.DENY))
        g.decide(request())
        assertEquals(GateDecision.ABORT_TASK, g.decide(GateEvent.TIMEOUT))
    }

    // -- task boundary ---------------------------------------------------------------------

    @Test
    fun `trust never crosses tasks - a fresh TaskGate asks again`() {
        val first = gate()
        first.decide(request(HClass.H4_LOGIN, "login.example"))
        first.decide(GateEvent.APPROVE)

        val second = TaskGate() // the loop constructs one per task — no shared trust set
        assertEquals(GateDecision.ASK, second.decide(request(HClass.H4_LOGIN, "login.example")))
    }

    @Test
    fun `an explicitly shared trust set is the only way trust survives, and it stays scoped`() {
        val shared = mutableSetOf<Pair<HClass, String>>()
        val first = TaskGate(shared)
        first.decide(request(HClass.H4_LOGIN, "login.example"))
        first.decide(GateEvent.APPROVE)
        assertTrue(
            shared.single() == (HClass.H4_LOGIN to "login.example"),
            "trust is exactly the (class, site) pair — nothing broader",
        )
    }

    // -- illegal transitions are rejected loudly ---------------------------------------------

    @Test
    fun `approve with no pending request is illegal`() {
        val e = assertFailsWith<IllegalGateTransitionException> { gate().decide(GateEvent.APPROVE) }
        assertTrue("APPROVE" in e.message!!)
    }

    @Test
    fun `deny with no pending request is illegal`() {
        assertFailsWith<IllegalGateTransitionException> { gate().decide(GateEvent.DENY) }
    }

    @Test
    fun `timeout with no pending request is illegal`() {
        assertFailsWith<IllegalGateTransitionException> { gate().decide(GateEvent.TIMEOUT) }
    }

    @Test
    fun `a request while another request is pending is illegal (resolve the open card first)`() {
        val g = gate()
        g.decide(request(HClass.H1_PAYMENT, "pay.example"))
        assertFailsWith<IllegalGateTransitionException> {
            g.decide(request(HClass.H2_PURCHASE_CONFIRM, "pay.example"))
        }
    }

    @Test
    fun `no gate event is accepted after the task aborted`() {
        val g = gate()
        g.decide(request())
        g.decide(GateEvent.DENY)
        g.decide(request())
        assertEquals(GateDecision.ABORT_TASK, g.decide(GateEvent.DENY))
        assertFailsWith<IllegalGateTransitionException> { g.decide(request()) }
        assertFailsWith<IllegalGateTransitionException> { g.decide(GateEvent.APPROVE) }
        assertFailsWith<IllegalGateTransitionException> { g.decide(GateEvent.DENY) }
        assertFailsWith<IllegalGateTransitionException> { g.decide(GateEvent.TIMEOUT) }
    }

    @Test
    fun `a trusted request leaves nothing pending, so resolving afterwards is illegal`() {
        val g = gate()
        g.decide(request())
        g.decide(GateEvent.APPROVE)
        g.decide(request()) // trusted auto-execute — no card was shown
        assertFailsWith<IllegalGateTransitionException> { g.decide(GateEvent.APPROVE) }
    }

    @Test
    fun `blank site on a request is rejected loudly`() {
        assertFailsWith<IllegalGateTransitionException> { gate().decide(request(site = "")) }
    }

    @Test
    fun `the gate timeout constant is the 30s law (the timer lives with the UI)`() {
        assertEquals(30L, TaskGate.TIMEOUT_SECONDS)
    }
}
