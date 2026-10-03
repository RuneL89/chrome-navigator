package navigator.core.eval

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
 * touched, vision 07 §2) and proves each one is CAUGHT, naming the fixture:
 *  1. a bucket perturbation (a candidate silently dropped from a scratch candidate list
 *     while the ORIGINAL recorded response is replayed) must fail the replay loudly —
 *     the recorded distribution then carries a key outside the declared option ids;
 *  2. a shifted recorded response (gold mass moved to another candidate) must diverge
 *     from the golden expectation;
 *  3. a deliberately positional resolver must be flagged UNSTABLE by the rotation check —
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
    fun `self-check runs every mutation on every fixture`() {
        val summary = EvalHarness.harness.selfCheck()
        val fixtures = EvalHarness.dumps.map { it.first }.toSet()
        for (mutation in listOf(
            "bucket-membership-drop",
            "recorded-response-mass-shift",
            "positional-resolution-leak",
        )) {
            val probed = summary.mutations.filter { it.mutation == mutation }.map { it.fixture }.toSet()
            assertEquals(fixtures, probed, "mutation '$mutation' must probe every fixture")
        }
        assertEquals(fixtures.size * 3, summary.mutations.size)
    }

    @Test
    fun `bucket perturbation in a scratch copy fails the replay loudly (wire law, no silent success)`() {
        // Gate 7.2's literal mechanism: perturb one bucket's membership in a scratch
        // candidate list and replay the SAME recorded response — the recorded distribution
        // then carries a key outside the declared option ids, which must fail loudly.
        val (name, dump) = EvalHarness.dumps.first { it.first == "adv-injection-buy-01.json" }
        val pairing = EvalHarness.pairings.forFixture(name)
        val base = CandidateEnumerator.enumerate(dump.observedNodes())
        val victim = base.flat.first { it.id != pairing.chosenNodeId }
        val mutated = dropCandidate(base, victim.id)
        val recorded = RecordedResponse.synthesize(pairing.chosenNodeId, base.flat.map { it.id })
        val thrown = runCatching {
            EvalHarness.runner.replayStep(name, dump, pairing, mutated, recorded)
        }.exceptionOrNull()
        assertIs<WireProtocolException>(
            thrown,
            "$name: the perturbed replay must fail LOUDLY on the wire law (unknown distribution key), " +
                "never succeed silently",
        )
        assertTrue(
            thrown.message.orEmpty().contains(victim.id),
            "$name: the failure must name the dropped candidate '${victim.id}', got: ${thrown.message}",
        )
        // the harness's own probe of the same mutation records the fixture by name
        val probe = EvalHarness.harness.selfCheck().mutations
            .first { it.mutation == "bucket-membership-drop" && it.fixture == name }
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
