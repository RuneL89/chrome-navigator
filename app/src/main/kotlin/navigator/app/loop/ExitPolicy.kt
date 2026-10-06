package navigator.app.loop

import navigator.core.enumerate.CandidateEnumerator
import navigator.core.enumerate.CandidateList

/**
 * The exit policy (vision 04 §3 v1.2.0 — law): decides every engine `finish` claim via
 * the three goal-achieved paths. The engine can never finish alone (ART-4) — code
 * evidence ([GuardLevel]) lowers the bar, never replaces it:
 *
 * - **Path A (classic):** anchored noul > [ExitPolicyConfig.HIGH_FLOOR] (the HIGH band,
 *   banding semantics: strictly greater) with any guard level (WEAK or STRONG — a NONE
 *   guard refuses every path).
 * - **Path B (sustained evidence):** STRONG guard + noul >= [ExitPolicyConfig.SUSTAINED_FLOOR]
 *   (inclusive) sustained over **K = [ExitPolicyConfig.SUSTAINED_CHECKS] consecutive**
 *   finish checks. Consecutive means the counter RESETS on any check that fails the
 *   full condition (the Test-1 specimen's 0.44 breaks its streak — that reset is the law).
 * - **Path C (owner arbiter):** finish refused on K consecutive checks while the guard
 *   still passes (guard != NONE) → one completion card (max ONE per task). Confirm →
 *   OWNER_CONFIRM exit; keep-going / 30s timeout → the loop continues.
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
) {
    init {
        require(highFloor in 0.0..1.0) { "highFloor out of range: $highFloor" }
        require(sustainedFloor in 0.0..1.0) { "sustainedFloor out of range: $sustainedFloor" }
        require(sustainedChecks >= 1) { "sustainedChecks must be >= 1, was $sustainedChecks" }
        require(exclusionDecisions >= 0) { "exclusionDecisions must be >= 0, was $exclusionDecisions" }
    }

    companion object {
        /** Vision 04 §3 v1.2.0, ART-5: the law's thresholds as code constants. */
        const val HIGH_FLOOR = 0.70
        const val SUSTAINED_FLOOR = 0.55
        const val SUSTAINED_CHECKS = 3
        const val EXCLUSION_DECISIONS = 2
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
     */
    fun onFinishCheck(guardLevel: GuardLevel, noul: Double): ExitDecision {
        finishChecks += 1
        if (guardLevel == GuardLevel.NONE) {
            // No code evidence at all: every path is closed and both streaks reset —
            // the code says the goal is not plausibly done.
            sustainedStreak = 0
            guardPassRefusalStreak = 0
            return ExitDecision.Refuse(
                reason = "the completion guard failed (${GuardLevel.NONE})",
                guardLevel = guardLevel,
                noul = noul,
            )
        }
        // Path A — classic: HIGH-band anchored noul, any guard level. Bands semantics:
        // HIGH is strictly greater than the floor (0.70 itself is UNCERTAIN).
        if (noul > config.highFloor) {
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
        return ExitDecision.Refuse(
            reason = "noul=${noul} below the high floor ${config.highFloor} and the sustained " +
                "streak is $sustainedStreak/${config.sustainedChecks}",
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
