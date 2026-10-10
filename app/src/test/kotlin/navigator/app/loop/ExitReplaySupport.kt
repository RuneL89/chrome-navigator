package navigator.app.loop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText
import navigator.core.log.TriageClass

/**
 * Shared replay support for the Phase-8J gates: the strict loader for the committed
 * Test-1 specimen fixture (`core/src/test/resources/exit/`) and the check-sequence
 * driver both the specimen-replay gate (8J.2) and the exit-calibration family (8J.5,
 * vision 07 §3a) run through — one replay path, so the tests gate exactly what the
 * family records.
 */
internal object ExitReplaySupport {

    @Serializable
    internal data class ReplayPage(
        val url: String,
        val title: String,
        val contentLines: List<String>,
    )

    @Serializable
    internal data class FixtureSpec(
        val note: String,
        val sourceSpecimen: String,
        val sourceTerminal: String? = null,
        val goal: String,
        val triageClass: String,
        val expectedEntity: String,
        val startPage: ReplayPage? = null,
        val terminalPage: ReplayPage,
        val recordedNoul: List<Double>,
        val continuationNoul: List<Double> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = false }

    /** Strict parse of a committed exit fixture (unknown fields fail loudly). */
    fun load(path: Path): FixtureSpec = json.decodeFromString(FixtureSpec.serializer(), path.readText())

    fun triageClass(spec: FixtureSpec): TriageClass = when (spec.triageClass) {
        "lookup" -> TriageClass.LOOKUP
        "research-multi-step" -> TriageClass.RESEARCH_MULTI_STEP
        "form-fill" -> TriageClass.FORM_FILL
        else -> throw IllegalStateException("unknown triage class '${spec.triageClass}' in the fixture")
    }

    /**
     * A Guard 2.0 over the fixture's task, walked to the terminal page exactly as the
     * loop would: seed the start URL, read the contentful start page, then the
     * contentful terminal page. [levelAtTerminal] is the guard level of the finish
     * checks that follow (all on the terminal page).
     */
    fun guardsAtTerminal(spec: FixtureSpec): Pair<CompletionGuards, GuardLevel> {
        val triage = triageClass(spec)
        val entity = TargetEntity.extract(spec.goal)
        check(entity?.text == spec.expectedEntity) {
            "the fixture's entity extraction drifted: extracted ${entity?.text}, " +
                "fixture records ${spec.expectedEntity}"
        }
        val guards = CompletionGuards(triage, entity)
        spec.startPage?.let { start ->
            guards.seed(start.url)
            guards.record(start.url, start.contentLines.size, textTyped = false, submitExecuted = false)
        } ?: guards.seed(spec.terminalPage.url)
        val terminal = spec.terminalPage
        guards.record(terminal.url, terminal.contentLines.size, textTyped = false, submitExecuted = false)
        // STRONG (v1.3.0) reads page identity only: title/URL, never the content lines.
        return guards to guards.levelAt(terminal.title, terminal.url)
    }

    /** One driven finish check's outcome, for the family's assertions and report. */
    data class CheckOutcome(
        val index: Int,
        val noul: Double,
        val guardLevel: GuardLevel,
        val decision: ExitDecision,
        val cardResolution: CompletionResolution?,
    )

    /**
     * Drives [noulSeries] finish checks through [policy] with Guard 2.0's level,
     * presenting any completion card to [cards] (null = the auto keep-going mode).
     * Every refusal starts the anti-oscillation window; the page hash is CONSTANT
     * (the specimen's screen never changed across its 8 refused finishes), so the
     * window only ever expires by its own M count — exactly the specimen's shape.
     */
    suspend fun replayChecks(
        policy: ExitPolicy,
        guardLevel: GuardLevel,
        noulSeries: List<Double>,
        cards: CompletionCardPresenter? = null,
        screenHash: Int = 0xABCD,
        /** The v1.6.0 sustained-streak condition key (constant: the replays' page/condition never change). */
        conditionKey: String = "replay:the terminal condition holds",
    ): List<CheckOutcome> {
        val outcomes = mutableListOf<CheckOutcome>()
        for ((index, noul) in noulSeries.withIndex()) {
            val first = policy.onFinishCheck(conditionKey, guardLevel, noul)
            var resolution: CompletionResolution? = null
            val decision = when (first) {
                is ExitDecision.AskOwner -> {
                    val presenter = cards ?: AutoKeepGoingCompletionPresenter
                    when (val r = presenter.present(
                        CompletionCard(
                            goal = "(replay)",
                            entity = null,
                            guardLevel = first.guardLevel,
                            noul = first.noul,
                            refusedChecks = first.refusedChecks,
                        ),
                    )) {
                        CompletionResolution.CONFIRM_DONE -> {
                            resolution = r
                            ExitDecision.Accept(ExitPath.OWNER_CONFIRM, first.guardLevel, first.noul)
                        }
                        else -> {
                            resolution = r
                            ExitDecision.Refuse("kept going", first.guardLevel, first.noul)
                        }
                    }
                }
                else -> first
            }
            outcomes.add(CheckOutcome(index, noul, guardLevel, decision, resolution))
            if (decision is ExitDecision.Accept) break
            policy.onRefusedFinish(screenHash)
        }
        return outcomes
    }
}
