package navigator.app.loop

import navigator.core.decision.Intents
import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateList
import navigator.core.enumerate.CandidateSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 10B (Gate 10B.2, the pure half): the cascade's routing module — the confidence
 * band route (Bands semantics, ART-5), the intent→slate mapping (the taxonomy's stage-2
 * grouping, browser-chrome EXCLUDED), and the page-host derivation for the intent view.
 */
class CascadeTest {

    private fun candidate(id: String, bucket: Bucket, browserChrome: Boolean = false) = Candidate(
        id = id,
        label = "label $id",
        kinds = listOf("tap"),
        cx = 1,
        cy = 1,
        bucket = bucket,
        source = CandidateSource.A11Y,
        sourceOrder = 0,
        synthetic = false,
        browserChrome = browserChrome,
    )

    @Test
    fun `band routing - HIGH executes, UNCERTAIN handshakes, LOW is the wrong-page signal`() {
        assertEquals(Cascade.Route.HIGH, Cascade.route(0.95))
        assertEquals(Cascade.Route.HIGH, Cascade.route(0.71))
        assertEquals(Cascade.Route.UNCERTAIN, Cascade.route(0.70), "0.70 itself is UNCERTAIN (Bands semantics)")
        assertEquals(Cascade.Route.UNCERTAIN, Cascade.route(0.50))
        assertEquals(Cascade.Route.UNCERTAIN, Cascade.route(0.30))
        assertEquals(Cascade.Route.LOW, Cascade.route(0.29))
        assertEquals(Cascade.Route.LOW, Cascade.route(0.05))
    }

    @Test
    fun `the intent-to-slate mapping is the taxonomy's stage-2 grouping`() {
        assertEquals(Bucket.NAV_LINKS, Cascade.bucketFor(Intents.FOLLOW_LINK))
        assertEquals(Bucket.BUTTONS, Cascade.bucketFor(Intents.ACTIVATE_CONTROL))
        assertEquals(Bucket.BUTTONS, Cascade.bucketFor(Intents.SUBMIT))
        assertEquals(Bucket.BUTTONS, Cascade.bucketFor(Intents.DISMISS_NAG))
        assertEquals(Bucket.FORM_FIELDS, Cascade.bucketFor(Intents.FILL_FIELD))
        assertEquals(null, Cascade.bucketFor(Intents.OPEN_SITE), "open-site is the code path")
        assertEquals(null, Cascade.bucketFor(Intents.GO_BACK))
        assertEquals(null, Cascade.bucketFor(Intents.SCROLL))
        assertEquals(null, Cascade.bucketFor(Intents.VERIFY_DONE))
    }

    @Test
    fun `the slate excludes browser-chrome candidates - URL needs are never stage-2 targets`() {
        val slate = CandidateList(
            flat = listOf(candidate("n1", Bucket.BUTTONS), candidate("n2", Bucket.BUTTONS, browserChrome = true)),
            bucketed = mapOf(
                Bucket.BUTTONS to listOf(
                    candidate("n1", Bucket.BUTTONS),
                    candidate("n2", Bucket.BUTTONS, browserChrome = true),
                ),
            ),
            truncatedBuckets = emptySet(),
            flatTruncated = false,
        )
        val drawn = Cascade.slate(slate, Intents.ACTIVATE_CONTROL)
        assertEquals(listOf("n1"), drawn.map { it.id }, "the omnibox subtree never draws a target slate")
        assertTrue(Cascade.slate(slate, Intents.VERIFY_DONE).isEmpty(), "code-path intents draw no slate")
    }

    @Test
    fun `hostOf extracts the page host and refuses non-URL sentinels`() {
        assertEquals("m0.test", Cascade.hostOf("http://m0.test/some/path?x=1"))
        assertEquals("m0.test", Cascade.hostOf("https://m0.test:8443/root"))
        assertEquals("deep.host.example", Cascade.hostOf("https://user@deep.host.example:443/p"))
        assertEquals("", Cascade.hostOf("hidden"), "the URL-bar sentinel renders no host")
        assertEquals("", Cascade.hostOf("unavailable"))
        assertEquals("", Cascade.hostOf(""))
        assertEquals("", Cascade.hostOf("about:blank"), "no authority separator, no host")
    }
}
