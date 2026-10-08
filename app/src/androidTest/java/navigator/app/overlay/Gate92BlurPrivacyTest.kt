package navigator.app.overlay

import org.junit.Before
import org.junit.Test

/**
 * Gate 9.2 (blur privacy, ART-8) — THIN JUnit wrapper around [GateScenarios.runGate92].
 * EVIDENCE PATH: the in-app debug runner (`adb broadcast navigator.app.action.GATE92`
 * → `logcat -s NavigatorGates`), NOT this class — the instrumented shell is
 * platform-blocked on the API 35 emulator (measured; see `.state/phase-9-status.json`).
 * Kept compiling + documenting for devices where the in-process handshake works.
 */
class Gate92BlurPrivacyTest {

    private val support = OverlayGateSupport

    @Before
    fun setUp() {
        support.enableAccessibilityService()
    }

    @Test
    fun `gate 9 2 - blur privacy scenario passes`() {
        val result = GateScenarios.runGate92(support.a11yService())
        check(result.passed) {
            "${result.gate} FAILED: ${result.summary}\n" +
                result.checks.joinToString("\n") { "  ${it.name}: ${it.passed} - ${it.detail}" }
        }
    }
}
