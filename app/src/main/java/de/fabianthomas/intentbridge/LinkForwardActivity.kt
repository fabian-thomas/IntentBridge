package de.fabianthomas.intentbridge

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast

class LinkForwardActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingUri: Uri? = null
    private var resumed = false
    private var hasFocus = false
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent?.getStringExtra(EXTRA_URL)
        pendingUri = runCatching { Uri.parse(url) }.getOrNull()
        if (pendingUri == null) {
            finish()
            return
        }

        maybeLaunch()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        maybeLaunch()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        this.hasFocus = hasFocus
        if (hasFocus) {
            maybeLaunch()
        }
    }

    private fun maybeLaunch() {
        if (launched) return
        val target = pendingUri ?: return
        if (!resumed || !hasFocus) return

        launched = true
        handler.post {
            if (launchRoutedViewIntent(target, addNewTask = true)) {
                CrossSpaceMessages.showOpenedToast(this)
            } else {
                Log.w(TAG, "Failed to auto-open $target")
                Toast.makeText(this, getString(R.string.handoff_failed), Toast.LENGTH_LONG).show()
            }

            finish()
        }
    }

    companion object {
        private const val EXTRA_URL = "de.fabianthomas.intentbridge.EXTRA_URL"
        private const val TAG = "LinkForwardActivity"

        fun createIntent(context: Context, url: String): Intent {
            return Intent(context, LinkForwardActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
        }
    }
}
