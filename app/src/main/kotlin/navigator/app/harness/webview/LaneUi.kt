package navigator.app.harness.webview

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import navigator.app.cards.CardMachine
import navigator.app.cards.CardState
import navigator.app.cards.NotificationPolicy
import navigator.app.cards.PurchaseDecisionClient

/**
 * V2 S3 — the lane's Android surfaces (FR-70/73/72's minimal S3 shapes): the purchase
 * card overlay, the corner chip, and the task notifications. The LAWS live in the
 * machines ([CardMachine], [CornerChipStateMachine]-equivalent state,
 * [NotificationPolicy]); this class only renders what the machines say and feeds back
 * what the owner touches.
 */
class LaneUi(
    private val context: Context,
    private val policy: NotificationPolicy,
    private val busTap: () -> List<String>,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    // -- the purchase card (FR-72's purchase shape, S3 minimal) ------------------------------

    private var cardWindow: View? = null

    /** Shows the purchase card; the buttons feed the machine ONCE (the machine rejects flips). */
    @Synchronized
    fun showPurchaseCard(machine: CardMachine, decision: PurchaseDecisionClient.Decision) {
        if (cardWindow != null) return
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(28, 30, 38))
            setPadding(pad, pad, pad, pad)
        }
        box.addView(TextView(context).apply {
            text = "Purchase approval"
            setTextColor(Color.WHITE)
            textSize = 18f
        })
        box.addView(TextView(context).apply {
            text = "${decision.item} ×${decision.quantity} — ${renderCents(decision.totalCents)} ${decision.currency.uppercase()}"
            setTextColor(Color.rgb(200, 205, 215))
            textSize = 15f
        })
        box.addView(TextView(context).apply {
            text = "Timeout = no order"
            setTextColor(Color.rgb(140, 146, 158))
            textSize = 12f
        })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(context).apply {
            text = "Approve"
            setOnClickListener {
                main.post { machine.approve() }
                removeCard()
            }
        })
        row.addView(Button(context).apply {
            text = "Decline"
            setOnClickListener {
                main.post { machine.decline() }
                removeCard()
            }
        })
        box.addView(row)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        main.post {
            try {
                wm.addView(box, params)
                cardWindow = box
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun removeCard() {
        cardWindow?.let {
            main.post {
                try {
                    wm.removeView(it)
                } catch (_: Exception) {
                }
            }
        }
        cardWindow = null
    }

    // -- the corner chip (FR-70, S3 minimal: caption + tap checklist + long-press stop) ------

    private var chipWindow: View? = null
    private var chipCaption: TextView? = null
    private var chipChecklist: TextView? = null
    private var chipStop: Button? = null

    @Synchronized
    fun showChip(initialCaption: String, onStop: () -> Unit) {
        if (chipWindow != null) return
        val density = context.resources.displayMetrics.density
        val pad = (10 * density).toInt()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 28, 30, 38))
            setPadding(pad, pad, pad, pad)
        }
        chipCaption = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            text = initialCaption
        }
        chipChecklist = TextView(context).apply {
            setTextColor(Color.rgb(180, 186, 198))
            textSize = 11f
            visibility = View.GONE
        }
        chipStop = Button(context).apply {
            text = "Stop"
            visibility = View.GONE
            setOnClickListener {
                onStop()
                removeChip()
            }
        }
        box.addView(chipCaption)
        box.addView(chipChecklist)
        box.addView(chipStop)
        var downAt = 0L
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * density).toInt()
            y = (80 * density).toInt()
        }
        box.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> downAt = event.downTime
                MotionEvent.ACTION_UP -> {
                    val held = event.eventTime - downAt
                    if (held > 550) {
                        // long-press: the actions menu (Watch/Park/Stop — S3 carries Stop)
                        chipStop?.visibility = if (chipStop?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    } else {
                        // tap: the checklist toggles
                        val checklist = chipChecklist
                        if (checklist != null) {
                            val show = checklist.visibility != View.VISIBLE
                            checklist.text = busTap().takeLast(6).joinToString("\n")
                            checklist.visibility = if (show) View.VISIBLE else View.GONE
                        }
                    }
                    v.performClick()
                }
            }
            false
        }
        android.util.Log.i("NavigatorLane", "chip: requesting addView")
        main.post {
            try {
                wm.addView(box, params)
                chipWindow = box
                android.util.Log.i("NavigatorLane", "chip: addView OK")
            } catch (e: Exception) {
                android.util.Log.e("NavigatorLane", "chip add failed: " + e.message)
            }
        }
    }

    @Synchronized
    fun updateChip(caption: String, done: Boolean) {
        main.post {
            chipCaption?.text = if (done) "$caption" else caption
        }
    }

    @Synchronized
    fun removeChip() {
        chipWindow?.let {
            main.post {
                try {
                    wm.removeView(it)
                } catch (_: Exception) {
                }
            }
        }
        chipWindow = null
        chipCaption = null
        chipChecklist = null
        chipStop = null
    }

    // -- notifications (FR-73: one per task; inline answers; auto-clean) ----------------------

    private val CHANNEL = "navigator-lane"
    private val TASK_NOTIF_ID = 4901

    fun ensureChannel() {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, "Navigator lane", android.app.NotificationManager.IMPORTANCE_LOW),
        )
    }

    /** The running task's minimal notification (Watch/Stop). */
    fun notifyTaskStarted(taskId: String): NotificationPolicy.Spec {
        val spec = policy.taskStarted(taskId)
        post(spec, null)
        return spec
    }

    /** The needs-you upgrade: heads-up with the inline answers (Approve / Not now / Cancel). */
    fun notifyNeedsYou(taskId: String, answers: List<String>, machine: CardMachine?): NotificationPolicy.Spec {
        val spec = policy.needsYou(taskId, answers)
        post(spec, machine)
        return spec
    }

    fun notifyTaskEnded(taskId: String) {
        policy.taskEnded(taskId)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.cancel(TASK_NOTIF_ID)
    }

    private fun post(spec: NotificationPolicy.Spec, machine: CardMachine?) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val stop = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, LaneServiceActionReceiver::class.java).setAction(LaneService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (spec.headsUp) "Navigator needs you" else "Navigator working")
            .setContentText(if (spec.headsUp) "A card is waiting for your answer" else "A task is running in the background lane")
            .setOngoing(spec.ongoing)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        for (answer in spec.actions.filter { it != "Watch" && it != "Stop" }) {
            val approve = answer.equals("Approve", ignoreCase = true) || answer.equals("Confirm", ignoreCase = true)
            val pi = PendingIntent.getBroadcast(
                context, answer.hashCode(),
                Intent(context, LaneServiceActionReceiver::class.java)
                    .setAction(LaneService.ACTION_CARD_ANSWER)
                    .putExtra("answer", answer)
                    .putExtra("approve", approve),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, answer, pi).build())
        }
        if (spec.headsUp) {
            @Suppress("DEPRECATION")
            builder.setPriority(Notification.PRIORITY_HIGH)
        }
        try {
            nm.notify(TASK_NOTIF_ID, builder.build())
        } catch (_: Exception) {
        }
    }

    private fun renderCents(cents: Long): String = "%.2f".format(cents / 100.0)
}
