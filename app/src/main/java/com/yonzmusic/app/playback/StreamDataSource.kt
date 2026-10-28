package com.yonzmusic.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded streaming transport with range support; no disk cache or arbitrary external URLs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamDataSource(private val streams:StreamRepository,
    private val connect:(java.net.URL)->HttpURLConnection={it.openConnection()as HttpURLConnection},
    private val addresses:(String)->Array<InetAddress> = {InetAddress.getAllByName(it)}):BaseDataSource(true) {
    private var connection:HttpURLConnection?=null
    private var input:InputStream?=null
    private var original:Uri?=null
    private var opened=false
    private var remaining=-1L
    private val stopped=AtomicBoolean(false)
    override fun open(spec:DataSpec):Long {
        transferInitializing(spec);original=spec.uri;stopped.set(false)
        try {
            val id=StreamPolicy.source(spec.uri)
            require(spec.position in 0..(512L*1024*1024))
            var info=streams.resolve(id,stopped)
            for(refresh in 0..1) {
                var next=info.url
                for(redirect in 0..5) {
                    if(stopped.get() || Thread.currentThread().isInterrupted)throw IOException("Dibatalkan")
                    val url=StreamPolicy.url(next)
                    val resolvedAddresses=addresses(url.host)
                    require(resolvedAddresses.isNotEmpty() && resolvedAddresses.none {it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress})
                    val c=connect(url);connection=c
                    c.instanceFollowRedirects=false;c.connectTimeout=8000;c.readTimeout=12000;c.useCaches=false
                    c.setRequestProperty("User-Agent",com.yonzmusic.app.network.SafeHttp.USER_AGENT);c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("Cache-Control","no-store")
                    info.headers.forEach {k,v->c.setRequestProperty(k,v)}
                    if(spec.position>0 || spec.length>=0)c.setRequestProperty("Range","bytes=${spec.position}-"+if(spec.length>=0)(spec.position+spec.length-1).toString()else "")
                    val status=c.responseCode
                    if(status in setOf(401,403) && refresh==0){c.disconnect();streams.invalidate(id);info=streams.resolve(id,stopped);break}
                    if(status in setOf(301,302,303,307,308)){next=c.url.toURI().resolve(c.getHeaderField("Location") ?: throw IOException("Pengalihan tidak valid")).toString();c.disconnect();continue}
                    if(status !in setOf(200,206))throw IOException("Sumber streaming menolak permintaan (HTTP $status).")
                    val mime=c.contentType.orEmpty().lowercase();if(mime.startsWith("text/") || mime.contains("json"))throw IOException("Sumber tidak mengirim audio.")
                    input=c.inputStream
                    if(status==200 && spec.position>0){var skip=spec.position;val b=ByteArray(8192);while(skip>0){val n=input!!.read(b,0,minOf(skip,b.size.toLong()).toInt());if(n<0)throw IOException("Posisi audio tidak tersedia");skip-=n;if(stopped.get())throw IOException("Dibatalkan")}}
                    remaining=if(spec.length>=0)spec.length else c.contentLengthLong.let {if(it>=0)(it-if(status==200)spec.position else 0).coerceAtLeast(0)else -1}
                    opened=true;transferStarted(spec);return remaining
                }
            }
            throw IOException("Audio belum dapat dibuka. Coba lagi.")
        }catch(e:Exception){close();throw IOException((e.message?.takeIf {!it.contains("https://")} ?: "Streaming belum tersedia. Periksa koneksi lalu coba lagi.").take(250))}
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        if(length==0)return 0
        if(remaining==0L)return C.RESULT_END_OF_INPUT
        if(stopped.get())throw IOException("Dibatalkan")
        val n=input?.read(buffer,offset,if(remaining>=0)minOf(length.toLong(),remaining).toInt()else length) ?: return C.RESULT_END_OF_INPUT
        if(n<0){if(remaining>0)throw IOException("Aliran audio terputus. Coba lagi.");return C.RESULT_END_OF_INPUT}
        if(remaining>=0)remaining-=n
        bytesTransferred(n);return n
    }
    override fun getUri():Uri?=original
    override fun close(){stopped.set(true);try{input?.close()}finally{input=null;connection?.disconnect();connection=null;if(opened){opened=false;transferEnded()}}}
}
