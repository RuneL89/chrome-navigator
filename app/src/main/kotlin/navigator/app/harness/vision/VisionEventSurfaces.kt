package navigator.app.harness.vision

import navigator.app.gen.AssistResult
import navigator.app.gen.GenUsage
import navigator.app.gen.Prompts
import navigator.app.loop.AssistSource
import navigator.app.loop.BoundaryJudge
import navigator.app.loop.BoundaryVerdict

/**
 * PHASE_17 Stage 2 — the hybrid's EVENT surfaces over RAW DUAL EVIDENCE (a11y text + one
 * screenshot):
 *
 * - [VisionBoundaryJudge] — the subtask-boundary audit (2.3): at a verified boundary the
 *   writer judges whether the REMAINING plan still holds, over the current page. Advisory
 *   by law (the host's bounded re-plan consumes it; code remains the decider).
 * - [VisionStuckEvent] — the stuck event (2.4): the a11y loop's one-shot uncertainty
 *   assist, upgraded to carry the screenshot through the EXISTING [AssistSource] contract
 *   (no new seam, no new prompt text: the same `ASSIST_SYSTEM` re-description law).
 *
 * Both read the screen through a capture lambda PER CALL (never a stale frame) and meter
 * the provider-reported usage into the caller's writer budget. A capture failure degrades
 * to the text-only call — the contract is unchanged, the evidence line says so.
 */
class VisionBoundaryJudge(
    private val haiku: VisionHaiku,
    private val capture: suspend () -> VisionCapture.ScreenShot?,
    private val log: (String) -> Unit = {},
) : BoundaryJudge {

    override suspend fun judge(
        goal: String,
        completedInstruction: String,
        completedDone: String,
        pageUrl: String,
        pageTitle: String,
        pageDigest: String,
    ): BoundaryVerdict? {
        val shot = try {
            capture()
        } catch (e: Exception) {
            log("[boundary] capture failed (${e::class.simpleName}) - judging text-only")
            null
        }
        val digest = buildString {
            append("URL: ").append(pageUrl.ifBlank { "(unknown)" }).append('\n')
            append("TITLE: ").append(pageTitle.ifBlank { "(unknown)" }).append('\n')
            append(pageDigest)
        }
        val content = try {
            haiku.chat(
                VisionPrompts.BOUNDARY_SYSTEM,
                VisionPrompts.boundaryUser(goal, completedInstruction, completedDone, digest),
                shot?.base64,
                "boundary",
            )
        } catch (e: Exception) {
            log("[boundary] the call failed (${e::class.simpleName}: ${e.message?.take(120)})")
            return null
        }
        val (holds, reason) = VisionPrompts.parseBoundaryVerdict(content) ?: run {
            log("[boundary] the verdict was refused (malformed answer)")
            return null
        }
        return BoundaryVerdict(
            planHolds = holds,
            reason = reason,
            promptVersion = VisionPrompts.VISION_PROMPT_VERSION,
            costMicroUsd = haiku.lastUsage?.costMicroUsd ?: 0L,
        )
    }
}

/**
 * The stuck event as an [AssistSource]: the SAME re-description contract the a11y handshake
 * uses, with the screenshot attached. Returns null on a refusal (the handshake falls back
 * to the code path, exactly as with the text-only assist).
 */
class VisionStuckEvent(
    private val haiku: VisionHaiku,
    private val capture: suspend () -> VisionCapture.ScreenShot?,
    private val log: (String) -> Unit = {},
) : AssistSource {

    override suspend fun assist(
        subtask: String,
        doneCondition: String,
        pageMaterial: String,
    ): AssistResult? {
        val shot = try {
            capture()
        } catch (e: Exception) {
            log("[stuck] capture failed (${e::class.simpleName}) - re-describing text-only")
            null
        }
        val content = try {
            haiku.chat(
                Prompts.ASSIST_SYSTEM,
                Prompts.assistUser(subtask, doneCondition, pageMaterial),
                shot?.base64,
                "stuck",
            ).trim()
        } catch (e: Exception) {
            log("[stuck] the call failed (${e::class.simpleName}: ${e.message?.take(120)})")
            return null
        }
        if (content.isEmpty() || content.contains(Prompts.ASSIST_SKIP_MARKER)) return null
        val usage = haiku.lastUsage
            ?: return null
        return AssistResult(
            text = content.take(Prompts.MAX_ASSIST_CHARS),
            usage = GenUsage(usage.inputTokens, usage.outputTokens, usage.costMicroUsd),
            promptVersion = VisionPrompts.VISION_PROMPT_VERSION,
        )
    }
}
