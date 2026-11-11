@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.yonzmusic.app

import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.playback.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Same decoder/fixture/network shaper, only the streaming thresholds vary. No physical network claim. */
@RunWith(AndroidJUnit4::class)
class StreamBufferExperimentTest {
    @Test fun compareThresholdsAndRebuffering():Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>();val inst=InstrumentationRegistry.getInstrumentation()
        fun main(block:()->Unit)=inst.runOnMainSync(block)
        val frames=16000*16;val wav=ByteBuffer.allocate(44+frames*2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36+frames*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(frames*2)
        repeat(frames){wav.putShort((kotlin.math.sin(it*2*Math.PI*220/16000)*800).toInt().toShort())}
        val payload=wav.array();val runs=JSONArray()
        for(stalled in listOf(false,true))for(candidate in listOf(false,true)) {
            val sent=java.util.concurrent.atomic.AtomicLong();val gap=AtomicBoolean(false)
            val resolver=StreamTransportTest.Resolver(StreamRepository.Resolved("https://r1.googlevideo.com/owned",emptyMap(),16000,"pcm","wav","pcm"))
            val factory=DataSource.Factory {StreamDataSource(resolver,{url->object:StreamTransportTest.Connection(url,payload,mime="audio/wav") {
                override fun getInputStream():java.io.InputStream {val input=super.getInputStream();return object:java.io.FilterInputStream(input) {
                    override fun read(b:ByteArray,off:Int,len:Int):Int {
                        if(stalled && sent.get()>=96000 && gap.compareAndSet(false,true))Thread.sleep(3500)
                        val n=input.read(b,off,minOf(len,2048));if(n>0){sent.addAndGet(n.toLong());Thread.sleep(n*1000L/48000)};return n
                    }
                    override fun read():Int {val b=ByteArray(1);return if(read(b,0,1)<0)-1 else b[0].toInt() and 255}
                }}
            }},{StreamTransportTest.addresses(it)})}
            lateinit var player:ExoPlayer;lateinit var metrics:StreamPlayerMetrics
            try {
                StreamPerformance.reset()
                main {
                    player=ExoPlayer.Builder(app).setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                        .setLoadControl(StreamingLoadControls.create(if(candidate)750 else 1000,if(candidate)2000 else 2500)).build()
                    metrics=StreamPlayerMetrics(player);StreamPerformance.begin("engine:QAbuffer001")
                    player.setMediaItem(MediaItem.Builder().setMediaId("engine:QAbuffer001").setUri("yono-stream://youtube/QAbuffer001").build());player.prepare();player.play()
                }
                withTimeout(45000) {
                    while(true) {
                        var done=false;main{assertNull(player.playerError);done=player.isPlaying && player.currentPosition>if(stalled)6000 else 1500}
                        if(done)break;delay(50)
                    }
                }
                main{player.pause()}
                val events=StreamPerformance.snapshot()
                assertTrue(events.any {it.stage==StreamPerformance.Stage.AUDIO_ADVANCING})
                if(stalled){assertTrue(gap.get());assertTrue("A real depletion should be measured",events.any {it.stage==StreamPerformance.Stage.REBUFFER})}
                runs.put(JSONObject().put("initialMs",if(candidate)750 else 1000).put("rebufferMs",if(candidate)2000 else 2500).put("rateBytesPerSecond",48000).put("stallMs",if(stalled)3500 else 0)
                    .put("readyMs",events.first {it.stage==StreamPerformance.Stage.STATE_READY}.ms).put("audioAdvancingMs",events.first {it.stage==StreamPerformance.Stage.AUDIO_ADVANCING}.ms)
                    .put("rebufferCount",events.count {it.stage==StreamPerformance.Stage.REBUFFER}).put("rebufferDurationMs",events.filter {it.stage==StreamPerformance.Stage.REBUFFER}.sumOf {it.ms}))
            }finally {main {metrics.close();player.release()}}
        }
        File(app.cacheDir,"performance-buffer-experiment.json").writeText(JSONObject().put("liveNetwork",false).put("runs",runs).toString(2))
    }
}
