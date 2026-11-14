package navigator.app.harness.webview

import navigator.app.harness.webview.dom.DomSnapshot
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.wire.EngineConfig
import navigator.core.wire.SystemOneClient
import navigator.core.wire.Usage

/**
 * The judge seam (FR-32): the loop talks to this; [DomJudge] is the live implementation,
 * the L2 suites script it (the scripted-transport law).
 */
interface DomJudger {
    suspend fun judge(
        goal: String,
        snapshot: DomSnapshot,
        subtaskInstruction: String,
        doneRender: String,
        elementId: Int,
        elementLabel: String,
        action: String,
        value: String?,
        expectedEffect: String,
    ): DomJudge.Verdict
}

/**
 * V2 S1 — the independent judge for the DOM lane (FR-32): the carried [SystemOneClient]
 * over the pinned systemone-family endpoint, answering ONE noul per proposal —
 * "does this proposed action advance the current step on THIS page state?".
 *
 * Refusal law (loud, never a fallback pick): a `none`/low answer — [ACCEPT_FLOOR] or
 * below, or inside the uncertainty band — REFUSES the dispatch; the loop does NOT ask
 * the writer for a different action as a fallback.
 *
 * Budget law (FR-36, AC-S1.7): the client is constructed with `maxRetries = 0` — its own
 * transport-retry budget is switched OFF and the [LaneModelGate] owns the entire retry
 * law (at most 3 attempts / 90 s for scripted failures). Wrapping a retrying client in
 * a gating client would multiply the budgets (3×3), which the AC forbids.
 *
 * Model id: `typesafe/jev-1.14` per `docs/V2_PLAN.md` §6 P0 — the carried
 * `DefaultEngineConfigs.OPENROUTER_MODEL` (`typesafe/jev-1.13-20260917`) is a RESPONSE
 * BUILD id, not a request id; the lane builds its own config instead of the broken
 * default (S2's AC-S2.1 makes the corrected default the pinned one with a live smoke).
 */
class DomJudge(
    private val client: SystemOneClient,
    private val gate: LaneModelGate,
    private val onUsage: (Usage) -> Unit = {},
) : DomJudger {

    data class Verdict(
        val accepted: Boolean,
        val pTrue: Double,
        val reason: String,
    )

    override suspend fun judge(
        goal: String,
        snapshot: DomSnapshot,
        subtaskInstruction: String,
        doneRender: String,
        elementId: Int,
        elementLabel: String,
        action: String,
        value: String?,
        expectedEffect: String,
    ): Verdict {
        val isSearch = action.equals("search", ignoreCase = true)
        // The purchase dispatch rides the lane's purchase law (auto-place or the owner's
        // card) — the judge judges NAVIGATION, and the old "no purchase" clause made it
        // refuse the lawful order click forever (the S3 fixture run's lesson).
        val isPurchaseDispatch = action.equals("click", ignoreCase = true) &&
            listOf("place order", "place your order", "complete order", "confirm order",
                "complete purchase", "buy now", "tilaa", "maksa tilaus")
                .any { elementLabel.lowercase().contains(it) }
        val state = buildString {
            append("THE GOAL: ").append(goal.take(300)).append('\n')
            append("GOAL STEP: ").append(subtaskInstruction).append('\n')
            append("DONE CONDITION: ").append(doneRender).append('\n')
            append("URL: ").append(snapshot.page.url).append('\n')
            if (isSearch) {
                // A search does not act ON this page — it navigates to the engine's results.
                // The page state is irrelevant to the judgment; the QUERY is the payload.
                append("PROPOSED ACTION: run the search query \"").append(value.orEmpty().take(80))
                append("\" on the default search engine\n")
                append("EXPECTED EFFECT: ").append(expectedEffect).append('\n')
                append(
                    "NOTE: the current page may be blank — a search is the correct move when the " +
                        "step needs results the current page cannot show.\n",
                )
            } else {
                append("PROPOSED ACTION: ").append(action)
                if (!value.isNullOrBlank()) append(" value=\"").append(value.take(80)).append('"')
                append(" on element [").append(elementId).append("] \"").append(elementLabel.take(120)).append("\"\n")
                append("EXPECTED EFFECT: ").append(expectedEffect).append('\n')
            }
            append("PAGE TEXT (untrusted):\n").append(snapshot.text.take(1200))
        }
        val proposition = when {
            isSearch -> {
                "Running the proposed search query is the right move for the GOAL STEP and its DONE " +
                    "CONDITION. (Safety is enforced separately by the code guards.)"
            }

            isPurchaseDispatch -> {
                "Executing the proposed action is the right move for the GOAL STEP and its DONE " +
                    "CONDITION. (The purchase decision itself is governed separately by the " +
                    "lane's purchase law; safety is enforced by the code guards.)"
            }

            else -> {
                "Executing the PROPOSED ACTION on this page is the right move for the GOAL STEP and its " +
                    "DONE CONDITION. (Safety is enforced separately by the code guards.)"
            }
        }
        val question = NoulQuestion(
            name = "advance",
            proposition = proposition,
        )
        val first = callOnce(state, question, onUsage)
        if (first.accepted) return first
        // The two-witness law (V2 S3): noul noise measurably swings p across identical
        // states (0.15 vs 0.95 for the same click between runs). A refusal stands only
        // when TWO independent nouls agree; a split goes with the affirmative — and a
        // split refusal is recorded as what it was: noise, not a lawful refusal.
        val second = callOnce(state, question, onUsage)
        if (second.accepted) {
            return Verdict(true, second.pTrue, "the second witness accepted (first p=${first.pTrue} was a noise split)")
        }
        return Verdict(false, minOf(first.pTrue, second.pTrue), first.reason + " | " + second.reason)
    }

    private suspend fun callOnce(
        state: String,
        question: NoulQuestion,
        onUsage: (Usage) -> Unit,
    ): Verdict {
        val decision = gate.call("dom-judge") { _ ->
            client.decide(state, linkedMapOf("advance" to question))
        }
        decision.usage.let(onUsage)
        val answer = decision.answers["advance"] as? NoulResult
            ?: throw DomJudgeException(
                "the judge returned no typed noul for 'advance' (got ${decision.answers.keys}) — refusing",
            )
        return when {
            answer.pTrue > ACCEPT_FLOOR -> Verdict(true, answer.pTrue, "advance p=${answer.pTrue}")
            answer.pTrue < REFUSE_CEILING -> Verdict(false, answer.pTrue, "refused: advance p=${answer.pTrue} below the floor")
            else -> Verdict(false, answer.pTrue, "refused: advance p=${answer.pTrue} inside the uncertainty band — don't know means don't act")
        }
    }

    companion object {
        /** p strictly above this accepts. */
        const val ACCEPT_FLOOR = 0.60

        /** p strictly below this refuses outright; between the two is the uncertainty band (don't act). */
        const val REFUSE_CEILING = 0.40

        /** The lane's judge config: the corrected request id per V2_PLAN §6 P0. */
        fun laneJudgeConfig(apiKey: String): EngineConfig =
            EngineConfig(
                DefaultEngineConfigsLaws.SYSTEMONE_DECISIONS_URL,
                apiKey,
                DefaultEngineConfigsLaws.JEV_REQUEST_MODEL,
            )
    }
}

/**
 * The model-id laws for S1 (V2_PLAN §6 P0): the decision endpoint is the carried one;
 * the model id is the CORRECTED request id (the carried default carries a response build
 * id — the S2 AC-S2.1 fix lands the default-level change with its live smoke).
 */
object DefaultEngineConfigsLaws {
    const val SYSTEMONE_DECISIONS_URL = "https://openrouter.ai/api/alpha/decisions"
    const val JEV_REQUEST_MODEL = "typesafe/jev-1.14"
}

class DomJudgeException(message: String) : Exception(message)
