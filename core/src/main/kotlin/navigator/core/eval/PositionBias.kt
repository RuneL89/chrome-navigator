package navigator.core.eval

import navigator.core.decision.Intents
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateList
import navigator.core.enumerate.CandidateEnumerator
import navigator.core.enumerate.observedNodes
import navigator.core.fixtures.A11yDump

/**
 * The position-bias check (vision 07 §4, ART-6 evidence; PHASE_07 §2.2; the vision 07
 * v1.3.0 cascade replay): for each fixture, ALL rotations of the candidate order (flat
 * list and, by the same offset, every bucket's list — the stage-2 slate among them) are
 * replayed against the SAME recorded name-keyed responses. The resolved intent AND the
 * resolved target must be identical for every rotation.
 *
 * Pass metric (Tier A law): 100% name-keyed stability. The stage-1 intent resolution is
 * keyed by the fixed intent ids; the stage-2 target resolution is the argmax over
 * `cand-<id>`-keyed expected levels ([TargetRubric]) — candidate order cannot leak into
 * either, so any instability is a serialization bug (a positional read in state
 * construction, question form, or parsing), never a fixture problem.
 *
 * What rotation perturbs, concretely:
 * - the stage-2 slate's candidate order (the target questions' declaration order within
 *   the batched request — "our own serialization", whose stability under rotation the
 *   check flags, PHASE_07 §2.2);
 * - the state text's candidate-line order within each bucket section (the shadow form's
 *   serialization);
 * - never the candidate SET, the recorded responses, or the gate context.
 */
object PositionBias {

    /** How the replay resolves the chosen candidate. */
    enum class Resolution {
        /** Production law: name-keyed argmax (intent ids; cand-<id> expected levels). */
        NAME_KEYED,

        /**
         * Self-check-only bug injection: resolve by list position (the slate's
         * offset-position candidate for target-bearing intents, the fixed vocabulary's
         * offset-position intent otherwise) — a deliberately broken resolver proving the
         * rotation check actually fails when ordering leaks into resolution (Gate 7.2
         * no-false-green). Never used in a golden path.
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
        /** the intent every rotation must resolve to (the stage-1 gold). */
        val expectedIntent: String,
    )

    /**
     * Runs the full rotation matrix for one fixture: `flatSize` rotations (offsets
     * `0 until flatSize`), each a complete replay — state text + views rebuilt from the
     * rotated candidate list, the cascade questions re-declared in rotated order, the
     * SAME recorded responses re-parsed, the intent and target resolutions compared
     * against the pairing's gold decision.
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
        val recordedIntent = RecordedResponse.synthesizeIntent(pairing.intent)
        val recordedTargets = if (Intents.needsTarget(pairing.intent)) {
            val slate = base.bucketed.entries
                .first { (_, members) -> members.any { it.id == pairing.chosenNodeId } }
                .value.filterNot { it.browserChrome }
                .map { it.id }
            RecordedResponse.synthesizeScoreSlate(pairing.chosenNodeId, slate)
        } else {
            null
        }

        val divergent = mutableListOf<Int>()
        // The positional resolver is pinned to the BASE declaration order — what a
        // name-blind read glued to the recorded positions would produce as the list
        // rotates under it (a rotate-then-index read is rotation-invariant and would
        // never diverge, which is exactly the false green this mutation must not be).
        val baseSlateIds = if (Intents.needsTarget(pairing.intent)) {
            base.bucketed.entries
                .first { (_, members) -> members.any { it.id == pairing.chosenNodeId } }
                .value.filterNot { it.browserChrome }
                .map { it.id }
        } else {
            emptyList()
        }
        for (offset in 0 until base.flat.size) {
            val rotated = rotate(base, offset)
            val step = runner.replayStep(
                fixture, dump, pairing, rotated, recordedIntent, recordedTargets,
            )
            val resolved: String = when (resolution) {
                Resolution.NAME_KEYED -> step.outcome.chosenNodeId
                Resolution.POSITIONAL -> positionalResolve(pairing, baseSlateIds, offset)
            }
            if (resolved != pairing.chosenNodeId) divergent += offset
        }
        return Result(
            fixture = fixture,
            flatSize = base.flat.size,
            rotationCount = base.flat.size,
            stable = divergent.isEmpty(),
            divergentRotations = divergent,
            resolution = resolution,
            expectedChosenId = pairing.chosenNodeId,
            expectedIntent = pairing.intent,
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
     * The deliberately broken resolver: a position-dependent read — the stage-2 slate's
     * [offset]-position candidate (the BASE order, pinned while the list rotates under
     * it) for target-bearing intents with a sweepable slate; the fixed vocabulary's
     * [offset]-position intent otherwise — what a positional (name-blind) read of the
     * wire answer would produce.
     */
    private fun positionalResolve(pairing: ReplayPairing, baseSlateIds: List<String>, offset: Int): String =
        if (baseSlateIds.size >= 2) baseSlateIds[offset % baseSlateIds.size]
        else Intents.IDS[offset % Intents.IDS.size]
}
