package navigator.app.overlay

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * The Phase-9 gate scenarios' touch SENTINEL — a test-only helper living in MAIN source
 * so the in-app debug runner ([OverlayDebugReceiver]'s GATE91..94 adb actions) can use
 * it WITHOUT instrumentation (the JUnit shell is platform-blocked on the API 35
 * emulator: the service binds but the in-process handshake never completes — measured,
 * see `.state/phase-9-status.json`). A blank full-screen activity that records every
 * touch it receives; it renders nothing and drives nothing.
 *
 * COUNTING (measured gate-run defect): a tap on the clickable content view is CONSUMED
 * by the view — [onTouchEvent] then never fires and the counter stays 0. So the
 * counter hooks [onUserInteraction], which the platform calls for EVERY dispatched
 * touch DOWN (consumed or not).
 */
class OverlayProbeActivity : Activity() {

    /** Every touch the probe received, as "action,x,y" (onTouchEvent) + user events. */
    val touches = mutableListOf<String>()

    fun touchCount(): Int = synchronized(touches) { touches.size }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            FrameLayout(this).apply { isClickable = true },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    /**
     * Fires for EVERY dispatched touch DOWN (and key/trackball events), consumed or
     * not — the reliable count of "a tap reached this activity".
     */
    override fun onUserInteraction() {
        synchronized(touches) { touches.add("userInteraction") }
        super.onUserInteraction()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        synchronized(touches) {
            touches.add("${event.actionMasked},${event.x.toInt()},${event.y.toInt()}")
        }
        return super.onTouchEvent(event)
    }

    companion object {
        /**
         * The live probe instance (same process as the scenarios); null when none.
         * The scenarios read [probeTouchCount] through it.
         */
        @Volatile
        var current: OverlayProbeActivity? = null
            private set

        /** The probe's received-touch count; -1 when no probe is alive. */
        fun probeTouchCount(): Int = current?.touchCount() ?: -1
    }
}
