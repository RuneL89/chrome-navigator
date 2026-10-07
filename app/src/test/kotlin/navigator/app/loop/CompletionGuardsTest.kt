package navigator.app.loop

import navigator.core.log.TriageClass
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    //    action-multi = tracker drained) -----------------------------------------------

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
    }

    @Test
    fun `action-multi reads the tracker - drained holds until Phase 8G tracks targets`() {
        val guards = guard(TriageClass.ACTION_MULTI)
        guards.seed("http://m0.test/channels")
        assertTrue(guards.satisfied(), "no tracker targets pending (the tracker is Phase 8G)")
        // STRONG still requires the class predicate AND the identity match: with the
        // predicate vacuously true, levelAt is the entity-identity gate.
        val entity = TargetEntity.extract("unsubscribe from all \"Retro Cooking\" channels")
        val guarded = CompletionGuards(TriageClass.ACTION_MULTI, entity)
        guarded.seed("http://m0.test/channels")
        assertEquals(
            GuardLevel.WEAK,
            guarded.levelAt("An unrelated page", "http://m0.test/other"),
            "predicate drained-vacuously: the ceiling is the identity match",
        )
        assertEquals(
            GuardLevel.STRONG,
            guarded.levelAt("Retro Cooking - featured", "http://m0.test/channels"),
        )
    }
}
