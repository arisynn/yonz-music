package com.yonzmusic.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.engine.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NewEngineBridgeTest {
 @Test fun realDoctorAnd110LocalEntriesThroughChaquopy(): Unit = runBlocking {
  val app = ApplicationProvider.getApplicationContext<YonzApp>()
  val tree = app.repository.dao.state("download_destination")!!.value
  val client = YonoEngineClient(app, SafDocumentStore(app, Uri.parse(tree)), YonoEngineClient.RuntimePaths(quickJs=File(app.applicationInfo.nativeLibraryDir,"libqjs.so")))
  try {
   val doctor = client.begin("doctor").result.await(); assertTrue(doctor.toString(),doctor.getBoolean("ok")); assertTrue(doctor.getJSONObject("result").getBoolean("javascript")); assertFalse(doctor.getJSONObject("result").getBoolean("mp3"))
   val uri=app.repository.dao.allTracks().first { it.active && it.available && it.uri.contains("externalstorage") }.uri
   val rows=JSONArray()
   repeat(110) { i -> rows.put(JSONObject().put("uri",uri+"?fixture="+i).put("title","Fixture $i").put("artists",JSONArray(listOf("Karisyono"))).put("duration",12)) }
   val snapshot=client.begin("snapshot",JSONObject().put("scope","android-fixture").put("rows",rows)).result.await();assertTrue(snapshot.toString(),snapshot.getBoolean("ok"))
   val prepared=client.begin("prepare",JSONObject().put("kind","text").put("content",(0 until 110).joinToString("\n") { "Karisyono - Fixture $it" })).result.await()
   assertTrue(prepared.toString(),prepared.getBoolean("ok"));val result=prepared.getJSONObject("result");assertEquals(110,result.getInt("total"));assertEquals(110,result.getJSONObject("counts").getInt("exists"))
   File(app.filesDir,"new-engine-android-proof.json").writeText(JSONObject().put("doctor",doctor).put("total",110).put("exists",110).put("fixture",true).toString(2))
   client.begin("snapshot",JSONObject().put("scope","android-fixture").put("rows",JSONArray())).result.await()
  } finally { client.close() }
 }
}
