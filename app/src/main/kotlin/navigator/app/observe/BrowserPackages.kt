package navigator.app.observe

/**
 * The browser-package decision (2026-10-08 pre-UAT2 RC1) — the PURE half of the
 * observation service's allowlist: a JVM-pure object with no Android types, so the
 * focus-gating decisions (the executor's active-window retry, the loop's observation
 * readiness) are unit-pinnable on the plain JVM. [NavigatorAccessibilityService]'s
 * `PACKAGE_ALLOWLIST` delegates here — one source of truth.
 */
object BrowserPackages {

    /**
     * Observation/execution allowlist (vision 03 §1): the allowlisted browsers. The
     * Chromium webview-shell is on it for fixture capture on images that ship no
     * Chrome stable (Phase-1 capture harness only).
     */
    val ALLOWLIST: Set<String> = setOf("com.android.chrome", "org.chromium.webview_shell")

    /** The pure decision: is this window package an allowlisted browser? */
    fun isBrowser(packageName: String?): Boolean = packageName in ALLOWLIST
}
