package navigator.app.loop

import android.app.Activity
import android.app.AlertDialog
import android.os.CountDownTimer
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

enum class GateResolution { APPROVED, DENIED, TIMEOUT }

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

/**
 * The harness gate stand-in (phase doc §2.1): an always-on-top debug dialog rendering
 * the [GateCard] verbatim — class in plain words, site + URL, target, one-line
 * consequence — with Approve/Deny and a live 30s countdown. Timeout = deny (vision 06
 * §2, fail-safe). The dialog window is TYPE_APPLICATION_OVERLAY when the owner granted
 * "display over other apps" (it must sit over Chrome during a run); otherwise it falls
 * back to an activity-local dialog. The product overlay (Phase 9) replaces this;
 * nothing here is product UI and no auto mode exists in Phase 9.
 */
class DebugGatePresenter(private val activity: Activity) : GatePresenter {

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
            val dialog = AlertDialog.Builder(activity)
                .setTitle("Navigator confirmation")
                .setMessage(text)
                .setCancelable(false)
                .setPositiveButton("Approve") { d, _ ->
                    resolution = GateResolution.APPROVED
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
                if (android.provider.Settings.canDrawOverlays(activity)) {
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
