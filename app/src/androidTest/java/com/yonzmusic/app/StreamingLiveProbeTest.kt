package com.yonzmusic.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.playback.PlaybackConnection
import com.yonzmusic.app.search.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit real-service test, never silently replaced with a fixture or counted as success on error. */
@RunWith(AndroidJUnit4::class)
class StreamingLiveProbeTest {
    @Test fun realLicensedSourceMustDecodeInTheSharedService():Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveStream")=="true")
        val inst=InstrumentationRegistry.getInstrumentation();val app=ApplicationProvider.getApplicationContext<YonzApp>()
        val saved=app.preferences.snapshot();val old=app.repository.dao.state("stream:aqz-KE-bpKQ")
        val song=OnlineSong("engine:aqz-KE-bpKQ",SearchProvider.ENGINE,"Big Buck Bunny · CC source probe","Blender Foundation",page="https://www.youtube.com/watch?v=aqz-KE-bpKQ",videoId="aqz-KE-bpKQ")
        app.streams.register(song)
        lateinit var connection:PlaybackConnection
        inst.runOnMainSync {connection=PlaybackConnection(app)}
        suspend fun await(check:()->Boolean) {withTimeout(45000){while(true){var yes=false;var failure:String?=null;inst.runOnMainSync {yes=check();if(connection.state.value.id==song.id)failure=connection.state.value.error};if(yes)break;if(failure!=null)fail("LIVE_STREAM_SOURCE_FAILED: $failure");delay(100)}}}
        try {
            await {connection.state.value.ready};inst.runOnMainSync {connection.playRemote(song)}
            await {connection.state.value.playing && connection.position()>300}
            inst.runOnMainSync {assertTrue(connection.duration()>0)};println("LIVE_STREAM_DECODE_SUCCESS")
        }finally {
            inst.runOnMainSync {connection.pause();connection.close()};app.stopService(android.content.Intent(app,com.yonzmusic.app.playback.PlaybackService::class.java));delay(300)
            if(saved!=null)app.preferences.save(saved)
            if(old!=null)app.repository.dao.state(old)else app.database.openHelper.writableDatabase.execSQL("DELETE FROM app_state WHERE name = ?",arrayOf("stream:aqz-KE-bpKQ"))
        }
    }
}
