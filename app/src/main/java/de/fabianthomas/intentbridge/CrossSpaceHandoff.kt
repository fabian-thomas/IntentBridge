package de.fabianthomas.intentbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object CrossSpaceHandoff {
    private const val TAG = "CrossSpaceHandoff"

    fun launch(context: Context, url: String) {
        runCatching { TlsIdentityStore.ensureIdentity(context) }
        Thread {
            val payload = JSONObject().apply {
                put("type", "handoff")
                put("uri", url)
                put("routing", LinkRoutingPrefs.snapshot(context))
            }
            val delivered = sendPayload(context, payload, "handoff:$url")
            if (!delivered) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context.applicationContext,
                        R.string.handoff_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    fun syncRoutingPreference(
        context: Context,
        category: LinkRoutingPrefs.LinkCategory,
        role: ProfileRoleStore.Role
    ) {
        runCatching { TlsIdentityStore.ensureIdentity(context) }
        Thread {
            val payload = JSONObject().apply {
                put("type", "routing_pref")
                put("category", category.storageKey)
                put("role", role.name)
            }
            sendPayload(context, payload, "routing_pref:${category.storageKey}:${role.name}")
        }.start()
    }

    private fun sendPayload(context: Context, payload: JSONObject, detail: String): Boolean {
        val role = ProfileRoleStore.getRole(context)
        val targetPort = ProfileRoleStore.targetPort(role)
        Log.i(TAG, "Sending $detail to port $targetPort")
        return runCatching {
            TlsSocketHelper.connect(context, "127.0.0.1", targetPort, 2_000).use { socket ->
                BufferedWriter(OutputStreamWriter(socket.outputStream, StandardCharsets.UTF_8)).use { writer ->
                    writer.write(payload.toString())
                    writer.write("\n")
                    writer.flush()
                }
            }
            true
        }.onFailure { error ->
            Log.w(TAG, "Failed to send $detail to port $targetPort", error)
        }.getOrDefault(false)
    }
}
