package com.yonzmusic.app

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Temporarily excludes selected sources using the same repository API as Settings; never clears data. */
@RunWith(AndroidJUnit4::class)
class EmptyHomeTest {
 @Test fun emptyHomeUsesUsefulSelectionActionWithoutDeletingCollections():Unit=runBlocking {
  val app=ApplicationProvider.getApplicationContext<YonzApp>();val inst=InstrumentationRegistry.getInstrumentation()
  val sources=app.repository.dao.allSources();val ids=app.repository.dao.allTracks().map{it.id}.toSet()
  fun containsText(node:android.view.accessibility.AccessibilityNodeInfo?,text:String,depth:Int=0):Boolean {
   if(node==null || depth>40)return false
   if(node.text?.toString()?.contains(text)==true)return true
   for(i in 0 until node.childCount)if(containsText(node.getChild(i),text,depth+1))return true
   return false
  }
  try {
   for(command in listOf("input keyevent KEYCODE_WAKEUP","wm dismiss-keyguard")) android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use{it.readBytes()}
   for(source in sources.filter{it.enabled})app.repository.changeSource(source.id,enabled=false)
   assertTrue(app.repository.dao.allTracks().none{it.active})
   inst.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
   inst.waitForIdleSync()
   val deadline=android.os.SystemClock.elapsedRealtime()+60000;var found=false
   while(android.os.SystemClock.elapsedRealtime()<deadline && !found) {
    found=containsText(inst.uiAutomation.rootInActiveWindow,"Mulai dari musikmu")
    if(!found)Thread.sleep(200)
   }
   Thread.sleep(1200)
   val bitmap=inst.uiAutomation.takeScreenshot();assertNotNull(bitmap)
   File(app.filesDir,if(found)"qa13-empty-home.png" else "qa13-empty-failure.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
   assertTrue("Empty Home should explain user-selected sources",found)
   assertTrue(!containsText(inst.uiAutomation.rootInActiveWindow,"Continue listening"))
  } finally {
   for(source in sources.filter{it.enabled})app.repository.changeSource(source.id,enabled=true)
   assertEquals("No collection/index identity may be removed for the empty-state test",ids,app.repository.dao.allTracks().map{it.id}.toSet())
  }
 }
}
