package com.yonzmusic.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.yonzmusic.app.data.AppState
import com.yonzmusic.engine.SafDocumentStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Generated own audio, real Python/Mutagen + SAF + Room + Media3. No live transfer claim. */
@RunWith(AndroidJUnit4::class)
class OriginalArtworkTest {
 @Test fun originalWebmAndOpusWithCompanionCoversRetainAfterRescan(): Unit = runBlocking {
  val inst=InstrumentationRegistry.getInstrumentation()
  val app=ApplicationProvider.getApplicationContext<YonzApp>()
  val dao=app.repository.dao
  val destination=dao.state("download_destination")!!.value
  val store=SafDocumentStore(app,Uri.parse(destination))
  val seeds=File(app.cacheDir,"own-original-fixtures").apply { mkdirs() }
  for(name in listOf("owned.webm","owned.opus","cover.png")) {
   inst.context.assets.open("original-audio/$name").use { input -> File(seeds,name).outputStream().use { input.copyTo(it) } }
  }
  if(!Python.isStarted()) Python.start(com.chaquo.python.android.AndroidPlatform(app))
  val py=Python.getInstance();val globals=py.getModule("builtins").callAttr("dict")
  globals.callAttr("__setitem__","store",store);globals.callAttr("__setitem__","state_dir",File(app.filesDir,"yono-engine").path);globals.callAttr("__setitem__","seed_dir",seeds.path)
  py.getModule("builtins").callAttr("exec", """
import json, shutil, uuid
from pathlib import Path
from yono_core.control import Control
from yono_core.index import LibraryIndex
from yono_core.engine import Engine
from yono_core.media import Media
from yono_core.model import Song
root=Path(state_dir); seed=Path(seed_dir); proof=[]
for ext in ['webm','opus']:
    token=Control('fixture-'+ext)
    song=Song('Owned tone '+ext+' '+uuid.uuid4().hex[:6],['Yono original fixture'],5,source_url='https://example.test/owned.'+ext+'?fixture='+uuid.uuid4().hex,direct_audio=True)
    class Source:
        def load(self,request): return {'name':'Original artwork fixture','count':1,'entries':[{'song':song.to_dict()}],'completeness':'complete'}
    class Catalogue:
        def resolve(self,value): return value, {'direct':True}
    media=Media(token,host=store)
    def transfer(value,workspace):
        target=workspace/('source.'+ext); shutil.copyfile(seed/('owned.'+ext),target); return target
    media.transfer=transfer
    media.artwork=lambda value: (seed/'cover.png').read_bytes()
    index=LibraryIndex(root/'acquisition.sqlite3',store.exists,store.matches)
    try:
        engine=Engine(root,index,Source(),Catalogue(),media,store,token)
        plan=engine.prepare({})
        result=engine.acquire(plan['id'],[plan['entries'][0]['id']])
        row=result['entries'][0]
        assert row['status']=='downloaded', row
        assert row['audio']['extension']==ext
        assert row['audio']['artwork_embedded']==(ext=='opus')
        assert row.get('artwork_uri')
        proof.append(result)
    finally: index.close()
proof_json=json.dumps(proof)
""".trimIndent(),globals)
  val plans=org.json.JSONArray(globals.callAttr("__getitem__","proof_json").toString())
  val tracks=mutableListOf<com.yonzmusic.app.data.Track>()
  for(i in 0 until plans.length()) {
   val plan=plans.getJSONObject(i)
   dao.state(AppState("engine-consent:${plan.getString("id")}","1"))
   val request="art-test-${java.util.UUID.randomUUID()}"
   dao.state(AppState("engine-request:$request",JSONObject().put("action","get_plan").put("destination",destination).put("payload",JSONObject().put("plan_id",plan.getString("id"))).toString()))
   app.acquisition.execute(request)
   assertEquals("Selesai",app.acquisition.message.value)
   val row=plan.getJSONArray("entries").getJSONObject(0)
   val track=dao.allTracks().single { it.uri==row.getString("uri") }
   assertEquals(row.getString("artwork_uri"),track.artUri)
   assertTrue(track.fileName.endsWith(if(i==0) ".webm" else ".opus"))
   val image=app.contentResolver.openInputStream(Uri.parse(track.artUri))!!.use { BitmapFactory.decodeStream(it) }
   assertNotNull("Companion artwork must decode",image);assertEquals(128,image.width)
   tracks+=track
  }
  // Explicit rescan and then a new Room read must retain the WebM companion binding.
  app.repository.scan()
  for(track in tracks) {
   val restored=dao.track(track.id)!!
   assertEquals(track.artUri,restored.artUri)
   assertEquals(track.title,restored.title);assertEquals(track.artist,restored.artist);assertEquals(track.album,restored.album)
  }
  app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  for(track in tracks) {
   var player:ExoPlayer?=null
   inst.runOnMainSync { player=ExoPlayer.Builder(app).build().apply { setMediaItem(MediaItem.fromUri(track.uri));repeatMode=androidx.media3.common.Player.REPEAT_MODE_ONE;prepare();play() } }
   try {
    var playing=false;var details="";val end=SystemClock.elapsedRealtime()+60000
    while(SystemClock.elapsedRealtime()<end && !playing) {
     inst.runOnMainSync { playing=player!!.isPlaying;details="state=${player!!.playbackState} play=${player!!.playWhenReady} error=${player!!.playerError}" };Thread.sleep(100)
    }
    assertTrue("Original ${track.fileName} should play: $details",playing)
   } finally { inst.runOnMainSync { player!!.release() } }
  }
  File(app.filesDir,"original-artwork-proof.json").writeText(JSONObject().put("fixture",true).put("live_download",false).put("plans",plans).put("native_playback",true).put("artwork_decodes",true).put("rescan_retained",true).toString(2))
 }
}
