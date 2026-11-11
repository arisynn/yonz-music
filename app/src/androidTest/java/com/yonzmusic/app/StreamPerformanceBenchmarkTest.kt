@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.yonzmusic.app

import android.content.Intent
import android.os.SystemClock
import androidx.media3.datasource.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.data.*
import com.yonzmusic.app.playback.*
import com.yonzmusic.app.search.*
import com.yonzmusic.engine.PythonRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.*

/** No public network: actual service/cache/extractor/decoder, generated PCM, shaped injected HTTP. */
@RunWith(AndroidJUnit4::class)
class StreamPerformanceBenchmarkTest {
    @Test fun measuredSessionMatrix():Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>();val inst=InstrumentationRegistry.getInstrumentation()
        fun main(f:()->Unit)=inst.runOnMainSync(f)
        suspend fun await(label:String,predicate:()->Boolean)=withTimeout(60000){while(true){var ready=false;main{ready=predicate()};if(ready)break;delay(30)}}
        val run=InstrumentationRegistry.getArguments().getString("performanceRun") ?: "baseline"
        val report=JSONObject().put("run",run).put("liveNetwork",false).put("physicalWifi",false).put("physicalMobile",false)
        val saved=app.preferences.snapshot();val preferences=app.preferences.flow.first();val originalTracks=app.repository.dao.allTracks()
        val startedPython=com.chaquo.python.Python.isStarted()
        val cold=SystemClock.elapsedRealtime();PythonRuntime.module(app,"yono_stream")
        report.put("pythonAlreadyStarted",startedPython).put("pythonFirstModuleMs",SystemClock.elapsedRealtime()-cold)
        val warm=SystemClock.elapsedRealtime();PythonRuntime.module(app,"yono_stream");report.put("pythonWarmModuleMs",SystemClock.elapsedRealtime()-warm)
        report.put("pythonStartupEvents",JSONArray().apply {StreamPerformance.snapshot().filter {it.stage in setOf(StreamPerformance.Stage.PYTHON_INIT,StreamPerformance.Stage.PYTHON_IMPORT)}.forEach {put(it.json())}})
        val fixtureRows=(0 until 5000).map {Track("qa-perf-row-$it","content://qa.perf/$it","Owned index fixture $it","QA","Owned",available=false,source="qa-perf")}
        app.repository.dao.upsert(fixtureRows);app.preferences.autoplay(false);app.preferences.onlineHistory(false)
        val frames=16000*20;val wav=ByteBuffer.allocate(44+frames*2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36+frames*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(frames*2)
        repeat(frames){wav.putShort((kotlin.math.sin(it*2*Math.PI*220/16000)*800).toInt().toShort())}
        val payload=wav.array();val profiles=JSONArray()
        try {
            for((profile,rate) in listOf("fast-shaped" to 256000,"mobile-shaped" to 48000)) {
                val numbers=AtomicInteger();val opens=AtomicInteger();val bytes=java.util.concurrent.ConcurrentHashMap<String,AtomicLong>()
                val resolution=StreamResolutionCache<StreamRepository.Resolved>({SystemClock.elapsedRealtime()})
                val resolver=object:StreamResolver {
                    override fun invalidate(id:String){resolution.invalidate(id)}
                    override fun resolve(id:String,cancelled:AtomicBoolean,formatId:String?,timeoutMillis:Long):StreamRepository.Resolved {
                        StreamPerformance.sinceTap("engine:$id",StreamPerformance.Stage.RESOLVER_START)
                        return resolution.resolve(id,{true},{check(!cancelled.get() && !Thread.currentThread().isInterrupted)},{120000}) {
                            val start=SystemClock.elapsedRealtime();numbers.incrementAndGet();repeat(12){Thread.sleep(10);check(!cancelled.get())}
                            StreamPerformance.mark("engine:$id",StreamPerformance.Stage.RESOLVER_LOAD,SystemClock.elapsedRealtime()-start)
                            StreamRepository.Resolved("https://r1.googlevideo.com/owned/$id",emptyMap(),20000,"owned-pcm","wav","pcm",256,"a".repeat(64))
                        }
                    }
                }
                val directory=File(app.cacheDir,"perf-$run-$profile-${System.nanoTime()}")
                val cache=StreamAudioCache(app,directory,resolver) {pinned->DataSource.Factory {
                    StreamDataSource(pinned,{url->object:StreamTransportTest.Connection(url,payload,mime="audio/wav") {
                        private var reported=false
                        override fun getResponseCode():Int {if(!reported){reported=true;Thread.sleep(if(profile=="fast-shaped")30 else 100);opens.incrementAndGet()};return super.getResponseCode()}
                        override fun getInputStream():java.io.InputStream {
                            val stream=super.getInputStream();val counter=bytes.computeIfAbsent(url.path.substringAfterLast('/')){AtomicLong()}
                            return object:java.io.FilterInputStream(stream) {
                                override fun read(b:ByteArray,off:Int,len:Int):Int {val n=stream.read(b,off,minOf(len,2048));if(n>0){Thread.sleep((n*1000L/rate).coerceAtLeast(1));counter.addAndGet(n.toLong())};return n}
                                override fun read():Int {val b=ByteArray(1);return if(read(b,0,1)<0)-1 else b[0].toInt() and 255}
                            }
                        }
                    }},{StreamTransportTest.addresses(it)})
                }}
                cache.configure(64)
                app.streamTestResolver=resolver
                app.streamTestDataSource=DataSource.Factory {CatalogDataSource(app,cache.factory().createDataSource())}
                lateinit var connection:PlaybackConnection
                val songs=(1..2).map {n->val id="QApf"+n.toString().padStart(7,'0');OnlineSong("engine:$id",SearchProvider.ENGINE,"Owned performance fixture","QA",durationMs=20000,page="https://www.youtube.com/watch?v=$id",videoId=id,artistVerified=true)}
                val old=songs.associate {it.videoId to app.repository.dao.state("stream:${it.videoId}")}
                try {
                    songs.forEach {app.streams.register(it)}
                    main{connection=PlaybackConnection(app)};await("connect"){connection.state.value.ready}
                    StreamPerformance.reset()
                    main{StreamPerformance.begin(songs[0].id);connection.playRemoteList(songs,0)}
                    await("first audio"){connection.state.value.playing && connection.position()>150}
                    main{connection.seek(12000)};await("forward seek"){connection.state.value.playing && connection.position()>12100}
                    main{connection.seek(1000)};await("backward seek"){connection.state.value.playing && connection.position() in 1100..6000}
                    var before=0L;main{before=connection.position()}
                    delay(500);main{assertTrue(connection.position()>before+150)}
                    val waitUntil=SystemClock.elapsedRealtime()+12000
                    while((bytes[songs[1].videoId]?.get() ?: 0)<16000 && SystemClock.elapsedRealtime()<waitUntil)delay(100)
                    val preloaded=bytes[songs[1].videoId]?.get() ?: 0
                    main{connection.next()};await("next"){connection.state.value.id==songs[1].id && connection.state.value.playing && connection.position()>150}
                    main{connection.pause()}
                    val repeated=SystemClock.elapsedRealtime()
                    main{StreamPerformance.begin(songs[0].id);connection.playRemote(songs[0])}
                    await("subsequent cached playback"){connection.state.value.id==songs[0].id && connection.state.value.playing && connection.position()>150}
                    val repeatedMs=SystemClock.elapsedRealtime()-repeated
                    main{connection.pause()};delay(150)
                    val events=JSONArray().apply {StreamPerformance.snapshot().forEach {put(it.json())}}
                    profiles.put(JSONObject().put("profile",profile).put("fixtureBytesPerSecond",rate).put("libraryRows",5000).put("httpOpens",opens.get()).put("extractions",numbers.get()).put("nextBytesBeforeTransition",preloaded).put("subsequentPlaybackPollMs",repeatedMs).put("backgroundAdvanced",true).put("events",events))
                }finally {
                    main{connection.pause();connection.close();app.stopService(Intent(app,PlaybackService::class.java))};delay(400)
                    app.streamTestDataSource=null;app.streamTestResolver=null;cache.release();directory.deleteRecursively()
                    old.forEach {(id,row)->if(row!=null)app.repository.dao.state(row)else app.repository.dao.deleteState("stream:$id")}
                }
            }
            report.put("profiles",profiles)
        }finally {
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM tracks WHERE source = ?",arrayOf("qa-perf"))
            app.preferences.autoplay(preferences.autoplay);app.preferences.onlineHistory(preferences.onlineHistory)
            if(saved!=null)app.preferences.save(saved)
            assertEquals(originalTracks,app.repository.dao.allTracks())
            File(app.cacheDir,"performance-$run.json").writeText(report.toString(2))
        }
    }
}
