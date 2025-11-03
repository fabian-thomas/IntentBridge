package de.fabianthomas.intentbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/**
 * Simple localhost server that listens for handoff requests from the opposite profile.
 * Listens on 39123 (Personal Space) or 39124 (Private Space) depending on the stored role and
 * relays each incoming URL via ACTION_VIEW.
 */
class SocketServerService : Service() {

    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    data class PingResult(
        val success: Boolean,
        val authError: Boolean,
        val message: String? = null
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createHandoffChannel()
        val notification = buildNotification()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        running.set(true)
        serviceRunning.set(true)
        serverThread = Thread({ runServer() }, "IntentBridgeSocketServer").apply { start() }
    }

    override fun onDestroy() {
        running.set(false)
        serviceRunning.set(false)
        serverSocket?.closeSafely()
        serverThread?.joinQuietly()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    private fun createHandoffChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val sourceRole = ProfileRoleStore.opposite(ProfileRoleStore.getRole(applicationContext))
        val sourceName = ProfileRoleStore.describe(sourceRole)
        val channel = NotificationChannel(
            HANDOFF_CHANNEL_ID,
            getString(R.string.notification_handoff_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_handoff_channel_description, sourceName)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val role = ProfileRoleStore.getRole(applicationContext)
        val roleDescription = ProfileRoleStore.describe(role)
        val port = ProfileRoleStore.listeningPort(role)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, SocketServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_socket_title))
            .setContentText(getString(R.string.notification_socket_description, roleDescription, port))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(launchPendingIntent)
            .addAction(R.mipmap.ic_launcher, getString(R.string.notification_action_stop), stopPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFAULT)
            .build()
    }

    private fun showHandoffNotification(uri: Uri) {
        val sourceRole = ProfileRoleStore.opposite(ProfileRoleStore.getRole(applicationContext))
        val sourceName = ProfileRoleStore.describe(sourceRole)
        val pendingIntent = PendingIntent.getActivity(
            this,
            HANDOFF_REQUEST_CODE,
            LinkForwardActivity.createIntent(this, uri.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, HANDOFF_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_handoff_title, sourceName))
            .setContentText(uri.toString())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(this).notify(HANDOFF_NOTIFICATION_ID, notification)
    }

    private fun showShareNotification(shareId: String, summary: String?) {
        val sourceRole = ProfileRoleStore.opposite(ProfileRoleStore.getRole(applicationContext))
        val sourceName = ProfileRoleStore.describe(sourceRole)
        val pendingIntent = PendingIntent.getActivity(
            this,
            SHARE_REQUEST_CODE_BASE + (shareId.hashCode() and 0x7FFFFFFF),
            ShareForwardActivity.createIntent(this, shareId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, HANDOFF_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_share_title, sourceName))
            .setContentText(summary ?: getString(R.string.notification_share_text_placeholder))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)

        summary?.let {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(it))
        }

        NotificationManagerCompat.from(this).notify(
            SHARE_NOTIFICATION_ID_BASE + (shareId.hashCode() and 0x0FFFFFFF),
            builder.build()
        )
    }

    private fun runServer() {
        val role = ProfileRoleStore.getRole(applicationContext)
        val listenPort = ProfileRoleStore.listeningPort(role)
        runCatching { TlsIdentityStore.ensureIdentity(applicationContext) }
        try {
            serverSocket = ServerSocket(listenPort, 0, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = 1_000
            }
            Log.i(TAG, "Listening on $listenPort for ${ProfileRoleStore.describe(role)} (pid=${Process.myPid()})")
        } catch (bind: BindException) {
            Log.w(TAG, "Socket server already bound on port $listenPort, stopping")
            stopSelf()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open socket server", e)
            stopSelf()
            return
        }

        while (running.get()) {
            try {
                val client = serverSocket?.accept() ?: break
                handleSecureConnection(listenPort, client)
            } catch (_: SocketTimeoutException) {
                // Accept timeout to allow checking running flag
            } catch (e: Exception) {
                Log.w(TAG, "Socket accept failed", e)
            }
        }
    }

    private fun handleSecureConnection(channelId: Int, rawSocket: Socket) {
        val tlsSocket = try {
            TlsSocketHelper.wrapServerSocket(applicationContext, rawSocket)
        } catch (handshake: Exception) {
            rawSocket.closeSafely()
            Log.w(TAG, "TLS handshake failed", handshake)
            TlsIdentityStore.markAuthError(applicationContext)
            return
        }

        tlsSocket.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val message = reader.readLine()?.trim()
            if (message.isNullOrEmpty()) {
                Log.w(TAG, "Received empty message on channel $channelId")
                return
            }
            val payload = runCatching { JSONObject(message) }.getOrElse { error ->
                Log.w(TAG, "Failed to parse message JSON", error)
                return
            }
            when (payload.optString("type")) {
                "handoff" -> {
                    val uri = payload.optString("uri")
                    if (LinkRoutingPrefs.applySnapshot(applicationContext, payload.optJSONObject("routing"))) {
                        createHandoffChannel()
                    }
                    if (!uri.isNullOrEmpty()) {
                        handleHandoff(uri)
                    } else {
                        Log.w(TAG, "Received handoff payload without URI")
                    }
                }
                "share" -> {
                    handleSharePayload(payload)
                }
                "routing_pref" -> {
                    val category = payload.optString("category").takeIf { it.isNotEmpty() }
                    val roleName = payload.optString("role").takeIf { it.isNotEmpty() }
                    val applied = LinkRoutingPrefs.applyRemoteUpdate(applicationContext, category, roleName)
                    if (applied) {
                        Log.i(TAG, "Updated routing preference $category -> $roleName")
                        createHandoffChannel()
                    } else {
                        Log.w(TAG, "Ignored invalid routing preference: $payload")
                    }
                }
                "ping" -> {
                    sendSecureMessage(channelId, socket, JSONObject().apply { put("type", "pong") })
                }
                else -> Log.w(TAG, "Unknown payload type: ${payload.optString("type")}")
            }
        }
    }

    private fun handleHandoff(url: String) {
        Log.i(TAG, "Received handoff URL: $url")
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        showHandoffNotification(uri)
    }

    private fun handleSharePayload(payload: JSONObject) {
        val stored = ShareStorage.persist(applicationContext, payload)
        if (stored == null) {
            Log.w(TAG, "Failed to persist incoming share payload")
            return
        }
        val summary = shareSummary(stored)
        Log.i(TAG, "Received share payload ${stored.id} (summary=$summary)")
        showShareNotification(stored.id, summary)
    }

    private fun shareSummary(stored: ShareStorage.StoredShare): String? {
        stored.subject?.takeIf { it.isNotBlank() }?.let { return it }
        stored.text?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            return text.take(MAX_NOTIFICATION_TEXT)
        }
        stored.items.firstOrNull()?.displayName?.takeIf { !it.isNullOrBlank() }?.let { return it }
        return null
    }

    private fun sendSecureMessage(channelId: Int, socket: Socket, payload: JSONObject) {
        runCatching {
            val raw = payload.toString() + "\n"
            val output = socket.getOutputStream()
            output.write(raw.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }.onFailure { error ->
            Log.w(TAG, "Failed to send response on channel $channelId", error)
        }
    }

    private fun ServerSocket.closeSafely() {
        try {
            close()
        } catch (_: Exception) {
        }
    }

    private fun Socket.closeSafely() {
        try {
            close()
        } catch (_: Exception) {
        }
    }

    private fun Thread.joinQuietly() {
        try {
            join(500)
        } catch (_: InterruptedException) {
            interrupt()
        }
    }

    companion object {
        private const val TAG = "IntentBridgeSocketServer"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "intentbridge_socket_listener"
        private const val HANDOFF_NOTIFICATION_ID = 2001
        private const val HANDOFF_CHANNEL_ID = "intentbridge_handoff_channel"
        private const val HANDOFF_REQUEST_CODE = 201
        private const val SHARE_NOTIFICATION_ID_BASE = 3000
        private const val SHARE_REQUEST_CODE_BASE = 301
        private const val MAX_NOTIFICATION_TEXT = 200
        private const val ACTION_STOP = "de.fabianthomas.intentbridge.action.STOP_SOCKET_SERVER"
        private val serviceRunning = AtomicBoolean(false)

        fun ensureRunning(context: Context) {
            val intent = Intent(context, SocketServerService::class.java)
            try {
                val appCtx = context.applicationContext
                runCatching { TlsIdentityStore.ensureIdentity(appCtx) }
                val role = ProfileRoleStore.getRole(appCtx)
                val port = ProfileRoleStore.listeningPort(role)
                Log.i(TAG, "Starting listener request for ${ProfileRoleStore.describe(role)} on $port")
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Unable to start socket server service", e)
            }
        }

        fun isRunning(): Boolean = serviceRunning.get()

        fun ping(context: Context, port: Int, timeoutMs: Int = 2_000): PingResult {
            runCatching { TlsIdentityStore.ensureIdentity(context) }
            return try {
                TlsSocketHelper.connect(context, "127.0.0.1", port, timeoutMs).use { socket ->
                    val payload = JSONObject().apply { put("type", "ping") }
                    val raw = payload.toString() + "\n"
                    socket.outputStream.write(raw.toByteArray(StandardCharsets.UTF_8))
                    socket.outputStream.flush()

                    val reader = BufferedReader(InputStreamReader(socket.inputStream))
                    val responseRaw = reader.readLine()?.trim()
                    if (responseRaw.isNullOrEmpty()) {
                        return PingResult(success = false, authError = false, message = "empty_response")
                    }
                    val responsePayload = runCatching { JSONObject(responseRaw) }.getOrElse { error ->
                        return PingResult(success = false, authError = false, message = error.message)
                    }
                    val success = responsePayload.optString("type") == "pong"
                    if (success) {
                        TlsIdentityStore.clearAuthError(context.applicationContext)
                    }
                    PingResult(success = success, authError = false, message = null)
                }
            } catch (e: Exception) {
                val authError = e is SSLException
                if (authError) {
                    TlsIdentityStore.markAuthError(context.applicationContext)
                }
                PingResult(success = false, authError = authError, message = e.message)
            }
        }
    }
}
