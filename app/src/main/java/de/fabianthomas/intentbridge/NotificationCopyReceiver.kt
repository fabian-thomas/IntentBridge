package de.fabianthomas.intentbridge

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class NotificationCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_COPY_TEXT) return
        val value = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clipboard_label_link), value))
    }

    companion object {
        const val ACTION_COPY_TEXT = "de.fabianthomas.intentbridge.action.NOTIFICATION_COPY"
        const val EXTRA_TEXT = "de.fabianthomas.intentbridge.extra.NOTIFICATION_COPY_TEXT"
    }
}
