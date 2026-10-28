package com.yonzmusic.app

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.datasource.DataSpec
import com.yonzmusic.engine.PythonRuntime
import com.yonzmusic.app.search.*
import com.yonzmusic.app.playback.*
import com.yonzmusic.app.data.AppState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class StreamingIntegrationTest {
    private val app get()=ApplicationProvider.getApplicationContext<YonzApp>()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private fun main(f:()->Unit)=inst.runOnMainSync(f)
    private fun await(message:String,timeout:Long=30000,f:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end){var yes=false;main {yes=f()};if(yes)return;Thread.sleep(100)};fail(message)
    }
    private fun song(id:String)=OnlineSong("engine:$id",SearchProvider.ENGINE,"Original streaming fixture","Karisyono QA",durationMs=20000,page="https://www.youtube.com/watch?v=$id",videoId=id,artistVerified=true)
    @Test fun oneNativeSessionPlaysRemoteReferenceSeekQueueAndBackgroundWithoutLibraryInsertion():Unit=runBlocking {
        val samples=16000*20;val b=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
        repeat(samples){b.putShort((kotlin.math.sin(it*2*Math.PI*220/16000)*800).toInt().toShort())}
        val count=app.repository.dao.allTracks().size;val saved=app.preferences.snapshot();val theme=app.preferences.flow.first().theme
        val settings=SearchSettings(app);val oldHistory=settings.history();val oldHistoryEnabled=settings.remembersHistory()
        val builtins=PythonRuntime.module(app,"builtins");val globals=builtins.callAttr("dict")
        builtins.callAttr("exec","""
import yono_stream as st, yono_search as ys
old_inspect,old_search=st.inspect_video,ys.run
def fake_inspect(ident,*args,**kwargs):
    return {'url':'https://r1.googlevideo.com/videoplayback?fixture=original','headers':{'User-Agent':'Yono-QA'},'song':{'duration':20}}
def fake_search(payload,control):
    return {'ok':True,'songs':[{'video_id':'QArender001','id':'engine:QArender001','title':'Original streaming fixture','artists':['Karisyono QA'],'duration_ms':20000,'page':'https://www.youtube.com/watch?v=QArender001'}],'next':''}
st.inspect_video,ys.run=fake_inspect,fake_search
""".trimIndent(),globals)
        val bytes=b.array();val ranges=java.util.concurrent.CopyOnWriteArrayList<String>()
        fun transport()=StreamDataSource(app.streams,{url->object:java.net.HttpURLConnection(url) {
            private fun start()=getRequestProperty("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            private fun end()=getRequestProperty("Range")?.substringAfter('-')?.toIntOrNull() ?: (bytes.size-1)
            override fun connect(){connected=true}
            override fun disconnect(){connected=false}
            override fun usingProxy()=false
            override fun getResponseCode()=if(getRequestProperty("Range")!=null)206 else 200
            override fun getContentType()="audio/wav"
            override fun getContentLengthLong()=(end()-start()+1).toLong()
            override fun getInputStream():java.io.InputStream {getRequestProperty("Range")?.let(ranges::add);return java.io.ByteArrayInputStream(bytes,start(),end()-start()+1)}
        }},{arrayOf(java.net.InetAddress.getByAddress(byteArrayOf(8,8,8,8)))})
        try {
            app.streamTestDataSource=androidx.media3.datasource.DataSource.Factory {transport()}
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var vm:MusicViewModel;scenario.onActivity {vm=ViewModelProvider(it)[MusicViewModel::class.java]}
                await("Shared session connects"){vm.playback.state.value.ready}
                main {vm.playOnline(song("QArender001"))}
                await("Remote reference must decode own WAV and play"){vm.playback.state.value.id=="engine:QArender001" && vm.playback.state.value.playing && vm.playback.position()>300}
                assertEquals(count,app.repository.dao.allTracks().size)
                val probe=transport();val offset=44000L
                assertEquals(1024L,probe.open(DataSpec.Builder().setUri("yono-stream://youtube/QArender001").setPosition(offset).setLength(1024).build()))
                val read=ByteArray(1024);assertEquals(1024,probe.read(read,0,1024));assertArrayEquals(bytes.copyOfRange(offset.toInt(),offset.toInt()+1024),read);assertEquals("yono-stream",probe.uri!!.scheme);probe.close();assertTrue(ranges.isNotEmpty())
                assertEquals("stream",vm.playback.state.value.remoteTracks.single().source)
                main {vm.playback.pause()};await("Remote pause is reflected"){!vm.playback.state.value.playing}
                main {vm.playback.toggle()};await("Stream resumes"){vm.playback.state.value.playing}
                main {vm.playback.seek(7000)};await("Remote seek supported by shared player"){vm.playback.position()>=6500}
                main {vm.playOnline(song("QArender002"),false)};await("Remote item joins same queue"){vm.playback.state.value.queue.size==2}
                main {vm.playback.next()};await("Next resolves registered remote identity"){vm.playback.state.value.id=="engine:QArender002" && vm.playback.state.value.playing}
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                await("Foreground media service continues while Activity backgrounded"){vm.playback.state.value.playing}
                main {vm.playback.pause()};await("Pause remains authoritative"){!vm.playback.state.value.playing}
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                println("STREAM_FUNCTIONAL_ASSERTIONS_PASSED: decode/range/seek/queue/next/background/pause; no collection insert")
                main {vm.playback.pause()};app.preferences.theme("Light");await("Light preference reaches UI"){vm.preferences.value.theme=="Light"};delay(3000)
                fun screenshot(name:String){val bitmap=inst.uiAutomation.takeScreenshot();java.io.File(app.cacheDir,name).outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
                screenshot("stream-mini-light.png")
                fun tap(node:android.view.accessibility.AccessibilityNodeInfo) {
                    val r=android.graphics.Rect();node.getBoundsInScreen(r)
                    val now=SystemClock.uptimeMillis()
                    for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                        val event=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,r.exactCenterX(),r.exactCenterY(),0)
                        event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                        assertTrue("Visible UI accepts touch",inst.uiAutomation.injectInputEvent(event,true));event.recycle();Thread.sleep(100)
                    }
                }
                fun texts(node:android.view.accessibility.AccessibilityNodeInfo?,value:String,depth:Int=0):List<android.view.accessibility.AccessibilityNodeInfo> {
                    if(node==null || depth>45)return emptyList()
                    return (if(node.text?.toString()?.contains(value)==true)listOf(node)else emptyList())+(0 until node.childCount).flatMap {texts(node.getChild(it),value,depth+1)}
                }
                inst.waitForIdleSync()
                val nodes=texts(inst.uiAutomation.rootInActiveWindow,"Search")
                assertTrue("Search navigation is visible",nodes.isNotEmpty());tap(nodes.last())
                delay(1000)
                fun hasInput(node:android.view.accessibility.AccessibilityNodeInfo?):Boolean {
                    if(node==null)return false
                    if(node.className?.toString()=="android.widget.EditText")return true
                    return (0 until node.childCount).any {hasInput(node.getChild(it))}
                }
                val inputDeadline=SystemClock.elapsedRealtime()+20000
                while(!hasInput(inst.uiAutomation.rootInActiveWindow) && SystemClock.elapsedRealtime()<inputDeadline)delay(200)
                assertTrue("Actual Search page is mounted",hasInput(inst.uiAutomation.rootInActiveWindow))
                main {vm.searchOnline.value=true;vm.searchQuery.value="Original streaming fixture"}
                await("Actual Search receives scoped metadata fixture"){vm.onlineSearch.state.value.songs.isNotEmpty()}
                delay(1500);screenshot("stream-search-light.png")
                val root=inst.uiAutomation.rootInActiveWindow
                val node=texts(root,"Original streaming fi").maxByOrNull {n->val r=android.graphics.Rect();n.getBoundsInScreen(r);r.top}
                assertNotNull("Remote mini-player visible",node);tap(node!!)
                delay(2000);screenshot("stream-now-playing-light.png")
                fun described(node:android.view.accessibility.AccessibilityNodeInfo?,value:String):android.view.accessibility.AccessibilityNodeInfo? {
                    if(node==null)return null
                    if(node.contentDescription?.toString()==value)return node
                    for(i in 0 until node.childCount)described(node.getChild(i),value)?.let{return it}
                    return null
                }
                var download=described(inst.uiAutomation.rootInActiveWindow,"Simpan offline")
                while(download!=null && !download.isClickable)download=download.parent
                assertNotNull("Shared Now Playing exposes download action",download)
                tap(download!!)
                delay(2000);assertTrue(texts(inst.uiAutomation.rootInActiveWindow,"Simpan untuk nanti").isNotEmpty());screenshot("stream-download-approval-light.png")
                inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);delay(1000)
                app.preferences.theme("Dark");await("Dark preference reaches UI"){vm.preferences.value.theme=="Dark"};delay(2000);screenshot("stream-now-playing-dark.png")

            }
        }finally {
            builtins.callAttr("exec","st.inspect_video,ys.run=old_inspect,old_search",globals)
            app.streamTestDataSource=null
            app.stopService(android.content.Intent(app,PlaybackService::class.java))
            delay(300);app.preferences.theme(theme);settings.clearHistory(true);oldHistory.reversed().forEach(settings::rememberQuery);if(!oldHistoryEnabled)settings.clearHistory(false);if(saved!=null)app.preferences.save(saved)
        }
    }
    @Test fun durableDownloadQueueDeduplicatesRecoversAndCancelsOneWithoutDroppingNext():Unit=runBlocking {
        val repo=app.acquisition;val calls=java.util.concurrent.CopyOnWriteArrayList<String>();repo.selectedScheduler={id,_->calls.add(id)}
        val destination=app.repository.dao.state("download_destination")!!.value
        val before=repo.queuedDownloads.value.map {it.optString("id")}.toSet()
        try {
            repo.enqueueSelected(song("QAqueue0001"),destination,false)
            repo.enqueueSelected(song("QAqueue0001"),destination,false)
            repo.enqueueSelected(song("QAqueue0002"),destination,false)
            withTimeout(10000){while(calls.size<2)delay(50)}
            repo.refreshQueued();val mine=repo.queuedDownloads.value.filter {it.optString("id") !in before && it.optString("source_id").startsWith("QAqueue")}
            assertEquals(2,mine.size);assertEquals(2,calls.size)
            val first=mine.first().getString("id");val second=mine.last().getString("id")
            repo.cancelQueued(first);withTimeout(5000){while(repo.queuedDownloads.value.first {it.getString("id")==first}.optString("status")!="cancelled")delay(30)}
            assertEquals("queued",repo.queuedDownloads.value.first {it.getString("id")==second}.getString("status"))
            calls.clear();repo.recoverSelected();assertEquals(listOf(second),calls.toList())
            repo.cancelQueued(second);withTimeout(5000){while(repo.queuedDownloads.value.first {it.getString("id")==second}.optString("status")!="cancelled")delay(30)}
            val workManager=androidx.work.WorkManager.getInstance(app)
            val nodes=listOf(first,second).map {id->
                val row=org.json.JSONObject(app.repository.dao.state("stream-download:$id")!!.value)
                androidx.work.OneTimeWorkRequestBuilder<com.yonzmusic.engine.EngineWorker>().setId(java.util.UUID.fromString(row.getString("work_id"))).setInputData(androidx.work.workDataOf("request" to id)).build()
            }
            workManager.beginUniqueWork("yono-queue-qa-"+first,androidx.work.ExistingWorkPolicy.KEEP,nodes[0]).then(nodes[1]).enqueue().result.get(5,java.util.concurrent.TimeUnit.SECONDS)
            withTimeout(30000){while(nodes.any {workManager.getWorkInfoById(it.id).get()?.state!=androidx.work.WorkInfo.State.SUCCEEDED})delay(100)}
            assertTrue("Cancelling one durable row must not cancel dependent work nodes",nodes.all {workManager.getWorkInfoById(it.id).get()?.state==androidx.work.WorkInfo.State.SUCCEEDED})
        }finally {
            val rows=app.repository.dao.states("stream-download:%").filter {val j=org.json.JSONObject(it.value);j.optString("source_id").startsWith("QAqueue")}
            for(row in rows){val j=org.json.JSONObject(row.value);app.database.openHelper.writableDatabase.execSQL("DELETE FROM app_state WHERE name = ?",arrayOf(row.name));app.database.openHelper.writableDatabase.execSQL("DELETE FROM app_state WHERE name = ?",arrayOf("engine-request:"+j.getString("id")))}
            repo.selectedScheduler=null;repo.refreshQueued()
        }
    }
}
