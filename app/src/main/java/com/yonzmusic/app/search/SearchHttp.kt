package com.yonzmusic.app.search

import com.yonzmusic.app.network.SafeHttp
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SearchFailure(val userMessage: String): Exception(userMessage)

/** Separate from acquisition's Python/global lock. Cancel disconnects the active socket. */
object SearchHttp {
    private val workers = Executors.newFixedThreadPool(2) { r -> Thread(r,"yono-search-http").apply { isDaemon = true } }
    private val canceller = Executors.newSingleThreadExecutor {r->Thread(r,"yono-search-cancel").apply {isDaemon=true}}
    suspend fun get(url: String, hosts: Set<String>, headers: Map<String,String> = emptyMap()): String = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>()
        val task = workers.submit {
            try {
                var next = url
                var body: String? = null
                for(attempt in 0..4) {
                    if(!continuation.isActive) return@submit
                    val connection = SafeHttp.safeUrl(next,hosts).openConnection() as HttpURLConnection
                    active.set(connection)
                    if(!continuation.isActive) {connection.disconnect();return@submit}
                    try {
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout=8000;connection.readTimeout=12000
                        connection.setRequestProperty("User-Agent",SafeHttp.USER_AGENT)
                        connection.setRequestProperty("Accept","application/json")
                        connection.setRequestProperty("Accept-Encoding","identity")
                        headers.forEach { (k,v) -> connection.setRequestProperty(k,v) }
                        val code = connection.responseCode
                        if(code in setOf(301,302,303,307,308)) {
                            next=connection.url.toURI().resolve(connection.getHeaderField("Location") ?: throw SearchFailure("Arah sumber tidak valid.")).toString()
                            continue
                        }
                        if(code==429)throw SearchFailure("Sumber membatasi pencarian. Tunggu sebentar lalu coba lagi.")
                        if(code==403)throw SearchFailure("Akses atau kuota API belum tersedia. Periksa konfigurasi sumber.")
                        if(code!=200)throw SearchFailure("Sumber belum dapat dihubungi (HTTP $code). Coba lagi nanti.")
                        val out=java.io.ByteArrayOutputStream()
                        connection.inputStream.use { input ->
                            val buffer=ByteArray(8192)
                            while(continuation.isActive) {
                                val count=input.read(buffer);if(count<0)break
                                if(out.size()+count>2*1024*1024)throw SearchFailure("Respons sumber terlalu besar.")
                                out.write(buffer,0,count)
                            }
                        }
                        body=out.toString("UTF-8");break
                    } finally {active.compareAndSet(connection,null);connection.disconnect()}
                }
                if(continuation.isActive)continuation.resume(body ?: throw SearchFailure("Terlalu banyak pengalihan sumber."))
            } catch(e: Exception) {
                if(continuation.isActive)continuation.resumeWithException(if(e is SearchFailure)e else SearchFailure("Pencarian online belum tersedia. Periksa koneksi lalu coba lagi."))
            }
        }
        continuation.invokeOnCancellation {task.cancel(true);active.getAndSet(null)?.let {connection->canceller.execute {connection.disconnect()}}}
    }
}
