package com.yonzmusic.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.content.ComponentName
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.artwork.*
import com.yonzmusic.app.playback.*
import com.yonzmusic.app.search.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Controlled local images/audio. Screenshots are fixtures, not live catalogue or network results. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Radio30ArtworkUiTest {
    private val app get()=ApplicationProvider.getApplicationContext<YonzApp>()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private fun main(block:()->Unit)=inst.runOnMainSync(block)
    private fun await(message:String,timeout:Long=30000,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end){var done=false;main {done=condition()};if(done)return;Thread.sleep(80)}
        fail(message)
    }
    private fun shell(command:String) {inst.uiAutomation.executeShellCommand(command).use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
    private fun capture(name:String) {
        inst.waitForIdleSync();Thread.sleep(600)
        val directory=File(app.getExternalFilesDir(null),"qa-v155").apply {mkdirs()}
        val bitmap=inst.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(directory,name+".png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun tab(text:String) {
        val nodes=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(text)
        val node=nodes.firstOrNull() ?: error("Missing tab: $text")
        var clickable:AccessibilityNodeInfo?=node
        while(clickable!=null && !clickable.isClickable)clickable=clickable.parent
        check(clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
        inst.waitForIdleSync();Thread.sleep(250)
    }
    @Test fun pixelQualityQueueMetadataAndCompactLayout():Unit=runBlocking {
        val pixels=Bitmap.createBitmap(2048,2048,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(pixels);canvas.drawColor(Color.rgb(20,75,68))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(105,225,199);textSize=150f}
        canvas.drawText("ARTWORK QA",160f,850f,paint);paint.textSize=80f;canvas.drawText("2048 px · controlled fixture",160f,1050f,paint)
        val encoded=ByteArrayOutputStream().also {pixels.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();pixels.recycle()
        val disk=ImageDiskCache(File(app.cacheDir,"artwork-v1"))
        val songs=(0..30).map {i->
            val id="HDQ"+i.toString().padStart(8,'0');val url="https://i.ytimg.com/fixture/$id-2048.png"
            val song=OnlineSong("engine:$id",SearchProvider.ENGINE,"Fixture ${i+1}","QA Artist ${i%10}",durationMs=300000,artwork=url,
                page="https://www.youtube.com/watch?v=$id",videoId=id,artistVerified=true,artworkCandidates=listOf(ArtworkCandidate(url,2048,2048)))
            disk.put("${song.id}|${ArtworkPolicy.hash(url)}",encoded);song
        }
        for(song in songs)app.streams.register(song)
        val card=withContext(Dispatchers.IO){app.artwork.song(songs.first(),456)}
        val player=withContext(Dispatchers.IO){app.artwork.song(songs.first(),960)}
        assertEquals(512,card!!.width);assertEquals(1024,player!!.width)
        val samples=8000*300;val wav=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
        val bytes=wav.array();app.streamTestDataSource=DataSource.Factory {ByteArrayDataSource(bytes)}
        main {app.forYou.state.value=ForYouState(songs.first(),songs.take(4),songs.drop(1).take(12),System.currentTimeMillis())}
        app.preferences.theme("Dark")
        val before=app.repository.dao.allTracks().size
        lateinit var pending:com.google.common.util.concurrent.ListenableFuture<MediaController>
        main {pending=MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()}
        val controller=pending.get(20,java.util.concurrent.TimeUnit.SECONDS)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var vm:MusicViewModel;scenario.onActivity {vm=ViewModelProvider(it)[MusicViewModel::class.java]}
                await("UI controller connects after the first rendered frame",timeout=90000) {vm.playback.state.value.ready}
                val session=java.util.UUID.randomUUID().toString()
                main {controller.setMediaItems(songs.mapIndexed {i,s->radioItem(remoteMediaItem(s),"playlist",session,s.videoId,i>0)},0,0);controller.prepare();controller.play()}
                await("Native fixture queue ready",timeout=60000) {
                    android.util.Log.d("YonoQa","native=${controller.playbackState}/${controller.isPlaying}/${controller.mediaItemCount} ui=${vm.playback.state.value.ready}/${vm.playback.state.value.queue.size}")
                    controller.isPlaying && controller.mediaItemCount==31 && vm.playback.state.value.queue.size==31
                }
                main {controller.seekTo(5000)};await("Fixture seek") {controller.currentPosition>=4900}
                var position=0L;main {position=controller.currentPosition}
                val fresh=songs.first().copy(artwork="https://i.ytimg.com/fixture/updated-2048.png",artworkCandidates=listOf(ArtworkCandidate("https://i.ytimg.com/fixture/updated-2048.png",2048,2048)))
                disk.put("${fresh.id}|${ArtworkPolicy.hash(fresh.artwork)}",encoded)
                main {app.streams.metadata.value=app.streams.metadata.value+mapOf(fresh.id to fresh)}
                await("Artwork metadata published without reset") {controller.mediaMetadata.artworkUri?.toString()==fresh.artwork && controller.currentPosition>=position && controller.isPlaying}
                main {assertEquals(31,controller.mediaItemCount);assertEquals(Player.REPEAT_MODE_OFF,controller.repeatMode);assertFalse(controller.shuffleModeEnabled);controller.seekToNextMediaItem();controller.seekToNextMediaItem()}
                await("Rapid skip retains track identity") {controller.currentMediaItem?.mediaId==songs[2].id && controller.isPlaying}
                val lateUrl="https://i.ytimg.com/fixture/late-2048.png"
                disk.put("${fresh.id}|${ArtworkPolicy.hash(lateUrl)}",encoded)
                main {app.streams.metadata.value=app.streams.metadata.value+mapOf(fresh.id to fresh.copy(artwork=lateUrl,artworkCandidates=listOf(ArtworkCandidate(lateUrl,2048,2048))))}
                await("Late artwork updates only its original queue item") {controller.getMediaItemAt(0).mediaMetadata.artworkUri?.toString()==lateUrl}
                main {assertEquals(songs[2].id,controller.currentMediaItem!!.mediaId);assertEquals(songs[2].artwork,controller.mediaMetadata.artworkUri.toString())}
                shell("wm size 1080x2160");shell("wm density 480");shell("settings put system font_scale 1.0")
                capture("Home_360_Fixture")
                tab("Koleksi");capture("Collection_360_Fixture");tab("Beranda")
                shell("wm size 1290x2160");app.preferences.theme("Light");capture("Home_430_Fixture")
                shell("wm size 1080x2160");app.preferences.theme("Dark");shell("settings put system font_scale 1.5");capture("Home_360_Font150_Fixture")
                assertEquals(before,app.repository.dao.allTracks().size)
                main {assertTrue("Playback survives configuration changes",controller.isPlaying);controller.pause();controller.clearMediaItems()}
            }
        }finally {
            main {controller.release()};app.streamTestDataSource=null
            shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset")
        }
    }
}
