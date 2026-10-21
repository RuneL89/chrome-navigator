package navigator.app.loop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Gate 8J.1 — the ExitPolicy matrix (vision 04 §3 v1.2.0 + the v1.6.0 amendments):
 * path boundaries (0.70 exclusive / 0.55 inclusive, per Bands semantics), K=3
 * consecutive-check counting (the streak RESETS on any failing check), the once-per-task
 * card, the M=2 anti-oscillation window cleared on a page-hash change — and the Phase
 * 10B v1.6.0 laws: the sustained streak is KEYED to the active done-condition's rendered
 * text (it survives a re-plan that keeps the condition and resets when the condition or
 * the pointer changes), and the bounded held-hash re-check API (every 3rd step, at most
 * 3 per hash) is code config.
 */
class ExitPolicyTest {

    /** The condition key the single-condition tests run under (the rendered done-condition). */
    private val key = "subtask:the URL host is wikipedia.org"

    // -- Path A (classic): HIGH band + a STRONG guard on LADDER runs (v1.7.0) ---------

    @Test
    fun `path A accepts at the first check above the high floor with a STRONG guard`() {
        val policy = ExitPolicy()
        val decision = policy.onFinishCheck(key, GuardLevel.STRONG, 0.99, ladderRun = true)
        assertIs<ExitDecision.Accept>(decision)
        assertEquals(ExitPath.HIGH, decision.path)
        assertEquals(GuardLevel.STRONG, decision.guardLevel)
        assertEquals(1, policy.finishChecks)
    }

    @Test
    fun `a WEAK guard cannot accept via path A on a LADDER run - it routes to B or C`() {
        // v1.7.0 (doc 04 §3, Phase 10C): the on-device battery showed weak subtask
        // conditions silently accepting conditions already true where the subtask started
        // (false completions) — on ladder runs WEAK falls through to the sustained path
        // or the owner card, never a silent accept.
        val policy = ExitPolicy()
        val decision = policy.onFinishCheck(key, GuardLevel.WEAK, 0.99, ladderRun = true)
        assertIs<ExitDecision.Refuse>(decision)
        assertEquals(GuardLevel.WEAK, decision.guardLevel)
        assertEquals(1, policy.finishChecks)
        // The refusal must name the REAL cause: the noul cleared the floor and the GUARD
        // is the blocker (VEXP9 runs 1 and 3 printed the opposite and misled the analysis).
        assertTrue(decision.reason.contains("the guard is WEAK"), decision.reason)
        assertFalse(decision.reason.contains("below the high floor"), decision.reason)
    }

    @Test
    fun `a WEAK guard still accepts via path A on a LEGACY run - the class-predicate law is byte-stable`() {
        // v1.7.0 scope refinement: non-ladder (harness/scripted) runs keep the v1.2.0
        // law — their WEAK is the class predicate (designed completion evidence for
        // old-style goals), never a proxy subtask condition.
        val policy = ExitPolicy()
        val decision = policy.onFinishCheck(key, GuardLevel.WEAK, 0.99)
        assertIs<ExitDecision.Accept>(decision)
        assertEquals(ExitPath.HIGH, decision.path)
    }

    @Test
    fun `path A boundary - exactly the high floor is the UNCERTAIN band and is refused`() {
        val policy = ExitPolicy()
        // band(): HIGH is strictly > 0.70; 0.70 itself is UNCERTAIN -> no Path A
        val atFloor = policy.onFinishCheck(key, GuardLevel.STRONG, ExitPolicyConfig.HIGH_FLOOR)
        assertIs<ExitDecision.Refuse>(atFloor)
        // one micro-step above the floor IS the HIGH band -> Path A
        val above = policy.onFinishCheck(key, GuardLevel.STRONG, ExitPolicyConfig.HIGH_FLOOR + 1e-9)
        assertIs<ExitDecision.Accept>(above)
        assertEquals(ExitPath.HIGH, above.path)
    }

    @Test
    fun `a NONE guard refuses even a maximal noul - no path accepts without code evidence`() {
        val policy = ExitPolicy()
        val decision = policy.onFinishCheck(key, GuardLevel.NONE, 0.99)
        assertIs<ExitDecision.Refuse>(decision)
        assertTrue(decision.reason.contains("guard failed"))
        assertEquals(GuardLevel.NONE, decision.guardLevel)
    }

    @Test
    fun `a NONE guard refusal carries the guard's detail - the pending count, named (Phase 08G)`() {
        val policy = ExitPolicy()
        val decision = policy.onFinishCheck(
            conditionKey = key,
            guardLevel = GuardLevel.NONE,
            noul = 0.99,
            guardDetail = "2 target(s) still pending (drained=false)",
        )
        assertIs<ExitDecision.Refuse>(decision)
        assertEquals(
            "the completion guard failed (NONE): 2 target(s) still pending (drained=false)",
            decision.reason,
        )
        // the detail never changes the verdict: NONE refuses regardless
        assertEquals(GuardLevel.NONE, decision.guardLevel)
    }

    // -- Path B (sustained): STRONG + >= 0.55 over K=3 CONSECUTIVE checks -------------

    @Test
    fun `path B accepts on the third consecutive STRONG sustained check`() {
        val policy = ExitPolicy()
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.55))
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.55))
        val third = policy.onFinishCheck(key, GuardLevel.STRONG, 0.55)
        assertIs<ExitDecision.Accept>(third)
        assertEquals(ExitPath.SUSTAINED, third.path)
        assertEquals(3, policy.finishChecks)
    }

    @Test
    fun `path B floor is inclusive - exactly the sustained floor sustains`() {
        val policy = ExitPolicy()
        assertEquals(ExitPolicyConfig.SUSTAINED_FLOOR, 0.55)
        val decision = policy.onFinishCheck(key, GuardLevel.STRONG, 0.55)
        // first sustained check: streak 1 of 3, refused for now
        assertIs<ExitDecision.Refuse>(decision)
    }

    @Test
    fun `a check below the sustained floor resets the streak - K counts CONSECUTIVE checks`() {
        val policy = ExitPolicy()
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.60)) // streak 1
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.61)) // streak 2
        // 0.44 resets the streak AND is the third guard-pass refusal -> the card asks
        assertIs<ExitDecision.AskOwner>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.44)) // streak RESET
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.60)) // streak 1
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.61)) // streak 2
        val sixth = policy.onFinishCheck(key, GuardLevel.STRONG, 0.60) // streak 3 -> accept
        assertIs<ExitDecision.Accept>(sixth)
        assertEquals(ExitPath.SUSTAINED, sixth.path)
        assertEquals(6, policy.finishChecks)
    }

    @Test
    fun `a WEAK guard cannot sustain even with a passing noul`() {
        val policy = ExitPolicy()
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.WEAK, 0.60))
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.WEAK, 0.60))
        val third = policy.onFinishCheck(key, GuardLevel.WEAK, 0.60)
        // not sustained (WEAK) - but 3 guard-pass refusals now ask the owner (Path C)
        assertIs<ExitDecision.AskOwner>(third)
    }

    @Test
    fun `a guard-failed check resets both streaks`() {
        val policy = ExitPolicy()
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.60)) // streak 1, refusals 1
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.60)) // streak 2, refusals 2
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.NONE, 0.60)) // everything resets
        val fourth = policy.onFinishCheck(key, GuardLevel.STRONG, 0.60)
        assertIs<ExitDecision.Refuse>(fourth)
        // the refusal streak restarted too: 1 guard-pass refusal, not 3 - no card ask
        assertTrue(fourth.reason.contains("1/3"), "streak restarted: ${fourth.reason}")
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.30)) // refusals 2
        val card = policy.onFinishCheck(key, GuardLevel.STRONG, 0.30) // refusal 3 -> the card
        assertIs<ExitDecision.AskOwner>(card)
    }

    // -- Streak re-keying (v1.6.0): the streak is keyed to the ACTIVE done-condition --

    @Test
    fun `the sustained streak survives a re-plan that keeps the condition`() {
        // vision 04 §3 v1.6.0: a re-plan is not evidence against arrival — two checks
        // under the same condition, a "re-plan" gap (no checks), then the third check:
        // the streak continues and Path B accepts on the same condition's third check.
        val policy = ExitPolicy()
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.58)) // streak 1
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.59)) // streak 2
        val third = policy.onFinishCheck(key, GuardLevel.STRONG, 0.60) // SAME condition -> streak 3
        assertIs<ExitDecision.Accept>(third)
        assertEquals(ExitPath.SUSTAINED, third.path)
    }

    @Test
    fun `a condition change resets the sustained streak - evidence under the old condition says nothing`() {
        val otherKey = "subtask:the text 'Mediterranean cuisine' appears on the page"
        val policy = ExitPolicy()
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.58)) // streak 1, refusals 1
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.59)) // streak 2, refusals 2
        // the pointer advanced: a NEW condition — the sustained streak resets (streak 1
        // on the new condition) and the guard-pass refusal counter (not keyed) hits K:
        // the completion card asks here, once per task.
        val card = policy.onFinishCheck(otherKey, GuardLevel.STRONG, 0.60)
        assertIs<ExitDecision.AskOwner>(card)
        assertEquals(1, policy.completionCardsShown)
        // the owner keeps going; the new condition's own streak continues
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(otherKey, GuardLevel.STRONG, 0.60)) // streak 2
        // the accept lands on the NEW condition's own third consecutive check
        val accepted = policy.onFinishCheck(otherKey, GuardLevel.STRONG, 0.60)
        assertIs<ExitDecision.Accept>(accepted)
        assertEquals(ExitPath.SUSTAINED, accepted.path)
        assertEquals(5, policy.finishChecks, "five checks were evaluated across the two conditions")
    }

    @Test
    fun `a blank condition key fails loudly - the streak is keyed to it`() {
        val policy = ExitPolicy()
        assertThrows<IllegalArgumentException> {
            policy.onFinishCheck("  ", GuardLevel.STRONG, 0.60)
        }
    }

    // -- Path C (owner arbiter): K guard-pass refusals, card once per task ------------

    @Test
    fun `the completion card asks on the Kth guard-pass refusal and only once per task`() {
        val policy = ExitPolicy()
        // 0.30 keeps the sustained streak at zero: pure Path C territory
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.30))
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.30))
        val third = policy.onFinishCheck(key, GuardLevel.STRONG, 0.30)
        assertIs<ExitDecision.AskOwner>(third)
        assertEquals(3, third.refusedChecks)
        assertEquals(1, policy.completionCardsShown)
        // the owner keeps going: later refusals never re-ask (once per task)
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.30))
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.30))
        assertIs<ExitDecision.Refuse>(policy.onFinishCheck(key, GuardLevel.STRONG, 0.30))
        assertEquals(1, policy.completionCardsShown)
    }

    // -- Anti-oscillation: the M=2 exclusion window ------------------------------------

    @Test
    fun `a refused finish excludes finish for the next M decisions on an unchanged page`() {
        val policy = ExitPolicy()
        policy.onRefusedFinish(screenHash = 100)
        assertTrue(policy.finishExcluded(100))
        policy.noteDecision(100)
        assertTrue(policy.finishExcluded(100), "one of M=2 decisions consumed")
        policy.noteDecision(100)
        assertFalse(policy.finishExcluded(100), "M=2 exhausted - finish is offered again")
    }

    @Test
    fun `a page-hash change clears the window immediately`() {
        val policy = ExitPolicy()
        policy.onRefusedFinish(screenHash = 100)
        assertTrue(policy.finishExcluded(100))
        assertFalse(policy.finishExcluded(200), "the page changed - the window is gone")
        assertFalse(policy.finishExcluded(100), "and it stays gone (never re-arms on the old hash)")
        // a later refusal on the new page starts a fresh window
        policy.onRefusedFinish(screenHash = 200)
        assertTrue(policy.finishExcluded(200))
    }

    @Test
    fun `no window exists before any refusal`() {
        val policy = ExitPolicy()
        assertFalse(policy.finishExcluded(100))
        policy.noteDecision(100)
        assertFalse(policy.finishExcluded(100))
    }

    // -- Held-hash re-checks (v1.6.0): the bounded re-key API --------------------------

    @Test
    fun `held-hash re-checks fire every third step, at most three per hash`() {
        val policy = ExitPolicy(config = ExitPolicyConfig())
        // before the cadence: not due
        assertFalse(policy.heldHashRecheckDue(stepsSinceLastCheck = 1, checksOnThisHash = 0))
        assertFalse(policy.heldHashRecheckDue(stepsSinceLastCheck = 2, checksOnThisHash = 0))
        assertTrue(policy.heldHashRecheckDue(stepsSinceLastCheck = 3, checksOnThisHash = 0), "every 3rd step")
        assertTrue(policy.heldHashRecheckDue(stepsSinceLastCheck = 7, checksOnThisHash = 1))
        // the per-hash cap: three checks on one hash close the window
        assertFalse(policy.heldHashRecheckDue(stepsSinceLastCheck = 9, checksOnThisHash = 3))
        assertFalse(policy.heldHashRecheckDue(stepsSinceLastCheck = 9, checksOnThisHash = 4))
    }

    @Test
    fun `the law thresholds are the default config`() {
        val config = ExitPolicyConfig()
        assertEquals(0.70, config.highFloor)
        assertEquals(0.55, config.sustainedFloor)
        assertEquals(3, config.sustainedChecks)
        assertEquals(2, config.exclusionDecisions)
        assertEquals(3, config.heldHashRecheckEverySteps, "the v1.6.0 held-hash cadence (ART-5)")
        assertEquals(3, config.heldHashRecheckMax, "the v1.6.0 per-hash cap (ART-5)")
    }

    @Test
    fun `an out-of-range config fails loudly`() {
        assertThrows<IllegalArgumentException> { ExitPolicyConfig(highFloor = 1.2) }
        assertThrows<IllegalArgumentException> { ExitPolicyConfig(sustainedChecks = 0) }
        assertThrows<IllegalArgumentException> { ExitPolicyConfig(exclusionDecisions = -1) }
        assertThrows<IllegalArgumentException> { ExitPolicyConfig(heldHashRecheckEverySteps = 0) }
        assertThrows<IllegalArgumentException> { ExitPolicyConfig(heldHashRecheckMax = 0) }
    }
}
