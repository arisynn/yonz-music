package com.yonzmusic.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated debug-app local WAV/artwork fixtures; no real catalogue/network claims. */
@RunWith(AndroidJUnit4::class)
class HomeInteractionTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private fun main(block:()->Unit)=inst.runOnMainSync(block)
    private fun await(label:String,timeout:Long=60000,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end){var ready=false;main {ready=condition()};if(ready)return;Thread.sleep(150)}
        fail(label)
    }
    private fun nodes(text:String):List<AccessibilityNodeInfo> {
        val found=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo?,depth:Int=0) {
            if(n==null || depth>50)return
            if(n.text?.toString()?.contains(text)==true)found+=n
            for(i in 0 until n.childCount)visit(n.getChild(i),depth+1)
        }
        visit(inst.uiAutomation.rootInActiveWindow);return found
    }
    private fun tap(text:String,last:Boolean=false) {
        val end=SystemClock.elapsedRealtime()+30000
        while(SystemClock.elapsedRealtime()<end) {
            val matches=nodes(text);val n=if(last)matches.lastOrNull()else matches.firstOrNull()
            var parent=n
            while(parent!=null && !parent.isClickable)parent=parent.parent
            if(parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true){Thread.sleep(300);return}
            Thread.sleep(200)
        }
        fail("Missing UI action: $text")
    }
    private fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
    @Test fun homeActionsUseExistingSessionAndPreserveLibrary():Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>();val dao=app.repository.dao
        // Run only in the separately installed debug application.
        check(app.packageName.endsWith(".dev"))
        val fixture=File(app.filesDir,"home-qa").apply {mkdirs()};val now=System.currentTimeMillis()
        val authority=Uri.parse("content://com.yonzmusic.qa.fixture")
        app.contentResolver.call(authority,"home-fixture",null,null)
        val audioUri=authority.buildUpon().appendPath("document").appendPath("root/audio").build().toString()
        val tracks=(1..9).map {i->
            val image=File(fixture,"art-$i.png");val b=Bitmap.createBitmap(768,768,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(b);canvas.drawColor(Color.rgb(45+i*13,30+i*6,70+i*9))
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;textSize=72f}
            canvas.drawText("HOME QA",60f,350f,paint);paint.textSize=36f;canvas.drawText("Controlled fixture $i",60f,415f,paint)
            image.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
            Track("home-qa-$i",audioUri,"Fixture $i","QA Artist ${i%4}",album="QA Album ${i%3}",duration=180000,artUri=Uri.fromFile(image).toString(),source="qa",added=now)
        }
        dao.upsert(tracks)
        for(t in tracks) {dao.listen(ListenEvent(trackId=t.id,startedAt=now-1000,listenedMs=60000));dao.listen(ListenEvent(trackId=t.id,startedAt=now-2000,listenedMs=100000));dao.remember(History(t.id,now-tracks.indexOf(t)*1000L))}
        tracks.take(3).forEach {dao.favorite(Favorite(it.id))}
        app.preferences.theme("Dark")
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var vm:MusicViewModel;scenario.onActivity {vm=ViewModelProvider(it)[MusicViewModel::class.java]}
            await("Home local composition and controller ready",120000){vm.home.state.value.ready && vm.home.state.value.daily.isNotEmpty() && vm.playback.state.value.ready}
            val deadline=SystemClock.elapsedRealtime()+60000
            while(nodes("Dengarkan sekarang").isEmpty() && SystemClock.elapsedRealtime()<deadline)Thread.sleep(300)
            assertTrue("Production Home header visible",nodes("Dengarkan sekarang").isNotEmpty())
            // Production UI opens a real mix, and its Play button drives the existing MediaSession.
            shell("input swipe 240 600 240 230 500")
            val mixDeadline=SystemClock.elapsedRealtime()+20000
            while(nodes("Daily Mix 01").isEmpty() && SystemClock.elapsedRealtime()<mixDeadline){shell("input swipe 240 600 240 230 350");Thread.sleep(300)}
            tap("Daily Mix 01");tap("Putar")
            await("Mix Play starts local native playback"){vm.playback.state.value.playing && vm.playback.state.value.queue.size>=3}
            val favorites=vm.home.state.value.favorites
            main {vm.playHome(favorites,shuffle=true)}
            await("Shuffle works through same controller"){vm.playback.state.value.shuffle && vm.playback.state.value.queue.size==3 && vm.playback.state.value.playing}
            val next=vm.home.state.value.quick.first()
            main {vm.playHome(listOf(next),enqueue=true)}
            await("Add to queue preserves playback"){vm.playback.state.value.queue.size==4 && vm.playback.state.value.playing}
            val oldIndex=vm.playback.state.value.index;main {vm.playback.next()}
            await("Next track advances existing queue"){vm.playback.state.value.index!=oldIndex && vm.playback.state.value.playing}
            shell("input keyevent KEYCODE_HOME");Thread.sleep(1500)
            main {assertTrue("Background playback survives leaving Home",vm.playback.state.value.playing)}
            shell("am start -n ${app.packageName}/com.yonzmusic.app.MainActivity");Thread.sleep(1000)
            tap("Cari");assertTrue("Search still navigates",nodes("Koleksi").isNotEmpty())
            tap("Koleksi",last=true);assertTrue("Collection still navigates",nodes("Favorit").isNotEmpty())
            tap("Beranda");shell("input swipe 240 230 240 650 400");Thread.sleep(500)
            assertEquals(9,dao.allTracks().count {it.id.startsWith("home-qa-")})
            assertEquals(3,vm.home.state.value.favorites.count {it.id.startsWith("home-qa-")})
            // Use shell capture: this emulator's UiAutomation.takeScreenshot returns null.
            val captures=File(app.getExternalFilesDir(null),"home-qa").apply {mkdirs()}
            for(theme in listOf("Dark","Light")) {
                app.preferences.theme(theme);Thread.sleep(1200)
                runCatching {val bytes=shell("screencap -p");check(bytes.size>1000 && bytes[0]==(-119).toByte());File(captures,"Home_${theme}_Fixture.png").writeBytes(bytes)}
            }
            main {vm.playback.pause()}
        }
    }
}
