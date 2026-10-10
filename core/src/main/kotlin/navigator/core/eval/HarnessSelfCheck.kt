package navigator.core.eval

import navigator.core.decision.Intents
import navigator.core.decision.TargetRubric
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
 * Injected mutations (the vision 07 v1.3.0 cascade set — the stage-1/stage-2 analogues
 * of the Phase-7 probes):
 *  1. `membership-drop` — one name silently dropped from a declared question set while
 *     the ORIGINAL recorded response is replayed: the recorded distribution then carries
 *     a key outside the declared names, which the wire parser must reject loudly (wire
 *     law vision 02 §2) — the replay fails naming the fixture. Target-bearing intents
 *     drop a stage-2 slate candidate (the unknown `cand-<id>` answer name); target-less
 *     intents drop a fixed vocabulary option from a scratch intent question.
 *  2. `intent-mass-shift` — the recorded stage-1 response re-synthesized with the gold
 *     intent's mass moved to a different intent: the stage-1 resolution diverges from
 *     the pairing's recorded gold intent.
 *  3. `score-mass-shift` (target-bearing intents) — the recorded stage-2 body
 *     re-synthesized with the gold candidate's favored level mass moved to another
 *     candidate's question: the argmax resolves a different target than the expectation.
 *  4. `flat-spread-no-target` — the [TargetRubric] resolver fed a slate with equal
 *     expected levels: the NO-GOOD-TARGET verdict must fire (a flat spread is evidence,
 *     never an action — vision 02 §3; the resolver has no false green).
 *  5. `positional-resolution-leak` — the position-bias check run with the deliberately
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

    /** Runs every mutation across every fixture (four probes per fixture + score on target-bearing ones). */
    fun run(
        runner: ReplayRunner,
        dumps: List<Pair<String, A11yDump>>,
        pairings: ReplayPairings,
    ): Summary {
        val checks = mutableListOf<Check>()
        for ((name, dump) in dumps) {
            val pairing = pairings.forFixture(name)
            checks += membershipDrop(runner, name, dump, pairing)
            checks += intentMassShift(runner, name, dump, pairing)
            if (Intents.needsTarget(pairing.intent)) {
                checks += scoreMassShift(runner, name, dump, pairing)
            }
            checks += flatSpreadNoTarget(name, dump, pairing)
            checks += positionalResolutionLeak(runner, name, dump, pairing)
        }
        return Summary(checks, checks.all { it.caught })
    }

    /**
     * Mutation 1: drop one name from the declared questions and replay the ORIGINAL
     * recorded response — the parser must fail loudly (a distribution/answer key outside
     * the declared names), naming the fixture. Adaptive per cascade stage.
     */
    private fun membershipDrop(
        runner: ReplayRunner,
        name: String,
        dump: A11yDump,
        pairing: ReplayPairing,
    ): Check {
        val base = CandidateEnumerator.enumerate(dump.observedNodes())
        val goldSlate = if (Intents.needsTarget(pairing.intent)) {
            base.bucketed.entries
                .first { (_, members) -> members.any { it.id == pairing.chosenNodeId } }
                .value.filterNot { it.browserChrome }
        } else {
            emptyList()
        }
        val droppable = goldSlate.firstOrNull { it.id != pairing.chosenNodeId }
        return if (droppable != null) {
            val victim = droppable
            val mutated = dropCandidate(base, victim.id)
            val recordedIntent = RecordedResponse.synthesizeIntent(pairing.intent)
            val recordedTargets = RecordedResponse.synthesizeScoreSlate(
                pairing.chosenNodeId, goldSlate.map { it.id },
            )
            try {
                val step = runner.replayStep(name, dump, pairing, mutated, recordedIntent, recordedTargets)
                Check(
                    "membership-drop", name, caught = false,
                    "replay silently succeeded against a mutated slate " +
                        "(resolved '${step.outcome.chosenNodeId}') — the parser accepted an " +
                        "answer for the dropped candidate '${victim.id}' outside the declared questions",
                )
            } catch (e: Exception) {
                // Expected: the wire parser (or the list invariants) reject the mutated replay.
                Check(
                    "membership-drop", name, caught = true,
                    "rejected loudly: ${e.message?.take(120)}",
                )
            }
        } else {
            // Stage-1 analogue (also the fallback for a single-candidate slate, which has
            // no droppable candidate): a scratch intent question missing one non-gold
            // vocabulary option; the recorded distribution still carries that mass — loud.
            val victim = Intents.IDS.first { it != pairing.intent }
            val scratch = Intents.QUESTION.copy(
                options = Intents.QUESTION.options.filterNot { it.id == victim },
            )
            val recorded = RecordedResponse.synthesizeIntent(pairing.intent)
            try {
                runner.replayStep(name, dump, pairing, base, recorded, null, intentQuestion = scratch)
                Check(
                    "membership-drop", name, caught = false,
                    "replay silently succeeded against a scratch intent question missing " +
                        "'$victim' — the parser accepted a key outside the declared options",
                )
            } catch (e: Exception) {
                Check(
                    "membership-drop", name, caught = true,
                    "rejected loudly: ${e.message?.take(120)}",
                )
            }
        }
    }

    /**
     * Mutation 2: re-record the stage-1 response with the gold intent's mass on a
     * different intent — the replay must resolve that other intent, diverging from the
     * pairing's recorded gold.
     */
    private fun intentMassShift(
        runner: ReplayRunner,
        name: String,
        dump: A11yDump,
        pairing: ReplayPairing,
    ): Check {
        val base = CandidateEnumerator.enumerate(dump.observedNodes())
        val wrong = Intents.IDS.first { it != pairing.intent }
        val shifted = RecordedResponse.synthesizeIntent(wrong)
        val recordedTargets = if (Intents.needsTarget(wrong)) {
            val slate = base.bucketed.entries
                .firstOrNull { (_, members) -> members.any { it.id == pairing.chosenNodeId } }
                ?.value?.filterNot { it.browserChrome }?.map { it.id }
            slate?.let { RecordedResponse.synthesizeScoreSlate(pairing.chosenNodeId, it) }
        } else {
            null
        }
        return try {
            val step = runner.replayStep(name, dump, pairing, base, shifted, recordedTargets)
            val caught = step.outcome.intent != pairing.intent
            Check(
                "intent-mass-shift", name, caught,
                if (caught) "stage-1 resolution diverged from the gold as required " +
                    "(resolved intent '${step.outcome.intent}')"
                else "replay still resolved the gold intent '${pairing.intent}' — a changed " +
                    "recording went undetected",
            )
        } catch (e: Exception) {
            // A loud replay failure (e.g. the shifted intent's slate lacks the gold) also
            // proves the mutation changed the outcome — caught.
            Check("intent-mass-shift", name, caught = true, "rejected loudly: ${e.message?.take(120)}")
        }
    }

    /**
     * Mutation 3 (target-bearing intents): re-record the stage-2 body with the gold
     * candidate's favored-level mass moved to another candidate — the argmax must
     * resolve that other candidate, diverging from the expectation.
     */
    private fun scoreMassShift(
        runner: ReplayRunner,
        name: String,
        dump: A11yDump,
        pairing: ReplayPairing,
    ): Check {
        val base = CandidateEnumerator.enumerate(dump.observedNodes())
        val slate = base.bucketed.entries
            .first { (_, members) -> members.any { it.id == pairing.chosenNodeId } }
            .value.filterNot { it.browserChrome }
        // A single-candidate slate has no other candidate to favor: demote the gold to
        // level 1 instead — the recording changed, so the outcome must change (the
        // no-good-target verdict fires where the gold once resolved decisively).
        val shifted = if (slate.size < 2) {
            RecordedResponse.synthesizeScoreSlateFavoring(pairing.chosenNodeId, slateIds(slate), 0)
        } else {
            val wrong = slateIds(slate).first { it != pairing.chosenNodeId }
            RecordedResponse.synthesizeScoreSlateShifted(pairing.chosenNodeId, wrong, slateIds(slate))
        }
        val step = runner.replayStep(
            name, dump, pairing, base, RecordedResponse.synthesizeIntent(pairing.intent), shifted,
        )
        val caught = step.outcome.chosenNodeId != pairing.chosenNodeId || step.outcome.stage2NoGoodTarget
        return Check(
            "score-mass-shift", name, caught,
            if (caught) "stage-2 resolution diverged from the gold as required " +
                "(resolved '${step.outcome.chosenNodeId}', noGoodTarget=${step.outcome.stage2NoGoodTarget})"
            else "replay still resolved the gold target '${pairing.chosenNodeId}' — a changed " +
                "recording went undetected",
        )
    }

    /**
     * Mutation 4: the resolver's own no-false-green — a slate of EQUAL expected levels
     * (the flat spread) must produce the NO-GOOD-TARGET verdict (evidence, never an
     * action; vision 02 §3). The fixture's real slate supplies the candidate ids.
     */
    private fun flatSpreadNoTarget(
        name: String,
        dump: A11yDump,
        pairing: ReplayPairing,
    ): Check {
        val base = CandidateEnumerator.enumerate(dump.observedNodes())
        val ids = if (Intents.needsTarget(pairing.intent)) {
            slateIds(
                base.bucketed.entries
                    .first { (_, members) -> members.any { it.id == pairing.chosenNodeId } }
                    .value.filterNot { it.browserChrome },
            )
        } else {
            // No stage-2 slate exists for this intent: probe the resolver on the fixture's
            // nav-links census (any non-empty id set exercises the same law).
            slateIds(base.bucketed[navigator.core.enumerate.Bucket.NAV_LINKS].orEmpty())
        }
        return if (ids.isEmpty()) {
            Check("flat-spread-no-target", name, caught = false, "no slate ids to probe the resolver with")
        } else {
            // A multi-candidate slate probes the GAP half (equal levels, gap 0 < 0.5); a
            // single-candidate slate has no second — it probes the FLOOR half (top < 3.0).
            val flatLevel = if (ids.size >= 2) 3.0 else 2.5
            val flat = ids.associateWith { flatLevel }
            val resolution = TargetRubric.resolve(flat)
            val caught = resolution.noGoodTarget
            Check(
                "flat-spread-no-target", name, caught,
                if (caught) "the flat spread reported NO-GOOD-TARGET (${resolution.reason})"
                else "the resolver accepted a flat spread (top ${resolution.top.expectedLevel}) — " +
                    "the no-good-target law has a false green",
            )
        }
    }

    /**
     * Mutation 5: run the rotation matrix with the positional resolver — the check must
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

    private fun slateIds(slate: List<navigator.core.enumerate.Candidate>): List<String> = slate.map { it.id }

    /** A scratch candidate list without [id] (dropped from the flat list AND its bucket). */
    private fun dropCandidate(list: CandidateList, id: String): CandidateList = CandidateList(
        flat = list.flat.filterNot { it.id == id },
        bucketed = list.bucketed.mapValues { (_, members) -> members.filterNot { it.id == id } }
            .filterValues { it.isNotEmpty() },
        truncatedBuckets = emptySet(),
        flatTruncated = list.flatTruncated,
    )
}
