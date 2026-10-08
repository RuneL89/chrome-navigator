package navigator.app

import android.app.Activity
import android.os.Bundle

/**
 * The launcher entry (Phase 0 placeholder → Phase 9 thin launcher): the manifest keeps
 * `.MainActivity` as the LAUNCHER (no launcher-icon churn); it immediately opens the
 * Phase-9 setup wizard ([navigator.app.setup.SetupActivity]) — the product's home base.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(android.content.Intent(this, navigator.app.setup.SetupActivity::class.java))
        finish()
    }
}
