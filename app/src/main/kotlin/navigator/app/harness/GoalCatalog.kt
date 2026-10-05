package navigator.app.harness

import kotlinx.serialization.Serializable
import navigator.app.stub.ScriptedAnswer
import navigator.app.stub.StubScript

/**
 * The scripted goal families (phase doc §2.3): the M0 families — search-and-read x2,
 * multi-page research x1, search-box fill+submit x1, one dead-end page for
 * stuck-recovery, one unreadable page. Each goal carries its goal text, target start
 * URL, expected terminal state, and (STUB mode) the scripted decision answers.
 *
 * The catalog lives in `assets/harness/goals.json`; the pages it drives are served from
 * `assets/harness/pages/` by [HarnessPagesServer] on the device's loopback interface.
 */
@Serializable
data class ScriptedGoal(
    val id: String,
    val family: String,
    val goal: String,
    val startUrl: String,
    /** The [navigator.app.loop.TerminalState] name this scripted run must end in. */
    val expectedTerminal: String,
    val script: List<ScriptedAnswer>,
) {
    /** Builds the STUB engine's transcript for this goal. */
    fun stubScript(): StubScript = StubScript(answers = script)
}

@Serializable
data class GoalCatalog(
    val goals: List<ScriptedGoal>,
) {
    fun byId(id: String): ScriptedGoal =
        goals.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("unknown goal id '$id' (known: ${goals.map { it.id }})")

    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = false }

        /** Strict parse: unknown fields and missing goals fail loudly (fixture discipline). */
        fun parse(text: String): GoalCatalog = json.decodeFromString(serializer(), text)

        /**
         * Builds a one-off custom goal at RUNTIME (harness-only debug tooling; never a
         * fixture): drives the LIVE engine against a real website. The expected terminal
         * is GOAL_ACHIEVED and the completion-guard semantics are the standard ones —
         * the triage class comes from the LIVE engine's triage answer, so the guard is
         * whichever state machine that triage selects (the empty script only means "no
         * STUB transcript"; custom goals refuse to run under STUB — see
         * [HarnessActivity]). Blank fields or a non-http(s) start URL fail loudly.
         */
        fun customGoal(id: String?, goalText: String?, startUrl: String?): ScriptedGoal {
            val trimmedText = goalText?.trim()
            val trimmedUrl = startUrl?.trim()
            require(!trimmedText.isNullOrBlank()) { "custom goal needs non-blank customGoalText" }
            require(!trimmedUrl.isNullOrBlank()) { "custom goal needs non-blank customStartUrl" }
            require(trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")) {
                "custom goal start URL must be absolute http(s) (was: \"$trimmedUrl\")"
            }
            val goalId = id?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_CUSTOM_GOAL_ID
            return ScriptedGoal(
                id = goalId,
                family = CUSTOM_FAMILY,
                goal = trimmedText,
                startUrl = trimmedUrl,
                expectedTerminal = "GOAL_ACHIEVED",
                script = emptyList(),
            )
        }

        /** The family recorded on runtime-built custom goals (reports/filtering only). */
        const val CUSTOM_FAMILY = "custom"

        /** The goal id used when the launch intent omits `customGoalId`. */
        const val DEFAULT_CUSTOM_GOAL_ID = "custom-goal"
    }
}
