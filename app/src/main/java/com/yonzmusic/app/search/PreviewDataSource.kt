package com.yonzmusic.app.search

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.yonzmusic.app.network.SafeHttp
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection

/** Streaming buffers only. Every redirect is checked; no disk cache or preview export. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PreviewDataSource: BaseDataSource(true) {
    private var connection: HttpURLConnection?=null
    private var stream: InputStream?=null
    private var opened=false
    private var remaining=C.LENGTH_UNSET.toLong()
    private var transferred=0L
    private val maxBytes=64L*1024*1024
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            require(dataSpec.position in 0..maxBytes)
            var next=dataSpec.uri.toString()
            for(attempt in 0..4) {
                val c=SafeHttp.safeUrl(next,SearchContract.audioHosts).openConnection() as HttpURLConnection
                connection=c;c.instanceFollowRedirects=false;c.connectTimeout=8000;c.readTimeout=10000
                c.setRequestProperty("User-Agent",SafeHttp.USER_AGENT)
                c.setRequestProperty("Accept-Encoding","identity")
                c.setRequestProperty("Cache-Control","no-store");c.useCaches=false
                if(dataSpec.position>0 || dataSpec.length!=C.LENGTH_UNSET.toLong()) {
                    val end=if(dataSpec.length==C.LENGTH_UNSET.toLong())"" else (dataSpec.position+dataSpec.length-1).toString()
                    c.setRequestProperty("Range","bytes=${dataSpec.position}-$end")
                }
                val status=c.responseCode
                if(status in setOf(301,302,303,307,308)) {
                    next=c.url.toURI().resolve(c.getHeaderField("Location") ?: throw IOException("Missing redirect")).toString();c.disconnect();connection=null;continue
                }
                if(status !in setOf(200,206))throw IOException("Preview HTTP $status")
                val type=c.contentType.orEmpty().lowercase()
                if(type.startsWith("text/") || type.contains("json"))throw IOException("Preview is not audio")
                val length=c.contentLengthLong
                if(length>maxBytes)throw IOException("Preview exceeds streaming limit")
                val input=c.inputStream;stream=input
                if(status==200 && dataSpec.position>0) {
                    var skip=dataSpec.position
                    while(skip>0) {
                        val n=input.skip(skip)
                        if(n>0)skip-=n else if(input.read()<0)throw IOException("Invalid preview range") else skip--
                    }
                }
                remaining=when {dataSpec.length!=C.LENGTH_UNSET.toLong()->dataSpec.length;length>=0->(length-if(status==200)dataSpec.position else 0).coerceAtLeast(0);else->C.LENGTH_UNSET.toLong()}
                transferred=0;opened=true;transferStarted(dataSpec);return remaining
            }
            throw IOException("Too many redirects")
        } catch(e: Exception) {close();throw if(e is IOException)e else IOException("Preview unavailable",e)}
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if(length==0)return 0
        if(remaining==0L)return C.RESULT_END_OF_INPUT
        val count=stream?.read(buffer,offset,if(remaining>=0)minOf(length.toLong(),remaining).toInt() else length) ?: return C.RESULT_END_OF_INPUT
        if(count<0)return C.RESULT_END_OF_INPUT
        transferred+=count;if(transferred>maxBytes)throw IOException("Preview exceeds streaming limit")
        if(remaining>=0)remaining-=count
        bytesTransferred(count);return count
    }
    override fun getUri(): Uri?=connection?.url?.toString()?.let(Uri::parse)
    override fun getResponseHeaders(): Map<String,List<String>> = connection?.headerFields?.filterKeys {it!=null} ?: emptyMap()
    override fun close() {
        try {stream?.close()}finally {stream=null;connection?.disconnect();connection=null;if(opened){opened=false;transferEnded()}}
    }
}
