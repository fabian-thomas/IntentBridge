package de.fabianthomas.intentbridge

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

object ShareMessageBuilder {

    private const val TAG = "IntentBridgeShare"
    private const val MAX_STREAM_BYTES = 1050 * 1024 * 1024 // 1050 MiB total payload cap
    private const val MAX_SINGLE_STREAM = 1000 * 1024 * 1024 // 1000 MiB per attachment

    sealed class Result {
        data class Success(val payload: JSONObject) : Result()
        data class Error(val reason: Int) : Result()
    }

    fun buildPayload(context: Context, intent: Intent): Result {
        val action = intent.action
        val type = intent.type ?: "*/*"
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return Result.Error(R.string.share_unsupported)
        }

        val items = mutableListOf<JSONObject>()
        var totalBytes = 0L
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            if (!uris.isNullOrEmpty()) {
                for (uri in uris) {
                    val entry = serializeStream(context, uri) ?: return Result.Error(R.string.share_stream_error)
                    val size = entry.optLong("size", 0L)
                    totalBytes += size
                    if (size > MAX_SINGLE_STREAM || totalBytes > MAX_STREAM_BYTES) {
                        return Result.Error(R.string.share_too_large)
                    }
                    items += entry
                }
            }
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uri ->
                val entry = serializeStream(context, uri) ?: return Result.Error(R.string.share_stream_error)
                val size = entry.optLong("size", 0L)
                if (size > MAX_SINGLE_STREAM || size > MAX_STREAM_BYTES) {
                    return Result.Error(R.string.share_too_large)
                }
                totalBytes += size
                items += entry
            }
        }

        val payload = JSONObject().apply {
            put("type", "share")
            put("mime", type)
            intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { put("subject", it) }
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let { put("text", it) }
            intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let { put("html", it) }
            put("items", JSONArray().apply {
                items.forEach { put(it) }
            })
            put("has_streams", items.isNotEmpty())
        }
        return Result.Success(payload)
    }

    private fun serializeStream(context: Context, uri: Uri): JSONObject? {
        val resolver = context.contentResolver
        val (displayName, size) = resolver.queryNameAndSize(uri)

        return try {
            resolver.openInputStream(uri).use { input ->
                if (input == null) return null
                val (encoded, rawSize) = encodeStream(input)
                JSONObject().apply {
                    put("uri", uri.toString())
                    put("display_name", displayName ?: JSONObject.NULL)
                    val resolvedSize = if (size <= 0) rawSize.toLong() else size
                    put("size", resolvedSize)
                    put("mime", resolver.getType(uri) ?: JSONObject.NULL)
                    put("data", encoded)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read shared stream", e)
            null
        }
    }

    private fun encodeStream(input: InputStream): Pair<String, Int> {
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream()
        input.copyTo(out, buffer)
        val raw = out.toByteArray()
        val encoded = Base64.encodeToString(raw, Base64.NO_WRAP)
        return encoded to raw.size
    }

    private fun InputStream.copyTo(out: ByteArrayOutputStream, buffer: ByteArray) {
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
    }

    private fun ContentResolver.queryNameAndSize(uri: Uri): Pair<String?, Long> {
        var displayName: String? = null
        var size: Long = -1
        runCatching {
            query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    displayName = cursor.getStringSafe(OpenableColumns.DISPLAY_NAME)
                    size = cursor.getLongSafe(OpenableColumns.SIZE)
                }
            }
        }
        return displayName to size
    }

    private fun Cursor.getStringSafe(columnName: String): String? {
        val index = getColumnIndex(columnName)
        if (index == -1 || isNull(index)) return null
        return getString(index)
    }

    private fun Cursor.getLongSafe(columnName: String): Long {
        val index = getColumnIndex(columnName)
        if (index == -1 || isNull(index)) return -1
        return getLong(index)
    }
}
