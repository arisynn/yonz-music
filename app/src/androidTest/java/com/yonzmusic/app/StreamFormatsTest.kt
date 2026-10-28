package com.yonzmusic.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
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

@RunWith(AndroidJUnit4::class)
class StreamFormatsTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=ApplicationProvider.getApplicationContext<YonzApp>()
    private fun main(f:()->Unit)=inst.runOnMainSync(f)
    private suspend fun await(label:String,check:()->Boolean) {withTimeout(45000){while(true){var yes=false;main {yes=check()};if(yes)return@withTimeout;delay(100)}}}
    private fun song(id:String)=OnlineSong("engine:$id",SearchProvider.ENGINE,"Original format fixture","Karisyono QA",page="https://www.youtube.com/watch?v=$id",videoId=id,artistVerified=true)
    @Test fun nativeOriginalFormatsAndBoundedNetworkRecoveryUseOneSession():Unit=runBlocking {
        val assets=inst.context.assets;val before=app.repository.dao.allTracks();val saved=app.preferences.snapshot()
        val ids=(1..5).map {"QAfmt"+it.toString().padStart(6,'0')}
        val old=ids.associateWith {app.repository.dao.state("stream:$it")};val reports=org.json.JSONArray()
        val fixtures=listOf("owned-tone.mp3" to "mp3","original-audio/owned.webm" to "webm","original-audio/owned.opus" to "opus","formats/owned-tone.m4a" to "m4a")
        var bytes=ByteArray(0);var ext="wav";val failOnce=java.util.concurrent.atomic.AtomicBoolean(false)
        val injectedTimeouts=java.util.concurrent.atomic.AtomicInteger(0)
        val opened=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val media=java.util.concurrent.ConcurrentHashMap<String,Pair<ByteArray,String>>()
        val ranges=java.util.concurrent.CopyOnWriteArrayList<String>()
        val resolver=object:StreamResolver {
            override fun invalidate(id:String){}
            override fun resolve(id:String,cancelled:java.util.concurrent.atomic.AtomicBoolean,formatId:String?,timeoutMillis:Long):StreamRepository.Resolved {
                val extension=media.getValue(id).second
                return StreamRepository.Resolved("https://r1.googlevideo.com/owned?id=$id",emptyMap(),0,"owned-$extension",extension,extension)
            }
        }
        try {
            app.streamTestDataSource=androidx.media3.datasource.DataSource.Factory {StreamDataSource(resolver,{url->
                val id=url.query.substringAfter("id=");val fixture=media.getValue(id)
                val fail=if(id==ids[4] && failOnce.compareAndSet(true,false))32768 else null
                object:StreamTransportTest.Connection(url,fixture.first,failAfter=fail,mime=when(fixture.second){"mp3"->"audio/mpeg";"m4a"->"audio/mp4";"opus"->"audio/ogg";else->"audio/${fixture.second}"}) {
                    override fun getInputStream():java.io.InputStream {
                        opened.add(id)
                        if(id==ids[4])ranges.add(getRequestProperty("Range") ?: "bytes=0-")
                        val delegate=super.getInputStream()
                        return object:java.io.FilterInputStream(delegate) {
                            override fun read(b:ByteArray,off:Int,len:Int):Int = try {delegate.read(b,off,len)}catch(e:java.net.SocketTimeoutException){injectedTimeouts.incrementAndGet();throw e}
                            override fun read():Int = try {delegate.read()}catch(e:java.net.SocketTimeoutException){injectedTimeouts.incrementAndGet();throw e}
                        }
                    }
                }
            },{host->StreamTransportTest.addresses(host)})}
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var vm:MusicViewModel;scenario.onActivity {vm=ViewModelProvider(it)[MusicViewModel::class.java]}
                await("session"){vm.playback.state.value.ready}
                for((index,fixture)in fixtures.withIndex()) {
                    ext=fixture.second;bytes=assets.open(fixture.first).use {it.readBytes()};val item=song(ids[index]);media[ids[index]]=bytes to ext;app.streams.register(item)
                    main {vm.playback.playRemote(item)}
                    await("decode $ext"){
                        val expected=listOf(12000L,5000L,5000L,3000L)[index]
                        opened.contains(ids[index]) && vm.playback.state.value.id==item.id && vm.playback.state.value.playing && vm.playback.position()>300 && kotlin.math.abs(vm.playback.duration()-expected)<500
                    }
                    main {assertTrue("$ext duration",vm.playback.duration()>0)}
                    main {vm.playback.seek(1000)};await("seek $ext"){vm.playback.position()>=900}
                    main {reports.put(org.json.JSONObject().put("format",ext).put("durationMs",vm.playback.duration()).put("decoded",true).put("seek",true))}
                    main {vm.playback.pause()}
                }
                val samples=16000*20;val b=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
                b.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
                repeat(samples){b.putShort((kotlin.math.sin(it*2*Math.PI*220/16000)*800).toInt().toShort())}
                ext="wav";bytes=b.array();failOnce.set(true);ranges.clear();val item=song(ids[4]);media[ids[4]]=bytes to ext;app.streams.register(item)
                main {vm.playback.playRemote(item)}
                await("actual Media3 load retry decodes after injected timeout"){
                    injectedTimeouts.get()>0 && ranges.size>=2 && vm.playback.state.value.id==item.id && vm.playback.state.value.playing && vm.playback.position()>300 && vm.playback.duration()==20000L
                }
                assertTrue("Timeout recovery must reopen the transport",ranges.size>=2)
                // Media3 may restart an extractor at zero when failure precedes initialization.
                val resumedRange=ranges.any {it.substringAfter("bytes=").substringBefore('-').toLongOrNull()?.let {n->n>0}==true}
                var start=0L;main {start=vm.playback.position()};scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);delay(1500)
                main {assertTrue(vm.playback.state.value.playing);assertTrue(vm.playback.position()>start+400);vm.playback.pause()}
                assertEquals("Streaming must not insert or rewrite local tracks",before,app.repository.dao.allTracks())
                java.io.File(app.cacheDir,"stream-format-qa.json").writeText(org.json.JSONObject().put("fixtures",reports).put("injectedTimeoutRecovered",true).put("transportOpens",ranges.size).put("observedInjectedTimeouts",injectedTimeouts.get()).put("nonzeroRangeOnRecovery",resumedRange).put("backgroundAdvanced",true).put("liveNetwork",false).toString(2))
            }
        }finally {
            app.streamTestDataSource=null;main {app.stopService(android.content.Intent(app,PlaybackService::class.java))};delay(500)
            app.persistenceScope.launch {if(saved!=null)app.preferences.save(saved)}.join()
            for(id in ids){val row=old[id];if(row!=null)app.repository.dao.state(row)else app.database.openHelper.writableDatabase.execSQL("DELETE FROM app_state WHERE name=?",arrayOf("stream:$id"))}
        }
    }
}
