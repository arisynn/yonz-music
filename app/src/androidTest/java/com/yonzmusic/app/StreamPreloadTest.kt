package com.yonzmusic.app

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.yonzmusic.app.playback.*
import com.yonzmusic.app.search.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** Real MediaSession/ExoPlayer, generated audio and injected HTTP only; no live-service claim. */
@RunWith(AndroidJUnit4::class)
class StreamPreloadTest {
    @Test fun nextAudioLoadsBeforeTransitionWithoutChangingLibrary():Unit=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation();val app=ApplicationProvider.getApplicationContext<YonzApp>()
        fun main(f:()->Unit)=inst.runOnMainSync(f)
        suspend fun await(check:()->Boolean)=withTimeout(60000){while(true){var yes=false;main{yes=check()};if(yes)break;delay(100)}}
        val ids=listOf("QApre000001","QApre000002")
        val old=ids.associateWith {app.repository.dao.state("stream:$it")};val saved=app.preferences.snapshot();val tracks=app.repository.dao.allTracks()
        fun song(id:String)=OnlineSong("engine:$id",SearchProvider.ENGINE,"Preload fixture","Karisyono QA",page="https://www.youtube.com/watch?v=$id",videoId=id,artistVerified=true)
        val count=16000*60;val wav=ByteBuffer.allocate(44+count*2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36+count*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(count*2)
        repeat(count){wav.putShort((kotlin.math.sin(it*2*Math.PI*220/16000)*800).toInt().toShort())}
        val bytes=wav.array();val readBytes=ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicLong>()
        val resolver=object:StreamResolver {
            override fun invalidate(id:String){}
            override fun resolve(id:String,cancelled:java.util.concurrent.atomic.AtomicBoolean,formatId:String?,timeoutMillis:Long)=StreamRepository.Resolved("https://r1.googlevideo.com/qa?id=$id",emptyMap(),60000,"qa-wav","wav","pcm")
        }
        val online=com.yonzmusic.app.data.OnlineCollection(java.util.UUID.randomUUID().toString(),"QA preloaded online",ids.map(::song))
        try {
            app.onlineCollections.save(online)
            app.streamTestDataSource=androidx.media3.datasource.DataSource.Factory {StreamDataSource(resolver,{url->object:StreamTransportTest.Connection(url,bytes,mime="audio/wav") {
                override fun getInputStream():java.io.InputStream {
                    val input=super.getInputStream();val total=readBytes.computeIfAbsent(url.query.substringAfter("id=")){java.util.concurrent.atomic.AtomicLong()}
                    return object:java.io.FilterInputStream(input) {
                        override fun read(b:ByteArray,off:Int,len:Int):Int {val n=input.read(b,off,len);if(n>0)total.addAndGet(n.toLong());return n}
                        override fun read():Int {val n=input.read();if(n>=0)total.incrementAndGet();return n}
                    }
                }
            }},{StreamTransportTest.addresses(it)})}
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var vm:MusicViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MusicViewModel::class.java]}
                await{vm.playback.state.value.ready}
                main {vm.playOnlineCollection(online)}
                await {vm.playback.state.value.playing && vm.playback.duration()==60000L && vm.playback.position()>300}
                await {vm.playback.state.value.queue.size==2 && (readBytes[ids[1]]?.get() ?: 0)>32000}
                main {assertEquals("engine:${ids[0]}",vm.playback.state.value.id);assertTrue(vm.playback.state.value.playing)}
                val loadedBefore=readBytes[ids[1]]!!.get();val start=android.os.SystemClock.elapsedRealtime()
                main {vm.playback.next()}
                await {vm.playback.state.value.id=="engine:${ids[1]}" && vm.playback.state.value.playing && vm.playback.position()>300 && vm.playback.duration()==60000L}
                val elapsed=android.os.SystemClock.elapsedRealtime()-start
                main {vm.playback.pause()}
                assertEquals(tracks,app.repository.dao.allTracks())
                java.io.File(app.cacheDir,"stream-preload-qa.json").writeText(org.json.JSONObject().put("nextBytesBeforeTransition",loadedBefore).put("nextStartedWithinMs",elapsed).put("sameSession",true).put("liveNetwork",false).put("durationMs",60000).toString(2))
            }
        }finally {
            app.onlineCollections.delete(online.id)
            app.streamTestDataSource=null;main {app.stopService(android.content.Intent(app,PlaybackService::class.java))};delay(500)
            app.persistenceScope.launch {if(saved!=null)app.preferences.save(saved)}.join()
            for(id in ids){old[id]?.let{app.repository.dao.state(it)} ?: app.database.openHelper.writableDatabase.execSQL("DELETE FROM app_state WHERE name=?",arrayOf("stream:$id"))}
        }
    }
}
