package navigator.core.gate

import navigator.core.decision.Band
import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gate 8E.2 — the additive-only engine law, BOTH directions (vision 06 §3, Phase 8E):
 *  1. a non-LOW engine answer on an unlisted action ADDS an H9 gate (HIGH and UNCERTAIN
 *     both — only a confident LOW leaves the action ungated: fail-to-confirm carries
 *     into the consult);
 *  2. a "harmless" (LOW) engine answer can NEVER un-gate a code-triggered class —
 *     structurally: [AdditiveEngineCheck.resolve] unions with Elvis, [Verdict] has no
 *     "clear" shape, and a confident code class means the judge is never even invoked
 *     (a hostile judge cannot speak, so suppression fails LOUDLY at the wiring and is
 *     unrepresentable at the type).
 *
 * $0 by construction: judges are stub lambdas, never a live provider.
 */
class AdditiveEngineCheckTest {

    private companion object {
        /** A tap on a control in the given bucket (default: a real button, not a link). */
        private fun tap(label: String, id: String = "ctl", bucket: Bucket = Bucket.BUTTONS): GateAction =
            GateAction(
                target = Candidate(
                    id = id,
                    label = label,
                    kinds = listOf("tap"),
                    cx = 0,
                    cy = 0,
                    bucket = bucket,
                    source = CandidateSource.A11Y,
                    sourceOrder = 0,
                    synthetic = false,
                ),
                kind = ActionKind.TAP,
            )

        private fun context(url: String): PageContext = PageContext(url)
    }

    // -- 1. the engine may only RAISE the gate rate --------------------------------------

    @Test
    fun `engine HIGH on an unlisted action ADDS H9`() {
        // "Vanish my data" — no dictionary verb, no structural wording: the novel-verb
        // shape makes the net SUSPICIOUS (matrix row add9-novel-verb-my-data); only the
        // engine can classify it.
        val action = tap("Vanish my data", id = "vanish-data")
        val ctx = context("https://notes.example.com/clean")
        assertEquals(RiskGate.MutationNet.SUSPICIOUS, RiskGate.mutationNet(action, ctx))
        assertEquals(null, RiskGate.classify(action, ctx), "the code net must not be confident here")

        val verdict = runBlocking { AdditiveEngineCheck.evaluate(action, ctx.site) { Band.HIGH } }
        assertEquals(HClass.H9_PERSISTENT_MUTATION, AdditiveEngineCheck.resolve(null, verdict))
    }

    @Test
    fun `engine UNCERTAIN also adds H9 - only a confident LOW clears (fail-to-confirm)`() {
        val action = tap("Save", id = "save")
        val ctx = context("https://portal.example.com/account/preferences")
        assertEquals(RiskGate.MutationNet.SUSPICIOUS, RiskGate.mutationNet(action, ctx))
        val uncertain = runBlocking { AdditiveEngineCheck.evaluate(action, ctx.site) { Band.UNCERTAIN } }
        assertEquals(HClass.H9_PERSISTENT_MUTATION, AdditiveEngineCheck.resolve(null, uncertain))
        val low = runBlocking { AdditiveEngineCheck.evaluate(action, ctx.site) { Band.LOW } }
        assertEquals(null, AdditiveEngineCheck.resolve(null, low))
    }

    // -- 2. the engine can NEVER lower, suppress, or whitelist ---------------------------

    @Test
    fun `engine answers cannot un-gate or replace a code-triggered class`() {
        // A code-confident class: even an "everything is harmless" judge cannot remove
        // it — resolve() unions with Elvis, the verdict can only fill a null. Even the
        // ADD verdict cannot replace a code class (no override, no double-gate).
        val verdicts = runBlocking {
            listOf(
                AdditiveEngineCheck.evaluate(tap("anything"), "any.example") { Band.LOW },
                AdditiveEngineCheck.evaluate(tap("anything"), "any.example") { Band.UNCERTAIN },
                AdditiveEngineCheck.evaluate(tap("anything"), "any.example") { Band.HIGH },
            )
        }
        for (verdict in verdicts) {
            assertEquals(HClass.H1_PAYMENT, AdditiveEngineCheck.resolve(HClass.H1_PAYMENT, verdict))
            assertEquals(HClass.H9_PERSISTENT_MUTATION, AdditiveEngineCheck.resolve(HClass.H9_PERSISTENT_MUTATION, verdict))
            assertEquals(HClass.H7_DOWNLOAD, AdditiveEngineCheck.resolve(HClass.H7_DOWNLOAD, verdict))
        }
        assertEquals(HClass.H9_PERSISTENT_MUTATION, AdditiveEngineCheck.resolve(null, AdditiveEngineCheck.Verdict.AddH9))
        assertEquals(null, AdditiveEngineCheck.resolve(null, AdditiveEngineCheck.Verdict.None))
    }

    @Test
    fun `a hostile judge is never consulted for a code-confident class - suppression fails loudly`() {
        // The loop consults ONLY when classify == null AND the net is SUSPICIOUS. A
        // judge wired to explode if asked therefore survives every code-confident
        // action: the hostile judge never gets to speak; any wiring that DID ask a
        // confident-class consult would explode loudly, never silently clear the class.
        val hostileJudge: (String) -> Band = { _ -> throw IllegalStateException("the engine must not be consulted") }
        val codeConfident = listOf(
            tap("Unsubscribe now", id = "unsub") to context("https://lists.example.com/issue-42"), // H9 by dictionary
            tap("Buy now", id = "buy") to context("https://m0.test/item"), // H2
        )
        for ((action, ctx) in codeConfident) {
            assertEquals(
                RiskGate.MutationNet.GATED,
                RiskGate.mutationNet(action, ctx),
                "${action.target.label}: a code-confident class must short-circuit the consult",
            )
            val consulted = RiskGate.classify(action, ctx) == null &&
                RiskGate.mutationNet(action, ctx) == RiskGate.MutationNet.SUSPICIOUS
            assertFalse(consulted, "${action.target.label}: the loop guard must never consult here")
            if (consulted) runBlocking { AdditiveEngineCheck.evaluate(action, ctx.site, hostileJudge) }
        }
        // For contrast: on a SUSPICIOUS action the hostile judge IS invoked — and its
        // explosion is the loud failure (never a silent pass).
        val suspicious = tap("Continue", id = "continue")
        val ctx = context("https://updates.example.com/account/notice")
        assertEquals(RiskGate.MutationNet.SUSPICIOUS, RiskGate.mutationNet(suspicious, ctx))
        assertFailsWith<IllegalStateException> {
            runBlocking { AdditiveEngineCheck.evaluate(suspicious, ctx.site, hostileJudge) }
        }
    }

    // -- 3. the consult proposition -------------------------------------------------------

    @Test
    fun `the proposition carries the vision 06 §3 question verbatim, anchored to the action`() {
        val action = GateAction(
            target = Candidate(
                id = "continue",
                label = "Continue",
                kinds = listOf("tap"),
                cx = 0,
                cy = 0,
                bucket = Bucket.BUTTONS,
                source = CandidateSource.A11Y,
                sourceOrder = 0,
                synthetic = false,
            ),
            kind = ActionKind.TAP,
            textToType = "s3cret-typed-value",
        )
        val proposition = AdditiveEngineCheck.propositionFor(action, "updates.example.com")
        assertTrue(
            "Does this action make an irreversible change to the user's accounts, content, or data?" in proposition,
            "the §3 question must appear verbatim: $proposition",
        )
        assertTrue("Continue" in proposition, proposition)
        assertTrue("updates.example.com" in proposition, proposition)
        // Typed values never enter a proposition (the GateCard discipline, vision 06 §2).
        assertFalse("s3cret-typed-value" in proposition, proposition)
    }

    @Test
    fun `mutationNet tiers - GATED SUSPICIOUS ORDINARY cover the net's verdicts`() {
        // GATED: confident code class (dictionary on a real control).
        assertEquals(
            RiskGate.MutationNet.GATED,
            RiskGate.mutationNet(tap("Unsubscribe now", id = "unsub"), context("https://lists.example.com/x")),
        )
        // SUSPICIOUS: each uncertain tier (weak word / possessive shape / auth surface /
        // worded link).
        assertEquals(
            RiskGate.MutationNet.SUSPICIOUS,
            RiskGate.mutationNet(tap("Save draft", id = "save-draft"), context("https://notes.example.com/compose")),
        )
        assertEquals(
            RiskGate.MutationNet.SUSPICIOUS,
            RiskGate.mutationNet(tap("Frobnicate my feed", id = "frob"), context("https://app.example.com/feed")),
        )
        assertEquals(
            RiskGate.MutationNet.SUSPICIOUS,
            RiskGate.mutationNet(tap("Continue", id = "continue"), context("https://updates.example.com/account/notice")),
        )
        assertEquals(
            RiskGate.MutationNet.SUSPICIOUS,
            RiskGate.mutationNet(
                tap("Delete everything", id = "del", bucket = Bucket.NAV_LINKS),
                context("https://blog.example.com/x"),
            ),
        )
        // ORDINARY: confidently ordinary — neutral controls on neutral pages.
        assertEquals(
            RiskGate.MutationNet.ORDINARY,
            RiskGate.mutationNet(tap("Read more", id = "read"), context("https://news.example.com/story")),
        )
        assertEquals(
            RiskGate.MutationNet.ORDINARY,
            RiskGate.mutationNet(tap("Pause", id = "pause"), context("https://media.example.com/watch")),
        )
    }
}
