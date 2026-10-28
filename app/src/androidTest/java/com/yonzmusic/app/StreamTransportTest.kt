package com.yonzmusic.app

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.playback.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** No internet. Actual DataSource and HTTP contracts, with a constructor-injected fixture transport. */
@RunWith(AndroidJUnit4::class)
class StreamTransportTest {
    internal class Resolver(var value:StreamRepository.Resolved=StreamRepository.Resolved("https://r1.googlevideo.com/a?rev=old",emptyMap(),20000,"251","webm","opus")):StreamResolver {
        val requested=mutableListOf<String?>();var invalidations=0;var refreshed:StreamRepository.Resolved?=null
        override fun resolve(id:String,cancelled:AtomicBoolean,formatId:String?,timeoutMillis:Long):StreamRepository.Resolved {if(cancelled.get())throw StreamFailure(StreamIssue.CANCELLED);requested.add(formatId);return value}
        override fun invalidate(id:String){invalidations++;refreshed?.let {value=it}}
    }
    internal open class Connection(url:URL,private val bytes:ByteArray,private val status:Int?=null,
        private val wrongRange:Boolean=false,private val ignoreRange:Boolean=false,private val truncated:Boolean=false,
        private val failAfter:Int?=null,private val mime:String="audio/webm"):HttpURLConnection(url) {
        var readRequests=0
        private fun start()=if(ignoreRange)0 else getRequestProperty("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
        private fun end()=(getRequestProperty("Range")?.substringAfter('-')?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
        override fun connect(){connected=true}
        override fun disconnect(){connected=false}
        override fun usingProxy()=false
        override fun getResponseCode()=status ?: if(ignoreRange || getRequestProperty("Range")==null)200 else 206
        override fun getContentType()=mime
        override fun getHeaderField(name:String?):String?=if(name.equals("Content-Range",true) && getResponseCode()==206)"bytes ${if(wrongRange)0 else start()}-${end()}/${bytes.size}"else null
        override fun getContentLengthLong()=if(getResponseCode()==200)bytes.size.toLong()else (end()-start()+1).toLong()
        override fun getInputStream():java.io.InputStream {
            readRequests++
            val first=start();val count=(end()-first+1).coerceAtLeast(0)
            val data=java.io.ByteArrayInputStream(bytes,first,if(truncated)minOf(count,10)else count)
            return object:java.io.InputStream() {
                var read=0
                override fun read():Int {val one=ByteArray(1);return if(read(one,0,1)<0)-1 else one[0].toInt() and 255}
                override fun read(b:ByteArray,off:Int,len:Int):Int {
                    if(failAfter!=null && read>=failAfter)throw java.net.SocketTimeoutException("fixture-only private URL must not leak")
                    val n=data.read(b,off,if(failAfter!=null)minOf(len,(failAfter-read).coerceAtLeast(1))else len)
                    if(n>0)read+=n;return n
                }
            }
        }
    }
    internal companion object {
        fun addresses(host:String)=arrayOf(java.net.InetAddress.getByAddress(byteArrayOf(8,8,8,8)))
        fun spec(position:Long=0,length:Long=-1)=DataSpec.Builder().setUri("yono-stream://youtube/aqz-KE-bpKQ").setPosition(position).setLength(length).build()
    }
    @Test fun expiredUrlRefreshKeepsFormatAndExactByteRange() {
        val bytes=ByteArray(10000){(it%251).toByte()};val resolver=Resolver();var expired=false
        resolver.refreshed=resolver.value.copy(url="https://r1.googlevideo.com/a?rev=new")
        val ds=StreamDataSource(resolver,{url->Connection(url,bytes,if(expired && url.query=="rev=old")403 else null)},::addresses)
        assertEquals(10000L,ds.open(spec()));ds.close();expired=true
        assertEquals(1024L,ds.open(spec(1000,1024)));val read=ByteArray(1024);assertEquals(1024,ds.read(read,0,read.size));assertArrayEquals(bytes.copyOfRange(1000,2024),read)
        assertEquals(1,resolver.invalidations);assertEquals("251",resolver.requested.last());assertEquals("yono-stream",ds.uri?.scheme);ds.close()
    }
    @Test fun refreshCannotMixAnotherFormatOrChangedContentLength() {
        val bytes=ByteArray(1000);val resolver=Resolver();var expired=false;var inputRequests=0
        resolver.refreshed=resolver.value.copy(formatId="140",extension="m4a",codec="aac")
        val ds=StreamDataSource(resolver,{url->object:Connection(url,bytes,if(expired)403 else null){override fun getInputStream():java.io.InputStream {inputRequests++;return super.getInputStream()}}},::addresses)
        ds.open(spec());ds.close();expired=true
        try{ds.open(spec(100));fail("changed codec must be rejected")}catch(e:StreamFailure){assertEquals(StreamIssue.FORMAT_CHANGED,e.issue)}
        assertEquals(1,inputRequests);ds.close()
        var changed=false
        val other=StreamDataSource(Resolver(),{url->Connection(url,if(changed)ByteArray(2000)else bytes)},::addresses)
        other.open(spec());other.close();changed=true
        try{other.open(spec(100));fail("changed total must be rejected")}catch(e:StreamFailure){assertEquals(StreamIssue.FORMAT_CHANGED,e.issue)};other.close()
    }
    @Test fun ignoredOrIncorrectRangesCannotPretendSeekSucceeded() {
        val bytes=ByteArray(2*1024*1024)
        val wrong=StreamDataSource(Resolver(),{url->Connection(url,bytes,wrongRange=true)},::addresses)
        try{wrong.open(spec(100,100));fail("wrong response offset")}catch(e:StreamFailure){assertEquals(StreamIssue.SEEK,e.issue)};wrong.close()
        val ignored=StreamDataSource(Resolver(),{url->Connection(url,bytes,ignoreRange=true)},::addresses)
        try{ignored.open(spec(StreamRange.MAX_DISCARD+1));fail("unbounded discard")}catch(e:StreamFailure){assertEquals(StreamIssue.SEEK,e.issue)};ignored.close()
    }
    @Test fun truncatedAudioTimeoutAndCancellationHaveSafeDistinctFailures() {
        val bytes=ByteArray(1000)
        val short=StreamDataSource(Resolver(),{url->Connection(url,bytes,truncated=true)},::addresses)
        short.open(spec());val b=ByteArray(100);assertEquals(10,short.read(b,0,100))
        try{short.read(b,0,100);fail("truncation")}catch(e:StreamFailure){assertEquals(StreamIssue.NETWORK,e.issue)};short.close()
        val timeout=StreamDataSource(Resolver(),{url->Connection(url,bytes,failAfter=0)},::addresses)
        timeout.open(spec());try{timeout.read(b,0,100);fail("timeout")}catch(e:StreamFailure){assertEquals(StreamIssue.TIMEOUT,e.issue);assertFalse(e.message.orEmpty().contains("private URL"))}
        timeout.close();try{timeout.read(b,0,100);fail("cancelled")}catch(e:StreamFailure){assertEquals(StreamIssue.CANCELLED,e.issue)}
    }
    @Test fun transientRetryBudgetSurvivesProgressAndReopen() {
        val source=StreamDataSource(Resolver(),{url->Connection(url,ByteArray(1000),failAfter=1)},::addresses)
        val b=ByteArray(16)
        repeat(3){attempt->
            source.open(spec());assertEquals(1,source.read(b,0,b.size))
            try{source.read(b,0,b.size);fail("injected timeout")}catch(e:StreamFailure){assertEquals(StreamIssue.TIMEOUT,e.issue);assertEquals(attempt<2,e.retryAllowed)}
            source.close()
        }
    }
    @Test fun accessRejectionRefreshesOnceAndRateLimitsNeverRefresh() {
        val resolver=Resolver();val denied=StreamDataSource(resolver,{url->Connection(url,ByteArray(100),403)},::addresses)
        try{denied.open(spec());fail("403")}catch(e:StreamFailure){assertEquals(StreamIssue.ACCESS,e.issue)}
        assertEquals(1,resolver.invalidations);assertEquals(2,resolver.requested.size);denied.close()
        val limitedResolver=Resolver();val limited=StreamDataSource(limitedResolver,{url->Connection(url,ByteArray(100),429)},::addresses)
        try{limited.open(spec());fail("429")}catch(e:StreamFailure){assertEquals(StreamIssue.RATE_LIMIT,e.issue)};assertEquals(0,limitedResolver.invalidations);limited.close()
    }
}
