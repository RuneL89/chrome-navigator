package navigator.app.harness.webview

import navigator.app.harness.webview.events.PipelineEvent
import navigator.app.harness.webview.events.PipelineEventBus
import navigator.app.harness.webview.events.PipelineListener
import navigator.app.harness.webview.events.RefusalStage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V2 S1 FR-39 (AC-S1.8) — the pipeline event stream: the five-step vocabulary rides the
 * bus to every listener; the bus is synchronous and thread-safe; and the SECRET LAW —
 * no event's render carries cookie/token/key-shaped material.
 */
class PipelineEventsTest {

    private fun allKinds(): List<PipelineEvent> = listOf(
        PipelineEvent.Proposed("t1", 0, 0, 10, "click", 3, "Add to cart", "the cart gains the item"),
        PipelineEvent.Judged("t1", 0, 0, 20, true, "advance p=0.91"),
        PipelineEvent.Refused("t1", 1, 0, 30, RefusalStage.GUARD, "the element no longer matches"),
        PipelineEvent.Executed("t1", 1, 0, 40, "click", "Add to cart", true),
        PipelineEvent.Verified("t1", 1, 0, 50, true, "page content changed"),
    )

    @Test
    fun `the five event kinds exist and carry task, step, subtask and elapsed`() {
        val kinds = allKinds()
        assertEquals(
            listOf("Proposed", "Judged", "Refused", "Executed", "Verified"),
            kinds.map { it::class.simpleName },
            "the plan §5.3 vocabulary: proposed / judged / refused / executed / verified",
        )
        kinds.forEach { event ->
            assertEquals("t1", event.taskId)
            assertTrue(event.stepIndex >= 0)
            assertTrue(event.subtaskIndex >= 0)
            assertTrue(event.elapsedMs >= 0)
        }
    }

    @Test
    fun `every listener receives every event, in order`() {
        val bus = PipelineEventBus()
        val a = mutableListOf<String>()
        val b = mutableListOf<String>()
        bus.subscribe { a.add(it::class.simpleName!!) }
        bus.subscribe { b.add(it::class.simpleName!!) }
        allKinds().forEach(bus::emit)
        assertEquals(listOf("Proposed", "Judged", "Refused", "Executed", "Verified"), a)
        assertEquals(a, b, "both listeners see the same stream")
    }

    @Test
    fun `unsubscribed listeners stop receiving`() {
        val bus = PipelineEventBus()
        val seen = mutableListOf<String>()
        val listener = PipelineListener { seen.add("x") }
        bus.subscribe(listener)
        bus.emit(allKinds()[0])
        bus.unsubscribe(listener)
        bus.emit(allKinds()[1])
        assertEquals(1, seen.size, "no events after unsubscribe")
    }

    @Test
    fun `an exception in one listener does not stop the others`() {
        val bus = PipelineEventBus()
        val seen = mutableListOf<String>()
        bus.subscribe { error("a broken listener") }
        bus.subscribe { seen.add("ok") }
        try {
            bus.emit(allKinds()[0])
        } catch (_: IllegalStateException) {
        }
        assertEquals(listOf("ok"), seen, "the healthy listener still got the event")
    }

    @Test
    fun `the secret law - no event render carries secret-shaped material`() {
        // The sweep shape: cookie fragments, bearer tokens, key material must never ride.
        val secretShapes = listOf("cf_clearance=", "__cf_bm=", "Bearer ", "sk-", "GOCSPX-", "password=")
        val events = listOf(
            PipelineEvent.Proposed("t", 0, 0, 0, "text", 1, "email field", "the field holds the address"),
            PipelineEvent.Judged("t", 0, 0, 0, false, "refused: none"),
            PipelineEvent.Refused("t", 0, 0, 0, RefusalStage.ACTION_POLICY, "submit is the owner's tap"),
            PipelineEvent.Executed("t", 0, 0, 0, "text", "email field", true),
            PipelineEvent.Verified("t", 0, 0, 0, false, "no observable effect"),
        )
        events.forEach { event ->
            val render = event.toString()
            secretShapes.forEach { shape ->
                assertTrue(
                    !render.contains(shape),
                    "the event render must never carry '$shape' (got: $render)",
                )
            }
        }
    }
}
