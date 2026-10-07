package navigator.app.loop

import navigator.core.log.TriageClass
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Completion-guard law (vision 04 §3): per-triage-class code state machines, ART-4. */
class CompletionGuardsTest {

    private fun guard(kind: TriageClass) = CompletionGuards(kind)

    @Test
    fun `lookup needs a contentful page beyond the first page`() {
        val guards = guard(TriageClass.LOOKUP)
        guards.seed("http://m0.test/home")
        assertFalse(guards.satisfied())
        // the first page going contentful is NOT results loaded
        guards.record("http://m0.test/home", contentTextNodes = 5, textTyped = false, submitExecuted = false)
        assertFalse(guards.satisfied())
        // a contentful page on another URL is
        guards.record("http://m0.test/results", contentTextNodes = 5, textTyped = false, submitExecuted = false)
        assertTrue(guards.satisfied())
    }

    @Test
    fun `lookup on a sparse second page is not satisfied`() {
        val guards = guard(TriageClass.LOOKUP)
        guards.seed("http://m0.test/home")
        guards.record("http://m0.test/other", contentTextNodes = 2, textTyped = false, submitExecuted = false)
        assertFalse(guards.satisfied())
    }

    @Test
    fun `research needs two distinct contentful pages`() {
        val guards = guard(TriageClass.RESEARCH_MULTI_STEP)
        guards.seed("http://m0.test/hub")
        guards.record("http://m0.test/p1", contentTextNodes = 4, textTyped = false, submitExecuted = false)
        assertFalse(guards.satisfied())
        guards.record("http://m0.test/p2", contentTextNodes = 3, textTyped = false, submitExecuted = false)
        assertTrue(guards.satisfied())
        // the same URL again does not add a page
        guards.record("http://m0.test/p2", contentTextNodes = 9, textTyped = false, submitExecuted = false)
        assertTrue(guards.satisfied())
    }

    @Test
    fun `form-fill needs a typed field and a later submit`() {
        val guards = guard(TriageClass.FORM_FILL)
        guards.seed("http://m0.test/form")
        assertFalse(guards.satisfied())
        guards.record("http://m0.test/form", contentTextNodes = 0, textTyped = true, submitExecuted = false)
        assertFalse(guards.satisfied())
        guards.record("http://m0.test/form", contentTextNodes = 0, textTyped = false, submitExecuted = true)
        assertTrue(guards.satisfied())
    }

    @Test
    fun `form-fill - submit before any typed field does not satisfy`() {
        val guards = guard(TriageClass.FORM_FILL)
        guards.seed("http://m0.test/form")
        guards.record("http://m0.test/form", contentTextNodes = 0, textTyped = false, submitExecuted = true)
        assertFalse(guards.satisfied())
        guards.record("http://m0.test/form", contentTextNodes = 0, textTyped = true, submitExecuted = false)
        // typed after the submit still needs a submit AFTER the fill
        assertFalse(guards.satisfied())
    }

    @Test
    fun `content threshold is three non-interactive text nodes`() {
        val guards = guard(TriageClass.LOOKUP)
        guards.seed("http://m0.test/home")
        guards.record("http://m0.test/page", contentTextNodes = CompletionGuards.CONTENT_MIN_TEXT_NODES - 1, textTyped = false, submitExecuted = false)
        assertFalse(guards.satisfied())
        val guards2 = guard(TriageClass.LOOKUP)
        guards2.seed("http://m0.test/home")
        guards2.record("http://m0.test/page", contentTextNodes = CompletionGuards.CONTENT_MIN_TEXT_NODES, textTyped = false, submitExecuted = false)
        assertTrue(guards2.satisfied())
    }

    @Test
    fun `describe reports human-readable state for the step log`() {
        val guards = guard(TriageClass.FORM_FILL)
        val text = guards.describe()
        assertTrue(text.contains("fieldFilled=false"))
        assertTrue(text.contains("submittedAfterFill=false"))
    }

    // -- Phase 8D classes (vision 04 §3: action = post-action state observed;
    //    action-multi = tracker drained). Phase 08G re-pins the action-multi WEAK to
    //    the REAL tracker: the vacuous pending=0 interim is replaced (that behavior
    //    change IS Phase 08G's purpose, same precedent as 08J Round 2). ---------------

    @Test
    fun `action needs one executed action with a post-action state observed`() {
        val guards = guard(TriageClass.ACTION)
        guards.seed("http://m0.test/settings")
        assertFalse(guards.satisfied())
        // a refused/failed action is not a post-action state
        guards.record("http://m0.test/settings", contentTextNodes = 0, textTyped = false, submitExecuted = false, actionExecuted = false)
        assertFalse(guards.satisfied())
        // one executed action (recorded after its post-step observation) is
        guards.record("http://m0.test/confirm", contentTextNodes = 0, textTyped = false, submitExecuted = false, actionExecuted = true)
        assertTrue(guards.satisfied())
        assertEquals(
            "action: post-action state observed=true (needs one executed action)",
            guards.describe().substringBefore(";"),
        )
    }

    @Test
    fun `action-multi with pending targets is NONE - finish is impossible and the refusal names the count`() {
        val tracker = TargetTracker()
        val page = obs(
            "http://m0.test/channels",
            N("Unsubscribe cooking", clickable = true),
            N("Unsubscribe travel", clickable = true),
        )
        tracker.observe(page)
        val entity = TargetEntity.extract("unsubscribe from all \"Retro Cooking\" channels")
        val guards = CompletionGuards(TriageClass.ACTION_MULTI, entity, tracker)
        guards.seed("http://m0.test/channels")
        assertEquals(GuardLevel.NONE, guards.levelAt("Retro Cooking - featured", "http://m0.test/channels"),
            "pending targets refuse EVERY path - even a title/URL identity match")
        assertEquals(
            "2 target(s) still pending (drained=false)",
            guards.refusalDetail(),
            "the refusal must name the pending count (vision 04 §3: tracker drained)",
        )
    }

    @Test
    fun `action-multi is achieved only when drained - skipped targets count as resolved`() {
        val tracker = TargetTracker()
        val page = obs(
            "http://m0.test/channels",
            N("Unsubscribe cooking", clickable = true),
            N("Unsubscribe travel", clickable = true),
        )
        tracker.observe(page)
        val entity = TargetEntity.extract("unsubscribe from all \"Retro Cooking\" channels")
        val guards = CompletionGuards(TriageClass.ACTION_MULTI, entity, tracker)
        guards.seed("http://m0.test/channels")

        // one target processed, the owner refused the other (skip): drained either way
        tracker.markProcessed(page.nodes[0])
        tracker.markSkipped(page.nodes[1])
        assertTrue(guards.satisfied(), "drained = none pending; skipped is resolved")
        assertEquals(GuardLevel.WEAK, guards.levelAt("An unrelated page", "http://m0.test/other"))
        assertEquals(
            GuardLevel.STRONG,
            guards.levelAt("Retro Cooking - featured", "http://m0.test/channels"),
            "STRONG stays the strict superset: drained predicate AND the identity match",
        )
        assertNull(guards.refusalDetail(), "a satisfied guard carries no refusal detail")
    }

    @Test
    fun `action-multi describe carries the tracker counters for the step log`() {
        val tracker = TargetTracker()
        tracker.observe(obs("http://m0.test/channels", N("Unsubscribe cooking", clickable = true)))
        val guards = CompletionGuards(TriageClass.ACTION_MULTI, tracker = tracker)
        val text = guards.describe()
        assertTrue(text.startsWith("action-multi: targets:"))
        assertTrue(text.contains("pending=1"))
        assertTrue(text.contains("(drained=false)"))
    }
}
