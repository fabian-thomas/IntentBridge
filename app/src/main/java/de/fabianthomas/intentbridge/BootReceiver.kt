package de.fabianthomas.intentbridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in START_ACTIONS) return

        val canStart = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!canStart) {
            Log.i(TAG, "Skipping auto-start; notification permission not granted (action=$action)")
            return
        }

        runCatching { TlsIdentityStore.ensureIdentity(context) }
        Log.i(TAG, "Auto-starting socket listener due to $action")
        SocketServerService.ensureRunning(context)
    }

    companion object {
        private const val TAG = "IntentBridgeBootReceiver"
        private val START_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
