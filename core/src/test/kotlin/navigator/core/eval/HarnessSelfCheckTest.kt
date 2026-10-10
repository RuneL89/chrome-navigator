package navigator.core.eval

import navigator.core.decision.Intents
import navigator.core.enumerate.CandidateEnumerator
import navigator.core.enumerate.CandidateList
import navigator.core.enumerate.observedNodes
import navigator.core.wire.WireProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Gate 7.2: regression detection — the mutation check (isolation). The harness injects
 * regressions into SCRATCH copies of the replay inputs (committed fixtures are never
 * touched, vision 07 §2) and proves each one is CAUGHT, naming the fixture. The vision
 * 07 v1.3.0 cascade set — the stage-1/stage-2 analogues of the Phase-7 probes:
 *  1. `membership-drop` — a name silently dropped from a declared question set while the
 *     ORIGINAL recorded response is replayed must fail the replay loudly (the recorded
 *     distribution then carries a key outside the declared names — the wire law);
 *  2. `intent-mass-shift` — a shifted stage-1 recording (gold intent mass moved) must
 *     diverge from the pairing's recorded gold intent;
 *  3. `score-mass-shift` (target-bearing intents) — a shifted stage-2 recording (the
 *     gold candidate's favored level moved) must diverge from the golden target;
 *  4. `flat-spread-no-target` — the resolver fed a flat spread must fire the
 *     NO-GOOD-TARGET verdict (a flat spread is evidence, never an action);
 *  5. a deliberately positional resolver must be flagged UNSTABLE by the rotation check —
 *     proving Gate 7.3 has no false green for ordering leaks.
 * Any uncaught mutation is a false green and fails this suite. Each [HarnessSelfCheck.Check]
 * names its fixture; the golden comparison (ReplayGoldenTest) fails with fixture-named
 * messages on outcome drift.
 */
class HarnessSelfCheckTest {

    @Test
    fun `every injected mutation is caught (no false green)`() {
        val summary = EvalHarness.harness.selfCheck()
        val uncaught = summary.mutations.filter { !it.caught }
        assertTrue(
            summary.allCaught,
            "self-check found FALSE GREENS — ${uncaught.size} injected regression(s) went uncaught:\n" +
                uncaught.joinToString("\n") { "  - [${it.mutation}] ${it.fixture}: ${it.detail}" },
        )
    }

    @Test
    fun `self-check runs the cascade mutation set on every fixture`() {
        val summary = EvalHarness.harness.selfCheck()
        val fixtures = EvalHarness.dumps.map { it.first }.toSet()
        for (mutation in listOf(
            "membership-drop",
            "intent-mass-shift",
            "flat-spread-no-target",
            "positional-resolution-leak",
        )) {
            val probed = summary.mutations.filter { it.mutation == mutation }.map { it.fixture }.toSet()
            assertEquals(fixtures, probed, "mutation '$mutation' must probe every fixture")
        }
        val targetFixtures = EvalHarness.pairings.pairings
            .filter { Intents.needsTarget(it.intent) }.map { it.fixture }.toSet()
        val scoreProbed = summary.mutations.filter { it.mutation == "score-mass-shift" }.map { it.fixture }.toSet()
        assertEquals(targetFixtures, scoreProbed, "score-mass-shift must probe every target-bearing fixture")
        assertEquals(fixtures.size * 4 + targetFixtures.size, summary.mutations.size)
    }

    @Test
    fun `slate perturbation in a scratch copy fails the replay loudly (wire law, no silent success)`() {
        // Gate 7.2's literal mechanism: drop one candidate from a scratch candidate list
        // and replay the SAME recorded responses — the recorded stage-2 batch then
        // carries an answer for a question that was never declared, which must fail loudly.
        val (name, dump) = EvalHarness.dumps.first { it.first == "adv-injection-buy-01.json" }
        val pairing = EvalHarness.pairings.forFixture(name)
        assertTrue(Intents.needsTarget(pairing.intent), "the probe fixture must draw a stage-2 slate")
        val base = CandidateEnumerator.enumerate(dump.observedNodes())
        val slate = base.bucketed.entries
            .first { (_, members) -> members.any { it.id == pairing.chosenNodeId } }
            .value.filterNot { it.browserChrome }
        val victim = slate.first { it.id != pairing.chosenNodeId }
        val mutated = dropCandidate(base, victim.id)
        val recordedIntent = RecordedResponse.synthesizeIntent(pairing.intent)
        val recordedTargets = RecordedResponse.synthesizeScoreSlate(pairing.chosenNodeId, slate.map { it.id })
        val thrown = runCatching {
            EvalHarness.runner.replayStep(name, dump, pairing, mutated, recordedIntent, recordedTargets)
        }.exceptionOrNull()
        assertIs<WireProtocolException>(
            thrown,
            "$name: the perturbed replay must fail LOUDLY on the wire law (unknown answer name), " +
                "never succeed silently",
        )
        assertTrue(
            thrown.message.orEmpty().contains(victim.id),
            "$name: the failure must name the dropped candidate '${victim.id}', got: ${thrown.message}",
        )
        // the harness's own probe of the same mutation records the fixture by name
        val probe = EvalHarness.harness.selfCheck().mutations
            .first { it.mutation == "membership-drop" && it.fixture == name }
        assertEquals(name, probe.fixture)
        assertTrue(probe.caught, "$name: the harness probe must mark the mutation caught")
    }

    @Test
    fun `positional resolver is flagged unstable - rotation check has no false green`() {
        for ((name, dump) in EvalHarness.dumps) {
            val result = PositionBias.check(
                EvalHarness.runner, name, dump, EvalHarness.pairings.forFixture(name),
                PositionBias.Resolution.POSITIONAL,
            )
            assertTrue(
                !result.stable,
                "$name: the deliberately positional resolver MUST read as unstable — " +
                    "if it did not, Gate 7.3 could never catch an ordering leak",
            )
        }
    }
}

/** Scratch-list builder for the mutation probe (test-side twin of the harness internals). */
private fun dropCandidate(list: CandidateList, id: String): CandidateList = CandidateList(
    flat = list.flat.filterNot { it.id == id },
    bucketed = list.bucketed.mapValues { (_, members) -> members.filterNot { it.id == id } }
        .filterValues { it.isNotEmpty() },
    truncatedBuckets = emptySet(),
    flatTruncated = list.flatTruncated,
)
