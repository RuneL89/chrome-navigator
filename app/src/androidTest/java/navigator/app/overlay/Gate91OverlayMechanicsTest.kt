package navigator.app.overlay

import org.junit.Before
import org.junit.Test

/**
 * Gate 9.1 (overlay mechanics) — THIN JUnit wrapper around [GateScenarios.runGate91].
 * EVIDENCE PATH: the in-app debug runner (`adb broadcast navigator.app.action.GATE91`
 * → `logcat -s NavigatorGates`), NOT this class — the instrumented shell is
 * platform-blocked on the API 35 emulator (measured; see `.state/phase-9-status.json`).
 * Kept compiling + documenting for devices where the in-process handshake works.
 */
class Gate91OverlayMechanicsTest {

    private val support = OverlayGateSupport

    @Before
    fun setUp() {
        support.enableAccessibilityService()
    }

    @Test
    fun `gate 9 1 - overlay mechanics scenario passes`() {
        val result = GateScenarios.runGate91(support.a11yService())
        check(result.passed) {
            "${result.gate} FAILED: ${result.summary}\n" +
                result.checks.joinToString("\n") { "  ${it.name}: ${it.passed} - ${it.detail}" }
        }
    }
}
