package com.yonzmusic.app.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.Keep
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.data.AppState
import com.yonzmusic.app.data.Track
import com.yonzmusic.app.search.*
import com.yonzmusic.engine.PythonRuntime
import com.yonzmusic.app.network.AndroidNetworkContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Persisted source references, not media URLs or fake Library tracks. */
class StreamRepository(private val app:YonzApp):StreamResolver {
    suspend fun register(song:OnlineSong) {
        require(valid(song.videoId) && song.page=="https://www.youtube.com/watch?v=${song.videoId}")
        app.repository.dao.state(AppState("stream:${song.videoId}",encode(song).toString()))
    }
    suspend fun lookup(mediaId:String):OnlineSong?=withContext(Dispatchers.IO) {
        val id=mediaId.removePrefix("engine:");if(!mediaId.startsWith("engine:") || !valid(id))return@withContext null
        app.repository.dao.state("stream:$id")?.value?.let {runCatching {decode(JSONObject(it))}.getOrNull()}
    }
    @Keep class Cancellation(private val flag:AtomicBoolean,private val thread:Thread) {
        @Keep fun isCancelled()=flag.get() || thread.isInterrupted
        @Keep fun onEvent(value:String) {}
    }
    data class Resolved(val url:String,val headers:Map<String,String>,val duration:Long,val formatId:String="",val extension:String="",val codec:String="")
    private data class Cached(val until:Long,val value:Resolved)
    private val cache=linkedMapOf<String,Cached>()
    @Synchronized override fun invalidate(id:String){cache.remove(id)}
    /** Called only by ExoPlayer loader threads. Never stores the signed URL in Room/preferences/logs. */
    override fun resolve(id:String,cancelled:AtomicBoolean,formatId:String?,timeoutMillis:Long):Resolved {
        require(valid(id))
        if(cancelled.get() || Thread.currentThread().isInterrupted)throw StreamFailure(StreamIssue.CANCELLED)
        synchronized(this){cache[id]?.takeIf {android.os.SystemClock.elapsedRealtime()<it.until && (formatId==null || it.value.formatId==formatId)}?.let{return it.value}}
        val config=JSONObject().put("job_id",java.util.UUID.randomUUID().toString()).put("timeout_seconds",(timeoutMillis/1000).coerceIn(5,22))
            .put("network",AndroidNetworkContext.options(app,"https://www.youtube.com"))
        val qjs=File(app.applicationInfo.nativeLibraryDir,"libqjs.so")
        if(qjs.isFile)config.put("runtime",JSONObject().put("quickjs",JSONObject().put("path",qjs.canonicalPath)))
        val raw=PythonRuntime.module(app,"yono_stream").callAttr("execute",JSONObject().put("video_id",id).apply {if(formatId!=null)put("format_id",formatId)}.toString(),config.toString(),Cancellation(cancelled,Thread.currentThread())).toString()
        val j=JSONObject(raw);if(!j.optBoolean("ok"))throw StreamFailure(runCatching {StreamIssue.valueOf(j.optString("code","SOURCE"))}.getOrDefault(StreamIssue.SOURCE))
        val url=j.getString("url");runCatching {StreamPolicy.url(url)}.getOrElse {throw StreamFailure(StreamIssue.UNSAFE)}
        val headers=mutableMapOf<String,String>();j.optJSONObject("headers")?.let {h->h.keys().forEach {k->val v=h.optString(k);if(k.lowercase() in setOf("user-agent","accept","accept-language") && v.length<1500 && !v.contains('\r') && !v.contains('\n'))headers[k]=v}}
        val result=Resolved(url,headers,((j.optJSONObject("song")?.optDouble("duration",0.0) ?: 0.0)*1000).toLong().coerceIn(0,7*86400000L),j.optString("format_id").take(100),j.optString("ext").take(20),j.optString("acodec").take(100))
        synchronized(this){cache[id]=Cached(android.os.SystemClock.elapsedRealtime()+StreamRange.cacheMillis(url,System.currentTimeMillis()),result);while(cache.size>8)cache.remove(cache.keys.first())}
        return result
    }
    companion object {
        fun valid(id:String)=id.matches(Regex("[A-Za-z0-9_-]{11}"))
        fun encode(s:OnlineSong)=JSONObject().put("id",s.videoId).put("title",s.title.take(250)).put("credit",s.credit.take(1000)).put("artist",s.artistVerified).put("album",s.album.take(250)).put("duration",s.durationMs).put("art",s.artwork)
        fun decode(j:JSONObject):OnlineSong {
            val id=j.getString("id");require(valid(id))
            return OnlineSong("engine:$id",SearchProvider.ENGINE,SearchContract.text(j.getString("title")),SearchContract.text(j.optString("credit"),1000),SearchContract.text(j.optString("album")),j.optLong("duration").coerceIn(0,7*86400000L),j.optString("art").takeIf {SearchContract.https(it,SearchContract.artHosts)}.orEmpty(),"https://www.youtube.com/watch?v=$id",id,j.optBoolean("artist"))
        }
        /** View-only adapter for existing artwork/player composables; never inserted into Room. */
        fun display(s:OnlineSong)=Track(s.id,"",s.title,if(s.artistVerified)s.credit else s.credit.takeIf {it.isNotBlank()}?.let {"Kanal: $it"}.orEmpty(),s.album.ifBlank {"Streaming online"},duration=s.durationMs,artUri=s.artwork,source="stream")
    }
}
object StreamPolicy {
    fun url(value:String):java.net.URL {
        val u=URI(value);val host=u.host.orEmpty().lowercase()
        require(value.length<=16384 && u.scheme=="https" && u.userInfo==null && u.port in setOf(-1,443) && u.fragment==null && (host=="googlevideo.com" || host.endsWith(".googlevideo.com"))) {"Alamat streaming tidak diizinkan."}
        return u.toURL()
    }
    fun source(uri:Uri):String {
        val id=uri.path.orEmpty().removePrefix("/")
        require(StreamRepository.valid(id) && uri.toString()=="yono-stream://youtube/$id")
        return id
    }
}
