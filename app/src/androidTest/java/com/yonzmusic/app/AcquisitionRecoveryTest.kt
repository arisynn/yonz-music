package com.yonzmusic.app

import android.net.Uri
import android.provider.DocumentsContract as D
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Completed native publications, failure retry and user edits; no external media service. */
@RunWith(AndroidJUnit4::class)
class AcquisitionRecoveryTest {
 @Test fun persistedPlanRetryPreservesOrderUserEditsAndRejectsUnreadablePublication():Unit=runBlocking {
  val app=ApplicationProvider.getApplicationContext<YonzApp>();val dao=app.repository.dao
  val tree=Uri.parse(dao.state("download_destination")!!.value)
  val root=D.buildDocumentUriUsingTree(tree,D.getTreeDocumentId(tree))
  val stamp=java.util.UUID.randomUUID().toString().replace("-","")
  val folder=D.createDocument(app.contentResolver,root,D.Document.MIME_TYPE_DIR,"Retry-$stamp")!!
  fun audio(n:Int):Uri {
   val uri=D.createDocument(app.contentResolver,folder,"audio/wav","owned-$n.wav")!!
   val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("formats/owned-tone.wav").use{it.readBytes()}
   app.contentResolver.openOutputStream(uri,"wt")!!.use{it.write(bytes)};return uri
  }
  val first=audio(1);val second=audio(2);val manual=audio(3)
  fun row(n:Int,uri:Uri,status:String)=JSONObject().put("id","$stamp-$n").put("uri",uri.toString()).put("status",status)
   .put("song",JSONObject().put("title","Owned $n").put("artists",JSONArray(listOf("Karisyono QA","Second credit"))).put("album","").put("source_url","https://example.test/owned-$n.wav"))
  val rows=JSONArray().put(row(1,first,"failed")).put(row(2,second,"downloaded"))
   .put(row(4,Uri.parse("content://com.android.externalstorage.documents/document/primary%3AYono13DoesNotExist.wav"),"downloaded"))
  val plan=JSONObject().put("id",stamp).put("name","Retry proof $stamp").put("entries",rows)
  app.acquisition.importResult(plan)
  val playlist=dao.state("engine-playlist:$stamp")!!.value.toLong()
  val secondId=dao.entries(playlist).single().trackId
  val manualTrack=app.repository.indexDownloaded(manual,DownloadJob("manual-$stamp",stamp,0,playlist,"Retry proof","","Manual","Karisyono QA","","qa:$stamp","","Own fixture",""))
  app.repository.append(playlist,listOf(manualTrack.id))
  rows.getJSONObject(0).put("status","downloaded")
  app.acquisition.importResult(plan)
  val firstId=dao.allTracks().single{it.uri==first.toString()}.id
  assertEquals(listOf(firstId,secondId,manualTrack.id),dao.entries(playlist).map{it.trackId})
  assertEquals("Karisyono QA; Second credit",dao.track(firstId)!!.artist)
  assertEquals("",dao.track(firstId)!!.album)
  app.acquisition.importResult(plan)
  assertEquals("Repeated receipt recovery must not duplicate collection entries",3,dao.entries(playlist).size)
  // Preserve a manual reorder on a subsequent re-import/recovery.
  app.repository.move(playlist,0,1)
  app.acquisition.importResult(plan)
  assertEquals(listOf(secondId,firstId,manualTrack.id),dao.entries(playlist).map{it.trackId})
 }
}
