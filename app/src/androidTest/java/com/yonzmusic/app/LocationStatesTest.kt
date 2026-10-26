package com.yonzmusic.app

import android.net.Uri
import android.provider.DocumentsContract as D
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationStatesTest {
 @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),MusicDatabase::class.java)
 @Test fun schemaTwoToThreeRetainsEveryCollectionReference():Unit=runBlocking {
  val name="yono13-migration-only-fixture";helper.createDatabase(name,2).apply {
   execSQL("INSERT INTO tracks VALUES ('kept','content://fixture/kept','Title','Artist','Album','Music','kept.webm',12000,1,'','chosen',1,1)")
   execSQL("INSERT INTO favorites VALUES ('kept')");execSQL("INSERT INTO history VALUES ('kept',123,456)")
   execSQL("INSERT INTO playlists VALUES (1,'Preserved',1)");execSQL("INSERT INTO playlist_entries VALUES (1,'kept',0)");close()
  }
  helper.runMigrationsAndValidate(name,3,true,MusicDatabase.MIGRATION_2_3).close()
  val context=ApplicationProvider.getApplicationContext<android.content.Context>()
  val db=Room.databaseBuilder(context,MusicDatabase::class.java,name).addMigrations(MusicDatabase.MIGRATION_2_3).build()
  try { val d=db.music();assertNotNull(d.track("kept"));assertNotNull(d.favoriteRow("kept"));assertEquals("kept",d.entries(1).single().trackId);assertEquals(456L,d.historyRow("kept")!!.position) }
  finally { db.close();context.deleteDatabase(name) }
 }
 @Test fun permissionAndStorageFailuresRemainDistinctAndRecoverWithoutLoss():Unit=runBlocking {
  val context=ApplicationProvider.getApplicationContext<android.content.Context>();val resolver=context.contentResolver
  val authority=Uri.parse("content://com.yonzmusic.qa.fixture");fun mode(value:String){resolver.call(authority,"mode",value,null)}
  mode("online");val db=Room.inMemoryDatabaseBuilder(context,MusicDatabase::class.java).build();val repo=Repository(context,db)
  try {
   repo.importFolder(D.buildTreeDocumentUri("com.yonzmusic.qa.fixture","root"));val track=repo.dao.allTracks().single()
   val list=repo.create("Retain on disconnect",listOf(track.id));repo.dao.favorite(Favorite(track.id));repo.dao.remember(History(track.id,12,1234))
   for((setting,expected) in listOf("offline" to Availability.STORAGE,"permission" to Availability.PERMISSION,"missing" to Availability.MISSING)) {
    mode(setting);repo.scan();val row=repo.dao.track(track.id)!!
    assertFalse(row.available);assertEquals(expected,row.availability);assertEquals(track.sha256,row.sha256)
    assertNotNull(repo.dao.favoriteRow(track.id));assertEquals(track.id,repo.dao.entries(list).single().trackId);assertEquals(1234L,repo.dao.historyRow(track.id)!!.position)
    mode("online");repo.scan();assertTrue(repo.dao.track(track.id)!!.available);assertEquals(Availability.AVAILABLE,repo.dao.track(track.id)!!.availability)
   }
  } finally { mode("online");db.close() }
 }
}
