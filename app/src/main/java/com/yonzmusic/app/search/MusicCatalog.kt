package com.yonzmusic.app.search

import android.content.Context
import androidx.annotation.Keep
import com.yonzmusic.app.network.AndroidNetworkContext
import com.yonzmusic.engine.PythonRuntime
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Small bounded metadata bridge. No audio extraction, cookies, raw logs or arbitrary source hosts. */
class MusicCatalog(context:Context) {
    private val app=context.applicationContext
    @Keep class Callback(private val stop:AtomicBoolean) {
        @Keep fun isCancelled()=stop.get()
        @Keep fun onEvent(json:String) {}
    }
    suspend fun request(payload:JSONObject):JSONObject=suspendCancellableCoroutine { continuation ->
        val stop=AtomicBoolean(false)
        val task=FutureTask<Unit> {
            try {
                if(!continuation.isActive)return@FutureTask
                val config=JSONObject().put("job_id",UUID.randomUUID().toString()).put("timeout_seconds",18)
                    .put("network",AndroidNetworkContext.options(app,"https://music.youtube.com"))
                val module=PythonRuntime.module(app,"yono_discovery")
                if(!continuation.isActive)return@FutureTask
                val raw=module.callAttr("execute",payload.toString(),config.toString(),Callback(stop)).toString()
                require(raw.length<=256*1024)
                val result=JSONObject(raw)
                if(!result.optBoolean("ok"))throw SearchFailure(SearchContract.text(result.optJSONObject("error")?.optString("message").orEmpty()).ifBlank {"Katalog musik belum tersedia."})
                if(continuation.isActive)continuation.resume(result)
            }catch(error:Exception) {
                if(continuation.isActive)continuation.resumeWithException(error as? SearchFailure ?: SearchFailure("Katalog musik belum tersedia. Coba lagi."))
            }
        }
        continuation.invokeOnCancellation {stop.set(true);task.cancel(false);workers.purge();artWorkers.purge()}
        try {(if(payload.optString("operation")=="artwork")artWorkers else workers).execute(task)}catch(_:RejectedExecutionException) {
            if(continuation.isActive)continuation.resumeWithException(SearchFailure("Katalog sedang sibuk. Coba lagi sebentar."))
        }
    }
    suspend fun radio(videoId:String):List<OnlineSong> = SearchContract.engine(request(JSONObject()
        .put("operation","radio").put("video_id",videoId)).toString(),20).songs
    private companion object {
        val artWorkers=ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(4),
            {r->Thread(r,"yono-artwork-metadata").apply {isDaemon=true}})
        val workers=ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(4),
            {r->Thread(r,"yono-music-catalog").apply {isDaemon=true}})
    }
}
