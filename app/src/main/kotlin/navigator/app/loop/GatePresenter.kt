package navigator.app.loop

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.CountDownTimer
import android.util.Log
import android.widget.TextView
import navigator.core.gate.GateCard
import navigator.core.gate.HClass

/**
 * The gate-presenting edge (vision 06 §2): shows the confirmation card and returns the
 * owner's decision. Pure interface — the loop never touches UI types. Implementations:
 * the debug dialog (UAT 6.1 runs with this, auto-deny OFF) and the harness auto-deny
 * (Gate 6.2 verifies gate firing without a human; Phase 9's product overlay has NO auto
 * modes — this presenter never ships outside the harness).
 */
fun interface GatePresenter {
    /** Renders the card verbatim and waits for Approve / Deny / 30s timeout. */
    suspend fun present(card: GateCard): GateResolution
}

/**
 * The owner's card resolution (vision 06 §2). Sealed because doc 06 §2a adds the
 * credential variant: an approval of a credential-typing card CARRIES the secret the
 * owner typed on the card's masked input — the value lives in memory only for the
 * injection call and is never rendered, logged, or persisted ([TextSource.REDACTED]
 * is its only lawful rendering). A plain [APPROVED] on a credential card is a
 * wrong-source and fails closed in the loop.
 */
sealed interface GateResolution {
    /** Plain approve (non-credential cards). */
    data object APPROVED : GateResolution

    /** Approve WITH the card-supplied secret (credential-typing cards only, doc 06 §2a). */
    data class ApproveWithSecret(val secret: String) : GateResolution

    data object DENIED : GateResolution

    data object TIMEOUT : GateResolution
}

/** Harness test mode: denies immediately (timeout = deny semantics, fail-safe). */
data object AutoDenyGatePresenter : GatePresenter {
    override suspend fun present(card: GateCard): GateResolution = GateResolution.DENIED
}

/** Deterministic scripted presenter for JVM tests / scripted on-device runs. */
class ScriptedGatePresenter(private vararg val resolutions: GateResolution) : GatePresenter {
    private var index = 0
    override suspend fun present(card: GateCard): GateResolution {
        if (index >= resolutions.size) {
            throw IllegalStateException(
                "gate presenter script exhausted (card: ${card.classLabel} on ${card.site})",
            )
        }
        return resolutions[index++]
    }
}

/** The runbook's greppable harness tag — same value as `HarnessActivity.LOG_TAG`. */
private const val HARNESS_LOG_TAG = "NavigatorHarness"

/**
 * The harness-card front decision (2026-10-07 S25+ UAT defect): with the overlay
 * permission granted the card's dialog window is TYPE_APPLICATION_OVERLAY and draws
 * over Chrome; WITHOUT it an activity-local dialog is invisible while the harness
 * activity is STOPPED behind the browser — the card triggered and timed out unseen.
 * So when the overlay is absent the harness activity must be brought to the front
 * before the dialog shows. Pure so it stays JVM-testable without Robolectric.
 */
internal fun shouldBringToFront(overlayGranted: Boolean): Boolean = !overlayGranted

/**
 * Implemented by presenters that may bring the harness activity to the FRONT to show a
 * card (the no-overlay path — [shouldBringToFront]). The loop reads it right after a
 * resolution to decide whether the BROWSER must be restored to the foreground before
 * the loop continues (the 2026-10-08 pre-UAT defect: on the no-overlay path Chrome
 * stays backgrounded after the card — the post-approval gesture cannot land and the
 * next observation reads the harness window). Presenters that never front (the
 * scripted/auto test modes) do not implement it; the loop's restore is then a
 * byte-identical no-op. The flag reflects the MOST RECENT presentation, so callers
 * read it immediately after that presentation's `present` returns.
 */
interface HarnessFrontAware {
    /** Whether the most recent card presentation took the bring-to-front path. */
    fun lastCardFrontedHarness(): Boolean
}

/**
 * The `HarnessActivity` auto-return pattern verbatim: a relaunch carrying NO extras is
 * a silent bring-to-front (`handleLaunchIntent` returns before any run starts or is
 * refused), so this must never start or refuse a run. Best-effort: a failure never
 * touches the run.
 */
private fun bringToFront(activity: Activity) {
    runCatching {
        activity.startActivity(
            Intent(activity, activity.javaClass).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            ),
        )
    }.onFailure {
        Log.w(HARNESS_LOG_TAG, "card bring-to-front failed: ${it::class.simpleName}: ${it.message}")
    }
}

/**
 * The harness gate stand-in (phase doc §2.1): an always-on-top debug dialog rendering
 * the [GateCard] verbatim — class in plain words, site + URL, target, one-line
 * consequence — with Approve/Deny and a live 30s countdown. Timeout = deny (vision 06
 * §2, fail-safe). Credential-typing cards ([GateCard.credentialTyping], doc 06 §2a)
 * additionally show a MASKED text input: the owner types the secret on the card and
 * approval resolves [GateResolution.ApproveWithSecret]; the value lives in memory only
 * for the injection call. An empty masked input resolves DENY (fail-safe: nothing was
 * supplied). The dialog window is TYPE_APPLICATION_OVERLAY when the owner granted
 * "display over other apps" (it must sit over Chrome during a run); otherwise the
 * harness activity is brought to the FRONT first ([shouldBringToFront] /
 * [bringToFront]) — an activity-local dialog behind a stopped activity is never seen
 * (the 2026-10-07 S25+ UAT defect). The product overlay (Phase 9) replaces this;
 * nothing here is product UI and no auto mode exists in Phase 9.
 */
class DebugGatePresenter(private val activity: Activity) : GatePresenter, HarnessFrontAware {

    /** Whether the most recent card took the bring-to-front path ([HarnessFrontAware]). */
    private var frontedForLastCard = false

    override fun lastCardFrontedHarness(): Boolean = frontedForLastCard

    override suspend fun present(card: GateCard): GateResolution {
        var resolution: GateResolution? = null
        val latch = kotlinx.coroutines.CompletableDeferred<GateResolution>()

        activity.runOnUiThread {
            val countdown = TextView(activity).apply { setPadding(48, 16, 48, 0) }
            val text = buildString {
                append(card.classLabel).append('\n')
                append(card.site).append('\n')
                append(card.url).append('\n')
                append(card.target).append('\n')
                append(card.consequenceLine)
            }
            // Credential-secret variant (doc 06 §2a): the masked input ONLY on
            // credential-typing cards — the owner types the secret here and it is
            // injected straight into the target field, never logged or rendered.
            val secretInput = if (card.credentialTyping) {
                android.widget.EditText(activity).apply {
                    transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                    hint = "Type the secret (stays on this device)"
                    setSingleLine(true)
                    setPadding(48, 24, 48, 8)
                }
            } else {
                null
            }
            // Card visibility over Chrome (2026-10-07 S25+ defect): the overlay window
            // draws over the browser; without the permission an activity-local dialog is
            // invisible behind Chrome, so the harness comes to the front FIRST and the
            // dialog shows on a resumed activity.
            val overlayGranted = android.provider.Settings.canDrawOverlays(activity)
            frontedForLastCard = shouldBringToFront(overlayGranted)
            if (frontedForLastCard) {
                Log.d(
                    HARNESS_LOG_TAG,
                    "gate card: overlay permission absent - bringing the harness to the front " +
                        "(grant Display-over-other-apps for no-switch cards)",
                )
                bringToFront(activity)
            } else {
                Log.d(HARNESS_LOG_TAG, "gate card: overlay window over Chrome")
            }
            val builder = AlertDialog.Builder(activity)
                .setTitle("Navigator confirmation")
                .setMessage(text)
                .setCancelable(false)
            secretInput?.let { builder.setView(it) }
            val dialog = builder
                .setPositiveButton("Approve") { d, _ ->
                    resolution = if (card.credentialTyping) {
                        val secret = secretInput?.text?.toString().orEmpty()
                        if (secret.isBlank()) {
                            // Nothing supplied: the action has no lawful TextSource — deny.
                            Log.w(
                                HARNESS_LOG_TAG,
                                "credential card approved with an EMPTY secret input - resolving deny (fail-safe)",
                            )
                            GateResolution.DENIED
                        } else {
                            GateResolution.ApproveWithSecret(secret)
                        }
                    } else {
                        GateResolution.APPROVED
                    }
                    d.dismiss()
                }
                .setNegativeButton("Deny") { d, _ ->
                    resolution = GateResolution.DENIED
                    d.dismiss()
                }
                .create()
            dialog.window?.let { window ->
                // Always-on-top over Chrome (product overlay is Phase 9; this is the
                // harness stand-in the phase doc names).
                if (overlayGranted) {
                    window.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                }
            }
            dialog.setOnDismissListener {
                latch.complete(resolution ?: GateResolution.TIMEOUT)
            }
            dialog.show()
            object : CountDownTimer(TIMEOUT_MS, 250) {
                override fun onTick(msUntilFinished: Long) {
                    countdown.text = "Auto-deny in ${msUntilFinished / 1000 + 1}s"
                }

                override fun onFinish() {
                    resolution = GateResolution.TIMEOUT
                    dialog.dismiss()
                }
            }.start()
        }
        return latch.await()
    }

    private companion object {
        /** Vision 06 §2: 30 seconds, tunable by amendment only. */
        const val TIMEOUT_MS = 30_000L
    }
}

// -- completion card (vision 04 §3 v1.2.0, Path C — the owner arbiter) -----------------

/** The completion card payload (data only, no UI — the GateCard pattern). */
data class CompletionCard(
    /** The goal as given (context line for the owner). */
    val goal: String,
    /** The triage-extracted target entity, or null when none was extracted. */
    val entity: String?,
    /** The code evidence level at the card trigger. */
    val guardLevel: GuardLevel,
    /** The anchored noul of the triggering check. */
    val noul: Double,
    /** Consecutive guard-pass finish refusals that triggered the card (K). */
    val refusedChecks: Int,
)

/** The owner's completion-card resolution (vision 04 §3 Path C). */
enum class CompletionResolution { CONFIRM_DONE, KEEP_GOING, TIMEOUT }

/**
 * The completion-card presenting edge (the GatePresenter pattern): shows
 * "Goal appears complete — [Confirm done] / [Keep going]" and waits; 30s timeout =
 * keep going (fail-safe: the loop continues). The loop calls it at most ONCE per task
 * (the policy's card trigger is once-per-task); Phase 9's product overlay replaces the
 * debug implementation.
 */
fun interface CompletionCardPresenter {
    suspend fun present(card: CompletionCard): CompletionResolution
}

/**
 * Harness/test auto mode: keep going (timeout semantics, fail-safe — the mirror of
 * [AutoDenyGatePresenter]). The task only ends by engine-verified evidence, never by
 * an unattended card.
 */
data object AutoKeepGoingCompletionPresenter : CompletionCardPresenter {
    override suspend fun present(card: CompletionCard): CompletionResolution = CompletionResolution.KEEP_GOING
}

/** Deterministic scripted presenter for JVM tests / scripted on-device runs. */
class ScriptedCompletionCardPresenter(private vararg val resolutions: CompletionResolution) : CompletionCardPresenter {
    private var index = 0
    val presented = mutableListOf<CompletionCard>()

    override suspend fun present(card: CompletionCard): CompletionResolution {
        presented.add(card)
        if (index >= resolutions.size) {
            throw IllegalStateException(
                "completion card presenter script exhausted (card: entity=${card.entity}, " +
                    "guard=${card.guardLevel}) - a second ask is a once-per-task violation",
            )
        }
        return resolutions[index++]
    }
}

/**
 * The harness completion-card stand-in: the law's card verbatim — "Goal appears
 * complete" with Confirm done / Keep going and a live 30s countdown; timeout = keep
 * going (vision 04 §3 Path C). Overlay window when the owner granted it; otherwise the
 * harness activity is brought to the front first (the gate-card fix — an
 * activity-local dialog behind Chrome is never seen). Harness-only, never product UI.
 */
class DebugCompletionCardPresenter(private val activity: Activity) :
    CompletionCardPresenter, HarnessFrontAware {

    /** Whether the most recent card took the bring-to-front path ([HarnessFrontAware]). */
    private var frontedForLastCard = false

    override fun lastCardFrontedHarness(): Boolean = frontedForLastCard

    override suspend fun present(card: CompletionCard): CompletionResolution {
        var resolution: CompletionResolution? = null
        val latch = kotlinx.coroutines.CompletableDeferred<CompletionResolution>()

        activity.runOnUiThread {
            val countdown = TextView(activity).apply { setPadding(48, 16, 48, 0) }
            val text = buildString {
                append("Goal appears complete").append('\n')
                append(card.goal).append('\n')
                card.entity?.let { append("target: $it").append('\n') }
                append("evidence: guard=${card.guardLevel}, noul=${card.noul}, " +
                    "refused checks=${card.refusedChecks}")
            }
            // Card visibility over Chrome (2026-10-07 S25+ defect, same as the gate
            // card): overlay window when granted, otherwise the harness comes to the
            // front FIRST and the dialog shows on a resumed activity.
            val overlayGranted = android.provider.Settings.canDrawOverlays(activity)
            frontedForLastCard = shouldBringToFront(overlayGranted)
            if (frontedForLastCard) {
                Log.d(
                    HARNESS_LOG_TAG,
                    "completion card: overlay permission absent - bringing the harness to the " +
                        "front (grant Display-over-other-apps for no-switch cards)",
                )
                bringToFront(activity)
            } else {
                Log.d(HARNESS_LOG_TAG, "completion card: overlay window over Chrome")
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle("Navigator completion")
                .setMessage(text)
                .setCancelable(false)
                .setPositiveButton("Confirm done") { d, _ ->
                    resolution = CompletionResolution.CONFIRM_DONE
                    d.dismiss()
                }
                .setNegativeButton("Keep going") { d, _ ->
                    resolution = CompletionResolution.KEEP_GOING
                    d.dismiss()
                }
                .create()
            dialog.window?.let { window ->
                if (overlayGranted) {
                    window.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                }
            }
            dialog.setOnDismissListener {
                latch.complete(resolution ?: CompletionResolution.TIMEOUT)
            }
            dialog.show()
            object : CountDownTimer(TIMEOUT_MS, 250) {
                override fun onTick(msUntilFinished: Long) {
                    countdown.text = "Keep going in ${msUntilFinished / 1000 + 1}s"
                }

                override fun onFinish() {
                    resolution = CompletionResolution.TIMEOUT
                    dialog.dismiss()
                }
            }.start()
        }
        return latch.await()
    }

    private companion object {
        /** Vision 04 §3 Path C: 30 seconds; timeout = keep going. */
        const val TIMEOUT_MS = 30_000L
    }
}
