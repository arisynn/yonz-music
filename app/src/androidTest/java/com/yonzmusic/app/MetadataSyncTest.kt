@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.yonzmusic.app

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.metadata.*
import com.yonzmusic.app.network.SafeHttp
import com.yonzmusic.app.playback.*
import com.yonzmusic.app.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MetadataSyncTest {
    private fun image(color:Int)=Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888).let {b->b.eraseColor(color);java.io.ByteArrayOutputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it);b.recycle();it.toByteArray()}}
    @Test fun storedFullMetadataSurvivesLightRegistrationWithoutAnInMemoryEntry():Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>();val id="QAmg0000001";val key="stream:$id";val old=app.repository.dao.state(key)
        val full=OnlineSong("engine:$id",SearchProvider.ENGINE,"Complete title","Credited artist","Verified album",page="https://www.youtube.com/watch?v=$id",videoId=id,artistVerified=true,metadataSource=MetadataSource.EXTRACTION,metadataUpdatedAt=123)
        try {
            assertFalse(app.metadata.state.value.containsKey(full.id))
            app.repository.dao.state(com.yonzmusic.app.data.AppState(key,StreamRepository.encode(full).toString()))
            app.streams.register(full.copy(title="Light title",credit="Generic uploader",album="",artistVerified=false,metadataSource=MetadataSource.SEARCH,metadataUpdatedAt=0))
            val found=app.streams.lookup(full.id)!!
            assertEquals("Credited artist",found.credit);assertEquals("Verified album",found.album);assertEquals("Complete title",found.title)
            val stored=StreamRepository.decode(JSONObject(app.repository.dao.state(key)!!.value))
            assertEquals(found.copy(artworkUri=""),stored)
        }finally {if(old==null)app.repository.dao.deleteState(key)else app.repository.dao.state(old)}
    }
    @Test fun serviceMetadataAndArtworkUpdateInPlaceAndObsoleteArtworkCannotWin():Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>();val inst=InstrumentationRegistry.getInstrumentation()
        fun main(block:()->Unit)=inst.runOnMainSync(block)
        var phase=0
        suspend fun await(check:()->Boolean) {val step=++phase;try {withTimeout(35000){while(true){var yes=false;main {yes=check()};if(yes)break;delay(30)}}}catch(e:TimeoutCancellationException){throw AssertionError("Metadata phase $step timed out",e)}}
        val ids=listOf("QAMD0000001","QAMD0000002")
        val previous=ids.associateWith {app.repository.dao.state("stream:$it")};val preferences=app.preferences.flow.first();val snapshot=app.preferences.snapshot()
        val red=image(Color.RED);val blue=image(Color.BLUE);val calls=AtomicInteger()
        val frames=16000*30;val wav=ByteBuffer.allocate(44+frames*2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36+frames*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(frames*2)
        val songs=ids.mapIndexed {i,id->OnlineSong("engine:$id",SearchProvider.ENGINE,"Search ${if(i==0)"A"else "B"}","QA credited artist",durationMs=30000,artwork="https://i.ytimg.com/vi/$id/${System.nanoTime()}.png",page="https://www.youtube.com/watch?v=$id",videoId=id,artistVerified=true)}
        lateinit var raw:MediaController;lateinit var connection:PlaybackConnection;var rawReady=false;var connectionReady=false
        val opened=AtomicInteger();val platformStats=JSONObject()
        try {
            app.preferences.autoplay(false);app.preferences.onlineHistory(false)
            app.artwork.fetch={url->calls.incrementAndGet();Thread.sleep(if(url.contains("slow"))800 else 120);SafeHttp.Response(200,if(url.contains(ids[1]))blue else red,null,null,"image/png")}
            app.streamTestDataSource=DataSource.Factory {
                val delegate=ByteArrayDataSource(wav.array())
                object:DataSource by delegate {
                    override fun open(spec:androidx.media3.datasource.DataSpec):Long {
                        if(spec.uri.toString()=="yono-stream://youtube/${ids[0]}")opened.incrementAndGet()
                        return delegate.open(spec)
                    }
                }
            }
            main {assertNull("Media3 must not start its own remote artwork request",remoteMediaItem(songs[0]).mediaMetadata.artworkUri)}
            songs.forEach {app.streams.register(it)}
            main{connection=PlaybackConnection(app);connectionReady=true};await {connection.state.value.ready}
            lateinit var future:com.google.common.util.concurrent.ListenableFuture<MediaController>
            main{future=MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()}
            await{future.isDone};main{raw=future.get();rawReady=true;connection.playRemoteList(songs,0)}
            await{raw.currentMediaItem?.mediaId==songs[0].id}
            main {assertEquals("Search A",raw.mediaMetadata.title.toString());assertEquals("QA credited artist",raw.mediaMetadata.artist.toString())}
            await {raw.isPlaying && raw.mediaMetadata.artworkData!=null && connection.position()>100}
            var initialPosition=0L;var initialSource:androidx.media3.common.MediaItem.LocalConfiguration?=null;var initialKeys=emptyList<String>()
            main {raw.pause();raw.seekTo(4500);raw.shuffleModeEnabled=true;raw.repeatMode=Player.REPEAT_MODE_ALL}
            await {raw.playbackState==Player.STATE_READY && raw.currentPosition==4500L && !raw.isLoading}
            delay(300) // Let the paused seek acknowledgement finish before counting metadata-only opens.
            main {initialPosition=raw.currentPosition;initialSource=raw.currentMediaItem!!.localConfiguration;initialKeys=(0 until raw.mediaItemCount).map {raw.getMediaItemAt(it).mediaMetadata.extras!!.getString("yono.queue.key")!!}}
            val beforeOpen=opened.get();val started=android.os.SystemClock.elapsedRealtime()
            app.metadata.extracted(ids[0],JSONObject().put("title","Extracted A").put("album","Owned album").put("artists",JSONArray()).put("uploader","Generic uploader").put("duration",30).put("artwork_url",songs[0].artwork))
            await {raw.mediaMetadata.title.toString()=="Extracted A" && connection.state.value.remoteTracks.first {it.id==songs[0].id}.title=="Extracted A"}
            val updateMs=android.os.SystemClock.elapsedRealtime()-started
            main {
                assertEquals(initialPosition,raw.currentPosition);assertFalse(raw.playWhenReady);assertEquals(initialSource,raw.currentMediaItem!!.localConfiguration)
                assertEquals(initialKeys,(0 until raw.mediaItemCount).map {raw.getMediaItemAt(it).mediaMetadata.extras!!.getString("yono.queue.key")!!})
                assertTrue(raw.shuffleModeEnabled);assertEquals(Player.REPEAT_MODE_ALL,raw.repeatMode);assertEquals("QA credited artist",raw.mediaMetadata.artist.toString())
            }
            val metadataReopens=opened.get()-beforeOpen
            assertEquals("Metadata replacement must not open a new audio source",0,metadataReopens)
            val old=app.metadata.current(songs[0]);app.metadata.publish(old.copy(artwork="https://i.ytimg.com/vi/${ids[0]}/slow-${System.nanoTime()}.png",artworkUri=""))
            delay(100);main{raw.seekTo(1,0);raw.play()}
            await {raw.currentMediaItem?.mediaId==songs[1].id && raw.isPlaying && raw.mediaMetadata.artworkData!=null}
            delay(1000)
            main {
                val data=raw.mediaMetadata.artworkData!!;val bitmap=android.graphics.BitmapFactory.decodeByteArray(data,0,data.size)
                assertTrue(Color.blue(bitmap.getPixel(5,5))>200);assertTrue(Color.red(bitmap.getPixel(5,5))<40);bitmap.recycle()
                assertEquals("Search B",raw.mediaMetadata.title.toString());assertEquals(songs[1].id,connection.state.value.id)
            }
            // A platform controller reads the same session used by Android lock-screen/Bluetooth controls.
            var platform:android.media.session.MediaController?=null
            await {
                val notifications=(app.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager).activeNotifications
                val notification=notifications.firstOrNull {it.notification.extras.containsKey("android.mediaSession")}
                val notificationToken=notification?.notification?.extras?.getParcelable<android.media.session.MediaSession.Token>("android.mediaSession")
                // Read our own session token even if the emulator has suppressed notifications.
                val connected=raw.connectedToken
                val direct=connected?.javaClass?.getDeclaredMethod("getPlatformToken")?.apply {isAccessible=true}?.invoke(connected) as? android.media.session.MediaSession.Token
                val token=notificationToken ?: direct
                if(token!=null)platform=android.media.session.MediaController(app,token)
                val title=platform?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)=="Search B"
                val bitmap=platform?.metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART) ?: platform?.metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART) ?: platform?.metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                platformStats.put("notificationCount",notifications.size).put("notificationToken",notificationToken!=null).put("directToken",direct!=null).put("titleMatches",title).put("artworkBitmap",bitmap!=null).put("notificationIcon",notification?.notification?.getLargeIcon()!=null)
                title && bitmap!=null
            }
            val report=JSONObject().put("liveNetwork",false).put("metadataUpdateMs",updateMs).put("audioSourceReopensOnMetadata",metadataReopens).put("queueAndPositionPreserved",true).put("staleArtworkRejected",true).put("platformSessionArtwork",true).put("physicalBluetooth",false).put("platform",platformStats)
            java.io.File(app.cacheDir,"metadata-sync.json").writeText(report.toString(2))
        }finally {
            main{if(rawReady){
                java.io.File(app.cacheDir,"metadata-stage.json").writeText(JSONObject().put("phase",phase).put("index",raw.currentMediaItemIndex).put("state",raw.playbackState).put("position",raw.currentPosition).put("playerArtworkBytes",raw.mediaMetadata.artworkData?.size ?: 0).put("itemArtworkBytes",raw.currentMediaItem?.mediaMetadata?.artworkData?.size ?: 0).put("fetches",calls.get()).put("platform",platformStats).put("hasLocalUri",raw.currentMediaItem?.mediaMetadata?.artworkUri?.toString()?.let {app.artwork.owns(it)}==true).toString(2))
                raw.pause();raw.release()};if(connectionReady)connection.close();app.stopService(Intent(app,PlaybackService::class.java))};delay(400)
            app.streamTestDataSource=null;app.artwork.fetch=null
            app.preferences.autoplay(preferences.autoplay);app.preferences.onlineHistory(preferences.onlineHistory)
            if(snapshot!=null)app.preferences.save(snapshot)
            app.persistenceScope.launch {}.join()
            previous.forEach {(id,row)->if(row==null)app.repository.dao.deleteState("stream:$id")else app.repository.dao.state(row)}
        }
    }
    @Test fun invalidArtworkAndMimeFailWithoutBlockingAudioAndCacheIsIdentityBound():Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>();val counter=AtomicInteger()
        try {
            app.artwork.fetch={counter.incrementAndGet();SafeHttp.Response(200,image(Color.GREEN),null,null,"text/html")}
            assertNull(app.artwork.resolve("qa-invalid","http://127.0.0.1/art"));assertEquals(0,counter.get())
            assertNull(app.artwork.resolve("qa-mime","https://i.ytimg.com/${System.nanoTime()}.png"));assertEquals(1,counter.get())
            val url="https://i.ytimg.com/${System.nanoTime()}.png"
            app.artwork.fetch={counter.incrementAndGet();SafeHttp.Response(200,image(Color.GREEN),null,null,"image/png")}
            val first=app.artwork.resolve("qa-art-a",url)!!;val second=app.artwork.resolve("qa-art-a",url)!!
            assertEquals(first.uri,second.uri);assertArrayEquals(first.bytes,second.bytes)
            val other=app.artwork.resolve("qa-art-b",url)!!;assertNotEquals(first.uri,other.uri);assertTrue(app.artwork.owns(first.uri))
        }finally{app.artwork.fetch=null}
    }
    @Test fun artworkDiskCacheBoundsCommittedBytesAndRefreshesLruAccess():Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>()
        val directory=java.io.File(app.cacheDir,"qa-art-lru-${System.nanoTime()}")
        val cache=ArtworkRepository(app,directory)
        try {
            cache.fetch={SafeHttp.Response(200,image(Color.GREEN),null,null,"image/png")}
            val first=cache.resolve("qa-recent","https://i.ytimg.com/recent.png")!!
            val recent=java.io.File(android.net.Uri.parse(first.uri).path!!);recent.setLastModified(1)
            directory.mkdirs()
            for(i in 0..100)java.io.File(directory,i.toString().padStart(64,'0')+".jpg").apply {java.io.RandomAccessFile(this,"rw").use {it.setLength(1_000_000)};setLastModified(10L+i)}
            cache.resolve("qa-recent","https://i.ytimg.com/recent.png")!!
            assertNotNull(cache.resolve("qa-new","https://i.ytimg.com/new.png"))
            assertTrue(recent.isFile);assertTrue(directory.listFiles()!!.sumOf {it.length()}<=ArtworkRepository.CAPACITY)
            assertFalse(java.io.File(directory,"0".repeat(64)+".jpg").exists())
            cache.clear();assertTrue(directory.listFiles().orEmpty().isEmpty())
        }finally {directory.deleteRecursively()}
    }
}

