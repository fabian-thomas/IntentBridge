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
    private const val HANDOFF_NOTIFICATION_ID = 2001
    private const val HANDOFF_REQUEST_CODE = 201
    private const val HANDOFF_SHARE_ACTION_REQUEST_CODE_BASE = 401
    private const val HANDOFF_COPY_ACTION_REQUEST_CODE_BASE = 402
    private const val FAILURE_NOTIFICATION_ID = 2101
    private const val FAILURE_REQUEST_CODE = 211
    private const val FAILURE_SHARE_REQUEST_CODE = 521
    private const val FAILURE_COPY_REQUEST_CODE = 522

    fun showIncomingLink(context: Context, uri: Uri) {
        val appCtx = context.applicationContext
        val sourceRole = ProfileRoleStore.opposite(ProfileRoleStore.getRole(appCtx))
        val sourceName = ProfileRoleStore.describe(sourceRole)
        ensureHandoffChannel(appCtx, sourceName)
        val actionText = shareableActionText(uri)

        val openPendingIntent = PendingIntent.getActivity(
            appCtx,
            HANDOFF_REQUEST_CODE,
            LinkForwardActivity.createIntent(appCtx, uri.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hashOffset = actionText.hashCode() and 0x0FFFFFFF
        val sharePendingIntent = sharePendingIntent(
            appCtx,
            HANDOFF_SHARE_ACTION_REQUEST_CODE_BASE + hashOffset,
            actionText,
            appCtx.getString(R.string.notification_handoff_title, sourceName)
        )
        val copyPendingIntent = copyPendingIntent(
            appCtx,
            HANDOFF_COPY_ACTION_REQUEST_CODE_BASE + hashOffset,
            actionText
        )

        val notification = baseBuilder(appCtx, appCtx.getString(R.string.notification_handoff_title, sourceName), actionText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openPendingIntent)
            .addAction(shareAction(appCtx, sharePendingIntent))
            .addAction(copyAction(appCtx, copyPendingIntent))
            .build()

        NotificationManagerCompat.from(appCtx).notify(HANDOFF_NOTIFICATION_ID, notification)
    }

    fun showFailedLink(context: Context, failedUrl: String) {
        val appCtx = context.applicationContext
        val uri = runCatching { Uri.parse(failedUrl) }.getOrNull() ?: return
        val targetRole = ProfileRoleStore.opposite(ProfileRoleStore.getRole(appCtx))
        val targetName = ProfileRoleStore.describe(targetRole)
        ensureHandoffChannel(appCtx, targetName)

        val openPendingIntent = PendingIntent.getActivity(
            appCtx,
            FAILURE_REQUEST_CODE,
            Intent(appCtx, LinkRouterActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sharePendingIntent = sharePendingIntent(
            appCtx,
            FAILURE_SHARE_REQUEST_CODE,
            failedUrl,
            appCtx.getString(R.string.notification_handoff_failed_title, targetName)
        )
        val copyPendingIntent = copyPendingIntent(appCtx, FAILURE_COPY_REQUEST_CODE, failedUrl)

        val notification = baseBuilder(appCtx, appCtx.getString(R.string.notification_handoff_failed_title, targetName), failedUrl)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openPendingIntent)
            .addAction(shareAction(appCtx, sharePendingIntent))
            .addAction(copyAction(appCtx, copyPendingIntent))
            .build()

        NotificationManagerCompat.from(appCtx).notify(FAILURE_NOTIFICATION_ID, notification)
    }

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

    private fun sharePendingIntent(context: Context, requestCode: Int, text: String, subject: String): PendingIntent {
        val chooser = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, subject)
            },
            context.getString(R.string.share_chooser)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            chooser,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun copyPendingIntent(context: Context, requestCode: Int, text: String): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, NotificationCopyReceiver::class.java).apply {
                action = NotificationCopyReceiver.ACTION_COPY_TEXT
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
