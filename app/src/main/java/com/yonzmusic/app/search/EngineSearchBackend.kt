package com.yonzmusic.app.search

import android.content.Context
import androidx.annotation.Keep
import com.yonzmusic.engine.PythonRuntime
import com.yonzmusic.app.network.AndroidNetworkContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded search workers are independent from the acquisition queue. Cancellation is cooperative. */
class EngineSearchBackend(context: Context): SearchBackend {
    private val app=context.applicationContext
    @Keep class Callback(private val cancelled: AtomicBoolean) {
        @Keep fun isCancelled()=cancelled.get()
        @Keep fun onEvent(json:String) {} // No raw engine logs or user query history here.
    }
    override suspend fun search(provider:SearchProvider,query:String,page:String):SearchPage=suspendCancellableCoroutine { c->
        val stop=AtomicBoolean(false)
        val task=FutureTask<Unit> {
            try {
                if(!c.isActive)return@FutureTask
                val input=JSONObject().put("query",query).put("page",page.toIntOrNull() ?: 0)
                val config=JSONObject().put("job_id",UUID.randomUUID().toString()).put("timeout_seconds",18)
                    .put("network",AndroidNetworkContext.options(app,"https://www.youtube.com"))
                val module=PythonRuntime.module(app,"yono_search")
                if(!c.isActive)return@FutureTask
                val json=module.callAttr("execute",input.toString(),config.toString(),Callback(stop)).toString()
                val parsed=SearchContract.engine(json)
                if(c.isActive)c.resume(parsed)
            }catch(e:Exception){if(c.isActive)c.resumeWithException(if(e is SearchFailure)e else SearchFailure("Mesin belum dapat dijalankan. Coba lagi sebentar."))}
        }
        c.invokeOnCancellation {stop.set(true);task.cancel(false);workers.purge()}
        try {workers.execute(task)}catch(_:RejectedExecutionException){if(c.isActive)c.resumeWithException(SearchFailure("Permintaan sebelumnya sedang berhenti. Coba lagi sebentar."))}
    }
    private companion object {
        val workers=ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(2),{r->Thread(r,"yono-search-engine").apply {isDaemon=true}})
    }
}
