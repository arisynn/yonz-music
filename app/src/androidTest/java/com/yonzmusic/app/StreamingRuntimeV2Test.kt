package com.yonzmusic.app

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.*
import com.yonzmusic.app.playback.PlaybackService
import com.yonzmusic.app.search.*
import com.yonzmusic.engine.PythonRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Generated owned WAV through the real service; public-catalog transport is scoped to this test only. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamingRuntimeV2Test {
    @Test fun favoritesQualifiedHistoryAutoplayPauseAndExplicitRadioShareOnePlayer(): Unit = runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>()
        val inst=InstrumentationRegistry.getInstrumentation()
        fun main(block: ()->Unit)=inst.runOnMainSync(block)
        suspend fun await(label: String, check: ()->Boolean) {
            try {withTimeout(90000) {while(true) {var result=false;main {result=check()};if(result)break;delay(100)}}}
            catch(e:TimeoutCancellationException) {throw AssertionError("V2 runtime stage timed out: $label",e)}
            android.util.Log.i("YonoV2Test","PASS $label")
        }
        val ids=(1..4).map {"QAnew"+it.toString().padStart(6,'0')}
        fun song(n: Int)=OnlineSong("engine:${ids[n-1]}",SearchProvider.ENGINE,"Original Yono fixture $n","Karisyono QA",durationMs=20000,
            page="https://www.youtube.com/watch?v=${ids[n-1]}",videoId=ids[n-1],artistVerified=true)
        val settings=app.preferences.flow.first();val snapshot=app.preferences.snapshot()
        val oldSources=ids.associateWith {app.repository.dao.state("stream:$it")}
        val oldActivity=ids.associateWith {app.repository.dao.onlineActivity(it)}
        val tracks=app.repository.dao.allTracks()
        val frames=16000*20;val wav=ByteBuffer.allocate(44+frames*2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36+frames*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(frames*2)
        repeat(frames) {wav.putShort((kotlin.math.sin(it*2*Math.PI*220/16000)*800).toInt().toShort())}
        val builtins=PythonRuntime.module(app,"builtins");val globals=builtins.callAttr("dict")
        builtins.callAttr("exec","""
import yono_catalog as yc
old_v2_run=yc.run
def v2_run(payload,control):
    for _ in range(20):control.wait(.1)
    seed=payload.get('video_id')
    rows=[{'video_id':v,'title':'Original Yono fixture','artists':['Karisyono QA'],'duration_ms':20000,'page':'https://www.youtube.com/watch?v='+v} for v in [seed,'QAnew000003','QAnew000003','QAnew000004'] if v]
    return {'ok':True,'songs':rows,'next':''}
yc.run=v2_run
""".trimIndent(),globals)
        try {
            app.preferences.autoplay(false);app.preferences.onlineHistory(true)
            app.streamTestDataSource=DataSource.Factory {ByteArrayDataSource(wav.array())}
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var vm:MusicViewModel
                scenario.onActivity {vm=ViewModelProvider(it)[MusicViewModel::class.java]}
                await("shared session connected") {vm.playback.state.value.ready}
                main {vm.playOnline(song(1))}
                await("native decoded playback") {vm.playback.state.value.id==song(1).id && vm.playback.state.value.playing && vm.playback.position()>300}
                main {vm.favoriteOnline(song(1))}
                await("favorite reaches Home") {vm.onlineFavorites.value.any {it.id==song(1).id}}
                await("qualified listening reaches history") {vm.onlineHistory.value.any {it.id==song(1).id}}
                main {assertEquals(1,vm.playback.state.value.queue.size)}
                fun screenshot(name: String) {inst.uiAutomation.takeScreenshot()?.let {bitmap->java.io.File(app.cacheDir,name).outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
                screenshot("v2-home.png")
                app.preferences.autoplay(true)
                await("autoplay appends deduplicated related songs") {vm.playback.state.value.queue.size==3}
                main {assertEquals(3,vm.playback.state.value.queue.distinct().size);vm.playback.pause()}
                await("pause remains authoritative") {!vm.playback.state.value.requested}
                app.preferences.autoplay(false)
                main {vm.radio(song(2))}
                await("explicit radio starts even with autoplay disabled") {vm.playback.state.value.id==song(2).id && vm.playback.state.value.playing}
                main {vm.playback.pause()};delay(3000)
                main {assertFalse(vm.playback.state.value.requested);assertEquals(1,vm.playback.state.value.queue.size);vm.playback.toggle()}
                await("explicit radio resumes and extends") {vm.playback.state.value.queue.size==3 && vm.playback.state.value.playing}
                main {vm.playback.pause()}
                assertEquals(tracks,app.repository.dao.allTracks())
            }
        } finally {
            builtins.callAttr("exec","yc.run=old_v2_run",globals)
            main {app.stopService(android.content.Intent(app,PlaybackService::class.java))};delay(500)
            app.streamTestDataSource=null
            app.preferences.autoplay(settings.autoplay);app.preferences.onlineHistory(settings.onlineHistory)
            app.persistenceScope.launch {if(snapshot!=null)app.preferences.save(snapshot)}.join()
            for(id in ids) {
                oldSources[id]?.let {app.repository.dao.state(it)} ?: app.database.openHelper.writableDatabase.execSQL("DELETE FROM app_state WHERE name=?",arrayOf("stream:$id"))
                val old=oldActivity[id]
                if(old!=null)app.repository.dao.onlineActivity(old) else app.database.openHelper.writableDatabase.execSQL("DELETE FROM online_activity WHERE videoId=?",arrayOf(id))
            }
        }
    }
}
