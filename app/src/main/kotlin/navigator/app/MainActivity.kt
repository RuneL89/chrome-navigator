package navigator.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

/** Phase 0 placeholder: proves the Android module builds and launches. Replaced by the
 *  overlay UX in its own phase (vision 01 §3). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Text("Chrome Navigator")
        }
    }
}
