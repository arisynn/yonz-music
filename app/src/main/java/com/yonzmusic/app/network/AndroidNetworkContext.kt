package com.yonzmusic.app.network

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.security.KeyStore

/** Give embedded OpenSSL the same system roots/proxy as Android; never disable certificate checks. */
object AndroidNetworkContext {
    private var trustFile: File? = null
    @Synchronized private fun roots(context: Context): File {
        trustFile?.takeIf { it.isFile }?.let { return it }
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        val output = File(context.filesDir, "engine-network/system-roots.pem").apply { parentFile!!.mkdirs() }
        val certificates = store.aliases().asSequence().filter { it.startsWith("system:") }.mapNotNull { store.getCertificate(it)?.encoded }.toList()
        require(certificates.isNotEmpty()) { "Android's system trust store is unavailable." }
        output.bufferedWriter(Charsets.US_ASCII).use { writer ->
            certificates.forEach { certificate ->
                writer.appendLine("-----BEGIN CERTIFICATE-----")
                Base64.encodeToString(certificate, Base64.NO_WRAP).chunked(64).forEach { writer.appendLine(it) }
                writer.appendLine("-----END CERTIFICATE-----")
            }
        }
        trustFile = output
        return output
    }
    fun options(context: Context, url: String): JSONObject {
        val json = JSONObject().put("ca", roots(context).absolutePath)
        val proxy = runCatching { ProxySelector.getDefault()?.select(URI(url))?.firstOrNull { it.type() == Proxy.Type.HTTP } }.getOrNull()
        (proxy?.address() as? InetSocketAddress)?.let { address ->
            val host = address.hostString.let { if (':' in it) "[$it]" else it }
            json.put("proxy", "http://$host:${address.port}")
        }
        return json
    }
}
