@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.yonzmusic.app

import androidx.media3.datasource.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.playback.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class StreamPerformanceContractsTest {
    @Test fun coldCacheAdapterAndCompletedHttpLifecycleMeasurements() {
        val app=ApplicationProvider.getApplicationContext<YonzApp>();val calls=AtomicInteger();val disconnected=AtomicInteger()
        val payload=ByteArray(65536){(it%251).toByte()}
        val resolver=object:StreamResolver {
            override fun invalidate(id:String){}
            override fun resolve(id:String,cancelled:AtomicBoolean,formatId:String?,timeoutMillis:Long):StreamRepository.Resolved {
                calls.incrementAndGet();Thread.sleep(40)
                return StreamRepository.Resolved("https://r1.googlevideo.com/audio?expire=1&signature=never-log",emptyMap(),10000,"251","webm","opus",128,"c".repeat(64))
            }
        }
        val directory=File(app.cacheDir,"perf-contract-${System.nanoTime()}")
        val cache=StreamAudioCache(app,directory,resolver){r->DataSource.Factory {StreamDataSource(r,{url->object:StreamTransportTest.Connection(url,payload) {
            override fun disconnect(){disconnected.incrementAndGet();super.disconnect()}
        }},{StreamTransportTest.addresses(it)})}}
        fun read(){val source=cache.factory().createDataSource();try {source.open(StreamTransportTest.spec(0,payload.size.toLong()));val output=java.io.ByteArrayOutputStream();val b=ByteArray(8192);while(true){val n=source.read(b,0,b.size);if(n<0)break;output.write(b,0,n)};assertArrayEquals(payload,output.toByteArray())}finally{source.close()}}
        try {
            StreamPerformance.reset();StreamPerformance.begin("engine:aqz-KE-bpKQ")
            val start=android.os.SystemClock.elapsedRealtime();read();val first=android.os.SystemClock.elapsedRealtime()-start
            val firstCalls=calls.get();val firstDisconnects=disconnected.get();read()
            val run=InstrumentationRegistry.getArguments().getString("performanceRun") ?: "baseline"
            File(app.cacheDir,"performance-contract-$run.json").writeText(JSONObject().put("firstOpenMs",first).put("firstResolverCalls",firstCalls).put("completedBodyDisconnects",firstDisconnects).put("secondOpenResolverCalls",calls.get()-firstCalls).put("liveNetwork",false).toString(2))
            val json=StreamPerformance.snapshot().joinToString {it.json().toString()}
            assertFalse(json.contains("googlevideo"));assertFalse(json.contains("aqz-KE"));assertFalse(json.contains("signature"));assertFalse(json.contains("never-log"))
        }finally{cache.release();directory.deleteRecursively()}
    }
}
