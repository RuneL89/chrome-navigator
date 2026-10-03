package navigator.app.loop

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.Option
import navigator.core.log.TriageClass

/**
 * Task triage (vision 04 §1 — law): ONE `DecisionEngine.choose` over the goal text,
 * `lookup | research-multi-step | form-fill | impossible`. `impossible` (or anything
 * outside v1 scope) explains and aborts before any navigation. The triage result is
 * logged — it rides the run report and every navigation row's `triageClass` field
 * (vision 05 §2), since the DecisionLog row schema is defined for navigation steps.
 */
object Triage {

    const val QUESTION_NAME = "triage"

    /** The one triage question: four options, deterministic order (ART-6). */
    fun question(): ChoiceQuestion = ChoiceQuestion(
        name = QUESTION_NAME,
        options = listOf(
            Option("lookup", "Lookup", "Find one specific piece of information and read it"),
            Option(
                "research-multi-step",
                "Multi-step research",
                "Read several pages and combine what they say",
            ),
            Option("form-fill", "Form fill", "Fill a form field and submit it"),
            Option("impossible", "Impossible", "This goal is impossible or outside v1 scope"),
        ),
    )

    sealed interface Result {
        /** A navigable task class — the step loop runs. */
        data class Task(val triageClass: TriageClass) : Result

        /** `impossible` (or out-of-scope) — explain and abort pre-navigation. */
        data class Impossible(val chosenId: String) : Result
    }

    /** Interprets the engine's triage answer; anything unexpected is a loud error. */
    fun interpret(result: ChoiceResult): Result = when (result.chosenId) {
        "lookup" -> Result.Task(TriageClass.LOOKUP)
        "research-multi-step" -> Result.Task(TriageClass.RESEARCH_MULTI_STEP)
        "form-fill" -> Result.Task(TriageClass.FORM_FILL)
        "impossible" -> Result.Impossible(result.chosenId)
        else -> throw IllegalStateException(
            "triage answered an out-of-scope class '${result.chosenId}' — v1 scope is " +
                "lookup/research-multi-step/form-fill/impossible (vision 04 §1)",
        )
    }
}
