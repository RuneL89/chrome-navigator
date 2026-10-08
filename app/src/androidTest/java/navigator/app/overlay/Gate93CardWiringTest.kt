package navigator.app.overlay

import org.junit.Before
import org.junit.Test

/**
 * Gate 9.3 (gate card wiring) — THIN JUnit wrapper around [GateScenarios.runGate93].
 * EVIDENCE PATH: the in-app debug runner (`adb broadcast navigator.app.action.GATE93`
 * → `logcat -s NavigatorGates`; includes the measured 30s expiry), NOT this class —
 * the instrumented shell is platform-blocked on the API 35 emulator (measured; see
 * `.state/phase-9-status.json`). Kept compiling + documenting for devices where the
 * in-process handshake works.
 */
class Gate93CardWiringTest {

    private val support = OverlayGateSupport

    @Before
    fun setUp() {
        support.enableAccessibilityService()
    }

    @Test
    fun `gate 9 3 - card wiring scenario passes`() {
        val result = GateScenarios.runGate93(support.a11yService())
        check(result.passed) {
            "${result.gate} FAILED: ${result.summary}\n" +
                result.checks.joinToString("\n") { "  ${it.name}: ${it.passed} - ${it.detail}" }
        }
    }
}
