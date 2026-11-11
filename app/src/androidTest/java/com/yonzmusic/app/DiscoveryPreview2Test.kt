package com.yonzmusic.app

import android.media.MediaExtractor
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.inspector.MediaExtractorCompat
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.*
import com.yonzmusic.app.download.AudioValidation
import com.yonzmusic.app.playback.StreamRepository
import com.yonzmusic.app.playback.CatalogDataSource
import androidx.media3.datasource.*
import com.yonzmusic.app.search.*
import com.yonzmusic.engine.OpusRemuxer
import com.yonzmusic.engine.PythonRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DiscoveryPreview2Test {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),MusicDatabase::class.java)
    private val app get()=ApplicationProvider.getApplicationContext<YonzApp>()
    @Test fun realAndroidOpusRemuxTagsSeekAndOfflinePlayback() {
        val dir=File(app.cacheDir,"preview2-opus-${System.nanoTime()}").apply {mkdirs()}
        var player:ExoPlayer?=null
        try {
            val input=File(dir,"owned.webm")
            InstrumentationRegistry.getInstrumentation().context.assets.open("original-audio/owned.webm").use {a->input.outputStream().use {a.copyTo(it)}}
            val output=OpusRemuxer.remux(app,input)
            assertEquals("opus",AudioValidation.validate(output,5000).extension)
            val builtins=PythonRuntime.module(app,"builtins")
            val globals=builtins.callAttr("dict")
            globals.callAttr("__setitem__","p",output.absolutePath)
            builtins.callAttr("exec","from yono_core.media import tag\nfrom yono_core.model import Song\nfrom mutagen.oggopus import OggOpus\ntag(p,Song('Offline fixture',['QA'],duration=5))\nassert OggOpus(p)['title']==['Offline fixture']\n",globals)
            assertEquals("2026.10.26",PythonRuntime.module(app,"yt_dlp.version").get("__version__").toString())
            val info=AudioValidation.validate(output,5000)
            assertEquals("audio/opus",info.mime)
            val extractor=MediaExtractorCompat(app)
            try {extractor.setDataSource(output.absolutePath);extractor.selectTrack(0);extractor.seekTo(2_000_000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);assertTrue(extractor.sampleTime>=0)}finally{extractor.release()}
            val ready=CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player=ExoPlayer.Builder(app).build().apply {
                    addListener(object:Player.Listener {override fun onIsPlayingChanged(value:Boolean){if(value)ready.countDown()}})
                    setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(output)));seekTo(2000);prepare();play()
                }
            }
            assertTrue("Ogg Opus must decode offline",ready.await(60,TimeUnit.SECONDS))
        }finally {InstrumentationRegistry.getInstrumentation().runOnMainSync {player?.release()};dir.deleteRecursively()}
    }
    @Test fun additiveMigrationRetainsLocalAndOnlineCollections():Unit=runBlocking {
        val name="preview2-migration"
        helper.createDatabase(name,4).apply {
            execSQL("INSERT INTO tracks (id,uri,title,artist,album,folder,fileName,duration,added,artUri,source,available,active) VALUES ('keep','content://fixture/audio','Keep','Artist','Album','','old.webm',5000,1,'','saf',1,1)")
            execSQL("INSERT INTO favorites VALUES ('keep')")
            execSQL("INSERT INTO playlists VALUES (1,'Keep playlist',1)")
            execSQL("INSERT INTO playlist_entries VALUES (1,'keep',0)")
            execSQL("INSERT INTO online_activity VALUES ('abcdefghijk','{}',1,123,1,30000,42)")
            close()
        }
        helper.runMigrationsAndValidate(name,5,true,MusicDatabase.MIGRATION_4_5).close()
        val db=Room.databaseBuilder(app,MusicDatabase::class.java,name).addMigrations(MusicDatabase.MIGRATION_4_5).build()
        try {
            assertEquals("old.webm",db.music().track("keep")!!.fileName)
            assertEquals("keep",db.music().favorites().first().single().trackId)
            assertEquals("keep",db.music().entries(1).single().trackId)
            assertTrue(db.music().onlineActivity("abcdefghijk")!!.favorite)
            db.music().saveCatalog(SavedCatalog("mb:artist:fixture","artist","{}",1))
            assertEquals(1,db.music().savedCatalog().first().size)
        }finally{db.close();app.deleteDatabase(name)}
    }
    @Test fun catalogueIdentityAndSourceMappingSurviveWithoutNetwork():Unit=runBlocking {
        val id="mb:recording:11111111-1111-1111-1111-111111111111"
        val j=JSONObject().put("id",id).put("kind","song").put("title","Owned recording").put("credit","QA").put("duration_ms",5000)
        val song=DiscoveryRepository.parseItem(j).song()
        val mapped=song.copy(videoId="QAowned0001",page="https://www.youtube.com/watch?v=QAowned0001")
        app.repository.dao.catalogCache(CatalogCache("audio:$id",StreamRepository.encode(mapped).toString(),1))
        try {
            app.streams.register(song)
            assertEquals(id,app.streams.lookup(id)!!.id)
            val resolved=app.discovery.audio(song)
            assertEquals("QAowned0001",resolved.videoId);assertEquals(id,resolved.id)
            assertEquals(id,StreamRepository.decode(StreamRepository.encode(resolved)).id)
            val uri=android.net.Uri.parse("content://com.yonzmusic.qa.fixture/document/root%2Faudio")
            val bytes=app.contentResolver.openInputStream(uri)!!.use {it.readBytes()}
            val sha=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val localId="qa-preview2-offline"
            app.repository.dao.upsert(listOf(Track(localId,uri.toString(),"Owned recording","QA","Fixture",fileName="owned.wav",sha256=sha,sourceIdentity="youtube:QAowned0001")))
            val network=object:DataSource by ByteArrayDataSource(ByteArray(1)) {override fun open(spec:DataSpec):Long=throw AssertionError("Offline playback attempted network")}
            val source=CatalogDataSource(app,network)
            try {
                source.open(DataSpec(android.net.Uri.parse("yono-catalog://recording/${android.net.Uri.encode(id)}")))
                val read=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=source.read(buffer,0,buffer.size);if(n<0)break;read.write(buffer,0,n)}
                assertArrayEquals(bytes,read.toByteArray())
            }finally{source.close();app.repository.dao.deleteIndexRow(localId)}
        }finally{app.repository.dao.deleteState("stream:$id")}
    }
}
