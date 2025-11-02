package de.fabianthomas.intentbridge

import android.os.Bundle
import androidx.activity.ComponentActivity

class LinkRouterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LinkRouter.handle(this, intent)
    }
}
