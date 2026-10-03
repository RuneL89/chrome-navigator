package navigator.core.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate 7.3: rotation stability (isolation; vision 07 §4 — position-bias test, ART-6
 * evidence). For every fixture, ALL rotations of the candidate order (flat list +, by the
 * same offset, every bucket's list) must resolve to the SAME chosen candidate against the
 * SAME recorded name-keyed distribution — 100% stability at Tier A; any instability is a
 * serialization bug (ordering leaking into state construction, question form, or parsing),
 * never a fixture problem.
 */
class PositionBiasTest {

    @Test
    fun `every rotation of every fixture resolves to the recorded gold (100 percent stable)`() {
        val results = EvalHarness.harness.rotationMatrix()
        val unstable = results.filter { !it.stable }
        assertTrue(
            unstable.isEmpty(),
            "rotation instability — name-keyed resolution leaked ordering " +
                "(serialization bug):\n" + unstable.joinToString("\n") {
                    "  - ${it.fixture}: divergent rotations ${it.divergentRotations} of ${it.rotationCount}"
                },
        )
    }

    @Test
    fun `every fixture's rotation matrix spans its full flat list`() {
        for (result in EvalHarness.harness.rotationMatrix()) {
            assertEquals(
                result.flatSize, result.rotationCount,
                "${result.fixture}: the matrix must cover every rotation of the flat list",
            )
            assertTrue(
                result.rotationCount >= 2,
                "${result.fixture}: a rotation matrix of ${result.rotationCount} cannot detect ordering leaks",
            )
            assertEquals(
                EvalHarness.pairings.forFixture(result.fixture).chosenNodeId,
                result.expectedChosenId,
                "${result.fixture}: the rotation target must be the recorded gold decision",
            )
        }
    }

    @Test
    fun `rotation matrix sizes (per-fixture evidence for the report)`() {
        val results = EvalHarness.harness.rotationMatrix()
        val total = results.sumOf { it.rotationCount }
        println("Rotation matrix (Gate 7.3): $total rotations across ${results.size} fixtures")
        for (result in results) {
            println("  ${result.fixture}: flatSize=${result.flatSize} rotations=${result.rotationCount} stable=${result.stable}")
        }
        assertTrue(results.isNotEmpty() && total > 0)
    }
}
