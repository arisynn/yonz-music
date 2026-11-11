package com.yonzmusic.app.search

import androidx.annotation.Keep
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.network.AndroidNetworkContext
import com.yonzmusic.app.playback.StreamWork
import com.yonzmusic.app.playback.StreamTimings
import com.yonzmusic.engine.PythonRuntime
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

interface SourceCatalog {
    suspend fun search(query: String, page: String = ""): SearchPage
    suspend fun related(videoId: String): List<OnlineSong>
}

/** One low-priority metadata worker; active streaming can preempt it through cooperative cancellation. */
class MusicCatalog(private val app: YonzApp): SourceCatalog {
    private val worker=Dispatchers.IO.limitedParallelism(1)
    @Keep class Callback {
        @Keep fun isCancelled() = Thread.currentThread().isInterrupted || StreamWork.urgent
        @Keep fun onEvent(value: String) {}
    }
    override suspend fun search(query: String, page: String): SearchPage = request(JSONObject().put("operation","search").put("query",query).put("page",page.toIntOrNull() ?: 0),12)
    override suspend fun related(videoId: String): List<OnlineSong> = request(JSONObject().put("operation","related").put("video_id",videoId),20).songs
    private suspend fun request(payload: JSONObject, limit: Int): SearchPage = withTimeout(22000) {
        var result: SearchPage?=null
        while(result==null) {
            StreamWork.awaitAudio()
            val raw=runInterruptible(worker) {
                val started=android.os.SystemClock.elapsedRealtime()
                val config=JSONObject().put("job_id",UUID.randomUUID().toString()).put("timeout_seconds",18)
                    .put("network",AndroidNetworkContext.options(app,"https://music.youtube.com"))
                PythonRuntime.module(app,"yono_catalog").callAttr("execute",payload.toString(),config.toString(),Callback()).toString()
                    .also {StreamTimings.record("catalog",android.os.SystemClock.elapsedRealtime()-started)}
            }
            currentCoroutineContext().ensureActive()
            require(raw.toByteArray().size<=1024*1024) { "Metadata katalog terlalu besar." }
            if(JSONObject(raw).optJSONObject("error")?.optString("code")=="CANCELLED") {delay(100);continue}
            result=SearchContract.engine(raw,limit)
        }
        result
    }
}
