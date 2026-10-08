package navigator.app.overlay

import org.junit.Before
import org.junit.Test

/**
 * Gate 9.4 (latency law) — THIN JUnit wrapper around [GateScenarios.runGate94].
 * EVIDENCE PATH: the in-app debug runner (`adb broadcast navigator.app.action.GATE94`
 * → `logcat -s NavigatorGates`; the RESULT + CHECK lines carry the p95 ms and all
 * samples), NOT this class — the instrumented shell is platform-blocked on the API 35
 * emulator (measured; see `.state/phase-9-status.json`). Kept compiling + documenting
 * for devices where the in-process handshake works.
 */
class Gate94LiveLogLatencyTest {

    private val support = OverlayGateSupport

    @Before
    fun setUp() {
        support.enableAccessibilityService()
    }

    @Test
    fun `gate 9 4 - latency scenario passes`() {
        val result = GateScenarios.runGate94(support.a11yService())
        check(result.passed) {
            "${result.gate} FAILED: ${result.summary}\n" +
                result.checks.joinToString("\n") { "  ${it.name}: ${it.passed} - ${it.detail}" }
        }
    }
}
