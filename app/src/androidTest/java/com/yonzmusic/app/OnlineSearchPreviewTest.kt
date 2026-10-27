package com.yonzmusic.app

import android.content.ContentValues
import android.provider.MediaStore
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.Track
import com.yonzmusic.app.search.*
import com.yonzmusic.app.ui.OnlinePreviewSheet
import com.yonzmusic.app.ui.YonzTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class OnlineSearchPreviewTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private fun main(action: ()->Unit)=inst.runOnMainSync(action)
    private fun await(message:String,timeout:Long=45000,check:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end) {var ok=false;main {ok=check()};if(ok)return;Thread.sleep(150)}
        fail(message)
    }
    @Test fun realAppleSearchAndDecodedStreamingPreviewKeepLocalQueueAndDatabase(): Unit=runBlocking {
        assumeTrue("Explicit live integration gate",InstrumentationRegistry.getArguments().getString("liveSearch")=="true")
        val app=ApplicationProvider.getApplicationContext<YonzApp>()
        val metadata=NetworkSearchBackend(SearchSettings(app)).search(SearchProvider.APPLE,"Adele All I Ask")
        val song=metadata.songs.first {it.title=="All I Ask" && it.credit=="Adele" && it.preview.isNotBlank()}
        assertTrue(song.page.startsWith("https://music.apple.com/"))
        val uri=app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME,"Yono_Online_Preview_Original_${System.nanoTime()}.wav")
            put(MediaStore.Audio.Media.MIME_TYPE,"audio/wav");put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/YonoPreviewQA");put(MediaStore.Audio.Media.IS_PENDING,1)
        })!!
        val samples=16000*20
        val b=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
        repeat(samples){i->b.putShort((kotlin.math.sin(i*2.0*Math.PI*220/16000)*800).toInt().toShort())}
        app.contentResolver.openOutputStream(uri)!!.use {it.write(b.array())}
        app.contentResolver.update(uri,ContentValues().apply {put(MediaStore.Audio.Media.IS_PENDING,0)},null,null)
        val track=Track("preview-qa-${System.nanoTime()}",uri.toString(),"Original local preview QA","Karisyono QA",duration=20000,source="instrumentation")
        app.repository.dao.upsert(listOf(track))
        val count=app.repository.dao.allTracks().size
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var vm:MusicViewModel
                scenario.onActivity {activity->vm=ViewModelProvider(activity)[MusicViewModel::class.java]}
                await("Shared local controller connects"){vm.playback.state.value.ready}
                val old=app.preferences.snapshot()
                try {
                    main {vm.playback.play(listOf(track),track.id)}
                    await("Original local WAV plays"){vm.playback.state.value.playing}
                    main {vm.playback.seek(3000)}
                    scenario.onActivity {activity->activity.setContent {YonzTheme("Light"){OnlinePreviewSheet(song,vm,{},{})}}}
                    Thread.sleep(1500)
                    main {vm.onlinePreview.play(song)}
                    await("Live Apple AAC preview must actually play: ${vm.onlinePreview.state.value}",60000){vm.onlinePreview.state.value.playing}
                    assertFalse(vm.playback.state.value.playing)
                    assertEquals(listOf(track.id),vm.playback.state.value.queue)
                    var before=0L;main {before=vm.onlinePreview.position();assertTrue(vm.onlinePreview.duration()>1000)}
                    await("Decoded preview position advances"){vm.onlinePreview.position()>before+400}
                    assertEquals("Remote metadata/preview must not create library tracks",count,app.repository.dao.allTracks().size)
                    main {vm.playback.toggle()}
                    await("Local resume stops preview and retains local seek position"){vm.playback.state.value.playing && vm.onlinePreview.state.value.id==null && vm.playback.position()>=2800}
                    main {vm.playback.pause();vm.onlinePreview.play(song)}
                    await("Preview can be started again"){vm.onlinePreview.state.value.playing}
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    await("Leaving the foreground stops preview without autoplaying local music"){vm.onlinePreview.state.value.id==null && !vm.playback.state.value.playing}
                } finally {
                    main {vm.onlinePreview.stop();vm.playback.pause()}
                    if(old!=null) {
                        val originals=old.ids.mapNotNull {app.repository.dao.track(it)?.takeIf {t->t.available}}
                        if(originals.isNotEmpty())main {vm.playback.play(originals,originals[old.index.coerceIn(originals.indices)].id);vm.playback.pause();vm.playback.seek(old.position)}
                        app.preferences.save(old)
                    }
                }
            }
        }finally {
            app.contentResolver.delete(uri,null,null)
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM history WHERE trackId = ?",arrayOf(track.id))
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM listens WHERE trackId = ?",arrayOf(track.id))
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM tracks WHERE id = ?",arrayOf(track.id))
        }
    }
    @Test fun searchConfigurationHistoryAndProviderIsolationUsePrivateStorage() {
        val app=ApplicationProvider.getApplicationContext<YonzApp>();val settings=SearchSettings(app)
        val previous=settings.key();val enabled=settings.remembersHistory();val history=settings.history()
        try {
            settings.setKey("AIza"+"x".repeat(35));assertTrue(settings.key().isNotBlank())
            settings.clearHistory(true);settings.rememberQuery("Adele All I Ask");settings.rememberQuery("adele all i ask")
            settings.rememberQuery("https://example.test/private?token=not-a-real-token");settings.rememberQuery(settings.key())
            assertEquals(listOf("adele all i ask"),settings.history())
            settings.clearHistory(false);settings.rememberQuery("new search");assertTrue(settings.history().isEmpty())
            settings.setKey("");assertTrue(settings.key().isBlank())
        }finally {settings.setKey(previous);settings.clearHistory(true);history.reversed().forEach(settings::rememberQuery);if(!enabled)settings.clearHistory(false)}
    }
}
