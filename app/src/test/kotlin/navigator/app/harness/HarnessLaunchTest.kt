package navigator.app.harness

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The headless launch policy (owner-directed harness-visibility tooling, 2026-10-06):
 * show-only never auto-runs, the extra aliases resolve, and unknown
 * `navigator.harness.*` extras are detected instead of dropped. Pure JVM — no
 * instrumentation, no Robolectric. PHASE_VEXP3: the retired shadow/laya names now fall
 * to their defaults (and the long names are flagged as unknown).
 */
class HarnessLaunchTest {

    /** Map-backed [HarnessLaunch.Extras] (mirrors how `am start` extras resolve). */
    private class FakeExtras(private val map: Map<String, Any>) : HarnessLaunch.Extras {
        override fun string(name: String): String? = map[name] as? String
        override fun bool(name: String, default: Boolean): Boolean = map[name] as? Boolean ?: default
        override fun long(name: String, default: Long): Long = map[name] as? Long ?: default
        override fun keys(): Set<String> = map.keys
    }

    private fun parse(vararg pairs: Pair<String, Any>): HarnessLaunch = HarnessLaunch.parse(FakeExtras(mapOf(*pairs)))

    @Test
    fun `show-only never auto-runs - even with a goal extra riding along`() {
        val launch = parse(
            "showOnly" to true,
            "goal" to "search-and-read-1",
            "engine" to "stub",
            "autoDeny" to true,
        )
        assertTrue(launch.showOnly)
        assertTrue(launch.hasRunRequest, "the goal request is still parsed and visible")
        assertFalse(launch.autoRuns, "show-only NEVER starts a run")
    }

    @Test
    fun `show-only with custom-goal extras registers the row but never runs`() {
        val launch = parse(
            "showOnly" to true,
            "customGoalText" to "find the apple pie recipe and open it",
            "customStartUrl" to "https://www.allrecipes.com",
            "customGoalId" to "uat-applepie-02",
        )
        assertTrue(launch.showOnly)
        assertEquals("uat-applepie-02", launch.customGoal?.id)
        assertFalse(launch.autoRuns, "show-only NEVER starts a run")
    }

    @Test
    fun `a goal request without show-only auto-runs`() {
        assertTrue(parse("goal" to "search-and-read-2").autoRuns)
        assertTrue(
            parse(
                "customGoalText" to "read it",
                "customStartUrl" to "https://example.com",
            ).autoRuns,
        )
    }

    @Test
    fun `no extras means no run request and a silent bring-to-front`() {
        val launch = parse()
        assertFalse(launch.autoRuns)
        assertFalse(launch.hasRunRequest)
        assertFalse(launch.extrasPresent)
    }

    @Test
    fun `unrecognized navigator harness extras are flagged, unknown short names are not`() {
        val launch = parse(
            "goal" to "search-and-read-1",
            "navigator.harness.extra.SHADWO" to true, // a typo'd long name
            "navigator.harness.extra.FORCE" to "yes",
            "stranger" to true, // not namespaced: not ours to judge
        )
        assertEquals(
            listOf("navigator.harness.extra.FORCE", "navigator.harness.extra.SHADWO"),
            launch.unrecognizedExtras,
        )
    }

    @Test
    fun `known namespaced extras are never flagged`() {
        val launch = parse(
            HarnessLaunch.EXTRA_GOAL to "search-and-read-1",
            HarnessLaunch.EXTRA_SETTLE_MS to 300L,
            HarnessLaunch.EXTRA_SHOW_ONLY to false,
        )
        assertTrue(launch.unrecognizedExtras.isEmpty())
    }

    @Test
    fun `custom goal build failures surface as customError - the launch decides how loud`() {
        val bad = parse("customGoalText" to "read it", "customStartUrl" to "en.wikipedia.org")
        assertNull(bad.customGoal)
        assertEquals(
            "custom goal start URL must be absolute http(s) (was: \"en.wikipedia.org\")",
            bad.customError,
        )
        assertTrue(bad.customRequested, "the request itself is still visible")
    }

    @Test
    fun `defaults mirror the headless launch`() {
        val launch = parse("goal" to "search-and-read-2")
        assertFalse(launch.live, "engine defaults to STUB")
        assertTrue(launch.autoDeny, "auto-deny defaults ON in the harness only")
        assertEquals(HarnessLaunch.DEFAULT_SETTLE_MS, launch.settleMs)
        assertEquals(HarnessLaunch.DEFAULT_START_DELAY_MS, launch.startDelayMs)
        assertFalse(launch.keyPresent)
        assertFalse(launch.showOnly)
    }

    @Test
    fun `the key extra reports PRESENCE only - never the value`() {
        val launch = parse("openrouterKey" to "sk-or-v1-SUPER-SECRET")
        assertTrue(launch.keyPresent)
        // The plan object structurally carries no key field: its only string-typed
        // members are ids/messages; assert none echoes the secret.
        val echoed = listOfNotNull(launch.goalId, launch.customError) +
            launch.unrecognizedExtras +
            listOf(launch.customGoal?.goal, launch.customGoal?.startUrl, launch.customGoal?.id)
        echoed.filterNotNull().forEach { field ->
            assertFalse("SUPER-SECRET" in field, field)
        }
    }

    @Test
    fun `settle extras are coerced to legal values`() {
        assertEquals(0, parse("settleMs" to -5L).settleMs)
    }

    @Test
    fun `live engine selection parses through the shared mode parser`() {
        assertTrue(parse("engine" to "live", "goal" to "x").live)
        assertTrue(parse("engine" to "jev", "goal" to "x").live, "the `jev` alias stays live")
        assertFalse(parse("engine" to "stub", "goal" to "x").live)
        assertFalse(parse("engine" to "nonsense", "goal" to "x").live, "unknown engines degrade to STUB")
    }

    @Test
    fun `the retired laya mode names degrade to STUB`() {
        assertEquals(
            HarnessEngineMode.STUB,
            parse("engine" to "laya", "goal" to "x").mode,
            "the laya modes retired with the training program (PHASE_VEXP3)",
        )
        assertEquals(
            HarnessEngineMode.STUB,
            parse("engine" to "laya-fb-jev", "goal" to "x").mode,
        )
    }
}
