package com.yonzmusic.app

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.yonzmusic.app.search.*
import com.yonzmusic.engine.PythonRuntime
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnlineSearchPreviewTest {
    private val app get()=ApplicationProvider.getApplicationContext<YonzApp>()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val setup="""
import yono_catalog as yc
yc._qa_original_run=yc.run
class CatalogFixture:
    def __init__(self,control):self.control=control
    def close(self):pass
    def call(self,method,query,**kwargs):
        if 'slow fixture' in query:
            for _ in range(200):self.control.wait(.05)
        return [{'videoId':'aqz-KE-bpKQ','title':'Original fixture audio','artists':[{'name':'Karisyono QA'}],'duration_seconds':20}]
def fixture_run(payload,control):
    return yc._qa_original_run(payload,control,CatalogFixture)
yc.run=fixture_run
""".trimIndent()
    @Test fun actualPythonCatalogBridgeUsesReadOnlySearchAndCancellation(): Unit=runBlocking {
        val builtins=PythonRuntime.module(app,"builtins");val globals=builtins.callAttr("dict");builtins.callAttr("exec",setup,globals)
        val count=app.repository.dao.allTracks().size
        try {
            val backend=EngineSearchBackend(app)
            val rows=backend.search(SearchProvider.ENGINE,"Original fixture audio")
            assertEquals("Original fixture audio",rows.songs.single().title)
            assertTrue(rows.songs.single().artistVerified);assertEquals(20000,rows.songs.single().durationMs)
            val old=launch {backend.search(SearchProvider.ENGINE,"slow fixture")}
            delay(100);old.cancelAndJoin()
            val latest=withTimeout(5000){backend.search(SearchProvider.ENGINE,"Original fixture audio")}
            assertEquals(1,latest.songs.size);assertEquals(count,app.repository.dao.allTracks().size)
        }finally {builtins.callAttr("exec","yc.run=yc._qa_original_run\ndel yc._qa_original_run",globals)}
    }
    @Test fun optionalHistoryAndObsoleteKeyRemovalKeepCollections(): Unit=runBlocking {
        val count=app.repository.dao.allTracks().size
        val key=File(app.noBackupFilesDir,"youtube-search-key")
        // Only a fake test marker, never read or expose a previous user credential.
        key.writeText("obsolete-fixture-marker")
        val settings=SearchSettings(app);assertFalse(key.exists())
        val enabled=settings.remembersHistory();val history=settings.history()
        try {
            settings.clearHistory(true);settings.rememberQuery("Original audio");settings.rememberQuery("original audio")
            settings.rememberQuery("https://example.test/private");assertEquals(listOf("original audio"),settings.history())
            settings.clearHistory(false);settings.rememberQuery("another audio");assertTrue(settings.history().isEmpty())
            assertEquals(count,app.repository.dao.allTracks().size)
        }finally {settings.clearHistory(true);history.reversed().forEach(settings::rememberQuery);if(!enabled)settings.clearHistory(false)}
    }
    @Test fun liveEngineSearchReportsActualSourceAvailability(): Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSearch")=="true")
        try {
            val page=withTimeout(25000){EngineSearchBackend(app).search(SearchProvider.ENGINE,"Blender Big Buck Bunny")}
            assertTrue("Live query returned actual metadata",page.songs.isNotEmpty())
            println("LIVE_ENGINE_SEARCH_SUCCESS count=${page.songs.size}")
        }catch(e:SearchFailure) {
            // Recorded as a failure rather than claiming that a blocked network is success.
            fail("LIVE_ENGINE_SEARCH_BLOCKED: ${e.userMessage}")
        }
    }
}
