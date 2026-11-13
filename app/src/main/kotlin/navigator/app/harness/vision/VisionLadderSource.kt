package navigator.app.harness.vision

import navigator.app.gen.LadderSchema
import navigator.app.loop.LadderSource
import navigator.app.loop.RepairedCondition
import navigator.app.loop.RestructuredLadder
import navigator.core.ladder.GoalFidelity
import navigator.core.ladder.Ladder
import navigator.core.ladder.ReplanContext

/**
 * PHASE_17 Stage 2 — THE HYBRID'S WRITER: the vision planner as the ladder source.
 *
 * The a11y route plans through `GenClient`/`restructure-v6` (text only). The hybrid's
 * event cadence pays ONE screenshot with the full a11y state at every plan/re-plan, and
 * the writer that already speaks that dual evidence is the vision planner
 * ([VisionPrompts.PLAN_SYSTEM] over the same ladder schema). This source binds the two:
 *
 * - [restructure] — ONE plan call with the screenshot + the a11y digest (a re-plan carries
 *   the failure context's page facts); the SAME `LadderSchema` validation, the SAME
 *   `GoalFidelity` goal binding and the SAME writer-authored verify battery as the vision
 *   loop, so the host's tree steps receive an identical ladder contract;
 * - [repairCondition] — the vision repair turn (`REPAIR_SYSTEM`), mapped to the host's
 *   [RepairedCondition];
 * - provenance: the version rides `VISION_PROMPT_VERSION` (`<version>/gen<n>`), the cost is
 *   the provider-reported usage of the call (ART-5 micro-dollars).
 *
 * The capture is a lambda read PER CALL (the host's live screen), so the screenshot at a
 * re-plan is the CURRENT page, never a stale frame. A null screenshot degrades to the
 * text-only call (the same degrade the vision loop carries) — the evidence line says so.
 */
class VisionLadderSource(
    private val haiku: VisionHaiku,
    private val capture: suspend () -> VisionCapture.ScreenShot?,
    private val log: (String) -> Unit = {},
    /**
     * PHASE_18 Stage 18.E part 1 — the SHARED run record seam: when wired (the hybrid's
     * product wiring passes the run's ONE [VisionEvidence] — the instance the tool's
     * loop and the host record into), every plan/repair call records its evidence row
     * (`call <kind> … attempts=… channel=HAIKU`), so the host's own writer calls ride
     * the run's call ledger (the record-unification law — the 18.C residual closed).
     * Null (every other construction site) = byte-identical.
     */
    private val evidence: VisionEvidence? = null,
) : LadderSource {

    override suspend fun restructure(
        goal: String,
        generation: Int,
        failure: ReplanContext?,
    ): RestructuredLadder? {
        val shot = try {
            capture()
        } catch (e: Exception) {
            log("[vision-writer] capture failed (${e::class.simpleName}) - planning text-only")
            null
        }
        val pageText = failure?.let { pageFacts(it) } ?: ""
        val user = if (failure == null) {
            VisionPrompts.planUser(goal, pageText)
        } else {
            VisionPrompts.replanUser(goal, failure.cause, pageText)
        }
        var rawReq = ""
        var rawRes = ""
        val started = System.currentTimeMillis()
        val content = try {
            haiku.chat(
                VisionPrompts.planSystem(replan = failure != null),
                user,
                shot?.base64,
                "plan",
            ) { req, res -> rawReq = req; rawRes = res }
        } catch (e: Exception) {
            recordCall("plan", started, rawReq, rawRes, shot != null)
            log("[vision-writer] the plan call failed (${e::class.simpleName}: ${e.message?.take(120)})")
            return null
        }
        recordCall("plan", started, rawReq, rawRes, shot != null)
        val ladder: Ladder = try {
            val battery = VisionPrompts.parseVerifyBlocks(content, "plan-vision")
            val enterText = VisionPrompts.parseEnterTexts(content, "plan-vision")
            val parsed = LadderSchema.parse(content, maxSubtasks = 8)
            val fidelity = GoalFidelity.check(goal, parsed)
            if (!fidelity.ok) {
                log(
                    "[vision-writer] the ladder violates goal fidelity: " +
                        fidelity.violations.joinToString("; "),
                )
                return null
            }
            // The vision plan's per-subtask verify battery is retained for the tool; the
            // host's a11y ladder carries no battery field, so the battery rides the
            // evidence only (the tool re-reads it when it runs a subtask).
            if (battery.isNotEmpty() || enterText.isNotEmpty()) {
                log(
                    "[vision-writer] ladder carries ${battery.size} verify battery(ies) and " +
                        "${enterText.size} enterText field(s)",
                )
            }
            parsed
        } catch (e: Exception) {
            log("[vision-writer] the plan output was refused (${e.message?.take(160)})")
            return null
        }
        return RestructuredLadder(
            ladder = ladder,
            version = "${VisionPrompts.VISION_PROMPT_VERSION}/gen$generation",
            costMicroUsd = haiku.lastUsage?.costMicroUsd ?: 0L,
        )
    }

    override suspend fun repairCondition(
        subtaskInstruction: String,
        current: navigator.core.ladder.DoneCondition,
        pageUrl: String,
        pageTitle: String,
    ): RepairedCondition? {
        val shot = try {
            capture()
        } catch (e: Exception) {
            null
        }
        var rawReq = ""
        var rawRes = ""
        val started = System.currentTimeMillis()
        val content = try {
            haiku.chat(
                VisionPrompts.REPAIR_SYSTEM,
                VisionPrompts.repairUser(
                    subtaskInstruction,
                    navigator.core.ladder.DoneConditionChecker.render(current),
                    "URL: $pageUrl\nTITLE: $pageTitle",
                    "",
                ),
                shot?.base64,
                "repair",
            ) { req, res -> rawReq = req; rawRes = res }
        } catch (e: Exception) {
            recordCall("repair", started, rawReq, rawRes, shot != null)
            log("[vision-writer] the repair call failed (${e::class.simpleName})")
            return null
        }
        recordCall("repair", started, rawReq, rawRes, shot != null)
        val repaired = try {
            VisionPrompts.parseRepairedCondition(content, "repair")
        } catch (e: Exception) {
            log("[vision-writer] the repaired condition was refused (${e.message?.take(160)})")
            return null
        }
        return RepairedCondition(
            done = repaired,
            promptVersion = VisionPrompts.VISION_PROMPT_VERSION,
            costMicroUsd = haiku.lastUsage?.costMicroUsd ?: 0L,
        )
    }

    /** The in-context page facts a re-plan's failure context carries (never page prose). */
    private fun pageFacts(failure: ReplanContext): String = buildString {
        append("URL host: ").append(failure.pageHost.ifBlank { "(unknown)" }).append('\n')
        append("TITLE: ").append(failure.pageTitle.ifBlank { "(unknown)" }).append('\n')
        failure.pageExcerptLines.takeIf { it.isNotEmpty() }?.let {
            append(it.joinToString("\n").take(1_500)).append('\n')
        }
    }

    /**
     * The call's evidence row (CN-16: real attempts + real elapsed, the provider's usage
     * or UNKNOWN). No record wired or no request bytes captured = no row (a fabricated
     * row for an unconnected call would be a false record).
     */
    private fun recordCall(kind: String, started: Long, rawReq: String, rawRes: String, hadImage: Boolean) {
        val ev = evidence ?: return
        if (rawReq.isEmpty()) return
        ev.call(
            kind,
            rawReq,
            rawRes,
            System.currentTimeMillis() - started,
            haiku.lastUsage,
            channel = VisionEvidence.Channel.HAIKU,
            images = if (hadImage) 1 else 0,
            attempts = haiku.lastAttempts,
        )
    }
}
