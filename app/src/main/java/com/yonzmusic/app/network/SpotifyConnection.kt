package com.yonzmusic.app.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Optional, explicitly configured official metadata connection. Never loaded for offline playback. */
class SpotifyConnection(private val context: Context) {
    private val alias="yono.spotify.oauth.v1"
    private val file=AtomicFile(File(context.noBackupFilesDir,"spotify-oauth.enc"))
    val message=MutableStateFlow("")
    val redirect get()="${context.packageName}://spotify/callback"
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        return (store.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(): JSONObject = if(!file.baseFile.exists())JSONObject()else {
        val stored=JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(stored.getString("iv"),Base64.NO_WRAP)))
        cipher.updateAAD(context.packageName.toByteArray())
        JSONObject(String(cipher.doFinal(Base64.decode(stored.getString("data"),Base64.NO_WRAP)),Charsets.UTF_8))
    }
    private fun write(value: JSONObject) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(context.packageName.toByteArray())
        val encrypted=JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).put("data",Base64.encodeToString(cipher.doFinal(value.toString().toByteArray()),Base64.NO_WRAP))
        val output=file.startWrite()
        try { output.write(encrypted.toString().toByteArray());file.finishWrite(output) }catch(e:Exception){file.failWrite(output);throw e}
    }
    @Synchronized fun begin(clientId:String): Intent {
        require(clientId.matches(Regex("[A-Fa-f0-9]{32}"))) {"Client ID Spotify tidak valid."}
        val state=SpotifyPkce.random();val verifier=SpotifyPkce.random()
        // Starting a different configuration discards the previous tokens, never mixes identities.
        write(JSONObject().put("client_id",clientId).put("state",state).put("verifier",verifier).put("started",System.currentTimeMillis()))
        val url=Uri.parse("https://accounts.spotify.com/authorize").buildUpon().appendQueryParameter("client_id",clientId)
            .appendQueryParameter("response_type","code").appendQueryParameter("redirect_uri",redirect)
            .appendQueryParameter("code_challenge_method","S256").appendQueryParameter("code_challenge",SpotifyPkce.challenge(verifier))
            .appendQueryParameter("state",state).appendQueryParameter("scope","playlist-read-private playlist-read-collaborative").build()
        message.value="Lanjutkan izin di browser resmi."
        return Intent(Intent.ACTION_VIEW,url)
    }
    private fun exchange(parameters:Map<String,String>):JSONObject {
        val connection=URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects=false;connection.connectTimeout=12000;connection.readTimeout=15000
            connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
            val body=parameters.entries.joinToString("&"){URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8")}.toByteArray()
            connection.setFixedLengthStreamingMode(body.size);connection.outputStream.use {it.write(body)}
            check(connection.responseCode==200){"Koneksi Spotify ditolak atau kedaluwarsa. Hubungkan ulang; akses mengikuti konfigurasi aplikasi Spotify Developer."}
            val bytes=connection.inputStream.use { input ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=64*1024);out.write(buffer,0,n)};out.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8)).also {
                require(it.optString("token_type").equals("Bearer",true) && it.getString("access_token").length in 1..8192)
            }
        }finally {connection.disconnect()}
    }
    @Synchronized fun callback(uri: Uri): Boolean {
        if(uri.scheme!=context.packageName || uri.host!="spotify" || uri.path!="/callback")return false
        try {
            val saved=read()
            require(SpotifyPkce.validState(saved.optString("state"),uri.getQueryParameter("state").orEmpty()) &&
                System.currentTimeMillis()-saved.optLong("started") in 0..600000) {"Sesi koneksi tidak cocok atau sudah kedaluwarsa. Mulai koneksi baru."}
            val verifier=saved.getString("verifier")
            saved.remove("state");saved.remove("verifier");saved.remove("started");write(saved) // Single-use even on network failure.
            check(uri.getQueryParameter("error")==null){"Izin Spotify dibatalkan."}
            val code=uri.getQueryParameter("code").orEmpty();require(code.length in 1..8192)
            val tokens=exchange(mapOf("client_id" to saved.getString("client_id"),"grant_type" to "authorization_code","code" to code,"redirect_uri" to redirect,"code_verifier" to verifier))
            saved.put("tokens",tokens).put("expires",System.currentTimeMillis()+tokens.optLong("expires_in",3600).coerceIn(60,86400)*1000)
            write(saved);message.value="Spotify terhubung untuk metadata playlist yang diizinkan."
        }catch(e:Exception){
            val safe=e.message.orEmpty()
            message.value=if(listOf("Sesi koneksi tidak cocok","Izin Spotify dibatalkan","Koneksi Spotify ditolak").any(safe::startsWith))safe.take(180)
                else "Koneksi Spotify belum berhasil. Periksa koneksi dan konfigurasi Developer."
        }
        return true
    }
    /** IO thread only, and only for an explicit Spotify operation; tokens never enter Room or plans. */
    @Synchronized fun accessToken():String {
        val saved=read();var tokens=saved.optJSONObject("tokens") ?: return ""
        if(System.currentTimeMillis()+60000>=saved.optLong("expires")) {
            val refresh=tokens.optString("refresh_token");if(refresh.isBlank())return ""
            val next=exchange(mapOf("client_id" to saved.getString("client_id"),"grant_type" to "refresh_token","refresh_token" to refresh))
            if(next.optString("refresh_token").isBlank())next.put("refresh_token",refresh)
            tokens=next;saved.put("tokens",tokens).put("expires",System.currentTimeMillis()+tokens.optLong("expires_in",3600).coerceIn(60,86400)*1000);write(saved)
        }
        return tokens.optString("access_token")
    }
    @Synchronized fun disconnect() {file.delete();message.value="Koneksi Spotify dihapus dari perangkat."}
}
