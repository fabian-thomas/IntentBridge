package de.fabianthomas.intentbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import java.util.ArrayList

class ShareForwardActivity : Activity() {

    private var shareId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shareId = intent?.getStringExtra(EXTRA_SHARE_ID)
        if (shareId.isNullOrEmpty()) {
            finish()
            return
        }

        val stored = ShareStorage.load(this, shareId!!)
        if (stored == null) {
            Toast.makeText(this, R.string.share_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val forwardIntent = buildForwardIntent(stored)
        if (forwardIntent == null) {
            Toast.makeText(this, R.string.share_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        forwardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(forwardIntent, getString(R.string.share_chooser)))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch share chooser", e)
            Toast.makeText(this, R.string.share_missing, Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }

    private fun buildForwardIntent(stored: ShareStorage.StoredShare): Intent? {
        val hasMultiple = stored.items.size > 1
        if (stored.items.isEmpty() && stored.text == null && stored.html == null) {
            return null
        }

        val intent = Intent(if (hasMultiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
        val type = when {
            hasMultiple -> "*/*"
            stored.items.isNotEmpty() -> stored.items.first().mime ?: stored.mime ?: "*/*"
            else -> "text/plain"
        }
        intent.type = type

        stored.subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        stored.text?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        stored.html?.let { intent.putExtra(Intent.EXTRA_HTML_TEXT, it) }

        if (hasMultiple) {
            val uris = ArrayList<Uri>()
            stored.items.mapTo(uris) { it.uri }
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        } else if (stored.items.isNotEmpty()) {
            intent.putExtra(Intent.EXTRA_STREAM, stored.items.first().uri)
            stored.items.first().displayName?.let {
                intent.putExtra(Intent.EXTRA_TITLE, it)
            }
        }

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    companion object {
        private const val EXTRA_SHARE_ID = "de.fabianthomas.intentbridge.EXTRA_SHARE_ID"
        private const val TAG = "ShareForwardActivity"

        fun createIntent(context: Context, shareId: String): Intent {
            return Intent(context, ShareForwardActivity::class.java).apply {
                putExtra(EXTRA_SHARE_ID, shareId)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
        }
    }
}
