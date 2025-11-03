package de.fabianthomas.intentbridge

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

/**
 * Entry point for ACTION_SEND / ACTION_SEND_MULTIPLE hand-offs.
 * Collects the shared payload and relays it to the opposite profile.
 */
class ShareBridgeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incomingIntent = intent
        if (incomingIntent == null) {
            finish()
            return
        }

        // Process in background to avoid blocking the main thread while reading attachments.
        thread(name = "IntentBridgeShareRelay") {
            val result = ShareMessageBuilder.buildPayload(applicationContext, incomingIntent)
            when (result) {
                is ShareMessageBuilder.Result.Success -> {
                    val delivered = CrossSpaceHandoff.sendShare(applicationContext, result.payload)
                    runOnUiThread {
                        if (delivered) {
                            val targetRole = ProfileRoleStore.opposite(ProfileRoleStore.getRole(applicationContext))
                            Toast.makeText(
                                applicationContext,
                                getString(R.string.share_sent_to, ProfileRoleStore.describe(targetRole)),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                applicationContext,
                                R.string.share_failed,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        finish()
                    }
                }
                is ShareMessageBuilder.Result.Error -> {
                    runOnUiThread {
                        Toast.makeText(applicationContext, result.reason, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
    }
}

