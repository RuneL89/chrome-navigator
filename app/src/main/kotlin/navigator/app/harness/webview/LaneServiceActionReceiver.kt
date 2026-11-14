package navigator.app.harness.webview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * V2 S3 — the lane's notification/card actions (FR-73's inline answers): Stop, and the
 * card answers (Approve/Not now/Cancel) route to the live service. The receiver is a
 * thin router; the DECISIONS are the machines' ([CardMachine] laws).
 */
class LaneServiceActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            LaneService.ACTION_STOP -> LaneServiceHolder.instance?.onStopAction()
            LaneService.ACTION_CARD_ANSWER -> {
                val approve = intent.getBooleanExtra("approve", false)
                LaneServiceHolder.instance?.onCardAnswer(approve)
            }
        }
    }
}
