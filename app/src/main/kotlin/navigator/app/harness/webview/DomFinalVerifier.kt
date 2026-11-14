package navigator.app.harness.webview

import navigator.app.harness.webview.dom.DomSnapshot
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.DoneConditionChecker
import navigator.core.ladder.GoalContract
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass

/**
 * V2 S2 — the single final verifier for the DOM lane (FR-37, AC-S2.5), adapted from the
 * carried `app/loop/FinalGoalVerifier` to DOM evidence. It runs on EVERY success path,
 * EXACTLY once (the loop's only Done return is through [DomLaneLoop] finish, which calls
 * this), and a deficiency downgrades the terminal to an honest failure — a success the
 * verifier cannot prove is never claimed.
 *
 * Checks, in law order:
 *  1. CONTRACT BINDING — the goal contract still binds (every explicit parameter carried).
 *  2. VERIFIED PREFIX WALK — every subtask 0..n-1 verified, in order, no gaps.
 *  3. FINAL CONDITION — code-first over the final page's DOM evidence (the AnswerExtracted
 *     condition rides the code-owned answer count, never page content).
 *  4. ANSWER PRESENCE — a report goal's answer exists and is non-blank; a non-report
 *     goal needs none.
 *  5. NO FALSE SUCCESS — a WEAK final condition the code cannot verify is a deficiency
 *     (the DOM lane's honesty law: no hatch, no model rescue on the success path).
 */
object DomFinalVerifier {

    sealed interface Verdict {
        data object Accept : Verdict

        data class Deficient(val deficiencies: List<String>) : Verdict
    }

    fun verify(
        goal: String,
        ladder: Ladder,
        verified: Map<Int, String>,
        answer: String?,
        finalSnapshot: DomSnapshot?,
    ): Verdict {
        val deficiencies = mutableListOf<String>()

        // 1. contract binding
        val contract = GoalContract.fromGoal(goal)
        val contractViolations = contract.validateLadder(ladder)
        if (contractViolations.isNotEmpty()) {
            deficiencies.add("contract: " + contractViolations.joinToString("; "))
        }

        // 2. verified prefix walk — every subtask, in order, no gaps
        for (index in ladder.subtasks.indices) {
            if (!verified.containsKey(index)) {
                deficiencies.add("prefix: subtask ${index + 1}/${ladder.subtasks.size} was never verified")
            }
        }

        // 3. final condition, code-first over the final page
        val finalCondition = ladder.subtasks.last().done
        if (finalSnapshot == null) {
            deficiencies.add("final: no page evidence at the terminal")
        } else {
            val evidence = DoneConditionChecker.Evidence(
                url = finalSnapshot.page.url,
                title = finalSnapshot.page.title,
                pageText = listOf(finalSnapshot.text),
                candidateLabels = finalSnapshot.elements.map { it.label },
                counters = emptyMap(),
                answersProduced = if (answer != null) 1 else 0,
            )
            if (!DoneConditionChecker.satisfied(finalCondition, evidence)) {
                deficiencies.add(
                    "final: the final condition does not hold on the evidence (" +
                        DoneConditionChecker.render(finalCondition) + ")",
                )
            }
        }

        // 4. answer presence
        if (finalCondition is DoneCondition.AnswerExtracted) {
            if (answer.isNullOrBlank()) {
                deficiencies.add("answer: a report goal's answer is missing or blank")
            }
        }

        // 5. the WEAK hatch is shut in this lane: a WEAK condition that the code could not
        // verify above is already a deficiency — no model rescue fabricates a success.

        return if (deficiencies.isEmpty()) Verdict.Accept else Verdict.Deficient(deficiencies)
    }
}
