package com.yonzmusic.app

import android.content.*
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract as D
import androidx.media3.common.Player
import androidx.media3.session.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.*
import com.yonzmusic.app.playback.*
import com.yonzmusic.engine.SafDocumentStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QueueRelocationTest {
 private fun ownWave():ByteArray {
  val rate=16000;val samples=rate*12;val b=java.nio.ByteBuffer.allocate(44+samples*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
  b.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate*2).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
  val nonce=java.util.UUID.randomUUID().toString().toByteArray();repeat(samples){i->b.putShort(if(i<nonce.size)(nonce[i].toInt()*32).toShort() else (kotlin.math.sin(i*2.0*Math.PI*641/rate)*1000).toInt().toShort())};return b.array()
 }
 @Test fun realSessionRebindsPausedQueueUriAndKeepsPosition():Unit=runBlocking {
  val app=ApplicationProvider.getApplicationContext<YonzApp>();val inst=InstrumentationRegistry.getInstrumentation()
  val tree=Uri.parse(app.repository.dao.state("download_destination")!!.value);val root=D.buildDocumentUriUsingTree(tree,D.getTreeDocumentId(tree))
  val folder=D.createDocument(app.contentResolver,root,D.Document.MIME_TYPE_DIR,"Yono13Queue-${System.nanoTime()}")!!
  val nested=D.createDocument(app.contentResolver,folder,D.Document.MIME_TYPE_DIR,"old")!!
  val uri=D.createDocument(app.contentResolver,nested,"audio/wav","own-queue.wav")!!
  app.contentResolver.openOutputStream(uri,"wt")!!.use{it.write(ownWave())}
  val track=app.repository.indexDownloaded(uri,DownloadJob("queueqa","queueqa",0,0,"Queue fixture","","Queue relocation fixture","Karisyono QA","","qa:queue","","Own fixture",""))
  val before=app.preferences.snapshot()
  app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  var future:com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
  inst.runOnMainSync{future=MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()}
  val player=future!!.get(45,TimeUnit.SECONDS)
  fun await(message:String,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+75000;while(SystemClock.elapsedRealtime()<end){var ok=false;inst.runOnMainSync{ok=condition()};if(ok)return;Thread.sleep(100)};var details="";inst.runOnMainSync{details="state=${player.playbackState}, position=${player.currentPosition}, duration=${player.duration}, error=${player.playerError}"};fail("$message: $details")}
  try {
   inst.runOnMainSync{player.pause();player.setMediaItems(listOf(track.mediaItem()),0,3000);player.repeatMode=Player.REPEAT_MODE_OFF;player.prepare()}
   await("Paused queue should be ready at requested position"){player.playbackState==Player.STATE_READY && player.currentPosition>=2800}
   inst.runOnMainSync{player.play()};Thread.sleep(150);inst.runOnMainSync{player.pause()}
   assertTrue("A short tap must not count as a qualified play",app.repository.dao.allListens().none{it.trackId==track.id})
   inst.runOnMainSync{player.seekTo(10000)};Thread.sleep(150)
   assertTrue("Seeking while paused must not count",app.repository.dao.allListens().none{it.trackId==track.id})
   inst.runOnMainSync{player.seekTo(0);player.play()}
   val listenedDeadline=SystemClock.elapsedRealtime()+45000
   while(SystemClock.elapsedRealtime()<listenedDeadline && app.repository.dao.allListens().none{it.trackId==track.id})Thread.sleep(200)
   inst.runOnMainSync{player.pause();player.seekTo(3000)}
   assertEquals("One qualified session must be counted once",1,app.repository.dao.allListens().count{it.trackId==track.id})
   val moved=D.moveDocument(app.contentResolver,uri,nested,folder)!!
   app.repository.importFolder(folder)
   await("MediaSession queue must use reconciled URI"){player.currentMediaItem?.localConfiguration?.uri==moved}
   inst.runOnMainSync{assertEquals(track.id,player.currentMediaItem!!.mediaId);assertFalse(player.playWhenReady);assertTrue(player.currentPosition in 2800..3500)}
   assertEquals(1,app.repository.dao.allListens().count{it.trackId==track.id})
  } finally {
   val saved=before
   if(saved!=null){val restore=saved.ids.mapNotNull{app.repository.dao.track(it)?.takeIf{t->t.available}};inst.runOnMainSync{player.pause();if(restore.isNotEmpty()){player.setMediaItems(restore.map{it.mediaItem()},saved.index.coerceIn(0,restore.lastIndex),saved.position);player.prepare()}};app.preferences.save(saved)}
   inst.runOnMainSync{player.release()}
  }
 }
 @Test fun selectedPlaylistDestinationDoesNotGainDuplicateFolderLayer():Unit=runBlocking {
  val app=ApplicationProvider.getApplicationContext<YonzApp>();val tree=Uri.parse(app.repository.dao.state("download_destination")!!.value)
  val store=SafDocumentStore(app,tree);val root=D.buildDocumentUriUsingTree(tree,D.getTreeDocumentId(tree));val name=store.displayName(root.toString())
  val work=File(app.filesDir,"yono-engine/work/nesting-qa-${System.nanoTime()}").apply{mkdirs()};val source=File(work,"own.wav")
  InstrumentationRegistry.getInstrumentation().context.assets.open("formats/owned-tone.wav").use{input->source.outputStream().use{input.copyTo(it)}}
  val hash=java.security.MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it)}
  val uri=store.commit(source.path,name,"Yono13-nesting-${System.nanoTime()}.wav","audio/wav","nest-${System.nanoTime()}",hash)
  assertEquals(D.getTreeDocumentId(tree),D.getDocumentId(Uri.parse(uri)).substringBeforeLast('/'))
 }
}
