package com.yonzmusic.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.search.OnlinePlaylistLoader
import com.yonzmusic.engine.PythonRuntime
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnlinePlaylistBridgeTest {
    @Test fun actualBridgeReadsExactOrderAndCancellationWithoutDownloading():Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>();val builtins=PythonRuntime.module(app,"builtins");val globals=builtins.callAttr("dict")
        val tracks=app.repository.dao.allTracks()
        builtins.callAttr("exec","""
import yono_online_playlist as op
op._qa_read=op.read
class Fixture:
    def __init__(self,c):self.control=c;self.params={}
    def __enter__(self):return self
    def __exit__(self,*a):pass
    def extract_info(self,url,download=False):
        assert download is False
        if 'PLslow0000000' in url:
            for _ in range(100):self.control.wait(.05)
        return {'_type':'playlist','title':'Owned online fixture','playlist_count':3,'entries':[{'id':'QAlst000001','title':'Fixture one','artists':['Karisyono QA'],'duration':60},{'id':'QAlst000002','title':'Fixture two','channel':'Not an artist','duration':60},None]}
def fixture_read(value,control):return op._qa_read(value,control,lambda c,**opts:Fixture(c))
op.read=fixture_read
""".trimIndent(),globals)
        try {
            val p=OnlinePlaylistLoader(app).read("https://music.youtube.com/playlist?list=PL1234567890&si=tracking")
            assertEquals("Owned online fixture",p.collection.name);assertEquals(3,p.total);assertEquals(1,p.unavailable)
            assertEquals(listOf("QAlst000001","QAlst000002"),p.collection.songs.map{it.videoId});assertFalse(p.collection.songs[1].artistVerified)
            val job=launch{OnlinePlaylistLoader(app).read("https://music.youtube.com/playlist?list=PLslow0000000")};delay(100);job.cancelAndJoin()
            assertTrue(job.isCancelled);assertEquals(tracks,app.repository.dao.allTracks())
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var vm:MusicViewModel
                scenario.onActivity {vm=androidx.lifecycle.ViewModelProvider(it)[MusicViewModel::class.java];vm.readOnlinePlaylist("https://music.youtube.com.evil/playlist?list=PL1234567890")}
                withTimeout(5000){while(vm.onlinePlaylistError.value.isBlank())delay(50)}
                assertFalse("Synchronous validation failure must stop the spinner",vm.onlinePlaylistLoading.value)
                scenario.onActivity {vm.readOnlinePlaylist("https://music.youtube.com/playlist?list=PL1234567890")}
                withTimeout(10000){while(vm.onlinePlaylistPreview.value==null)delay(50)}
                assertEquals(2,vm.onlinePlaylistPreview.value!!.collection.songs.size)
                scenario.onActivity {vm.closeOnlineImport()}
            }
        } finally {builtins.callAttr("exec","op.read=op._qa_read\ndel op._qa_read",globals)}
    }
}
