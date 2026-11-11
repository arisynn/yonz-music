@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.yonzmusic.app

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.datasource.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.data.PlaybackSnapshot
import com.yonzmusic.app.playback.*
import com.yonzmusic.app.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.*

@RunWith(AndroidJUnit4::class)
class UpcomingPreparationTest {
    @Test fun fourUpcomingSourcesPrepareSeriallyWhileNativeNextAudioIsPreloaded():Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>();val inst=InstrumentationRegistry.getInstrumentation()
        fun main(block:()->Unit)=inst.runOnMainSync(block)
        suspend fun await(check:()->Boolean)=withTimeout(35000){while(true){var yes=false;main {yes=check()};if(yes)break;delay(40)}}
        val snapshot=app.preferences.snapshot();val prefs=app.preferences.flow.first();val tracks=app.repository.dao.allTracks()
        val ids=(1..5).map {"QAup"+it.toString().padStart(7,'0')}
        val previous=ids.associateWith {app.repository.dao.state("stream:$it")}
        val active=AtomicInteger();val peak=AtomicInteger();val completed=ConcurrentLinkedQueue<String>()
        val readBytes=ConcurrentHashMap<String,AtomicLong>()
        val resolver=object:StreamResolver {
            override fun invalidate(id:String){}
            override fun resolve(id:String,cancelled:AtomicBoolean,formatId:String?,timeoutMillis:Long):StreamRepository.Resolved {
                val concurrent=active.incrementAndGet();peak.updateAndGet {maxOf(it,concurrent)}
                try {
                    check(StreamWork.isSpeculative && !StreamWork.urgent && !StreamWork.foregroundLoading)
                    repeat(4){Thread.sleep(20);check(!cancelled.get() && !Thread.currentThread().isInterrupted)}
                    completed.add(id)
                    return StreamRepository.Resolved("https://r1.googlevideo.com/owned/$id",emptyMap(),30000,"owned-pcm","wav","pcm",256,"d".repeat(64))
                }finally{active.decrementAndGet()}
            }
        }
        val frames=16000*30;val wav=ByteBuffer.allocate(44+frames*2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36+frames*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(frames*2)
        lateinit var connection:PlaybackConnection;var connected=false
        try {
            app.preferences.autoplay(false);app.preferences.onlineHistory(false)
            app.preferences.save(PlaybackSnapshot(emptyList(),0,0,false,Player.REPEAT_MODE_OFF))
            app.streamTestResolver=resolver
            app.streamTestDataSource=DataSource.Factory {
                val delegate=ByteArrayDataSource(wav.array());var source=""
                object:DataSource by delegate {
                    override fun open(spec:DataSpec):Long {source=spec.uri.lastPathSegment.orEmpty();return delegate.open(spec)}
                    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                        val n=delegate.read(buffer,offset,length);if(n>0)readBytes.computeIfAbsent(source){AtomicLong()}.addAndGet(n.toLong());return n
                    }
                }
            }
            val songs=ids.map {id->OnlineSong("engine:$id",SearchProvider.ENGINE,"Owned window fixture","QA artist",durationMs=30000,page="https://www.youtube.com/watch?v=$id",videoId=id,artistVerified=true)}
            songs.forEach {app.streams.register(it)}
            main {connection=PlaybackConnection(app);connected=true};await {connection.state.value.ready}
            main {connection.playRemoteList(songs,0)}
            await {connection.state.value.playing && completed.toSet().containsAll(ids.drop(1)) && (readBytes[ids[1]]?.get() ?: 0)>1024}
            assertEquals(ids.drop(1),completed.toList().distinct());assertFalse(completed.contains(ids[0]));assertEquals(1,peak.get())
            var before=0L;main {before=connection.position()};delay(300)
            main {assertTrue(connection.position()>before);assertEquals("engine:${ids[0]}",connection.state.value.id)}
            val nextBytes=readBytes[ids[1]]!!.get();val start=android.os.SystemClock.elapsedRealtime()
            main {connection.next()};await {connection.state.value.id=="engine:${ids[1]}" && connection.state.value.playing && connection.position()>100}
            val transition=android.os.SystemClock.elapsedRealtime()-start
            java.io.File(app.cacheDir,"upcoming-window.json").writeText(JSONObject().put("liveNetwork",false).put("preparedUpcoming",ids.drop(1).size).put("maximumConcurrentResolvers",peak.get()).put("nativeNextBytesBeforeTransition",nextBytes).put("nextAdvancingWithinMs",transition).put("currentKeptPlaying",true).toString(2))
            assertEquals(tracks,app.repository.dao.allTracks())
        }finally {
            main {if(connected){connection.pause();connection.close()};app.stopService(Intent(app,PlaybackService::class.java))};delay(400)
            app.streamTestResolver=null;app.streamTestDataSource=null
            app.persistenceScope.launch {}.join()
            app.preferences.autoplay(prefs.autoplay);app.preferences.onlineHistory(prefs.onlineHistory)
            app.preferences.save(snapshot ?: PlaybackSnapshot(emptyList(),0,0,false,Player.REPEAT_MODE_OFF))
            previous.forEach {(id,row)->if(row==null)app.repository.dao.deleteState("stream:$id")else app.repository.dao.state(row)}
        }
    }
}
