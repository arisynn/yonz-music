@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.yonzmusic.app.playback

import android.net.Uri
import androidx.media3.datasource.*
import com.yonzmusic.app.YonzApp
import kotlinx.coroutines.runBlocking
import java.io.IOException

fun String.isRemoteId()=startsWith("engine:") || startsWith("mb:recording:")

/** Resolves just the requested recording, preferring a verified offline copy in any queue. */
class CatalogDataSource(private val app:YonzApp,private val upstream:DataSource):DataSource {
    private var active:DataSource=upstream
    private val listeners=mutableListOf<TransferListener>()
    override fun addTransferListener(listener:TransferListener){listeners.add(listener);upstream.addTransferListener(listener)}
    override fun open(dataSpec:DataSpec):Long {
        if(dataSpec.uri.scheme !in setOf("yono-catalog","yono-stream"))return upstream.open(dataSpec)
        val video=if(dataSpec.uri.scheme=="yono-catalog") {
            val id=Uri.decode(dataSpec.uri.encodedPath.orEmpty().removePrefix("/"))
            runBlocking {app.streams.lookup(id)?.let {app.discovery.audio(it)}}?.videoId ?: throw IOException("Rekaman katalog belum ditemukan.")
        } else StreamPolicy.source(dataSpec.uri)
        val local=runBlocking {
            app.repository.dao.allTracks().firstOrNull {it.available && it.active && it.sourceIdentity=="youtube:$video" && it.sha256.matches(Regex("[a-f0-9]{64}"))}
        }?.takeIf {track->runCatching {
            val hash=java.security.MessageDigest.getInstance("SHA-256")
            app.contentResolver.openInputStream(Uri.parse(track.uri))!!.use {input->
                val b=ByteArray(65536);var bytes=0L
                while(true){if(Thread.currentThread().isInterrupted)throw IOException("Cancelled");val n=input.read(b);if(n<0)break;bytes+=n;require(bytes<=com.yonzmusic.app.data.Repository.MAX_AUDIO_BYTES);hash.update(b,0,n)}
                require(bytes>0)
            }
            hash.digest().joinToString(""){"%02x".format(it)}==track.sha256
        }.getOrDefault(false)}
        if(local!=null) {
            active=DefaultDataSource.Factory(app).createDataSource().also {source->listeners.forEach(source::addTransferListener)}
            return active.open(dataSpec.buildUpon().setUri(local.uri).build())
        }
        active=upstream
        return active.open(dataSpec.buildUpon().setUri("yono-stream://youtube/$video").build())
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int)=active.read(buffer,offset,length)
    override fun getUri()=active.uri
    override fun getResponseHeaders()=active.responseHeaders
    override fun close(){active.close();active=upstream}
}
