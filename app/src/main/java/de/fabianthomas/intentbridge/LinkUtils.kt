package de.fabianthomas.intentbridge

import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object LinkUtils {
    fun isMapsShort(uri: Uri?): Boolean {
        return uri != null && uri.scheme == "https" && uri.host == "maps.app.goo.gl"
    }

    // Simple resolver for maps.app.goo.gl short links. Returns the final absolute URL or null on failure.
    fun resolveMapsShortLink(shortUrl: String): String? {
        return try {
            val start = URL(shortUrl)
            if (start.protocol != "https" || start.host != "maps.app.goo.gl") return null
            var current = start
            var hops = 0
            while (hops < 10) {
                val conn = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile Safari/537.36")
                    setRequestProperty("Accept", "*/*")
                }
                try {
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location") ?: return current.toString()
                        val next = URL(current, loc)
                        current = next
                        hops++
                        continue
                    }
                    if (code == HttpURLConnection.HTTP_OK) {
                        val ct = (conn.contentType ?: "").lowercase()
                        if (ct.startsWith("text/html")) {
                            val body = try {
                                BufferedReader(InputStreamReader(conn.inputStream)).use { br ->
                                    val sb = StringBuilder()
                                    var line: String?
                                    var total = 0
                                    while (br.readLine().also { line = it } != null && total < 20000) {
                                        sb.append(line)
                                        sb.append('\n')
                                        total = sb.length
                                    }
                                    sb.toString()
                                }
                            } catch (_: Exception) { "" }
                            val regex = Regex("""<meta\s+http-equiv=['"]?refresh['"]?\s+content=['"][^'\"]*url=([^"'>\s]+)""", RegexOption.IGNORE_CASE)
                            val m = regex.find(body)
                            val urlStr = m?.groupValues?.getOrNull(1)?.trim()
                            if (!urlStr.isNullOrEmpty()) {
                                return URL(current, urlStr).toString()
                            }
                        }
                    }
                    return current.toString()
                } finally {
                    conn.disconnect()
                }
            }
            current.toString()
        } catch (_: Exception) {
            null
        }
    }
}

