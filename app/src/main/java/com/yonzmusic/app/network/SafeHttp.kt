package com.yonzmusic.app.network

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** No cookies, credentials, cleartext, remote code or unbounded response bodies. */
object SafeHttp {
    const val USER_AGENT = "YonoMusic/1.1 (+https://github.com/arisynn/yonz-music)"
    data class Response(val status: Int, val bytes: ByteArray, val etag: String?, val retryAfter: String?)
    fun safeUrl(value: String, allowed: Set<String>): URL {
        require(value.length <= 4096) { "Link is too long." }
        val uri = URI(value)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) && uri.fragment == null &&
            uri.host?.lowercase() in allowed) { "Unsupported or unsafe source link." }
        return uri.toURL()
    }
    private fun connect(value: String, allowed: Set<String>, etag: String? = null): HttpURLConnection {
        var url = safeUrl(value, allowed)
        repeat(6) {
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12000; connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (etag != null) connection.setRequestProperty("If-None-Match", etag)
            val status = connection.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location") ?: error("Download redirect is missing.")
                connection.disconnect()
                url = safeUrl(url.toURI().resolve(location).toString(), allowed)
            } else return connection
        }
        error("Too many source redirects.")
    }
    fun get(url: String, allowed: Set<String>, limit: Int = 2 * 1024 * 1024, etag: String? = null): Response {
        val connection = connect(url, allowed, etag)
        try {
            val status = connection.responseCode
            val bytes = if (status == 200) connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= limit) { "Source response is too large." }; out.write(buffer, 0, n) }
                out.toByteArray()
            } else ByteArray(0)
            return Response(status, bytes, connection.getHeaderField("ETag"), connection.getHeaderField("Retry-After") ?: connection.getHeaderField("X-RateLimit-Reset"))
        } finally { connection.disconnect() }
    }
    suspend fun download(url: String, allowed: Set<String>, target: File, limit: Long, expected: Long = 0,
        cancelled: () -> Boolean = { false }, progress: suspend (Long, Long) -> Unit = { _, _ -> }): Long {
        val connection = connect(url, allowed)
        try {
            require(connection.responseCode == 200) { "Source returned HTTP ${connection.responseCode}. Retry later." }
            val length = connection.contentLengthLong
            require(length <= limit && (expected <= 0 || length <= 0 || expected == length)) { "Source size changed or exceeds the download limit." }
            val contentType = connection.contentType.orEmpty().lowercase()
            require(!contentType.startsWith("text/") && !contentType.contains("json") && !contentType.contains("html")) { "This link did not return an audio/APK file." }
            var received = 0L; var last = 0L
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    coroutineContext.ensureActive(); check(!cancelled()) { "Download cancelled." }
                    val n = input.read(buffer); if (n < 0) break
                    received += n; require(received <= limit) { "Download exceeds the size limit." }
                    output.write(buffer, 0, n)
                    if (System.currentTimeMillis() - last > 400) { progress(received, length.coerceAtLeast(expected)); last = System.currentTimeMillis() }
                }
                output.flush()
            } }
            require(received > 0 && (length <= 0 || received == length) && (expected <= 0 || received == expected)) { "The download is incomplete. Retry restarts it safely." }
            progress(received, received)
            return received
        } finally { connection.disconnect() }
    }
    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
