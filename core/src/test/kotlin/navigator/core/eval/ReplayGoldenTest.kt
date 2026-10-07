package navigator.core.eval

import navigator.core.enumerate.Bucket
import navigator.core.log.serialName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate 7.1 (part 1): the golden replay. Every fixture replays through the full pipeline
 * (state text v2 -> questions -> wire parse -> gate classify) and must match its recorded
 * expectation — chosen node id AND gate class, exactly. A code change that alters an
 * outcome fails here naming the fixture (vision 07 §2: if a test fails, the code is wrong).
 * Pass criteria also include coverage: every fixture has an expectation entry and a
 * pairing, and vice versa.
 */
class ReplayGoldenTest {

    @Test
    fun `every fixture has a pairing and an ACTIVE expectation entry (coverage)`() {
        val names = EvalHarness.dumps.map { it.first }
        assertEquals(
            names.sorted(),
            EvalHarness.pairings.pairings.map { it.fixture }.sorted(),
            "replay-pairings.json must cover exactly the committed a11y-dump fixtures",
        )
        assertEquals(
            names.sorted(),
            EvalHarness.expectations.active.map { it.fixture }.sorted(),
            "replay-expectations.json must cover exactly the committed a11y-dump fixtures " +
                "(active entries; retired v1 pins are provenance only)",
        )
    }

    @Test
    fun `every fixture's replay matches its golden expectation (chosen node id + gate class)`() {
        val steps = EvalHarness.harness.replayAll()
        val mismatches = EvalHarness.harness.expectationMismatches(steps)
        assertTrue(
            mismatches.isEmpty(),
            "golden replay drifted — ${mismatches.size} mismatch(es) (the code changed an outcome):\n" +
                mismatches.joinToString("\n") { "  - $it" },
        )
    }

    @Test
    fun `every replayed state text is within the token budget and fully measured`() {
        for (step in EvalHarness.harness.replayAll()) {
            assertTrue(
                step.outcome.stateTokenCount in 1..navigator.core.state.StateTextBuilder.TOKEN_BUDGET,
                "${step.fixture}: state text is ${step.outcome.stateTokenCount} tokens — outside the " +
                    "1..${navigator.core.state.StateTextBuilder.TOKEN_BUDGET} budget (vision 03 §4)",
            )
            // every outcome carries the full taxonomy in fixed order (the shadow's vocabulary)
            assertEquals(
                navigator.core.enumerate.Bucket.entries.map { it.serialName },
                step.outcome.bucketSizes.keys.toList(),
                "${step.fixture}: bucketSizes must list every taxonomy bucket in order",
            )
            assertEquals(
                step.flatQuestion.options.size,
                step.outcome.flatSize,
                "${step.fixture}: the flat question must offer exactly the flat candidates",
            )
        }
    }

    @Test
    fun `adversarial fixtures gate exactly as the expectations record (gate classes fire)`() {
        for (step in EvalHarness.harness.replayAll()) {
            if (!step.fixture.startsWith("adv-")) continue
            assertTrue(
                step.outcome.gateClass != null,
                "${step.fixture}: adversarial replay must show the gate FIRING (expected " +
                    "${EvalHarness.expectations.forFixture(step.fixture).gateClass})",
            )
        }
    }
}
