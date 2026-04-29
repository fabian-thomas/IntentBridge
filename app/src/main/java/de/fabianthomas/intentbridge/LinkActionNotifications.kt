package de.fabianthomas.intentbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.util.Locale

object LinkActionNotifications {
    private const val HANDOFF_CHANNEL_ID = "intentbridge_handoff_channel"
    private const val ACTION_NOTIFICATION_OPEN = "de.fabianthomas.intentbridge.action.NOTIFICATION_OPEN"
    private const val ACTION_NOTIFICATION_SHARE = "de.fabianthomas.intentbridge.action.NOTIFICATION_SHARE"
    private const val ACTION_NOTIFICATION_COPY = "de.fabianthomas.intentbridge.action.NOTIFICATION_COPY"
    private const val REQUEST_CODE = 0

    fun showIncomingLink(context: Context, uri: Uri) {
        val appCtx = context.applicationContext
        val sourceRole = ProfileRoleStore.opposite(ProfileRoleStore.getRole(appCtx))
        val sourceName = ProfileRoleStore.describe(sourceRole)
        ensureHandoffChannel(appCtx, sourceName)
        val rawUrl = uri.toString()
        val key = notificationKey(rawUrl)
        val notificationId = key.stableId()
        val actionText = shareableActionText(uri)
        val keyHash = key.stableId().toString()

        val openPendingIntent = PendingIntent.getActivity(
            appCtx,
            REQUEST_CODE,
            LinkForwardActivity.createIntent(appCtx, uri.toString()).apply {
                action = ACTION_NOTIFICATION_OPEN
                data = Uri.parse("intentbridge://notification/$keyHash/open")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sharePendingIntent = sharePendingIntent(
            appCtx,
            keyHash,
            actionText,
            appCtx.getString(R.string.notification_handoff_title, sourceName)
        )
        val copyPendingIntent = copyPendingIntent(
            appCtx,
            keyHash,
            actionText
        )

        val notification = baseBuilder(appCtx, appCtx.getString(R.string.notification_handoff_title, sourceName), actionText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openPendingIntent)
            .addAction(shareAction(appCtx, sharePendingIntent))
            .addAction(copyAction(appCtx, copyPendingIntent))
            .build()

        NotificationManagerCompat.from(appCtx).notify(notificationId, notification)
    }

    fun showFailedLink(context: Context, failedUrl: String) {
        val appCtx = context.applicationContext
        val uri = runCatching { Uri.parse(failedUrl) }.getOrNull() ?: return
        val targetRole = ProfileRoleStore.opposite(ProfileRoleStore.getRole(appCtx))
        val targetName = ProfileRoleStore.describe(targetRole)
        ensureHandoffChannel(appCtx, targetName)
        val rawUrl = uri.toString()
        val key = notificationKey(rawUrl)
        val notificationId = key.stableId()
        val keyHash = key.stableId().toString()

        val openPendingIntent = PendingIntent.getActivity(
            appCtx,
            REQUEST_CODE,
            Intent(appCtx, LinkRouterActivity::class.java).apply {
                action = ACTION_NOTIFICATION_OPEN
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sharePendingIntent = sharePendingIntent(
            appCtx,
            keyHash,
            failedUrl,
            appCtx.getString(R.string.notification_handoff_failed_title, targetName)
        )
        val copyPendingIntent = copyPendingIntent(
            appCtx,
            keyHash,
            failedUrl
        )

        val notification = baseBuilder(appCtx, appCtx.getString(R.string.notification_handoff_failed_title, targetName), failedUrl)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openPendingIntent)
            .addAction(shareAction(appCtx, sharePendingIntent))
            .addAction(copyAction(appCtx, copyPendingIntent))
            .build()

        NotificationManagerCompat.from(appCtx).notify(notificationId, notification)
    }

    private fun notificationKey(rawUrl: String): String = "link|$rawUrl"

    private fun String.stableId(): Int = hashCode() and 0x7fffffff

    private fun ensureHandoffChannel(context: Context, sourceName: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            HANDOFF_CHANNEL_ID,
            context.getString(R.string.notification_handoff_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_handoff_channel_description, sourceName)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun baseBuilder(context: Context, title: String, text: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, HANDOFF_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    private fun sharePendingIntent(context: Context, keyHash: String, text: String, subject: String): PendingIntent {
        val chooser = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, subject)
            },
            context.getString(R.string.share_chooser)
        ).apply {
            action = ACTION_NOTIFICATION_SHARE
            data = Uri.parse("intentbridge://notification/$keyHash/share")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            chooser,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun copyPendingIntent(context: Context, keyHash: String, text: String): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, NotificationCopyReceiver::class.java).apply {
                action = ACTION_NOTIFICATION_COPY
                data = Uri.parse("intentbridge://notification/$keyHash/copy")
                putExtra(NotificationCopyReceiver.EXTRA_TEXT, text)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun shareAction(context: Context, pendingIntent: PendingIntent): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, R.drawable.ic_notification_share),
            context.getString(R.string.notification_action_share),
            pendingIntent
        ).setContextual(true).build()
    }

    private fun copyAction(context: Context, pendingIntent: PendingIntent): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, R.drawable.ic_notification_copy),
            context.getString(R.string.notification_action_copy),
            pendingIntent
        ).setContextual(true).build()
    }

    private fun shareableActionText(uri: Uri): String {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return uri.toString()
        return when (scheme) {
            "tel" -> uri.schemeSpecificPart?.let { Uri.decode(it) }?.takeIf { it.isNotBlank() } ?: uri.toString()
            "mailto" -> {
                val address = uri.schemeSpecificPart?.substringBefore('?')
                address?.let { Uri.decode(it) }?.takeIf { it.isNotBlank() } ?: uri.toString()
            }
            else -> uri.toString()
        }
    }
}
