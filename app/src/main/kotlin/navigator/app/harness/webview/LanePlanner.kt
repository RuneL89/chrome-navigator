package navigator.app.harness.webview

import android.util.Log
import navigator.app.gen.GenClient
import navigator.core.ladder.Ladder
import navigator.core.ladder.ReplanContext
import java.io.File

/**
 * V2 S1 — the lane's planning seam: the carried [GenClient] (OpenRouter chat completions,
 * the pinned Haiku id, the full code-validation chain: schema → whitelist → goal fidelity
 * → [SearchStepLaw] — the oscillation law rides every install and re-plan).
 *
 * The seam exists so the L1/L2 suites script it (the scripted-transport law: unit and
 * integration tests never make live paid calls) while the lane loop stays transport-blind.
 */
interface LanePlanner {
    suspend fun restructure(goal: String, generation: Int = 1, failure: ReplanContext? = null): PlanResult

    suspend fun extractAnswer(goal: String, finalState: String): AnswerResult

    data class PlanResult(val ladder: Ladder, val promptVersion: String, val generation: Int)

    data class AnswerResult(val answer: String)
}

class GenClientPlanner(private val gen: GenClient) : LanePlanner {
    override suspend fun restructure(goal: String, generation: Int, failure: ReplanContext?): LanePlanner.PlanResult {
        val result = gen.restructure(goal, generation, failure)
        return LanePlanner.PlanResult(result.ladder, result.promptVersion, result.generation)
    }

    override suspend fun extractAnswer(goal: String, finalState: String): LanePlanner.AnswerResult =
        LanePlanner.AnswerResult(gen.extractAnswer(goal, finalState).answer)
}

/**
 * The lane's run-scoped evidence line recorder (FR-39): one append-only log per task —
 * event provenance for the sprint record and the Test Manager's audit. Lines carry
 * provenance and DOM facts only — NEVER secrets (no cookies, tokens, keys, message
 * bodies; the sweep in every functional run greps this file).
 */
class LaneEvidence(baseDir: File?, runId: String) {
    private val file: File? = baseDir?.let { dir ->
        File(dir, "lane-$runId.log").also { it.parentFile?.mkdirs() }
    }

    @Synchronized
    fun line(text: String) {
        Log.i("NavigatorLane", text)
        file?.let { try { it.appendText(text + "\n") } catch (_: Exception) {} }
    }

    @Synchronized
    fun dump(): String = file?.readText().orEmpty()
}
