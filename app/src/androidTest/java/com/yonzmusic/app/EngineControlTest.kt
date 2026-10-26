package com.yonzmusic.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.yonzmusic.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual Kotlin -> embedded Python callbacks + SAF; source/transfer replaced with own audio fixtures. */
@RunWith(AndroidJUnit4::class)
class EngineControlTest {
 @Test fun itemCancelRetryAndNewClientRecoverDurablePlan():Unit=runBlocking {
  val app=ApplicationProvider.getApplicationContext<YonzApp>()
  val store=SafDocumentStore(app,Uri.parse(app.repository.dao.state("download_destination")!!.value))
  val source=File(app.cacheDir,"control-own.wav")
  InstrumentationRegistry.getInstrumentation().context.assets.open("formats/owned-tone.wav").use{input->source.outputStream().use{input.copyTo(it)}}
  if(!Python.isStarted())Python.start(AndroidPlatform(app))
  val py=Python.getInstance();val globals=py.getModule("builtins").callAttr("dict")
  globals.callAttr("__setitem__","own_path",source.path)
  py.getModule("builtins").callAttr("exec","""
import yono_engine_api as api, time, uuid
from pathlib import Path
from yono_core.model import Song
saved_classes=(api.Sources,api.Catalogue,api.Media)
fixture_nonce=uuid.uuid4().hex
class Sources:
    def __init__(self,*args,**kw): pass
    def load(self,request):
        return {'name':'Control fixture '+fixture_nonce,'entries':[{'song':Song('Owned '+fixture_nonce+' '+str(i),['Karisyono QA'],3,source_url='https://example.test/'+fixture_nonce+'/'+str(i)+'.wav',direct_audio=True).to_dict()} for i in range(2)],'completeness':'complete'}
class Catalogue:
    def __init__(self,*args,**kw): pass
    def resolve(self,song): return song,{'direct':True}
    def close(self): pass
class Media(saved_classes[2]):
    def transfer(self,song,workspace):
        target=workspace/'own.wav';data=Path(own_path).read_bytes()
        with target.open('wb') as output:
            for offset in range(0,len(data),8192):
                self.control.check();output.write(data[offset:offset+8192])
                self.control.emit('downloading',downloaded_bytes=min(offset+8192,len(data)),total_bytes=len(data))
                time.sleep(.12)
        return target
    def artwork(self,song): return None
api.Sources,api.Catalogue,api.Media=Sources,Catalogue,Media
""".trimIndent(),globals)
  var client=YonoEngineClient(app,store)
  try {
   val prepared=client.begin("prepare",JSONObject().put("kind","text").put("text","fixture")).result.await()
   assertTrue(prepared.toString(),prepared.getBoolean("ok"))
   val plan=prepared.getJSONObject("result");val rows=plan.getJSONArray("entries");val first=rows.getJSONObject(0).getString("id")
   val ids=JSONArray((0 until rows.length()).map{rows.getJSONObject(it).getString("id")})
   val task=client.begin("acquire",JSONObject().put("plan_id",plan.getString("id")).put("entry_ids",ids).put("format","original"))
   var bytesObserved=0L
   val observer=launch { task.events.collect { event -> if(event.optString("stage")=="downloading" && task.currentEntry==first) {bytesObserved=event.optLong("downloaded_bytes");task.cancelEntry(first)} } }
   val result=withTimeout(120000){task.result.await()};observer.cancel()
   assertTrue(result.toString(),result.getBoolean("ok"));assertTrue("Real progress bytes must trigger cancel",bytesObserved>0)
   val completed=result.getJSONObject("result").getJSONArray("entries")
   assertEquals("cancelled",completed.getJSONObject(0).getString("status"));assertEquals("downloaded",completed.getJSONObject(1).getString("status"))
   assertTrue(store.exists(completed.getJSONObject(1).getString("uri")))
   client.close();client=YonoEngineClient(app,store)
   val recovered=client.begin("get_plan",JSONObject().put("plan_id",plan.getString("id"))).result.await().getJSONObject("result")
   assertEquals("cancelled",recovered.getJSONArray("entries").getJSONObject(0).getString("status"))
   val retry=client.begin("acquire",JSONObject().put("plan_id",plan.getString("id")).put("entry_ids",JSONArray(listOf(first))).put("format","original")).result.await()
   assertTrue(retry.toString(),retry.getBoolean("ok"));val row=retry.getJSONObject("result").getJSONArray("entries").getJSONObject(0)
   assertEquals("downloaded",row.getString("status"));assertTrue(store.exists(row.getString("uri")));assertTrue(store.exists(row.getString("metadata_uri")))
  } finally {
   client.close();py.getModule("builtins").callAttr("exec","api.Sources,api.Catalogue,api.Media=saved_classes",globals)
  }
 }
}
