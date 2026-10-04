package navigator.core.wire

import kotlinx.coroutines.runBlocking
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.Option
import navigator.core.decision.ScoreLevel
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.decision.TypedQuestion
import navigator.core.decision.band
import kotlin.system.measureTimeMillis

/**
 * UAT 3.1 — one-shot LIVE smoke through the production wire stack (PHASE_03 §4): TWO real
 * provider calls (choice+noul share one call; one score call) through the real
 * [SystemOneClient] over [OkHttpWireTransport], asserting the real-protocol round trip end
 * to end and printing the decision, distribution, usage and latency per call.
 *
 * Key handling: [DefaultEngineConfigs.resolveJevPrimary] reads env
 * `NAVIGATOR_OPENROUTER_KEY` or the git-ignored `.tools/keys.env` — the key is never
 * printed ([EngineConfig.toString] redacts). Run from the project root:
 * `./gradlew :core:liveSmoke`. Cost: 2 calls at the measured ~$0.00002/call (~$0.00005).
 */
private val state = """
    STATE_FORMAT_VERSION=1
    GOAL: find a recipe for apple pie
    PAGE: allrecipes.com — search results for 'apple pie' (recipe list, page 1)
    CANDIDATES:
    1. n3 Apple Pie From a Brown Paper Bag — 'Apple Pie From a Brown Paper Bag' (link)
    2. n7 Classic Apple Pie — 'Classic Apple Pie' (link)
    3. n9 Jump to Recipe — 'Jump to Recipe' (link)
    4. scroll-down — 'Scroll down for more results' (scroll)
    5. back — 'Back to the previous page' (back)
    6. finish — 'Declare the goal achieved' (finish)
""".trimIndent()

/** The combined choice+noul questions of call 1 (the live-golden scenario). */
private fun questions(): Map<String, TypedQuestion> = linkedMapOf(
    "next-action" to ChoiceQuestion(
        "next-action",
        listOf(
            Option("back", "Go back", "go back"),
            Option(
                "n3 Apple Pie From a Brown Paper Bag", "Open 'Apple Pie From a Brown Paper Bag'",
                "open the result 'Apple Pie From a Brown Paper Bag'",
            ),
            Option("n7 Classic Apple Pie", "Open 'Classic Apple Pie'", "open the result 'Classic Apple Pie'"),
            Option("scroll-down", "Scroll down", "scroll the page"),
            Option("finish", "Finish", "declare the goal achieved"),
            Option("n9 Jump to Recipe", "Jump to Recipe", "jump straight to the recipe card"),
        ),
    ),
    "goal-achieved" to NoulQuestion(
        "goal-achieved",
        "The page currently shows the full apple pie recipe, so the goal 'find a recipe for apple pie' is achieved.",
    ),
)

fun main(): Unit = runBlocking {
    val config = DefaultEngineConfigs.resolveJevPrimary()
        ?: error(
            "no OpenRouter key configured — set NAVIGATOR_OPENROUTER_KEY or add it to the " +
                "git-ignored .tools/keys.env (${DefaultEngineConfigs.DEFAULT_KEYS_FILE})",
        )
    val client = SystemOneClient(OkHttpWireTransport(), config)
    println("UAT 3.1 live smoke — prompt surface ${SystemOneClient.PROMPT_VERSION}, model ${config.model}")
    println("served by: $config (key redacted)")
    var totalCost = 0.0

    // Call 1: choice + noul share ONE real call (name-keyed multi-question).
    val decision: WireDecision
    val choiceLatencyMs = measureTimeMillis { decision = client.decide(state, questions()) }
    totalCost += decision.usage.costUsd
    println()
    println("call 1 (choice + noul): ${choiceLatencyMs} ms, usedFallback=${decision.usedFallback}")
    val choice = decision.answers.getValue("next-action") as ChoiceResult
    println("  choice  : ${choice.chosenId}")
    choice.probabilities.entries.sortedByDescending { it.value }
        .forEach { (id, p) -> println("            $id -> $p") }
    println("  confidence: ${choice.confidence} (${band(choice.confidence)})")
    val noul = decision.answers.getValue("goal-achieved") as NoulResult
    println("  noul    : P(true)=${noul.pTrue} (${band(noul.pTrue)})")
    println("  usage   : input=${decision.usage.inputTokens} output=${decision.usage.outputTokens} " +
        "cost=$${decision.usage.costUsd}")

    // Call 2: score over a 4-level ordered rubric.
    val scoreQuestion = ScoreQuestion(
        "progress",
        listOf(
            ScoreLevel("not-started", "not started"),
            ScoreLevel("browsing", "browsing"),
            ScoreLevel("close", "close"),
            ScoreLevel("done", "done"),
        ),
    )
    val scoreDecision: WireDecision
    val scoreLatencyMs = measureTimeMillis {
        scoreDecision = client.decide(state, mapOf("progress" to scoreQuestion))
    }
    totalCost += scoreDecision.usage.costUsd
    println()
    println("call 2 (score): ${scoreLatencyMs} ms, usedFallback=${scoreDecision.usedFallback}")
    val score = scoreDecision.answers.getValue("progress") as ScoreResult
    println("  expectedLevel (computed in code, ART-5): ${score.expectedLevel}")
    score.levelProbabilities.toSortedMap()
        .forEach { (level, p) -> println("            level $level -> $p") }
    println("  usage   : input=${scoreDecision.usage.inputTokens} output=${scoreDecision.usage.outputTokens} " +
        "cost=$${scoreDecision.usage.costUsd}")

    println()
    println("PASS: both live calls parsed through the real protocol; 2 calls, total cost ~$totalCost")
}
