package navigator.core.eval

import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateList
import navigator.core.enumerate.CandidateEnumerator
import navigator.core.enumerate.observedNodes
import navigator.core.fixtures.A11yDump

/**
 * The position-bias check (vision 07 §4, ART-6 evidence; PHASE_07 §2.2): for each fixture,
 * ALL rotations of the candidate order (the flat list and, by the same offset, every
 * bucket's list) are replayed against the SAME recorded name-keyed distribution. The chosen
 * candidate must be identical for every rotation.
 *
 * Pass metric (Tier A law): 100% name-keyed stability. Resolution is the argmax over a
 * distribution keyed by option id (wire law vision 02 §2), so candidate order cannot leak
 * into it — any instability is a serialization bug (a positional read in state
 * construction, question form, or parsing), never a fixture problem.
 *
 * What rotation perturbs, concretely:
 * - the primary question's option order (flat list order);
 * - the state text's candidate-line order within each bucket section (the shadow form's
 *   serialization — "our own serialization", whose stability under rotation the check
 *   flags, PHASE_07 §2.2);
 * - never the candidate SET, the recorded distribution, or the gate context.
 */
object PositionBias {

    /** How the replay resolves the chosen candidate. */
    enum class Resolution {
        /** Production law: argmax over the name-keyed distribution (ties by option id). */
        NAME_KEYED,

        /**
         * Self-check-only bug injection: resolve by list position (options[offset % size]) —
         * a deliberately broken resolver proving the rotation check actually fails when
         * ordering leaks into resolution (Gate 7.2 no-false-green). Never used in a golden path.
         */
        POSITIONAL,
    }

    /** Per-fixture rotation verdict (the report's rotation-matrix row). */
    data class Result(
        val fixture: String,
        val flatSize: Int,
        /** rotations replayed: every offset of the flat list, including the identity. */
        val rotationCount: Int,
        val stable: Boolean,
        /** offsets whose resolution diverged from the recorded gold (sorted). */
        val divergentRotations: List<Int>,
        val resolution: Resolution,
        /** the candidate id every rotation must resolve to. */
        val expectedChosenId: String,
    )

    /**
     * Runs the full rotation matrix for one fixture: `flatSize` rotations (offsets
     * `0 until flatSize`), each a complete replay — state text rebuilt from the rotated
     * candidate list, question re-declared in rotated order, the SAME recorded response
     * re-parsed, resolution compared against the pairing's gold decision.
     */
    fun check(
        runner: ReplayRunner,
        fixture: String,
        dump: A11yDump,
        pairing: ReplayPairing,
        resolution: Resolution = Resolution.NAME_KEYED,
    ): Result {
        val base = CandidateEnumerator.enumerate(dump.observedNodes())
        if (base.flat.none { it.id == pairing.chosenNodeId }) {
            throw IllegalStateException(
                "$fixture: pairing chosenNodeId '${pairing.chosenNodeId}' is not enumerable " +
                    "from the dump — the gold decision must be a real candidate",
            )
        }
        val recorded = RecordedResponse.synthesize(
            goldId = pairing.chosenNodeId,
            optionIds = base.flat.map { it.id },
        )

        val divergent = mutableListOf<Int>()
        for (offset in 0 until base.flat.size) {
            val rotated = rotate(base, offset)
            val step = runner.replayStep(fixture, dump, pairing, rotated, recorded)
            val chosen = when (resolution) {
                Resolution.NAME_KEYED -> step.outcome.chosenNodeId
                Resolution.POSITIONAL -> positionalResolve(rotated, offset)
            }
            if (chosen != pairing.chosenNodeId) divergent += offset
        }
        return Result(
            fixture = fixture,
            flatSize = base.flat.size,
            rotationCount = base.flat.size,
            stable = divergent.isEmpty(),
            divergentRotations = divergent,
            resolution = resolution,
            expectedChosenId = pairing.chosenNodeId,
        )
    }

    /**
     * The same candidate list, viewed from [offset]: the flat list rotated by [offset]
     * and each bucket's list rotated by `offset % bucketSize` (so every bucket sweeps its
     * full order across the matrix). Member sets — and therefore every [CandidateList]
     * invariant — are unchanged; only serialization order moves.
     */
    fun rotate(list: CandidateList, offset: Int): CandidateList {
        require(offset >= 0) { "rotation offset must be >= 0: $offset" }
        if (offset == 0) return list
        val flat = rotateList(list.flat, offset % list.flat.size)
        val bucketed = list.bucketed.mapValues { (_, members) ->
            rotateList(members, offset % members.size)
        }
        return CandidateList(flat, bucketed, list.truncatedBuckets, list.flatTruncated)
    }

    private fun rotateList(list: List<Candidate>, offset: Int): List<Candidate> =
        if (offset == 0) list else list.drop(offset) + list.take(offset)

    /**
     * The deliberately broken resolver: the option at the rotation-dependent list
     * position — what a positional (name-blind) read of the wire answer would produce.
     */
    private fun positionalResolve(candidates: CandidateList, offset: Int): String =
        candidates.flat[offset % candidates.flat.size].id
}
