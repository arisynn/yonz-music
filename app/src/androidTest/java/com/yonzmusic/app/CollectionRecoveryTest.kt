package com.yonzmusic.app

import android.content.*
import android.net.Uri
import android.provider.DocumentsContract as D
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.yonzmusic.app.data.*
import com.yonzmusic.app.ui.ArtCache
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import android.graphics.Bitmap
import android.graphics.Color

@RunWith(AndroidJUnit4::class)
class CollectionRecoveryTest {
 private val app get()=ApplicationProvider.getApplicationContext<YonzApp>()
 private val resolver get()=app.contentResolver
 private fun hash(uri:Uri):String { val d=MessageDigest.getInstance("SHA-256");resolver.openInputStream(uri)!!.use { input -> val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)} };return d.digest().joinToString(""){"%02x".format(it)} }
 private fun create(parent:Uri,mime:String,name:String,bytes:ByteArray):Uri { val uri=D.createDocument(resolver,parent,mime,name)!!;resolver.openOutputStream(uri,"wt")!!.use{it.write(bytes)};return uri }
 private fun asset(name:String)=InstrumentationRegistry.getInstrumentation().context.assets.open(name).use{it.readBytes()}
 @Test fun realSafRelocationRenamePortableWebmAndCollectionIdentity():Unit=runBlocking {
  val tree=Uri.parse(app.repository.dao.state("download_destination")!!.value)
  val fixtureDb=Room.inMemoryDatabaseBuilder(app,MusicDatabase::class.java).build();val repo=Repository(app,fixtureDb);val dao=repo.dao
  try {
  val root=D.buildDocumentUriUsingTree(tree,D.getTreeDocumentId(tree));val case=D.createDocument(resolver,root,D.Document.MIME_TYPE_DIR,"Yono13Case-${System.nanoTime()}")!!
  val english=D.createDocument(resolver,case,D.Document.MIME_TYPE_DIR,"english")!!;val nested=D.createDocument(resolver,english,D.Document.MIME_TYPE_DIR,"english")!!
  val uri=create(nested,"audio/webm","Karisyono - All I Ask QA.webm",asset("original-audio/owned-relocation.webm"));val digest=hash(uri)
  val image=create(nested,"image/png","cover.png",asset("original-audio/cover.png"))
  var track=repo.indexDownloaded(uri,DownloadJob("test13","test13",0,0,"Own QA","","All I Ask QA","Karisyono; Second credited artist","","own:fixture","https://example.test/owned.webm","Own generated sine","",sha256=digest))
  track=track.copy(artUri=image.toString(),artRevision=1);dao.upsert(listOf(track))
  val manifest=PortableMetadata(app).encode(track,"cover.png",hash(image));val doc=create(nested,"application/json","metadata.yono.json",manifest.toByteArray())
  val playlist=repo.create("Yono13 relocation QA",listOf(track.id));dao.favorite(Favorite(track.id));dao.remember(History(track.id,1234,2345));dao.listen(ListenEvent(trackId=track.id,startedAt=2345,listenedMs=30000))
  repo.importFolder(case)
  val moved=D.moveDocument(resolver,uri,nested,english)!!
  D.moveDocument(resolver,doc,nested,english);D.moveDocument(resolver,image,nested,english)
  assertFalse(runCatching{resolver.openFileDescriptor(uri,"r")?.close();true}.getOrDefault(false))
  repo.scan()
  var restored=dao.track(track.id)!!;assertTrue(restored.available);assertEquals(digest,restored.sha256);assertEquals(moved.toString(),restored.uri)
  assertEquals("All I Ask QA",restored.title);assertEquals("Karisyono; Second credited artist",restored.artist);assertEquals("",restored.album)
  assertEquals(track.id,dao.entries(playlist).single().trackId);assertNotNull(dao.favoriteRow(track.id));assertEquals(2345L,dao.historyRow(track.id)!!.position);assertEquals(track.id,dao.allListens().first{it.startedAt==2345L}.trackId)
  resolver.openInputStream(Uri.parse(restored.artUri))!!.use{assertNotNull(android.graphics.BitmapFactory.decodeStream(it))}
  val renamed=D.renameDocument(resolver,moved,"random-renamed.webm")!!;repo.scan();restored=dao.track(track.id)!!
  assertEquals(renamed.toString(),restored.uri);assertEquals("All I Ask QA",restored.title)
  // A fresh isolated database represents folder reimport, without clearing the installed application's data.
  val isolated=Room.inMemoryDatabaseBuilder(app,MusicDatabase::class.java).build()
  try {
   val repo=Repository(app,isolated);repo.importFolder(case)
   val reimport=repo.dao.allTracks().single{it.sha256==digest}
   assertEquals("All I Ask QA",reimport.title);assertEquals("Karisyono; Second credited artist",reimport.artist);assertTrue(reimport.artUri.isNotBlank())
  } finally { isolated.close() }
  // Same name, different audio bytes: never take over the favorite or its ID.
  val otherFolder=D.createDocument(resolver,case,D.Document.MIME_TYPE_DIR,"different-recording")!!
  val other=create(otherFolder,"audio/webm","random-renamed.webm",asset("original-audio/owned-alt.webm"))
  repo.scan();val otherTrack=dao.allTracks().single{it.uri==other.toString() && it.availability!=Availability.ALIAS}
  assertNotEquals(track.id,otherTrack.id);assertNotEquals(digest,otherTrack.sha256);assertEquals(track.id,dao.entries(playlist).single().trackId)
  // Exact duplicate bytes are another location of the same recording, not a different-name heuristic.
  val duplicate=create(english,"audio/webm","duplicate-copy.webm",asset("original-audio/owned-relocation.webm"));repo.scan()
  assertTrue(dao.locations().any{it.uri==duplicate.toString() && it.trackId==track.id})
  java.io.File(app.filesDir,"collection13-proof.json").writeText(JSONObject().put("saf_move",true).put("rename",true).put("same_id",track.id).put("portable_reimport",true).put("all_artists_retained",true).put("cover_recovered",true).put("same_name_different_audio_separate",true).put("exact_duplicate_location",true).put("playlist_id",playlist).toString(2))
  } finally { fixtureDb.close() }
 }
 @Test fun artworkCacheUsesSizeAndRevisionAndNeverUpscalesTinySource():Unit=runBlocking {
  val tree=Uri.parse(app.repository.dao.state("download_destination")!!.value);val root=D.buildDocumentUriUsingTree(tree,D.getTreeDocumentId(tree))
  val folder=D.createDocument(resolver,root,D.Document.MIME_TYPE_DIR,"Yono13Art-${System.nanoTime()}")!!
  fun image(color:Int,size:Int):ByteArray { val b=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);b.eraseColor(color);val out=java.io.ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle();return out.toByteArray() }
  val uri=create(folder,"image/png","cover.png",image(Color.RED,1024));val track=Track("artqa","content://missing/audio","Artwork QA",artUri=uri.toString(),artRevision=1)
  val thumb=ArtCache.load(app,track,96)!!;val full=ArtCache.load(app,track,900)!!;assertTrue(thumb.width<full.width);assertEquals(1024,full.width)
  resolver.openOutputStream(uri,"wt")!!.use{it.write(image(Color.BLUE,64))}
  val repaired=ArtCache.load(app,track.copy(artRevision=2),900)!!;assertEquals(64,repaired.width);assertEquals(Color.BLUE,repaired.getPixel(10,10))
  val missing=D.buildDocumentUriUsingTree(tree,D.getDocumentId(folder)+"/later.png")
  val first=track.copy(id="late",artUri=missing.toString(),artRevision=1);assertNull(ArtCache.load(app,first,96))
  create(folder,"image/png","later.png",image(Color.GREEN,64));assertNotNull(ArtCache.load(app,first.copy(artRevision=2),96));Thread.sleep(2100);assertNotNull(ArtCache.load(app,first,96))
 }
}
