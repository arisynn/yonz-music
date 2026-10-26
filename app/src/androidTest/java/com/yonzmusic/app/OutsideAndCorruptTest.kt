package com.yonzmusic.app

import android.net.Uri
import android.provider.DocumentsContract as D
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutsideAndCorruptTest {
 private val app get()=ApplicationProvider.getApplicationContext<YonzApp>()
 private fun ownWave():ByteArray {
  val rate=16000;val samples=rate*8;val b=java.nio.ByteBuffer.allocate(44+samples*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
  b.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate*2).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
  val f=550+(System.nanoTime()%400).toInt();repeat(samples){ i -> b.putShort((kotlin.math.sin(i*2.0*Math.PI*f/rate)*1000).toInt().toShort()) };return b.array()
 }
 private suspend fun fixture(block:suspend(Repository,Uri,Uri,ByteArray)->Unit) {
  val tree=Uri.parse(app.repository.dao.state("download_destination")!!.value)
  require(D.getTreeDocumentId(tree)=="primary:Music/YonoEngineGate") { "Use the existing QA destination; never manipulate an arbitrary collection path" }
  val root=D.buildDocumentUriUsingTree(tree,D.getTreeDocumentId(tree));val folder=D.createDocument(app.contentResolver,root,D.Document.MIME_TYPE_DIR,"Yono13Outside-${System.nanoTime()}")!!
  val audio=D.createDocument(app.contentResolver,folder,"audio/wav","owned.wav")!!;val bytes=ownWave();app.contentResolver.openOutputStream(audio,"wt")!!.use{it.write(bytes)}
  val db=Room.inMemoryDatabaseBuilder(app,MusicDatabase::class.java).build();val repo=Repository(app,db)
  try{repo.importFolder(folder);block(repo,folder,audio,bytes)}finally{db.close()}
 }
 @Test fun movingOutsideChosenTreeIsMissingNotDamagedAndRestoringReconnects():Unit=runBlocking { fixture { repo,_,uri,_ ->
  val track=repo.dao.allTracks().single();val list=repo.create("Outside retain",listOf(track.id));repo.dao.favorite(Favorite(track.id));repo.dao.remember(History(track.id,123,456))
  val source="/sdcard/"+D.getDocumentId(uri).substringAfter(':');require(source.matches(Regex("/sdcard/Music/YonoEngineGate/Yono13Outside-[0-9]+/owned.wav")))
  val target="/sdcard/Music/Yono13OutsideDestination-${System.nanoTime()}.wav"
  fun shell(command:String){InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use{ p -> android.os.ParcelFileDescriptor.AutoCloseInputStream(p).readBytes() }}
  shell("mv $source $target")
  try {
   assertFalse("Fixture must actually move before scanner validation",runCatching { app.contentResolver.openFileDescriptor(uri,"r")?.use{true} ?: false }.getOrDefault(false))
   repo.scan();val missing=repo.dao.track(track.id)!!;assertFalse(missing.available);assertEquals(Availability.MISSING,missing.availability)
   assertEquals(track.id,repo.dao.entries(list).single().trackId);assertNotNull(repo.dao.favoriteRow(track.id));assertEquals(456L,repo.dao.historyRow(track.id)!!.position)
   assertEquals(1,repo.dao.allTracks().size) // The scanner never indexes the out-of-scope destination.
  } finally {shell("mv $target $source")}
  repo.scan();assertTrue(repo.dao.track(track.id)!!.available)
 } }
 @Test fun unreadableBytesAreDistinctFromMissingAndRecoverAtSameId():Unit=runBlocking { fixture { repo,_,uri,bytes ->
  val track=repo.dao.allTracks().single();val list=repo.create("Corrupt retain",listOf(track.id))
  app.contentResolver.openOutputStream(uri,"wt")!!.use{it.write("own invalid test bytes".toByteArray())}
  try {repo.scan();val row=repo.dao.track(track.id)!!;assertFalse(row.available);assertEquals(Availability.UNREADABLE,row.availability);assertEquals(track.id,repo.dao.entries(list).single().trackId)}
  finally{app.contentResolver.openOutputStream(uri,"wt")!!.use{it.write(bytes)}}
  repo.scan();assertTrue(repo.dao.track(track.id)!!.available);assertEquals(track.sha256,repo.dao.track(track.id)!!.sha256)
 } }
}
