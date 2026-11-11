package navigator.app.harness.vision

import kotlinx.coroutines.runBlocking
import navigator.app.loop.LoopObserver
import navigator.app.loop.StepObservation
import navigator.core.enumerate.ObservedNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PHASE_18 Stage 18.B CN-02 — the snapshotter's bounded consistency sequence (doc 04
 * §9.1), the plan's five cases. Scripted observations/captures only; $0, no device.
 *
 * PHASE_18 CHARACTERIZATION NOTE: this pins the CN-02 TARGET behavior just landed
 * (Stage 18.B); the doc 04 §9.1 items it maps to are 9.1 (snapshot identity) itself.
 */
class VisionSnapshotterTest {

    private fun obs(url: String, pkg: String = "com.android.chrome") = StepObservation(
        url = url,
        title = "Site A",
        lines = listOf("""[n1] "Site A" (540,300)"""),
        nodes = listOf(
            ObservedNode(
                id = "n1", text = "Site A", desc = "", clickable = false, editable = false,
                scrollable = false, left = 0, top = 0, right = 400, bottom = 100,
                className = "android.widget.TextView", sourceOrder = 0,
            ),
        ),
        windowPackage = pkg,
    )

    /** Scripted environment: queued observations + identity reads + captures. */
    private class Env(
        var observations: ArrayDeque<StepObservation>,
        var identities: ArrayDeque<SnapshotIdentity>,
        var shots: ArrayDeque<VisionCapture.ScreenShot?>,
    ) : LoopObserver {
        override suspend fun observe(): StepObservation = observations.removeFirst()
        val captured: MutableList<Int> = mutableListOf()

        fun reader() = VisionSnapshotter.IdentityReader { identities.removeFirst() }

        fun captureStep() = { index: Int ->
            captured.add(index)
            shots.removeFirst()
        }
    }

    private fun shot(source: String = "window:com.android.chrome:id=9:bounds=0,0,1080,2400") =
        VisionCapture.ScreenShot(
            base64 = "ZmFrZQ==", width = 100, height = 200,
            screenWidth = 1080, screenHeight = 2400,
            sha256 = "abc", jpegBytes = ByteArray(4), originX = 0, originY = 0, source = source,
        )

    private fun snapshotter(
        env: Env,
        retries: MutableList<Long> = mutableListOf(),
    ): VisionSnapshotter = VisionSnapshotter(
        observer = env,
        identity = env.reader(),
        runId = "run-test",
        retryDelay = { ms -> retries.add(ms) },
        now = { 1_000L },
    )

    @Test
    fun `a URL change during capture issues no action snapshot - stale`() = runBlocking {
        val env = Env(
            observations = ArrayDeque(listOf(obs("http://a.test/home"), obs("http://a.test/home"))),
            identities = ArrayDeque(listOf(SnapshotIdentity("com.android.chrome", "http://b.test/other"), SnapshotIdentity("com.android.chrome", "http://b.test/other"))),
            shots = ArrayDeque(listOf(shot(), shot())),
        )
        val outcome = snapshotter(env).snapshot(0, env.captureStep())
        assertTrue(outcome is VisionSnapshotter.Outcome.Stale)
        // Both attempts captured (the retry is part of the bounded sequence); the
        // OUTCOME is the stale refusal - no ACTION snapshot is issued on it.
        assertEquals(2, env.captured.size)
    }

    @Test
    fun `an active package change during capture issues no action snapshot - stale`() = runBlocking {
        val env = Env(
            observations = ArrayDeque(listOf(obs("http://a.test/home"), obs("http://a.test/home"))),
            identities = ArrayDeque(listOf(SnapshotIdentity("com.other.app", "http://a.test/home"), SnapshotIdentity("com.other.app", "http://a.test/home"))),
            shots = ArrayDeque(listOf(shot(), shot())),
        )
        val outcome = snapshotter(env).snapshot(0, env.captureStep())
        assertTrue(outcome is VisionSnapshotter.Outcome.Stale)
        assertEquals(2, env.captured.size)
    }

    @Test
    fun `irrelevant tree churn while package and URL stay stable - the snapshot proceeds`() = runBlocking {
        val env = Env(
            observations = ArrayDeque(listOf(obs("http://a.test/home"), obs("http://a.test/home"))),
            identities = ArrayDeque(listOf(SnapshotIdentity("com.android.chrome", "http://a.test/home"))),
            shots = ArrayDeque(listOf(shot(), shot())),
        )
        val outcome = snapshotter(env).snapshot(0, env.captureStep())
        // The tree CHANGED (a fresh observation object with different ad text is what the
        // identity deliberately ignores); package + URL matched, so the snapshot issued.
        assertTrue(outcome is VisionSnapshotter.Outcome.Consistent)
        val snap = (outcome as VisionSnapshotter.Outcome.Consistent).snapshot
        assertEquals("snap-run-test-1", snap.id)
        assertEquals(VisionSnapshot.Verdict.CONSISTENT, snap.verdict)
    }

    @Test
    fun `one mismatch then a stable pair - exactly one bounded retry`() = runBlocking {
        val retries = mutableListOf<Long>()
        val env = Env(
            observations = ArrayDeque(listOf(obs("http://a.test/home"), obs("http://a.test/home"))),
            identities = ArrayDeque(
                listOf(
                    SnapshotIdentity("com.other.app", "http://a.test/home"), // first pass: mismatch
                    SnapshotIdentity("com.android.chrome", "http://a.test/home"), // retry: match
                ),
            ),
            shots = ArrayDeque(listOf(shot(), shot())),
        )
        val outcome = snapshotter(env, retries).snapshot(0, env.captureStep())
        assertTrue(outcome is VisionSnapshotter.Outcome.Consistent)
        assertEquals(
            VisionSnapshot.Verdict.CONSISTENT_AFTER_RETRY,
            (outcome as VisionSnapshotter.Outcome.Consistent).snapshot.verdict,
        )
        assertEquals(listOf(VisionSnapshotter.RETRY_WAIT_MS), retries)
        assertEquals(2, env.captured.size)
    }

    @Test
    fun `a persistent mismatch - the typed stale outcome and no second snapshot issued`() = runBlocking {
        val retries = mutableListOf<Long>()
        val env = Env(
            observations = ArrayDeque(listOf(obs("http://a.test/home"), obs("http://a.test/home"))),
            identities = ArrayDeque(
                listOf(
                    SnapshotIdentity("com.other.app", "http://a.test/home"),
                    SnapshotIdentity("com.android.chrome", "http://b.test/other"),
                ),
            ),
            shots = ArrayDeque(listOf(shot(), shot())),
        )
        val outcome = snapshotter(env, retries).snapshot(0, env.captureStep())
        assertTrue(outcome is VisionSnapshotter.Outcome.Stale)
        assertTrue(
            (outcome as VisionSnapshotter.Outcome.Stale).reason.contains("changed around the capture"),
        )
        assertEquals(listOf(VisionSnapshotter.RETRY_WAIT_MS), retries)
        assertEquals(2, env.captured.size)
    }
}
