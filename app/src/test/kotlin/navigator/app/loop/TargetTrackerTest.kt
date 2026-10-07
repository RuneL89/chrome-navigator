package navigator.app.loop

import navigator.core.enumerate.ObservedNode
import navigator.core.log.TriageClass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 8G.1 — the target tracker state machine (vision 04 §1, task working memory):
 * deterministic enumeration, the re-identification key derivation (label + position
 * band + container cue), processing/skip, page-change survival (scrolls AND navigation
 * via the documented collision fallback), and the drained semantics (none PENDING;
 * skipped counts as resolved).
 */
class TargetTrackerTest {

    // -- enumeration -----------------------------------------------------------------

    @Test
    fun `enumeration tracks clickable text rows only - editable and silent nodes are not targets`() {
        val tracker = TargetTracker()
        tracker.observe(
            obs(
                "http://m0.test/list",
                N("Subscriptions"),                 // container cue (text)
                N("Unsubscribe cooking", clickable = true),
                N("Unsubscribe travel", clickable = true),
                N("search field", editable = true), // editable: never a row
                N("", clickable = true),            // blank label: never a row
                N("Footer text"),                   // plain text: never a row
            ),
        )
        assertEquals(2, tracker.trackedCount())
        assertEquals(2, tracker.pendingCount())
        assertFalse(tracker.drained())
        assertEquals(listOf("unsubscribe cooking", "unsubscribe travel"), tracker.pendingLabels())
    }

    @Test
    fun `the key derivation is label plus position band plus container cue`() {
        val tracker = TargetTracker()
        tracker.observe(
            obs(
                "http://m0.test/list",
                N("Subscriptions"),
                N("Unsubscribe Cooking", clickable = true),   // top = 100 -> band 0
                N("Unsubscribe Travel", clickable = true),    // top = 200 -> band 7
            ),
        )
        val rows = tracker.rows()
        // label: lowercased, whitespace-collapsed (normalization); band: span-relative
        // position class over the enumerated rows; container: nearest preceding text.
        assertEquals("unsubscribe cooking", rows[0].key.label)
        assertEquals(0, rows[0].key.band)
        assertEquals("subscriptions", rows[0].key.container)
        assertEquals("unsubscribe travel", rows[1].key.label)
        assertEquals(7, rows[1].key.band)
        assertEquals("subscriptions", rows[1].key.container)
        assertEquals("unsubscribe cooking|0|subscriptions", rows[0].key.id)
    }

    @Test
    fun `a row with no preceding text carries an empty container cue`() {
        val tracker = TargetTracker()
        tracker.observe(obs("http://m0.test/list", N("Solo row", clickable = true)))
        assertEquals("", tracker.rows().single().key.container)
    }

    @Test
    fun `labels normalize - case and whitespace never change identity`() {
        val tracker = TargetTracker()
        tracker.observe(obs("http://m0.test/list", N("  Unsubscribe   COOKING ", clickable = true)))
        tracker.observe(obs("http://m0.test/list", N("unsubscribe cooking", clickable = true)))
        assertEquals(1, tracker.trackedCount(), "the same normalized row must not re-register")
    }

    @Test
    fun `enumeration caps at the row cap - truncation is visible in the count`() {
        val tracker = TargetTracker()
        val nodes = (0 until (TargetTracker.MAX_ROWS + 10)).map {
            N("row $it", clickable = true)
        }
        tracker.observe(obs("http://m0.test/list", *nodes.toTypedArray()))
        assertEquals(TargetTracker.MAX_ROWS, tracker.trackedCount())
    }

    @Test
    fun `an empty page drains trivially`() {
        val tracker = TargetTracker()
        tracker.observe(obs("http://m0.test/blank", N("just text")))
        assertEquals(0, tracker.trackedCount())
        assertTrue(tracker.drained())
    }

    // -- processing, skipping, idempotence ---------------------------------------------

    @Test
    fun `markProcessed resolves a pending row and is idempotent`() {
        val tracker = TargetTracker()
        val page = obs(
            "http://m0.test/list",
            N("Unsubscribe cooking", clickable = true),
            N("Unsubscribe travel", clickable = true),
        )
        tracker.observe(page)
        val row = tracker.markProcessed(page.nodes[0])
        assertTrue(row != null && row.status == TargetTracker.Status.PROCESSED)
        assertEquals(1, tracker.processedCount())
        assertEquals(1, tracker.pendingCount())
        // re-marking (a re-rendered row, a retried resolution) changes nothing
        tracker.markProcessed(page.nodes[0])
        assertEquals(1, tracker.processedCount())
    }

    @Test
    fun `markSkipped resolves a pending row - skipped does not block draining`() {
        val tracker = TargetTracker()
        val page = obs(
            "http://m0.test/list",
            N("Unsubscribe cooking", clickable = true),
            N("Unsubscribe travel", clickable = true),
        )
        tracker.observe(page)
        tracker.markSkipped(page.nodes[1])
        assertEquals(1, tracker.skippedCount())
        assertFalse(tracker.drained(), "one row is still pending")
        tracker.markProcessed(page.nodes[0])
        assertTrue(tracker.drained(), "skipped and processed rows are both resolved")
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `a non-row node never resolves - editable, text-less, unobserved`() {
        val tracker = TargetTracker()
        tracker.observe(
            obs(
                "http://m0.test/list",
                N("Unsubscribe cooking", clickable = true),
                N("search field", editable = true),
            ),
        )
        assertNull(tracker.markProcessed(ObservedNode("ghost", "x", "", true, false, false, 0, 0, 10, 10, "View", 99)))
        assertNull(tracker.markProcessed(obs("http://m0.test/other", N("x", editable = true)).nodes[0])
        )
    }

    @Test
    fun `resolution before any observation is a loud no-op`() {
        val tracker = TargetTracker()
        val page = obs("http://m0.test/list", N("Row", clickable = true))
        assertNull(tracker.markProcessed(page.nodes[0]))
        assertEquals(0, tracker.trackedCount())
    }

    // -- survival: scrolls (band shift) and navigation ---------------------------------

    @Test
    fun `a scroll shifts bands - the collision fallback re-identifies rows by label`() {
        val tracker = TargetTracker()
        val before = obs(
            "http://m0.test/list",
            N("Subscriptions"),
            N("Unsubscribe cooking", clickable = true),  // cy 120, band 0
            N("Unsubscribe travel", clickable = true),   // cy 220, band 7
        )
        tracker.observe(before)
        tracker.markProcessed(before.nodes[1]) // the cooking row (nodes[0] is the heading)
        assertEquals(1, tracker.processedCount())

        // The SAME rows after a scroll: identical labels, pushed far down the screen
        // (fresh indexes -> fresh bands), rendered under the same container.
        val after = obs(
            "http://m0.test/list",
            N("Subscriptions"),
            N(" filler above "),
            N(" filler mid "),
            N("Unsubscribe cooking", clickable = true),
            N("Unsubscribe travel", clickable = true),
        )
        tracker.observe(after)
        assertEquals(2, tracker.trackedCount(), "the scroll must not register new targets")
        assertEquals(1, tracker.processedCount(), "processed state survives the scroll")
        assertEquals(1, tracker.pendingCount())

        // the processed row is still the processed row on the shifted page
        val matchedCooking = tracker.rows().single { it.label == "unsubscribe cooking" }
        assertEquals(TargetTracker.Status.PROCESSED, matchedCooking.status)
    }

    @Test
    fun `navigation away and back preserves every status - the tracker is the memory of record`() {
        val tracker = TargetTracker()
        val list = obs(
            "http://m0.test/list",
            N("Unsubscribe cooking", clickable = true),
            N("Unsubscribe travel", clickable = true),
        )
        tracker.observe(list)
        tracker.markProcessed(list.nodes[0])

        // navigate to a detail page: neither row is present
        val detail = obs(
            "http://m0.test/confirm",
            N("Confirm unsubscribe"),
            N("Yes", clickable = true),
        )
        tracker.observe(detail)
        assertEquals(3, tracker.trackedCount(), "the confirm page's row registers as a new target")
        assertEquals(1, tracker.processedCount(), "processed rows survive navigation away")
        assertEquals(2, tracker.pendingCount())

        // navigate back: the list re-identifies by exact key (same labels, same bands)
        tracker.observe(list)
        assertEquals(1, tracker.processedCount())
        assertEquals(
            TargetTracker.Status.PROCESSED,
            tracker.rows().single { it.label == "unsubscribe cooking" }.status,
        )
        assertEquals(
            TargetTracker.Status.PENDING,
            tracker.rows().single { it.label == "unsubscribe travel" }.status,
        )
    }

    @Test
    fun `duplicate rows stay distinct targets and resolve one-to-one`() {
        val tracker = TargetTracker()
        val page = obs(
            "http://m0.test/list",
            N("Cooking"),
            N("Unsubscribe", clickable = true),  // under Cooking
            N("Travel"),
            N("Unsubscribe", clickable = true),  // under Travel - different container AND band
        )
        tracker.observe(page)
        assertEquals(2, tracker.trackedCount(), "identical labels in different sections are distinct rows")
        assertTrue(tracker.rows()[0].key.container != tracker.rows()[1].key.container)
        tracker.markProcessed(page.nodes[1]) // the first Unsubscribe node
        assertEquals(1, tracker.processedCount())
        assertEquals(1, tracker.pendingCount(), "the second identical row is still pending")
        assertEquals(listOf("unsubscribe"), tracker.pendingLabels())
    }

    @Test
    fun `newly revealed rows register as pending - lazy lists extend the target set`() {
        val tracker = TargetTracker()
        val first = obs(
            "http://m0.test/list",
            N("Unsubscribe cooking", clickable = true),
            N("Unsubscribe travel", clickable = true),
        )
        tracker.observe(first)
        tracker.markProcessed(first.nodes[0])

        val scrolled = obs(
            "http://m0.test/list",
            N(" filler "),
            N("Unsubscribe travel", clickable = true),
            N("Unsubscribe food", clickable = true), // revealed by the scroll
        )
        tracker.observe(scrolled)
        assertEquals(3, tracker.trackedCount())
        assertEquals(2, tracker.pendingCount())
        assertEquals(listOf("unsubscribe travel", "unsubscribe food"), tracker.pendingLabels())
    }

    // -- reporting ----------------------------------------------------------------------

    @Test
    fun `the summary and summary line carry the deterministic counters`() {
        val tracker = TargetTracker()
        val page = obs(
            "http://m0.test/list",
            N("Unsubscribe cooking", clickable = true),
            N("Unsubscribe travel", clickable = true),
            N("Unsubscribe food", clickable = true),
        )
        tracker.observe(page)
        tracker.markProcessed(page.nodes[0])
        tracker.markSkipped(page.nodes[1])
        val summary = tracker.summary()
        assertEquals(TargetTracker.Summary(tracked = 3, processed = 1, pending = 1, skipped = 1), summary)
        assertFalse(summary.drained)
        assertTrue(tracker.summaryLine().contains("tracked=3 processed=1 pending=1 skipped=1"))
        assertTrue(tracker.summaryLine().contains("(drained=false)"))
    }

    @Test
    fun `progress lines lead with the counters and list pending targets in first-seen order`() {
        val tracker = TargetTracker()
        val page = obs(
            "http://m0.test/list",
            N("Cooking"),
            N("Unsubscribe cooking", clickable = true),
            N("Unsubscribe travel", clickable = true),
        )
        tracker.observe(page)
        val lines = tracker.progressLines()
        assertEquals(
            "TASK PROGRESS: 0 processed, 2 pending, 0 skipped of 2 tracked targets (drained=false)",
            lines.first(),
        )
        assertEquals("pending target: \"unsubscribe cooking\" under \"cooking\"", lines[1])
        assertEquals("pending target: \"unsubscribe travel\" under \"cooking\"", lines[2])
        tracker.markProcessed(page.nodes[1])
        assertTrue(
            tracker.progressLines().first().startsWith("TASK PROGRESS: 1 processed, 1 pending"),
        )
    }

    @Test
    fun `progress line overflow is counted - never silently dropped`() {
        val tracker = TargetTracker()
        val nodes = (0 until TargetTracker.MAX_PROGRESS_LABELS + 2).map {
            N("row $it", clickable = true)
        }
        val page = obs("http://m0.test/list", N("Header"), *nodes.toTypedArray())
        tracker.observe(page)
        val lines = tracker.progressLines()
        assertEquals(TargetTracker.MAX_PROGRESS_LABELS + 2, lines.size)
        assertEquals("+2 more pending targets not listed", lines.last())
    }

    @Test
    fun `a drained tracker reports drained progress`() {
        val tracker = TargetTracker()
        val page = obs("http://m0.test/list", N("Row", clickable = true))
        tracker.observe(page)
        tracker.markSkipped(page.nodes[0])
        assertTrue(tracker.progressLines().first().endsWith("(drained=true)"))
    }

    // -- guard wiring (the vacuous Phase-8D interim is replaced) --------------------------

    @Test
    fun `action-multi guards REQUIRE the real tracker - the vacuous interim is unrepresentable`() {
        val error = assertThrows<IllegalArgumentException> {
            CompletionGuards(TriageClass.ACTION_MULTI)
        }
        assertTrue(error.message?.contains("TargetTracker") == true, error.message)
        // and a tracker is only lawful for action-multi
        assertThrows<IllegalArgumentException> {
            CompletionGuards(
                TriageClass.LOOKUP,
                tracker = TargetTracker(),
            )
        }
    }
}
