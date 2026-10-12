package navigator.core.ladder

import kotlinx.serialization.Serializable

/**
 * The DECLARED rollback of a re-plan generation (doc 04 §1 v1.8.0 — owner-ratified
 * 2026-10-12): a writer re-plan may rewrite the most recently VERIFIED subtask when that
 * subtask was technically satisfied but led down a wrong path, and only then — stating the
 * lesson in one sentence. Everything before the rolled-back subtask is frozen; [ReplanRule]
 * is the code check that enforces it (the writer is never trusted for plan integrity).
 *
 * Display/provenance laws: the lesson is ONE line (it rides the state/history digest and the
 * `plan.lesson` row field, doc 05 §2 v1.8.0), bounded by [RollbackLimits.MAX_LESSON_CHARS].
 * The generation rule (only gen ≥ 2 may declare a rollback) is enforced at the gen boundary
 * (`:app` `LadderSchema`/`GenClient`), not here — this type validates shape only.
 */
@Serializable
data class Rollback(
    /** The number of verified subtasks the re-plan rewrites — bounded by [RollbackLimits.MAX_STEPS]. */
    val steps: Int = 1,
    /** The writer's one-sentence lesson: why the rolled-back step was the wrong path. */
    val lesson: String,
) {
    init {
        require(steps in 1..RollbackLimits.MAX_STEPS) {
            "Rollback: steps=$steps outside 1..${RollbackLimits.MAX_STEPS} (the one-declared-step law, doc 04 §1 v1.8.0)"
        }
        require(lesson.isNotBlank()) { "Rollback: the lesson must not be blank" }
        require(!lesson.contains('\n') && !lesson.contains('\r')) {
            "Rollback: the lesson must be a single line (display law), got ${lesson.take(80)}"
        }
        require(lesson.length <= RollbackLimits.MAX_LESSON_CHARS) {
            "Rollback: lesson exceeds ${RollbackLimits.MAX_LESSON_CHARS} chars: ${lesson.length}"
        }
    }
}

/** The rollback bounds (ART-5 — numbers in code; raising the depth is a doc amendment + one line). */
object RollbackLimits {
    /** Exactly ONE step back (doc 04 §1 v1.8.0: "the most recently verified subtask — and only that one"). */
    const val MAX_STEPS = 1
    const val MAX_LESSON_CHARS = 240
}

/** The verdict of the monotonic re-plan check — loud either way, never a silent repair. */
sealed interface ReplanVerdict {

    /** The next ladder diverges from the verified prefix only within the declared rollback. */
    data class Accepted(val rolledBack: Int) : ReplanVerdict

    /** The re-plan rewrites frozen progress (or over-declares a rollback): refused loudly. */
    data class Refused(val reason: String) : ReplanVerdict
}

/**
 * THE PLAN-INTEGRITY CHECK (doc 04 §1 v1.8.0 — law, owner-ratified 2026-10-12): a re-plan
 * is accepted only when its first `verifiedCount − steps` subtasks are byte-equal
 * (instruction + rendered done-condition) to the previous ladder's verified prefix — i.e.
 * the writer refined the REMAINING work, and rewrote at most the declared one step back.
 * Growth is lawful (the failing subtask may split; the ladder cap still holds in
 * [Ladder.init]); shrinking the verified prefix without a declaration, rewriting an older
 * verified subtask, or declaring a rollback deeper than the verified progress are all
 * [ReplanVerdict.Refused] with an actionable reason — the caller treats a refusal as NO
 * re-plan capacity and says so loudly (never a silent acceptance, never a crash).
 *
 * Pure and deterministic: the same (previous, verifiedCount, next) triple always yields the
 * same verdict; nothing here reads the page or the engine (ART-2/ART-4).
 */
object ReplanRule {

    fun check(previous: Ladder, verifiedCount: Int, next: Ladder): ReplanVerdict {
        val steps = next.rollback?.steps ?: 0
        if (verifiedCount < 0) {
            return ReplanVerdict.Refused("verifiedCount=$verifiedCount is incoherent")
        }
        if (steps > verifiedCount) {
            return ReplanVerdict.Refused(
                "the re-plan declares a $steps-step rollback but only $verifiedCount subtask(s) are " +
                    "verified — a rollback may rewrite the last VERIFIED subtask only",
            )
        }
        val frozen = verifiedCount - steps
        if (frozen >= next.subtasks.size) {
            return ReplanVerdict.Refused(
                "the re-plan carries ${next.subtasks.size} subtask(s) but the law freezes the first " +
                    "$frozen — the failing subtask must be re-expressed (a re-plan cannot end at " +
                    "the verified prefix)",
            )
        }
        for (index in 0 until frozen) {
            val was = previous.subtasks.getOrNull(index)
            val now = next.subtasks[index]
            if (was == null) {
                return ReplanVerdict.Refused(
                    "the previous ladder has no subtask ${index + 1} but the re-plan freezes the " +
                        "first $frozen — the verified prefix is out of range (incoherent state)",
                )
            }
            if (now.instruction != was.instruction ||
                DoneConditionChecker.render(now.done) != DoneConditionChecker.render(was.done)
            ) {
                return ReplanVerdict.Refused(
                    "the re-plan rewrites verified subtask ${index + 1} " +
                        "('${was.instruction}' -> '${now.instruction}'); with ${next.rollback?.let { "a ${it.steps}-step rollback" } ?: "no rollback declared"} " +
                        "the first $frozen subtask(s) must stay byte-identical (doc 04 §1 v1.8.0)",
                )
            }
        }
        return ReplanVerdict.Accepted(rolledBack = steps)
    }
}
