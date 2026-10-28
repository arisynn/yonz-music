package com.yonzmusic.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

interface StreamResolver {
    fun resolve(id:String,cancelled:AtomicBoolean,formatId:String?=null,timeoutMillis:Long=22000):StreamRepository.Resolved
    fun invalidate(id:String)
}

/** Original progressive audio only. Retry/seek must retain the same format and byte layout. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamDataSource(private val streams:StreamResolver,
    private val connect:(java.net.URL)->HttpURLConnection={it.openConnection() as HttpURLConnection},
    private val addresses:(String)->Array<InetAddress> = {InetAddress.getAllByName(it)}):BaseDataSource(true) {
    private var connection:HttpURLConnection?=null
    private var input:InputStream?=null
    private var original:Uri?=null
    private var opened=false
    private var remaining=-1L
    private var delivered=0L
    private var transientFailures=0
    private fun failure(issue:StreamIssue)=StreamFailure(issue,issue.retryable && ++transientFailures<=2)
    private val stopped=AtomicBoolean(false)
    private var sourceId:String?=null
    private var anchor:StreamRepository.Resolved?=null
    private var totalBytes:Long?=null
    private var headers:Map<String,List<String>> = emptyMap()
    private fun active(deadline:Long?=null) {
        if(stopped.get() || Thread.currentThread().isInterrupted)throw StreamFailure(StreamIssue.CANCELLED)
        if(deadline!=null && android.os.SystemClock.elapsedRealtime()>=deadline)throw StreamFailure(StreamIssue.TIMEOUT)
    }
    private fun sameFormat(value:StreamRepository.Resolved) {
        val old=anchor
        if(old!=null && (old.formatId!=value.formatId || old.extension!=value.extension || old.codec!=value.codec))throw StreamFailure(StreamIssue.FORMAT_CHANGED)
        anchor=value
    }
    private fun total(value:Long?) {
        if(value!=null) {
            if(value !in 0..StreamRange.MAX_BYTES || (totalBytes!=null && totalBytes!=value))throw StreamFailure(StreamIssue.FORMAT_CHANGED)
            totalBytes=value
        }
    }
    override fun open(spec:DataSpec):Long {
        transferInitializing(spec);original=spec.uri;stopped.set(false);remaining=-1;delivered=0;headers=emptyMap()
        val deadline=android.os.SystemClock.elapsedRealtime()+45000
        try {
            val id=StreamPolicy.source(spec.uri)
            if(spec.position !in 0..StreamRange.MAX_BYTES || (spec.length!=C.LENGTH_UNSET.toLong() && spec.length<=0) ||
                (spec.length>0 && spec.length>StreamRange.MAX_BYTES-spec.position))throw StreamFailure(StreamIssue.SEEK)
            if(sourceId!=id){sourceId=id;anchor=null;totalBytes=null;transientFailures=0}
            fun resolve():StreamRepository.Resolved {
                active(deadline)
                val budget=deadline-android.os.SystemClock.elapsedRealtime()
                if(budget<5000)throw StreamFailure(StreamIssue.TIMEOUT)
                return streams.resolve(id,stopped,anchor?.formatId?.takeIf {it.isNotBlank()},minOf(22000,budget)).also {active(deadline);sameFormat(it)}
            }
            var info=resolve()
            for(refresh in 0..1) {
                var next=info.url
                for(redirect in 0..5) {
                    active(deadline)
                    val url=runCatching {StreamPolicy.url(next)}.getOrElse {throw StreamFailure(StreamIssue.UNSAFE)}
                    val found=addresses(url.host);active(deadline)
                    if(found.isEmpty() || found.any {it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress})throw StreamFailure(StreamIssue.UNSAFE)
                    val c=connect(url);connection=c
                    val budget=(deadline-android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1).toInt()
                    c.instanceFollowRedirects=false;c.connectTimeout=minOf(8000,budget);c.readTimeout=minOf(12000,budget);c.useCaches=false
                    c.setRequestProperty("User-Agent",com.yonzmusic.app.network.SafeHttp.USER_AGENT);c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("Cache-Control","no-store")
                    info.headers.filterKeys {it.lowercase() in setOf("user-agent","accept","accept-language")}.forEach {k,v->if(v.length<1500 && '\r' !in v && '\n' !in v)c.setRequestProperty(k,v)}
                    if(spec.position>0 || spec.length>=0)c.setRequestProperty("Range","bytes=${spec.position}-"+if(spec.length>=0)(spec.position+spec.length-1).toString()else "")
                    val status=c.responseCode;active(deadline)
                    if(status in setOf(401,403) && refresh==0) {
                        c.disconnect();connection=null
                        if(spec.position>0 && info.formatId.isBlank())throw StreamFailure(StreamIssue.FORMAT_CHANGED)
                        streams.invalidate(id);info=resolve();break
                    }
                    if(status in setOf(301,302,303,307,308)) {
                        if(redirect==5)throw StreamFailure(StreamIssue.UNSAFE)
                        next=c.url.toURI().resolve(c.getHeaderField("Location") ?: throw StreamFailure(StreamIssue.UNSAFE)).toString()
                        c.disconnect();connection=null;continue
                    }
                    if(status==416) {
                        val eof=StreamRange.eof(c.getHeaderField("Content-Range"),spec.position)
                        if(eof!=null){total(eof);remaining=0;opened=true;transferStarted(spec);return 0}
                    }
                    if(status !in setOf(200,206))throw StreamFailure(StreamIssue.http(status))
                    val mime=c.contentType.orEmpty().lowercase()
                    if(mime.startsWith("text/") || mime.contains("json") || !c.getHeaderField("Content-Encoding").isNullOrBlank() && c.getHeaderField("Content-Encoding")!="identity")throw StreamFailure(StreamIssue.FORMAT)
                    val length=c.contentLengthLong
                    val available=if(status==206) {
                        val slice=StreamRange.parse(c.getHeaderField("Content-Range"),spec.position,length);total(slice.total);slice.length
                    }else {
                        if(spec.position>StreamRange.MAX_DISCARD)throw StreamFailure(StreamIssue.SEEK)
                        if(length>=0){total(length);if(spec.position>length)throw StreamFailure(StreamIssue.SEEK);length-spec.position}else -1
                    }
                    input=c.inputStream
                    if(status==200 && spec.position>0) {
                        var skip=spec.position;val b=ByteArray(8192)
                        while(skip>0){active(deadline);val n=input!!.read(b,0,minOf(skip,b.size.toLong()).toInt());if(n<0)throw StreamFailure(StreamIssue.SEEK);skip-=n}
                    }
                    remaining=when {spec.length<0->available;available<0->spec.length;else->minOf(available,spec.length)}
                    headers=mapOf("Content-Type" to listOf(mime))
                    active(deadline);opened=true;transferStarted(spec);return remaining
                }
            }
            throw StreamFailure(StreamIssue.SOURCE)
        }catch(e:Exception){val issue=StreamIssue.from(e,stopped.get());close();throw failure(issue)}
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        if(length==0)return 0
        active()
        if(remaining==0L)return C.RESULT_END_OF_INPUT
        try {
            val n=input?.read(buffer,offset,if(remaining>=0)minOf(length.toLong(),remaining).toInt()else length) ?: return C.RESULT_END_OF_INPUT
            active()
            if(n<0){if(remaining>0)throw StreamFailure(StreamIssue.NETWORK);return C.RESULT_END_OF_INPUT}
            delivered+=n
            if(delivered>StreamRange.MAX_BYTES)throw StreamFailure(StreamIssue.FORMAT)
            if(remaining>=0)remaining-=n
            bytesTransferred(n);return n
        }catch(e:Exception){throw failure(StreamIssue.from(e,stopped.get()))}
    }
    override fun getUri():Uri?=original
    override fun getResponseHeaders()=headers
    override fun close() {stopped.set(true);runCatching {input?.close()};input=null;connection?.disconnect();connection=null;remaining=-1;if(opened){opened=false;transferEnded()}}
}
