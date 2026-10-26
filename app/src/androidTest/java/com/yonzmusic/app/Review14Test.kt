package com.yonzmusic.app

import android.net.Uri
import android.provider.DocumentsContract as D
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.*
import com.yonzmusic.engine.SafDocumentStore
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class Review14Test {
 private val app get()=ApplicationProvider.getApplicationContext<YonzApp>()
 private fun asset(name:String)=InstrumentationRegistry.getInstrumentation().context.assets.open(name).use{it.readBytes()}
 private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
 @Test fun deletedFilesAndFoldersDisappearAfterSyncAndReturnWithCollectionIdentity():Unit=runBlocking {
  val tree=Uri.parse(app.repository.dao.state("download_destination")!!.value)
  val root=D.buildDocumentUriUsingTree(tree,D.getTreeDocumentId(tree))
  val folder=D.createDocument(app.contentResolver,root,D.Document.MIME_TYPE_DIR,"DeleteQA-${System.nanoTime()}")!!
  val db=Room.inMemoryDatabaseBuilder(app,MusicDatabase::class.java).build()
  try {
   val repo=Repository(app,db);val dao=repo.dao
   fun create(name:String):Uri {
    val file=D.createDocument(app.contentResolver,folder,"audio/wav",name)!!
    app.contentResolver.openOutputStream(file,"w")!!.use{it.write(asset("formats/owned-tone.wav"))};return file
   }
   val first=create("own.wav");repo.importFolder(folder)
   val track=dao.allTracks().single();val playlist=repo.create("Deletion QA",listOf(track.id))
   dao.favorite(Favorite(track.id));dao.remember(History(track.id,123,456))
   assertTrue(D.deleteDocument(app.contentResolver,first));repo.scan()
   assertFalse(dao.track(track.id)!!.active);assertEquals(Availability.MISSING,dao.track(track.id)!!.availability)
   assertNotNull(dao.favoriteRow(track.id));assertEquals(track.id,dao.entries(playlist).single().trackId)
   val restored=create("renamed.wav");repo.scan()
   assertTrue(dao.track(track.id)!!.active);assertEquals(restored.toString(),dao.track(track.id)!!.uri)
   assertEquals(456L,dao.historyRow(track.id)!!.position)
   assertTrue(D.deleteDocument(app.contentResolver,folder));repo.scan()
   assertFalse(dao.track(track.id)!!.active)
   // Typed permission/storage failures remain visible for reconnection; not deletion evidence.
   val location=dao.locations().first {it.uri==restored.toString()}
   dao.locations(listOf(location.copy(available=false,status=Availability.PERMISSION)));repo.refreshVisibility()
   assertTrue(dao.track(track.id)!!.active)
   dao.locations(listOf(location.copy(available=false,status=Availability.STORAGE)));repo.refreshVisibility()
   assertTrue(dao.track(track.id)!!.active)
  } finally {db.close()}
 }
 @Test fun hiddenCoverOriginalWebmManifestReimportsWithoutRecursiveAudioScan():Unit=runBlocking {
  val tree=Uri.parse(app.repository.dao.state("download_destination")!!.value)
  val parent=D.buildDocumentUriUsingTree(tree,D.getTreeDocumentId(tree));val stamp=System.nanoTime().toString()
  val folder=parent
  val selected=tree // Keep the exact tree URI granted by the real picker; do not manufacture a child grant.
  val store=SafDocumentStore(app,selected)
  val work=File(app.filesDir,"yono-engine/work/test14-$stamp").apply{mkdirs()}
  val audio=File(work,"owned.webm").apply{writeBytes(asset("original-audio/owned-relocation.webm"))}
  val art=File(work,"cover.png").apply{writeBytes(asset("original-audio/cover.png"))}
  val audioUri=Uri.parse(store.commit(audio.path,"","Owner - Original-$stamp.webm","audio/webm","a-$stamp",hash(audio.readBytes())))
  val artUri=Uri.parse(store.commitCover(art.path,"","Owner - Original-$stamp.cover.png","image/png","c-$stamp",hash(art.readBytes())))
  assertEquals(D.getDocumentId(folder),D.getDocumentId(audioUri).substringBeforeLast('/'))
  assertEquals(D.getDocumentId(folder)+"/.cover",D.getDocumentId(artUri).substringBeforeLast('/'))
  assertTrue(store.exists(D.buildDocumentUriUsingTree(selected,D.getDocumentId(folder)+"/.cover/.nomedia").toString()))
  assertFalse(store.exists(D.buildDocumentUriUsingTree(selected,D.getDocumentId(folder)+"/.nomedia").toString()))
  val track=Track("unused",audioUri.toString(),"Original recording",artist="Owner; Second credit",album="",duration=5000,sha256=hash(audio.readBytes()),artUri=artUri.toString(),metadataOrigin="source")
  val portable=PortableMetadata(app)
  val json=portable.encode(track,".cover/Owner - Original-$stamp.cover.png",hash(art.readBytes()))
  assertNotNull(portable.parse(json))
  val manifest=File(work,"meta.json").apply{writeText(json)}
  store.commit(manifest.path,"","Owner - Original-$stamp.webm.yono.json","application/json","m-$stamp",hash(manifest.readBytes()))
  val db=Room.inMemoryDatabaseBuilder(app,MusicDatabase::class.java).build()
  try {
   val repo=Repository(app,db);repo.importFolder(selected)
   val source=repo.dao.allSources().single()
   repo.changeSource(source.id,recursive=false)
   val imported=repo.dao.allTracks().single {it.uri==audioUri.toString()}
   assertEquals("Original recording",imported.title)
   assertEquals("Owner; Second credit",imported.artist)
   assertEquals(artUri.toString(),imported.artUri)
   assertNotNull(com.yonzmusic.app.ui.ArtCache.load(app,imported,128))
   assertEquals(hash(audio.readBytes()),hash(app.contentResolver.openInputStream(audioUri)!!.use{it.readBytes()}))
   val bad=JSONObject(json);bad.getJSONObject("artwork").put("file_name",".cover/../../other.jpg")
   assertNull(portable.parse(bad.toString()))
   bad.getJSONObject("artwork").put("file_name","other/path.jpg");assertNull(portable.parse(bad.toString()))
  } finally {db.close()}
 }
 @Test fun optionalPlaylistHonorsAcceptedSubsetAndDoesNotCreateEmptyCollection():Unit=runBlocking {
  val tree=Uri.parse(app.repository.dao.state("download_destination")!!.value)
  val root=D.buildDocumentUriUsingTree(tree,D.getTreeDocumentId(tree));val stamp=java.util.UUID.randomUUID().toString().replace("-","")
  fun row(n:Int):JSONObject {
   val uri=D.createDocument(app.contentResolver,root,"audio/wav","Yono14-$stamp-$n.wav")!!
   app.contentResolver.openOutputStream(uri,"w")!!.use{it.write(asset("formats/owned-tone.wav"))}
   return JSONObject().put("id","$stamp-$n").put("status","downloaded").put("uri",uri.toString()).put("song",JSONObject().put("title","Own $n").put("artists",JSONArray(listOf("Owner"))))
  }
  val rows=JSONArray().put(row(1)).put(row(2));val id=rows.getJSONObject(0).getString("id")
  val plan=JSONObject().put("id",stamp).put("name","Optional playlist QA").put("create_playlist",false).put("accepted_ids",JSONArray(listOf(id))).put("entries",rows)
  app.acquisition.importResult(plan)
  assertNull(app.repository.dao.state("engine-playlist:$stamp"))
  assertNotNull(app.repository.dao.allTracks().find {it.uri==rows.getJSONObject(0).getString("uri")})
  assertNull(app.repository.dao.allTracks().find {it.uri==rows.getJSONObject(1).getString("uri")})
  plan.put("create_playlist",true);app.acquisition.importResult(plan)
  val playlist=app.repository.dao.state("engine-playlist:$stamp")!!.value.toLong()
  assertEquals(1,app.repository.dao.entries(playlist).size)
  app.acquisition.importResult(plan);assertEquals(1,app.repository.dao.entries(playlist).size)
 }
 @Test fun oauthUsesEncryptedSingleUseStateAndRejectsUnsolicitedCallback() {
  val connection=app.spotify;connection.disconnect()
  val intent=connection.begin("0123456789abcdef0123456789abcdef")
  assertEquals("accounts.spotify.com",intent.data!!.host)
  assertEquals("S256",intent.data!!.getQueryParameter("code_challenge_method"))
  assertEquals(connection.redirect,intent.data!!.getQueryParameter("redirect_uri"))
  val state=intent.data!!.getQueryParameter("state")!!
  val stored=File(app.noBackupFilesDir,"spotify-oauth.enc").readText()
  assertFalse(stored.contains(state));assertFalse(stored.contains("0123456789abcdef0123456789abcdef"))
  assertFalse(connection.callback(Uri.parse("https://example.test/spotify?state=$state")))
  assertTrue(connection.callback(Uri.parse(connection.redirect+"?state=wrong&code=wrong")))
  assertTrue(connection.message.value.contains("tidak cocok"))
  assertTrue(connection.callback(Uri.parse(connection.redirect+"?state=$state&error=access_denied")))
  assertTrue(connection.message.value.contains("dibatalkan"))
  assertTrue(connection.callback(Uri.parse(connection.redirect+"?state=$state&error=access_denied")))
  assertTrue(connection.message.value.contains("tidak cocok"))
  connection.disconnect()
 }
}
