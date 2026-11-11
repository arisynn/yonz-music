package com.yonzmusic.app

import android.net.Uri
import androidx.media3.datasource.*
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.*
import com.yonzmusic.app.playback.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.yonzmusic.app.search.*
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamingV2Test {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),MusicDatabase::class.java)
    @Test fun queuedListeningBeforeHistoryClearCannotRecreateAnOldEntry(): Unit = runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>()
        val id="QAepoch0001"
        val sourceBefore=app.repository.dao.state("stream:$id")
        val rowBefore=app.repository.dao.onlineActivity(id)
        val song=OnlineSong("engine:$id",SearchProvider.ENGINE,"Owned epoch fixture","Owner",page="https://www.youtube.com/watch?v=$id",videoId=id)
        try {
            app.streams.register(song)
            val queuedToken=app.onlineActivity.historyToken()
            app.onlineActivity.invalidateHistory() // The synchronous boundary used by clear/disable.
            app.onlineActivity.listened(song.id,30000,queuedToken)
            assertEquals(rowBefore,app.repository.dao.onlineActivity(id))
        } finally {
            sourceBefore?.let {app.repository.dao.state(it)} ?: app.database.openHelper.writableDatabase.execSQL("DELETE FROM app_state WHERE name=?",arrayOf("stream:$id"))
        }
    }
    @Test fun stableDatabaseUpgradePreservesEveryCollectionAndAddsOnlineActivity(): Unit = runBlocking {
        val name="streaming-v2-migration"
        helper.createDatabase(name,3).apply {
            execSQL("INSERT INTO tracks (id,uri,title,artist,album,folder,fileName,duration,added,artUri,source,available,active) VALUES ('owned','content://fixture/audio','Owned','Artist','Album','Music','owned.wav',30000,1,'','saf',1,1)")
            execSQL("INSERT INTO favorites VALUES ('owned')")
            execSQL("INSERT INTO history VALUES ('owned',123,456)")
            execSQL("INSERT INTO playlists VALUES (1,'Keep',1)")
            execSQL("INSERT INTO playlist_entries VALUES (1,'owned',0)")
            execSQL("INSERT INTO app_state VALUES ('online-playlist:fixture','unchanged')")
            close()
        }
        helper.runMigrationsAndValidate(name,4,true,MusicDatabase.MIGRATION_3_4).close()
        val context=ApplicationProvider.getApplicationContext<YonzApp>()
        val db=Room.databaseBuilder(context,MusicDatabase::class.java,name).addMigrations(MusicDatabase.MIGRATION_3_4).build()
        try {
            val dao=db.music()
            assertEquals("content://fixture/audio",dao.track("owned")!!.uri)
            assertEquals("owned",dao.favorites().first().single().trackId)
            assertEquals(456L,dao.historyRow("owned")!!.position)
            assertEquals("owned",dao.entries(1).single().trackId)
            assertEquals("unchanged",dao.state("online-playlist:fixture")!!.value)
            dao.onlineActivity(OnlineActivity("abcdefghijk","{}",true,100,2,30000,12))
            dao.clearOnlineHistory();dao.trimOnlineActivity()
            val online=dao.onlineActivity("abcdefghijk")!!
            assertTrue(online.favorite);assertEquals(0L,online.lastPlayed);assertEquals(0L,online.playCount)
            dao.onlineActivity(online.copy(favorite=false));dao.trimOnlineActivity()
            assertNull(dao.onlineActivity("abcdefghijk"))
            assertEquals(1,dao.allTracks().size)
        } finally {db.close();context.deleteDatabase(name)}
    }

    @Test fun diskCacheReusesOnlyMatchingBytesAndHonorsClearAndDisabledMode() {
        val app=ApplicationProvider.getApplicationContext<YonzApp>()
        val directory=File(app.cacheDir,"v2-cache-test-${System.nanoTime()}")
        val opens=AtomicInteger()
        var payload=ByteArray(1024*1024) {(it%251).toByte()}
        var revision="a".repeat(64)
        val resolver=object: StreamResolver {
            override fun invalidate(id: String) {}
            override fun resolve(id: String,cancelled: AtomicBoolean,formatId: String?,timeoutMillis: Long)=
                StreamRepository.Resolved("https://r1.googlevideo.com/audio",emptyMap(),30000,"251","webm","opus",128,revision)
        }
        val cache=StreamAudioCache(app,directory,resolver) {
            DataSource.Factory {
                val delegate=ByteArrayDataSource(payload.copyOf())
                object: DataSource by delegate { override fun open(spec: DataSpec): Long {opens.incrementAndGet();return delegate.open(spec)} }
            }
        }
        fun read(): ByteArray {
            val source=cache.factory().createDataSource()
            return try {
                source.open(DataSpec.Builder().setUri(Uri.parse("yono-stream://youtube/abcdefghijk")).setLength(payload.size.toLong()).build())
                val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {val n=source.read(buffer,0,buffer.size);if(n<0)break;output.write(buffer,0,n)}
                output.toByteArray()
            } finally {source.close()}
        }
        try {
            cache.configure(32)
            assertArrayEquals(payload,read());val first=opens.get()
            assertArrayEquals(payload,read());assertEquals(first,opens.get())
            payload=ByteArray(1024*1024) {42};revision="b".repeat(64)
            assertArrayEquals(payload,read());assertTrue(opens.get()>first)
            cache.refreshSize();assertTrue(cache.bytes.value>0)
            payload=ByteArray(34*1024*1024) {(it%251).toByte()};revision="c".repeat(64)
            val large=cache.factory().createDataSource();var received=0L
            try {
                large.open(DataSpec.Builder().setUri("yono-stream://youtube/abcdefghijk").setLength(payload.size.toLong()).build())
                val buffer=ByteArray(32768)
                while(true) {val n=large.read(buffer,0,buffer.size);if(n<0)break;received+=n}
            } finally {large.close()}
            assertEquals(payload.size.toLong(),received)
            cache.refreshSize();assertTrue("LRU enforces the selected limit",cache.bytes.value<=32L*1024*1024)
            cache.clear();assertEquals(0L,cache.bytes.value)
            payload=ByteArray(1024) {7};revision="d".repeat(64)
            cache.configure(0);val before=opens.get()
            assertArrayEquals(payload,read());assertArrayEquals(payload,read())
            assertEquals(before+2,opens.get());assertEquals(0L,cache.bytes.value)
        } finally {cache.release();directory.deleteRecursively()}
    }

    @Test fun onlineActivityBackupRoundTripAndLegacySchemaKeepFavoritesAndCounts(): Unit = runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>()
        val id="QAback00001"
        val old=app.repository.dao.onlineActivity(id)
        val oldSource=app.repository.dao.state("stream:$id")
        val before=app.repository.dao.playlists().first().map {it.id}.toSet()
        val saved=app.preferences.snapshot()
        val song=OnlineSong("engine:$id",SearchProvider.ENGINE,"Owned backup fixture","Owner",page="https://www.youtube.com/watch?v=$id",videoId=id)
        val uri=Uri.parse("content://com.yonzmusic.qa.fixture/document/root%2Fbackup")
        try {
            app.repository.dao.onlineActivity(OnlineActivity(id,StreamRepository.encode(song).toString(),true,123,5,150000,100))
            CollectionBackup(app).export(uri)
            val text=app.contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readText()}
            assertEquals(2,JSONObject(text).getInt("schemaVersion"))
            assertFalse(text.contains("googlevideo.com"))
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM online_activity WHERE videoId=?",arrayOf(id))
            CollectionBackup(app).restore(uri)
            val restored=app.repository.dao.onlineActivity(id)!!
            assertTrue(restored.favorite);assertEquals(5L,restored.playCount)
            CollectionBackup(app).restore(uri)
            assertEquals(5L,app.repository.dao.onlineActivity(id)!!.playCount)
            val legacy=JSONObject(text).put("schemaVersion",1).apply {remove("onlineActivity")}.toString()
            app.contentResolver.openOutputStream(uri,"wt")!!.bufferedWriter().use {it.write(legacy)}
            CollectionBackup(app).restore(uri)
            assertTrue(app.repository.dao.onlineActivity(id)!!.favorite)
        } finally {
            app.repository.dao.playlists().first().filter {it.id !in before}.forEach {app.database.openHelper.writableDatabase.execSQL("DELETE FROM playlists WHERE id=?",arrayOf(it.id))}
            if(old!=null)app.repository.dao.onlineActivity(old) else app.database.openHelper.writableDatabase.execSQL("DELETE FROM online_activity WHERE videoId=?",arrayOf(id))
            oldSource?.let {app.repository.dao.state(it)} ?: app.database.openHelper.writableDatabase.execSQL("DELETE FROM app_state WHERE name=?",arrayOf("stream:$id"))
            if(saved!=null)app.preferences.save(saved)
        }
    }
}
