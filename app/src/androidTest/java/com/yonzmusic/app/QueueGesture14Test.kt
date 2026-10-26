package com.yonzmusic.app

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.playback.PlaybackConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueGesture14Test {
 @Test fun keyedMoveDuplicateOccurrencesSwipeUndoAndShuffleUseOneSession():Unit=runBlocking {
  val app=ApplicationProvider.getApplicationContext<YonzApp>();val inst=InstrumentationRegistry.getInstrumentation()
  val tracks=app.repository.dao.allTracks().filter {it.active && it.available}.take(3)
  assertTrue("Selected-source fixture music is required",tracks.size>=2)
  app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  lateinit var connection:PlaybackConnection
  inst.runOnMainSync {connection=PlaybackConnection(app)}
  fun await(message:String,condition:()->Boolean) {
   val end=android.os.SystemClock.elapsedRealtime()+45000
   while(android.os.SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(100)}
   fail(message+": "+connection.state.value)
  }
  try {
   await("Controller connects"){connection.state.value.ready}
   inst.runOnMainSync {connection.play(tracks,tracks.first().id)}
   await("Queue set"){connection.state.value.queue.size==tracks.size}
   inst.runOnMainSync {connection.toggle();connection.enqueue(tracks.first(),false)}
   await("Duplicate occurrence retained"){connection.state.value.queue.size==tracks.size+1}
   val before=connection.state.value;assertEquals(before.queue.size,before.queueKeys.toSet().size)
   val first=before.queueKeys.first();val last=before.queueKeys.last()
   inst.runOnMainSync {connection.moveKey(last,first)}
   await("Drag move changes actual session order"){connection.state.value.queueKeys.first()==last}
   assertEquals(first,connection.state.value.queueKeys[1])
   lateinit var removed:PlaybackConnection.RemovedQueueItem
   inst.runOnMainSync {removed=connection.removeKey(last)!!}
   await("Swipe removes one occurrence"){connection.state.value.queue.size==tracks.size}
   assertTrue(first in connection.state.value.queueKeys)
   inst.runOnMainSync {connection.restore(removed)}
   await("Undo restores exactly one"){connection.state.value.queue.size==tracks.size+1}
   inst.runOnMainSync {connection.shuffle()}
   await("Shuffle remains on shared session"){connection.state.value.shuffle}
   val keys=connection.state.value.queueKeys
   inst.runOnMainSync {connection.moveKey(keys.last(),keys.first())}
   Thread.sleep(300)
   assertEquals("Shuffle order is random; reordering waits until shuffle is off",keys,connection.state.value.queueKeys)
   assertTrue(connection.state.value.shuffle)
   inst.runOnMainSync {connection.shuffle()}
   await("Shuffle disabled"){!connection.state.value.shuffle}
   inst.runOnMainSync {connection.moveKey(keys.last(),keys.first())}
   await("Reorder after shuffle updates shared session"){connection.state.value.queueKeys.first()==keys.last()}
   // A new queue must not receive an Undo from an old queue.
   inst.runOnMainSync {connection.play(tracks,tracks.first().id);connection.toggle()}
   await("New queue ready"){connection.state.value.queue.size==tracks.size}
   inst.runOnMainSync {connection.restore(removed)}
   Thread.sleep(500);assertEquals(tracks.size,connection.state.value.queue.size)
  } finally {inst.runOnMainSync {connection.close()}}
 }
}
