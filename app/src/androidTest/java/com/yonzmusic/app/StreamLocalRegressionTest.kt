package com.yonzmusic.app

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.data.*
import com.yonzmusic.app.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamLocalRegressionTest {
    @Test fun chosenLocalContentSeeksAndContinuesInTheSameBackgroundSession():Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>();val inst=InstrumentationRegistry.getInstrumentation()
        fun main(f:()->Unit)=inst.runOnMainSync(f)
        suspend fun await(f:()->Boolean)=withTimeout(30000){while(true){var yes=false;main{yes=f()};if(yes)break;delay(30)}}
        val saved=app.preferences.snapshot();val id="qa-performance-local";val old=app.repository.dao.track(id)
        val track=Track(id,"content://com.yonzmusic.qa.fixture/document/root%2Faudio","Owned local fixture","QA","Owned",duration=3000,source="qa-local")
        lateinit var controller:PlaybackConnection
        try {
            app.repository.dao.upsert(listOf(track));app.streamTestDataSource=null;app.streamTestResolver=null
            main{controller=PlaybackConnection(app)};await{controller.state.value.ready}
            main{controller.play(listOf(track),id)};await{controller.state.value.playing && controller.position()>100}
            main{controller.seek(1600)};await{controller.state.value.playing && controller.position()>=1650}
            main{controller.seek(200)};await{controller.state.value.playing && controller.position()>=300}
            var before=0L;main{before=controller.position()};delay(450)
            main{assertTrue(controller.state.value.playing);assertTrue(controller.position()>before+150);controller.pause()}
        }finally {
            main{controller.close();app.stopService(Intent(app,PlaybackService::class.java))};delay(300)
            if(old!=null)app.repository.dao.upsert(listOf(old))else app.repository.dao.deleteIndexRow(id)
            if(saved!=null)app.preferences.save(saved)
        }
    }
}
