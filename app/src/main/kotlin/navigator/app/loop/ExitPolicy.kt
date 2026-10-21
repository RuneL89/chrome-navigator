package navigator.app.loop

import navigator.core.enumerate.CandidateEnumerator
import navigator.core.enumerate.CandidateList

/**
 * The exit policy (vision 04 §3 v1.2.0 — law): decides every engine `finish` claim via
 * the three goal-achieved paths. The engine can never finish alone (ART-4) — code
 * evidence ([GuardLevel]) lowers the bar, never replaces it:
 *
 * - **Path A (classic):** anchored noul > [ExitPolicyConfig.HIGH_FLOOR] (the HIGH band,
 *   banding semantics: strictly greater) with a **STRONG** guard (v1.7.0, Phase 10C — the
 *   battery showed WEAK guards silently accepting already-true conditions; a WEAK HIGH
 *   claim routes to Path B/C; a NONE guard refuses every path).
 * - **Path B (sustained evidence):** STRONG guard + noul >= [ExitPolicyConfig.SUSTAINED_FLOOR]
 *   (inclusive) sustained over **K = [ExitPolicyConfig.SUSTAINED_CHECKS] consecutive**
 *   finish checks. Consecutive means the counter RESETS on any check that fails the
 *   full condition (the Test-1 specimen's 0.44 breaks its streak — that reset is the law).
 *   **Streak re-keying (v1.6.0, Phase 10B):** the streak is keyed to the ACTIVE
 *   SUBTASK'S DONE-CONDITION — the caller passes the condition's rendered text as the
 *   [ExitPolicy.onFinishCheck] condition key; the streak SURVIVES a re-plan that keeps
 *   the condition (a re-plan is not evidence against arrival) and RESETS when the
 *   condition or the pointer changes. (The v1.5.0 per-generation reset made sustained
 *   acceptance structurally unreachable — live-proven 2026-10-10.)
 * - **Path C (owner arbiter):** finish refused on K consecutive checks while the guard
 *   still passes (guard != NONE) → one completion card (max ONE per task). Confirm →
 *   OWNER_CONFIRM exit; keep-going / 30s timeout → the loop continues.
 * - **Held-hash re-checks (v1.6.0):** on an UNCHANGED page-hash whose guard stays
 *   satisfied, code-triggered re-checks fire at a bounded interval — every
 *   [ExitPolicyConfig.heldHashRecheckEverySteps] steps, at most
 *   [ExitPolicyConfig.heldHashRecheckMax] per hash — so honest leaning evidence can
 *   mature on a static page. The rate-bound API is [ExitPolicy.heldHashRecheckDue].
 *
 * **Anti-oscillation:** a refused finish excludes `finish` from the next
 * [ExitPolicyConfig.EXCLUSION_DECISIONS] decisions unless the page hash changes — the
 * window is cleared by any hash change and never survives one (the finish↔tap cycling
 * of the 2026-10-06 Test-1 specimen is structurally impossible).
 *
 * Thresholds (0.70 / 0.55 / K=3 / M=2) are code config (ART-5), tunable by amendment
 * and gated by the exit-calibration eval family (vision 07 §3a). Pure JVM — no clock,
 * no I/O, fully deterministic; every decision is derived only from the checks fed in.
 */
data class ExitPolicyConfig(
    /** Path A floor: the HIGH band (band() is strictly > 0.70 — same semantics here). */
    val highFloor: Double = HIGH_FLOOR,
    /** Path B floor, inclusive (vision 04 §3: "noul >= 0.55"). */
    val sustainedFloor: Double = SUSTAINED_FLOOR,
    /** K: consecutive checks for Path B and Path C. */
    val sustainedChecks: Int = SUSTAINED_CHECKS,
    /** M: decisions `finish` stays excluded after a refused finish. */
    val exclusionDecisions: Int = EXCLUSION_DECISIONS,
    /**
     * v1.6.0 held-hash re-check cadence (ART-5): on an unchanged page-hash whose guard
     * stays satisfied, a code-triggered re-check fires every Nth step. The loop consumes
     * this (Round-2 cascade wiring); the policy exposes the pure decision.
     */
    val heldHashRecheckEverySteps: Int = HELD_HASH_RECHECK_EVERY_STEPS,
    /** v1.6.0 held-hash re-check cap: at most this many checks per unchanged page-hash. */
    val heldHashRecheckMax: Int = HELD_HASH_RECHECK_MAX,
) {
    init {
        require(highFloor in 0.0..1.0) { "highFloor out of range: $highFloor" }
        require(sustainedFloor in 0.0..1.0) { "sustainedFloor out of range: $sustainedFloor" }
        require(sustainedChecks >= 1) { "sustainedChecks must be >= 1, was $sustainedChecks" }
        require(exclusionDecisions >= 0) { "exclusionDecisions must be >= 0, was $exclusionDecisions" }
        require(heldHashRecheckEverySteps >= 1) {
            "heldHashRecheckEverySteps must be >= 1, was $heldHashRecheckEverySteps"
        }
        require(heldHashRecheckMax >= 1) { "heldHashRecheckMax must be >= 1, was $heldHashRecheckMax" }
    }

    companion object {
        /** Vision 04 §3 v1.2.0, ART-5: the law's thresholds as code constants. */
        const val HIGH_FLOOR = 0.70
        const val SUSTAINED_FLOOR = 0.55
        const val SUSTAINED_CHECKS = 3
        const val EXCLUSION_DECISIONS = 2

        /** Vision 04 §3 v1.6.0, ART-5: the bounded held-hash re-check cadence + cap. */
        const val HELD_HASH_RECHECK_EVERY_STEPS = 3
        const val HELD_HASH_RECHECK_MAX = 3
    }
}

/** The goal-achieved path an accepted finish took (vision 04 §3; logged on the
 *  terminal row, doc 05 v1.4.0). Echoed in the core log's [navigator.core.log.LoggedExitPath]. */
enum class ExitPath { HIGH, SUSTAINED, OWNER_CONFIRM }

/** The policy's verdict on one engine finish claim. */
sealed interface ExitDecision {
    val guardLevel: GuardLevel
    val noul: Double

    /** Goal verified through a path — the loop exits GOAL_ACHIEVED. */
    data class Accept(
        val path: ExitPath,
        override val guardLevel: GuardLevel,
        override val noul: Double,
    ) : ExitDecision

    /** Self-report without verifiable evidence — the loop refuses (ART-4). */
    data class Refuse(
        val reason: String,
        override val guardLevel: GuardLevel,
        override val noul: Double,
    ) : ExitDecision

    /** K guard-pass refusals reached, card not yet shown — the owner arbitrates. */
    data class AskOwner(
        override val guardLevel: GuardLevel,
        override val noul: Double,
        val refusedChecks: Int,
    ) : ExitDecision
}

class ExitPolicy(private val config: ExitPolicyConfig = ExitPolicyConfig()) {

    private var sustainedStreak = 0
    private var guardPassRefusalStreak = 0
    private var cardShown = false
    private var exclusionRemaining = 0
    private var exclusionHash: Int? = null
    /** The condition key of the previous check — the sustained streak's key (v1.6.0). */
    private var lastConditionKey: String? = null

    /** Finish checks evaluated so far (the run report's exit block). */
    var finishChecks: Int = 0
        private set

    /** How many times the completion card was asked (0 or 1 — once per task, law). */
    var completionCardsShown: Int = 0
        private set

    /**
     * Evaluates one engine finish claim against the three paths (vision 04 §3). Order
     * of evaluation: guard NONE → refuse; Path A → accept; Path B (sustained) → accept;
     * Path C (K guard-pass refusals, card unused) → ask the owner; otherwise refuse.
     *
     * [conditionKey] (v1.6.0 streak re-keying) is the ACTIVE done-condition's rendered
     * text — the sustained streak is keyed to it: it SURVIVES a re-plan that keeps the
     * condition and RESETS when the condition (or the pointer, which changes the
     * condition) changes. The caller derives it (the loop renders the active subtask's
     * [navigator.core.ladder.DoneCondition]; the task-level path keys on its condition
     * text). Never blank.
     *
     * [guardDetail] (Phase 08G) is the guard's own why-it-failed detail — it is appended
     * to the NONE refusal's reason (e.g. the action-multi tracker's pending count) and
     * never changes the verdict: with a NONE guard every path is closed regardless.
     */
    fun onFinishCheck(
        conditionKey: String,
        guardLevel: GuardLevel,
        noul: Double,
        guardDetail: String? = null,
        /**
         * v1.7.0 (Phase 10C): true when the run is a LADDER run — the production shape
         * (every product run wires a ladder source), where the battery's false completions
         * lived: a WEAK-shaped subtask condition already satisfied by the page the subtask
         * started from, silently accepted via Path A. On ladder runs Path A therefore
         * requires `guard=STRONG`. The LEGACY (non-ladder, harness/scripted) runs keep the
         * v1.2.0 class-predicate law byte-stable: their WEAK is the class predicate
         * (designed completion evidence for old-style goals), not a proxy subtask condition.
         */
        ladderRun: Boolean = false,
    ): ExitDecision {
        require(conditionKey.isNotBlank()) { "the condition key must not be blank (the streak is keyed to it)" }
        finishChecks += 1
        // Streak re-keying (vision 04 §3 v1.6.0): a condition/pointer change resets the
        // sustained streak — evidence under the OLD condition says nothing about the new.
        val previousKey = lastConditionKey
        lastConditionKey = conditionKey
        if (previousKey != null && previousKey != conditionKey) {
            sustainedStreak = 0
        }
        if (guardLevel == GuardLevel.NONE) {
            // No code evidence at all: every path is closed and both streaks reset —
            // the code says the goal is not plausibly done.
            sustainedStreak = 0
            guardPassRefusalStreak = 0
            val detail = guardDetail?.let { ": $it" } ?: ""
            return ExitDecision.Refuse(
                reason = "the completion guard failed (${GuardLevel.NONE})$detail",
                guardLevel = guardLevel,
                noul = noul,
            )
        }
        // Path A — classic: HIGH-band anchored noul. LADDER runs (v1.7.0, doc 04 §3, Phase
        // 10C owner-ratified) require a STRONG guard: the 2026-10-11 on-device battery showed
        // WEAK-shaped subtask conditions already true where the subtask started being silently
        // accepted (false completions). A WEAK HIGH claim on a ladder run falls through to
        // Path B's sustained evidence or Path C's owner card. LEGACY runs keep the v1.2.0
        // law (any guard level) — their WEAK is the class predicate, not a proxy condition.
        // Bands semantics: HIGH is strictly greater than the floor (0.70 itself is UNCERTAIN).
        if (noul > config.highFloor && (guardLevel == GuardLevel.STRONG || !ladderRun)) {
            return ExitDecision.Accept(ExitPath.HIGH, guardLevel, noul)
        }
        // Path B — sustained: STRONG guard + noul >= floor, K CONSECUTIVE checks.
        // Any check failing the full condition resets the streak (the specimen's 0.44).
        sustainedStreak = if (guardLevel == GuardLevel.STRONG && noul >= config.sustainedFloor) {
            sustainedStreak + 1
        } else {
            0
        }
        if (sustainedStreak >= config.sustainedChecks) {
            return ExitDecision.Accept(ExitPath.SUSTAINED, guardLevel, noul)
        }
        // Path C — owner arbiter: K consecutive guard-pass refusals, once per task.
        guardPassRefusalStreak += 1
        if (guardPassRefusalStreak >= config.sustainedChecks && !cardShown) {
            cardShown = true
            completionCardsShown += 1
            return ExitDecision.AskOwner(guardLevel, noul, guardPassRefusalStreak)
        }
        // The truthful cause (the old wording claimed a low noul even when the noul
        // cleared the floor and the GUARD was the blocker — seen in every VEXP9 tree run).
        val cause = when {
            noul <= config.highFloor ->
                "noul=${noul} at/below the high floor ${config.highFloor}"
            ladderRun && guardLevel != GuardLevel.STRONG ->
                "the guard is $guardLevel (Path A needs STRONG on a ladder run)"
            else ->
                "the sustained streak did not reach ${config.sustainedChecks}"
        }
        return ExitDecision.Refuse(
            reason = "finish refused: $cause (noul=${noul}, guard=$guardLevel; " +
                "sustained streak $sustainedStreak/${config.sustainedChecks})",
            guardLevel = guardLevel,
            noul = noul,
        )
    }

    /**
     * Records a refused finish (any refusal, including a keep-going completion card):
     * `finish` is excluded from the next M decisions unless the page hash changes.
     */
    fun onRefusedFinish(screenHash: Int) {
        exclusionRemaining = config.exclusionDecisions
        exclusionHash = screenHash
    }

    /**
     * Whether `finish` must be withheld from THIS step's candidates. A page-hash change
     * clears the window immediately (vision 04 §3: "unless the page hash changes").
     */
    fun finishExcluded(screenHash: Int): Boolean {
        if (exclusionRemaining <= 0) return false
        if (screenHash != exclusionHash) {
            exclusionRemaining = 0
            return false
        }
        return true
    }

    /**
     * Counts one taken non-finish decision against the window (the "next M decisions"
     * meter). A hash change clears the window instead of counting.
     */
    fun noteDecision(screenHash: Int) {
        if (exclusionRemaining <= 0) return
        if (screenHash != exclusionHash) {
            exclusionRemaining = 0
        } else {
            exclusionRemaining -= 1
        }
    }

    /**
     * The v1.6.0 bounded held-hash re-check decision (the rate-bound re-key API): on an
     * UNCHANGED page-hash whose guard stays satisfied, a code-triggered re-check is due
     * when at least [ExitPolicyConfig.heldHashRecheckEverySteps] steps have passed since
     * the last check AND fewer than [ExitPolicyConfig.heldHashRecheckMax] checks have
     * run on this hash. Pure — the loop owns the counters (Round-2 cascade wiring).
     */
    fun heldHashRecheckDue(stepsSinceLastCheck: Int, checksOnThisHash: Int): Boolean =
        checksOnThisHash < config.heldHashRecheckMax &&
            stepsSinceLastCheck >= config.heldHashRecheckEverySteps
}

/**
 * The anti-oscillation view of one candidate list (vision 04 §3 v1.2.0): the `finish`
 * pseudo-action withheld. Truncation records are untouched — the finish bucket holds a
 * single synthetic candidate and is never a truncated bucket.
 */
fun CandidateList.withoutFinish(): CandidateList = CandidateList(
    flat = flat.filterNot { it.id == CandidateEnumerator.FINISH_ID },
    bucketed = bucketed
        .mapValues { (_, list) -> list.filterNot { it.id == CandidateEnumerator.FINISH_ID } }
        .filterValues { it.isNotEmpty() },
    truncatedBuckets = truncatedBuckets,
    flatTruncated = flatTruncated,
)
