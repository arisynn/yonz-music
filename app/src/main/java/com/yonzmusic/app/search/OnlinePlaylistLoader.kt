package com.yonzmusic.app.search

import androidx.annotation.Keep
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.data.*
import com.yonzmusic.app.network.AndroidNetworkContext
import com.yonzmusic.engine.PythonRuntime
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

data class OnlinePlaylistPreview(val collection:OnlineCollection,val total:Int,val unavailable:Int,val duplicates:Int)
class OnlinePlaylistLoader(private val app:YonzApp) {
    private companion object {val workers=Dispatchers.IO.limitedParallelism(1)}
    @Keep class Cancellation { @Keep fun isCancelled()=Thread.currentThread().isInterrupted;@Keep fun onEvent(event:String){} }
    suspend fun read(value:String):OnlinePlaylistPreview=withTimeout(45000) {
        val caller=currentCoroutineContext()
        val canonical=OnlineCollectionContract.playlistUrl(value)
        runInterruptible(workers) {
            val config=JSONObject().put("job_id",UUID.randomUUID().toString()).put("network",AndroidNetworkContext.options(app,canonical))
            val raw=PythonRuntime.module(app,"yono_online_playlist").callAttr("execute",JSONObject().put("url",canonical).toString(),config.toString(),Cancellation()).toString()
            caller.ensureActive() // Cancellation wins over the engine's sanitized CANCELLED response.
            require(raw.toByteArray().size<=2*1024*1024){"Metadata playlist terlalu besar."}
            val result=JSONObject(raw)
            if(!result.optBoolean("ok"))throw SearchFailure(result.optJSONObject("error")?.optString("message") ?: "Playlist belum dapat dibaca.")
            require(result.getString("origin")==canonical)
            val rows=result.getJSONArray("songs");require(rows.length() in 1..OnlineCollectionContract.MAX_SONGS)
            val songs=(0 until rows.length()).map {i->
                SearchContract.engine(JSONObject().put("ok",true).put("songs",JSONArray().put(rows.getJSONObject(i))).toString()).songs.single()
            }
            val collection=OnlineCollectionContract.validate(OnlineCollection(UUID.randomUUID().toString(),result.getString("name"),songs,canonical))
            OnlinePlaylistPreview(collection,result.getInt("total"),result.optInt("unavailable"),result.optInt("duplicates"))
        }
    }
}
