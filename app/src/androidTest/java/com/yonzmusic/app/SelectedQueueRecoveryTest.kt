package com.yonzmusic.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.data.AppState
import com.yonzmusic.app.search.*
import com.yonzmusic.engine.EngineWorker
import androidx.work.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectedQueueRecoveryTest {
    private val app get()=ApplicationProvider.getApplicationContext<YonzApp>()
    private val dao get()=app.repository.dao
    private fun song(id:String)=OnlineSong("engine:$id",SearchProvider.ENGINE,"Owned queue recovery fixture","Karisyono QA",page="https://www.youtube.com/watch?v=$id",videoId=id,artistVerified=true)
    private suspend fun settle(check:()->Boolean)=withTimeout(10000){while(!check())delay(50)}
    private fun clear(id:String){for(key in listOf("stream-download:$id","engine-request:$id"))app.database.openHelper.writableDatabase.execSQL("DELETE FROM app_state WHERE name=?",arrayOf(key))}
    @Test fun retryIsDeduplicatedAndStaleAttemptCannotOverwriteNewGeneration():Unit=runBlocking {
        val repo=app.acquisition;val calls=java.util.concurrent.CopyOnWriteArrayList<String>();repo.selectedScheduler={id,_->calls.add(id)}
        val destination=dao.state("download_destination")!!.value;val before=dao.states("stream-download:%").map {it.name}.toSet()
        try {
            repo.enqueueSelected(song("QAqueue1001"),destination,false);settle {calls.size==1};val id=calls.single()
            val old=JSONObject(dao.state("stream-download:$id")!!.value).getString("work_id")
            repo.cancelQueued(id);settle {repo.queuedDownloads.value.any {it.optString("id")==id && it.optString("status")=="cancelled"}}
            calls.clear();repeat(10){repo.retryQueued(id)};settle {calls.isNotEmpty()};delay(300);assertEquals(1,calls.size)
            val newer=JSONObject(dao.state("stream-download:$id")!!.value);assertNotEquals(old,newer.getString("work_id"))
            repo.failQueued(id,"stale failure",old);assertEquals("queued",JSONObject(dao.state("stream-download:$id")!!.value).getString("status"))
            repo.execute(id,old);assertEquals(newer.getString("work_id"),JSONObject(dao.state("stream-download:$id")!!.value).getString("work_id"));assertFalse(repo.busy.value)
            dao.state(AppState("stream-download:$id",newer.put("status","complete").put("cancel_requested",false).toString()))
            repo.cancelQueued(id);delay(300)
            val complete=JSONObject(dao.state("stream-download:$id")!!.value);assertEquals("complete",complete.getString("status"));assertFalse(complete.optBoolean("cancel_requested"))
        }finally {dao.states("stream-download:%").filter {it.name !in before && JSONObject(it.value).optString("source_id")=="QAqueue1001"}.forEach {clear(JSONObject(it.value).getString("id"))};repo.selectedScheduler=null;repo.refreshQueued()}
    }
    @Test fun finishedWorkReconcilesOnlyHashVerifiedPublishedReceiptWithoutChangingCollections():Unit=runBlocking {
        val repo=app.acquisition;val destination=dao.state("download_destination")!!.value
        // Reuse only a pre-existing owned QA fixture; never rewrite/delete its bytes or collection rows.
        val track=dao.allTracks().first {it.available && it.artist.startsWith("Karisyono QA") && it.uri.startsWith("content://")}
        val tracks=dao.allTracks();val oldBinding=dao.state("engine-row:${track.id}")
        val id=java.util.UUID.randomUUID().toString();val planId=java.util.UUID.randomUUID().toString().replace("-","");val source="QAqueue1002"
        val planFile=java.io.File(app.filesDir,"yono-engine/plans/$planId.json");val calls=mutableListOf<String>();repo.selectedScheduler={job,_->calls.add(job)}
        try {
            val job=JSONObject().put("id",id).put("source_id",source).put("song",com.yonzmusic.app.playback.StreamRepository.encode(song(source))).put("destination",destination).put("playlist",false).put("status","cancelled").put("cancel_requested",true).put("created",System.currentTimeMillis()).put("work_id",id)
            dao.state(AppState("stream-download:$id",job.toString()));dao.state(AppState("engine-request:$id",JSONObject().put("action","selected_download").put("payload",JSONObject().put("video_id",source)).put("destination",destination).toString()))
            val node=OneTimeWorkRequestBuilder<EngineWorker>().setId(java.util.UUID.fromString(id)).setInputData(workDataOf("request" to id)).build();val manager=WorkManager.getInstance(app);manager.enqueue(node).result.get()
            withTimeout(30000){while(manager.getWorkInfoById(node.id).get()?.state!=WorkInfo.State.SUCCEEDED)delay(100)}
            val hash=java.security.MessageDigest.getInstance("SHA-256");app.contentResolver.openInputStream(android.net.Uri.parse(track.uri))!!.use {input->val b=ByteArray(262144);while(true){val n=input.read(b);if(n<0)break;hash.update(b,0,n)}}
            val digest=hash.digest().joinToString(""){"%02x".format(it)}
            val receipt=JSONObject().put("id",java.util.UUID.randomUUID().toString().replace("-","")).put("uri",track.uri).put("status","exists").put("audio",JSONObject().put("sha256",digest))
                .put("song",JSONObject().put("title",track.title).put("artists",JSONArray(track.artist.split(';').map {it.trim()})).put("album",track.album).put("youtube_id",source))
            val plan=JSONObject().put("id",planId).put("name","Owned receipt fixture").put("create_playlist",false).put("entries",JSONArray().put(receipt))
            planFile.parentFile!!.mkdirs();planFile.writeText(plan.toString())
            dao.state(AppState("stream-download:$id",job.put("plan_id",planId).put("status","running").put("cancel_requested",false).toString()))
            repo.recoverSelected();assertEquals("complete",JSONObject(dao.state("stream-download:$id")!!.value).getString("status"));assertTrue(calls.isEmpty());assertEquals(tracks,dao.allTracks())
            receipt.getJSONObject("audio").put("sha256","f".repeat(64));planFile.writeText(plan.toString())
            dao.state(AppState("stream-download:$id",job.put("status","running").toString()));repo.recoverSelected()
            assertEquals("failed",JSONObject(dao.state("stream-download:$id")!!.value).getString("status"));assertTrue(calls.isEmpty());assertEquals(tracks,dao.allTracks())
        }finally {
            planFile.delete();clear(id)
            if(oldBinding!=null)dao.state(oldBinding)else app.database.openHelper.writableDatabase.execSQL("DELETE FROM app_state WHERE name=?",arrayOf("engine-row:${track.id}"))
            repo.selectedScheduler=null;repo.refreshQueued()
        }
    }
}
