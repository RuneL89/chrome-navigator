package navigator.core.eval

import navigator.core.enumerate.CandidateEnumerator
import navigator.core.enumerate.CandidateList
import navigator.core.enumerate.observedNodes
import navigator.core.fixtures.A11yDump

/**
 * The harness self-check (Gate 7.2 — "no false green"): the harness deliberately injects
 * regressions into SCRATCH copies of the replay inputs (never the committed fixtures —
 * vision 07 §2) and proves each one is caught, naming the fixture. A self-check mutation
 * that is NOT caught means the gate would wave a real regression through, so
 * [HarnessSelfCheck.run] returning any uncaught mutation must fail the suite.
 *
 * Injected mutations:
 *  1. `bucket-membership-drop` — one candidate silently dropped from a bucket in a scratch
 *     candidate list while the ORIGINAL recorded response is replayed: the recorded
 *     distribution now carries a key outside the declared option ids, which the wire
 *     parser must reject loudly (wire law vision 02 §2) — the replay fails naming the
 *     fixture. This is the bucket-perturbation probe of Gate 7.2.
 *  2. `recorded-response-mass-shift` — the recorded response re-synthesized with the gold
 *     mass moved to a different candidate: the replay resolves a different node than the
 *     expectation, which the golden comparison must flag naming the fixture.
 *  3. `positional-resolution-leak` — the position-bias check run with the deliberately
 *     positional resolver ([PositionBias.Resolution.POSITIONAL]): the rotation matrix must
 *     report instability. If it stayed "stable", Gate 7.3 could never catch an ordering
 *     leak — the definition of a false green.
 */
object HarnessSelfCheck {

    /** One injected mutation and whether the harness caught it (fixture always named). */
    data class Check(
        val mutation: String,
        val fixture: String,
        val caught: Boolean,
        val detail: String,
    )

    /** The summary the report carries (deterministic field for the writers). */
    data class Summary(
        val mutations: List<Check>,
        val allCaught: Boolean,
    )

    /** Runs every mutation across every fixture (three probes per fixture). */
    fun run(
        runner: ReplayRunner,
        dumps: List<Pair<String, A11yDump>>,
        pairings: ReplayPairings,
    ): Summary {
        val checks = mutableListOf<Check>()
        for ((name, dump) in dumps) {
            val pairing = pairings.forFixture(name)
            checks += bucketMembershipDrop(runner, name, dump, pairing)
            checks += recordedResponseMassShift(runner, name, dump, pairing)
            checks += positionalResolutionLeak(runner, name, dump, pairing)
        }
        return Summary(checks, checks.all { it.caught })
    }

    /**
     * Mutation 1: drop one non-gold candidate from every view of a scratch candidate
     * list and replay the ORIGINAL recorded response — the parser must fail loudly
     * (distribution key outside the declared names), naming the fixture.
     */
    private fun bucketMembershipDrop(
        runner: ReplayRunner,
        name: String,
        dump: A11yDump,
        pairing: ReplayPairing,
    ): Check {
        val base = CandidateEnumerator.enumerate(dump.observedNodes())
        val victim = base.flat.firstOrNull { it.id != pairing.chosenNodeId }
            ?: return Check("bucket-membership-drop", name, caught = false, "no droppable candidate")
        val mutated = dropCandidate(base, victim.id)
        val recorded = RecordedResponse.synthesize(pairing.chosenNodeId, base.flat.map { it.id })
        return try {
            val step = runner.replayStep(name, dump, pairing, mutated, recorded)
            Check(
                "bucket-membership-drop", name, caught = false,
                "replay silently succeeded against a mutated candidate list " +
                    "(resolved '${step.outcome.chosenNodeId}') — the parser accepted a " +
                    "distribution key outside the declared option ids",
            )
        } catch (e: Exception) {
            // Expected: the wire parser (or the list invariants) reject the mutated replay.
            Check(
                "bucket-membership-drop", name, caught = true,
                "rejected loudly: ${e.message?.take(120)}",
            )
        }
    }

    /**
     * Mutation 2: re-record the response with the gold mass on a different candidate —
     * the replay must resolve that other node, which cannot match the expectation.
     */
    private fun recordedResponseMassShift(
        runner: ReplayRunner,
        name: String,
        dump: A11yDump,
        pairing: ReplayPairing,
    ): Check {
        val base = CandidateEnumerator.enumerate(dump.observedNodes())
        val wrong = base.flat.firstOrNull { it.id != pairing.chosenNodeId }
            ?: return Check("recorded-response-mass-shift", name, caught = false, "no alternative candidate")
        val shifted = RecordedResponse.synthesize(wrong.id, base.flat.map { it.id })
        val step = runner.replayStep(name, dump, pairing, base, shifted)
        val caught = step.outcome.chosenNodeId != pairing.chosenNodeId
        return Check(
            "recorded-response-mass-shift", name, caught,
            if (caught) "outcome diverged from the gold as required (resolved '${step.outcome.chosenNodeId}')"
            else "replay still resolved the gold '${pairing.chosenNodeId}' — a changed recording went undetected",
        )
    }

    /**
     * Mutation 3: run the rotation matrix with the positional resolver — the check must
     * report instability, or Gate 7.3 is a false green for ordering leaks.
     */
    private fun positionalResolutionLeak(
        runner: ReplayRunner,
        name: String,
        dump: A11yDump,
        pairing: ReplayPairing,
    ): Check {
        val result = PositionBias.check(
            runner, name, dump, pairing, PositionBias.Resolution.POSITIONAL,
        )
        val caught = !result.stable
        return Check(
            "positional-resolution-leak", name, caught,
            if (caught) "rotation check flagged the positional resolver " +
                "(divergent rotations: ${result.divergentRotations.size}/${result.rotationCount})"
            else "rotation check called the positional resolver stable — ordering leaks are invisible",
        )
    }

    /** A scratch candidate list without [id] (dropped from the flat list AND its bucket). */
    private fun dropCandidate(list: CandidateList, id: String): CandidateList = CandidateList(
        flat = list.flat.filterNot { it.id == id },
        bucketed = list.bucketed.mapValues { (_, members) -> members.filterNot { it.id == id } }
            .filterValues { it.isNotEmpty() },
        truncatedBuckets = emptySet(),
        flatTruncated = list.flatTruncated,
    )
}
