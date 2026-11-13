package navigator.app.harness.vision

import navigator.app.harness.hybrid.SubtaskOutcome
import navigator.app.harness.hybrid.VisionToolStep
import navigator.core.ladder.Ladder

/**
 * PHASE_17 Stage 1 — the [VisionToolStep] adapter over the proven vision engine: the
 * hybrid host's vision tool IS [VisionLoop.runSubtask] (the same propose/ground/gate/act
 * machinery and the same writer battery), bounded to ONE subtask and reporting back.
 *
 * The adapter exists so the host depends on the interface, not the engine class: the
 * always-vision route still runs the full [VisionLoop]; the hybrid route runs one
 * subtask at a time through this tool.
 */
class VisionLoopTool(private val loop: VisionLoop) : VisionToolStep {

    override suspend fun runSubtask(
        goal: String,
        ladder: Ladder,
        index: Int,
        isCancelled: () -> Boolean,
    ): SubtaskOutcome = loop.runSubtask(goal, ladder, index, isCancelled = isCancelled)

    override suspend fun runSubtask(
        goal: String,
        ladder: Ladder,
        index: Int,
        checks: List<VisionPrompts.VerifyCheck>,
        isCancelled: () -> Boolean,
    ): SubtaskOutcome =
        loop.runSubtask(goal, ladder, index, checks = checks, isCancelled = isCancelled)
}
