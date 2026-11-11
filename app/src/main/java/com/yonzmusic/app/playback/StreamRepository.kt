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
        require((valid(song.videoId) && song.page=="https://www.youtube.com/watch?v=${song.videoId}") || (song.id.startsWith("mb:recording:") && song.catalogJson.isNotBlank()))
        app.repository.dao.state(AppState("stream:${if(song.id.startsWith("mb:"))song.id else song.videoId}",encode(song).toString()))
    }
    suspend fun lookup(mediaId:String):OnlineSong?=withContext(Dispatchers.IO) {
        val id=mediaId.removePrefix("engine:");if(!mediaId.isRemoteId() || (!mediaId.startsWith("mb:") && !valid(id)))return@withContext null
        app.repository.dao.state("stream:$id")?.value?.let {runCatching {decode(JSONObject(it))}.getOrNull()}
    }
    @Keep class Cancellation(private val flag:AtomicBoolean,private val thread:Thread) {
        @Keep fun isCancelled()=flag.get() || thread.isInterrupted
        @Keep fun onEvent(value:String) {}
    }
    data class Resolved(val url:String,val headers:Map<String,String>,val duration:Long,val formatId:String="",val extension:String="",val codec:String="",val bitrate:Long=0,val revision:String="")
    @Volatile var quality: String = "balanced"
    private val sessionRevision = java.util.UUID.randomUUID().toString()
    private val cache=StreamResolutionCache<Resolved>({android.os.SystemClock.elapsedRealtime()})
    override fun invalidate(id:String){listOf("economy","balanced","best").forEach {cache.invalidate("$id:$it")}}
    /** Called only by ExoPlayer loader threads. Never stores the signed URL in Room/preferences/logs. */
    override fun resolve(id:String,cancelled:AtomicBoolean,formatId:String?,timeoutMillis:Long):Resolved {
        require(valid(id))
        StreamPerformance.sinceTap("engine:$id",StreamPerformance.Stage.RESOLVER_START)
        val started=android.os.SystemClock.elapsedRealtime()
        val selectedQuality=quality
        val deadline=android.os.SystemClock.elapsedRealtime()+timeoutMillis.coerceIn(1,22000)
        fun active() {
            if(cancelled.get() || Thread.currentThread().isInterrupted)throw StreamFailure(StreamIssue.CANCELLED)
            if(android.os.SystemClock.elapsedRealtime()>=deadline)throw StreamFailure(StreamIssue.TIMEOUT)
        }
        return cache.resolve("$id:$selectedQuality",{formatId==null || it.formatId==formatId},::active,
            {StreamRange.cacheMillis(it.url,System.currentTimeMillis())}) {
        StreamPerformance.mark("engine:$id",StreamPerformance.Stage.RESOLVER_LOAD,0)
        val remaining=deadline-android.os.SystemClock.elapsedRealtime()
        if(remaining<5000)throw StreamFailure(StreamIssue.TIMEOUT)
        val config=JSONObject().put("job_id",java.util.UUID.randomUUID().toString()).put("timeout_seconds",(remaining/1000).coerceIn(5,22))
            .put("network",AndroidNetworkContext.options(app,"https://www.youtube.com"))
        val qjs=File(app.applicationInfo.nativeLibraryDir,"libqjs.so")
        if(qjs.isFile)config.put("runtime",JSONObject().put("quickjs",JSONObject().put("path",qjs.canonicalPath)))
        val raw=PythonRuntime.module(app,"yono_stream").callAttr("execute",JSONObject().put("video_id",id).put("quality",selectedQuality).apply {if(formatId!=null)put("format_id",formatId)}.toString(),config.toString(),Cancellation(cancelled,Thread.currentThread())).toString()
        val j=JSONObject(raw);if(!j.optBoolean("ok"))throw StreamFailure(runCatching {StreamIssue.valueOf(j.optString("code","SOURCE"))}.getOrDefault(StreamIssue.SOURCE))
        j.optJSONObject("metrics")?.let {StreamPerformance.mark("engine:$id",StreamPerformance.Stage.YTDLP_EXTRACT,it.optLong("extraction_ms").coerceIn(0,30000))}
        val url=j.getString("url");runCatching {StreamPolicy.url(url)}.getOrElse {throw StreamFailure(StreamIssue.UNSAFE)}
        val headers=mutableMapOf<String,String>();j.optJSONObject("headers")?.let {h->h.keys().forEach {k->val v=h.optString(k);if(k.lowercase() in setOf("user-agent","accept","accept-language") && v.length<1500 && !v.contains('\r') && !v.contains('\n'))headers[k]=v}}
        val result=Resolved(url,headers,((j.optJSONObject("song")?.optDouble("duration",0.0) ?: 0.0)*1000).toLong().coerceIn(0,7*86400000L),j.optString("format_id").take(100),j.optString("ext").take(20),j.optString("acodec").take(100),j.optLong("bitrate").coerceIn(0,10000),j.optString("revision").takeIf {it.matches(Regex("[a-f0-9]{64}"))} ?: "$sessionRevision:${java.util.UUID.randomUUID()}")
        StreamTimings.record("resolve",android.os.SystemClock.elapsedRealtime()-started)
        result
        }
    }

    companion object {
        fun valid(id:String)=id.matches(Regex("[A-Za-z0-9_-]{11}"))
        fun encode(s:OnlineSong)=JSONObject().put("id",s.videoId).put("mediaId",s.id).put("catalog",s.catalogJson).put("title",s.title.take(250)).put("credit",s.credit.take(1000)).put("artist",s.artistVerified).put("album",s.album.take(250)).put("duration",s.durationMs).put("art",s.artwork)
        fun decode(j:JSONObject):OnlineSong {
            val id=j.getString("id");val mediaId=j.optString("mediaId","engine:$id");val catalog=j.optString("catalog");require(valid(id) || (mediaId.matches(Regex("mb:recording:[a-f0-9-]{36}")) && catalog.isNotBlank()))
            return OnlineSong(mediaId,SearchProvider.ENGINE,SearchContract.text(j.getString("title")),SearchContract.text(j.optString("credit"),1000),SearchContract.text(j.optString("album")),j.optLong("duration").coerceIn(0,7*86400000L),j.optString("art").takeIf {SearchContract.artwork(it)}.orEmpty(),"https://www.youtube.com/watch?v=$id",id,j.optBoolean("artist"),catalog)
        }
        /** View-only adapter for existing artwork/player composables; never inserted into Room. */
        fun display(s:OnlineSong)=Track(s.id,"",s.title,if(s.artistVerified)s.credit else s.credit.takeIf {it.isNotBlank()}?.let {"Kanal: $it"}.orEmpty(),s.album.ifBlank {"Pemutaran daring"},duration=s.durationMs,artUri=s.artwork,source="stream")
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
