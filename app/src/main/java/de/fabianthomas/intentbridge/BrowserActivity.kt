package de.fabianthomas.intentbridge

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Default browser stub (set as default in Private Space).
 * Now delegates link handling to the shared LinkRouter.
 */
class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LinkRouter.handle(this, intent)
    }
}
