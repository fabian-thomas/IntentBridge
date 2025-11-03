package de.fabianthomas.intentbridge

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

object ShareStorage {

    private const val TAG = "IntentBridgeShareStore"
    private const val STORAGE_SUBDIR = "sharebridge"
    private const val METADATA_SUFFIX = ".json"
    private const val EXPIRY_MS = 6 * 60 * 60 * 1000L // 6 hours

    data class StoredItem(
        val displayName: String?,
        val mime: String?,
        val file: File,
        val uri: Uri
    )

    data class StoredShare(
        val id: String,
        val subject: String?,
        val text: String?,
        val html: String?,
        val mime: String?,
        val createdAt: Long,
        val items: List<StoredItem>
    )

    fun persist(context: Context, payload: JSONObject): StoredShare? {
        pruneExpired(context)
        val dir = storageDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Unable to create storage directory")
            return null
        }
        val id = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()

        val subject = payload.optStringOrNull("subject")
        val text = payload.optStringOrNull("text")
        val html = payload.optStringOrNull("html")
        val mime = payload.optStringOrNull("mime")

        val itemsJson = payload.optJSONArray("items") ?: JSONArray()
        val storedItems = mutableListOf<StoredItem>()

        for (index in 0 until itemsJson.length()) {
            val itemJson = itemsJson.optJSONObject(index) ?: continue
            val originalName = itemJson.optStringOrNull("display_name")
            val mimeType = itemJson.optStringOrNull("mime")
            val file = uniqueFile(
                dir,
                originalName,
                fallbackName = buildFallbackFileName(id, index, mimeType)
            )
            val base64 = itemJson.optString("data")
            if (base64.isNullOrEmpty()) continue
            val decoded = try {
                Base64.decode(base64, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Failed to decode base64 attachment", e)
                continue
            }
            try {
                FileOutputStream(file).use { out ->
                    out.write(decoded)
                    out.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write attachment", e)
                file.delete()
                continue
            }
            val displayName = originalName
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
            storedItems += StoredItem(displayName, mimeType, file, uri)
        }

        val metadata = JSONObject().apply {
            put("id", id)
            put("created_at", createdAt)
            put("subject", subject ?: JSONObject.NULL)
            put("text", text ?: JSONObject.NULL)
            put("html", html ?: JSONObject.NULL)
            put("mime", mime ?: JSONObject.NULL)
            put("items", JSONArray().apply {
                storedItems.forEach { item ->
                    put(
                        JSONObject().apply {
                            put("display_name", item.displayName ?: JSONObject.NULL)
                            put("mime", item.mime ?: JSONObject.NULL)
                            put("file", item.file.name)
                        }
                    )
                }
            })
        }

        val metadataFile = metadataFile(dir, id)
        try {
            metadataFile.writeText(metadata.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist share metadata", e)
            storedItems.forEach { it.file.delete() }
            return null
        }

        return StoredShare(id, subject, text, html, mime, createdAt, storedItems)
    }

    fun load(context: Context, id: String): StoredShare? {
        val dir = storageDir(context)
        val metadataFile = metadataFile(dir, id)
        if (!metadataFile.exists()) return null
        val metadata = runCatching { JSONObject(metadataFile.readText()) }.getOrNull() ?: return null
        val createdAt = metadata.optLong("created_at", 0L)
        if (createdAt != 0L && System.currentTimeMillis() - createdAt > EXPIRY_MS) {
            delete(context, id)
            return null
        }

        val subject = metadata.optStringOrNull("subject")
        val text = metadata.optStringOrNull("text")
        val html = metadata.optStringOrNull("html")
        val mime = metadata.optStringOrNull("mime")

        val itemsArr = metadata.optJSONArray("items") ?: JSONArray()
        val storedItems = mutableListOf<StoredItem>()
        for (index in 0 until itemsArr.length()) {
            val item = itemsArr.optJSONObject(index) ?: continue
            val fileName = item.optString("file")
            if (fileName.isNullOrEmpty()) continue
            val file = File(dir, fileName)
            if (!file.exists()) continue
            val displayName = item.optStringOrNull("display_name")
            val mimeType = item.optStringOrNull("mime")
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
            storedItems += StoredItem(displayName, mimeType, file, uri)
        }

        if (storedItems.isEmpty() && text == null && html == null) {
            delete(context, id)
            return null
        }

        return StoredShare(id, subject, text, html, mime, createdAt, storedItems)
    }

    fun delete(context: Context, id: String) {
        val dir = storageDir(context)
        val metadataFile = metadataFile(dir, id)
        val metadata = runCatching { JSONObject(metadataFile.readText()) }.getOrNull()
        metadataFile.delete()
        if (metadata == null) {
            val marker = id.take(8)
            dir.listFiles { file -> file.name.contains(marker) }?.forEach { it.delete() }
            return
        }
        metadata.optJSONArray("items")?.let { items ->
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val fileName = item.optStringOrNull("file") ?: continue
                File(dir, fileName).delete()
            }
        }
    }

    private fun pruneExpired(context: Context) {
        val dir = storageDir(context)
        if (!dir.exists()) return
        val now = System.currentTimeMillis()
        dir.listFiles { file -> file.name.endsWith(METADATA_SUFFIX) }?.forEach { file ->
            val metadata = runCatching { JSONObject(file.readText()) }.getOrNull()
            val createdAt = metadata?.optLong("created_at", 0L) ?: 0L
            if (createdAt != 0L && now - createdAt > EXPIRY_MS) {
                val id = metadata?.optString("id")
                if (!id.isNullOrEmpty()) {
                    delete(context, id)
                } else {
                    file.delete()
                }
            }
        }
    }

    private fun storageDir(context: Context): File =
        File(context.cacheDir, STORAGE_SUBDIR)

    private fun metadataFile(dir: File, id: String): File =
        File(dir, "$id$METADATA_SUFFIX")

    private fun fileProviderAuthority(context: Context): String =
        "${context.packageName}.fileprovider"

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key)) return null
        val value = opt(key)
        if (value == null || value == JSONObject.NULL) return null
        val str = value.toString()
        return if (str.isEmpty() || str == "null") null else str
    }

    private fun uniqueFile(dir: File, desiredName: String?, fallbackName: String): File {
        val baseName = desiredName?.takeIf { it.isNotBlank() } ?: fallbackName
        var candidate = File(dir, baseName)
        if (!candidate.exists()) {
            return candidate
        }
        val dot = baseName.lastIndexOf('.')
        val namePart = if (dot > 0) baseName.substring(0, dot) else baseName
        val extPart = if (dot > 0) baseName.substring(dot) else ""
        var suffix = 1
        while (candidate.exists() && suffix < 1000) {
            val suffixName = if (extPart.isNotEmpty()) "$namePart-$suffix$extPart" else "$namePart-$suffix"
            candidate = File(dir, suffixName)
            suffix++
        }
        return candidate
    }

    private fun buildFallbackFileName(shareId: String, index: Int, mime: String?): String {
        val base = "intentbridge-share-${shareId.take(8)}-${index + 1}"
        val ext = mime?.let(::extensionFromMime)
        return if (!ext.isNullOrEmpty()) "$base.$ext" else base
    }

    private fun extensionFromMime(mime: String): String? {
        return MimeTypeMap.getSingleton()
            ?.getExtensionFromMimeType(mime.lowercase(Locale.ROOT))
    }

    fun createSampleTextFile(context: Context, fileName: String, contents: String): Uri? {
        val dir = storageDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Unable to create storage directory for test file")
            return null
        }
        val target = uniqueFile(dir, fileName, fileName)
        return try {
            FileOutputStream(target).use { output ->
                output.write(contents.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
            FileProvider.getUriForFile(context, fileProviderAuthority(context), target)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create sample share file", e)
            target.delete()
            null
        }
    }

}
