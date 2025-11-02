package de.fabianthomas.intentbridge

import android.content.Intent
import android.net.Uri
import java.util.ArrayList

object MailtoIntentUtils {

    fun buildMailtoString(intent: Intent?, fallbackUri: Uri?): String? {
        if (intent == null && fallbackUri == null) return null

        val toAddresses = mutableListOf<String>()
        val params = LinkedHashMap<String, MutableList<String>>()

        // Parse existing mailto URI if present
        fallbackUri?.let { uri ->
            val schemeSpecific = uri.schemeSpecificPart
            if (!schemeSpecific.isNullOrEmpty()) {
                val parts = schemeSpecific.split("?", limit = 2)
                val addressPart = parts.getOrNull(0)
                if (!addressPart.isNullOrBlank()) {
                    addressPart.split(",").mapNotNull { it.trim().takeIf { addr -> addr.isNotEmpty() } }
                        .let(toAddresses::addAll)
                }
                val queryPart = parts.getOrNull(1)
                if (!queryPart.isNullOrEmpty()) {
                    queryPart.split("&").forEach { entry ->
                        if (entry.isEmpty()) return@forEach
                        val kv = entry.split("=", limit = 2)
                        val key = Uri.decode(kv.getOrNull(0) ?: return@forEach)
                        val value = kv.getOrNull(1)?.let(Uri::decode) ?: ""
                        if (value.isNotEmpty()) {
                            params.getOrPut(key) { mutableListOf() }.add(value)
                        }
                    }
                }
            }
        }

        // Extras from intent override / complement
        intent?.let { inIntent ->
            extractAddresses(
                inIntent.getStringArrayExtra(Intent.EXTRA_EMAIL),
                inIntent.getStringArrayListExtra(Intent.EXTRA_EMAIL)
            )?.let { toAddresses.addAll(it) }
            extractAddresses(
                inIntent.getStringArrayExtra(Intent.EXTRA_CC),
                inIntent.getStringArrayListExtra(Intent.EXTRA_CC)
            )?.let {
                params.getOrPut("cc") { mutableListOf() }.add(it.joinToString(","))
            }
            extractAddresses(
                inIntent.getStringArrayExtra(Intent.EXTRA_BCC),
                inIntent.getStringArrayListExtra(Intent.EXTRA_BCC)
            )?.let {
                params.getOrPut("bcc") { mutableListOf() }.add(it.joinToString(","))
            }

            val subject = inIntent.getStringExtra(Intent.EXTRA_SUBJECT)
            if (!subject.isNullOrEmpty()) {
                params["subject"] = mutableListOf(subject)
            }

            val htmlBody = inIntent.getStringExtra(Intent.EXTRA_HTML_TEXT)
            val textBody = inIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            val body = when {
                !htmlBody.isNullOrEmpty() -> htmlBody
                !textBody.isNullOrEmpty() -> textBody
                else -> null
            }
            if (!body.isNullOrEmpty()) {
                params["body"] = mutableListOf(body)
            }
        }

        val builder = StringBuilder("mailto:")
        if (toAddresses.isNotEmpty()) {
            builder.append(toAddresses.joinToString(",") { Uri.encode(it) })
        }

        if (params.isNotEmpty()) {
            builder.append("?")
            val encodedParams = params.flatMap { (key, values) ->
                values.map { value -> "${Uri.encode(key)}=${Uri.encode(value)}" }
            }
            builder.append(encodedParams.joinToString("&"))
        }

        return builder.toString()
    }

    private fun extractAddresses(
        arrayValues: Array<out String>?,
        listValues: ArrayList<String>?
    ): List<String>? {
        val raw = mutableListOf<String>()
        if (!arrayValues.isNullOrEmpty()) {
            raw += arrayValues.filterNotNull()
        }
        if (!listValues.isNullOrEmpty()) {
            raw += listValues
        }

        if (raw.isEmpty()) return null

        return raw
            .flatMap { value ->
                value
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            .takeIf { it.isNotEmpty() }
    }
}
