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
    }
}
