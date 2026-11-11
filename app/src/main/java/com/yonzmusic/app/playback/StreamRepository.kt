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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Persisted source references, not media URLs or fake Library tracks. */
class StreamRepository(private val app:YonzApp):StreamResolver {
    val metadata=MutableStateFlow<Map<String,OnlineSong>>(emptyMap())
    private val artJobs=mutableSetOf<String>()
    private val artAttempts=mutableMapOf<String,Long>()
    private val artPermit=Semaphore(1)
    private val metadataMutex=Mutex()
    suspend fun register(song:OnlineSong) {
        require(valid(song.videoId) && song.page=="https://www.youtube.com/watch?v=${song.videoId}")
        metadataMutex.withLock {
            val old=metadata.value[song.id] ?: lookup(song.id)
            val merged=if(old!=null && old.artwork.isNotBlank())song.copy(artwork=old.artwork)else song
            store(merged)
        }
    }
    private suspend fun store(song:OnlineSong) {
        app.repository.dao.state(AppState("stream:${song.videoId}",encode(song).toString()))
        publishMetadata(song)
    }
    private fun publishMetadata(song:OnlineSong) {
        metadata.update {old->(old-song.id+mapOf(song.id to song)).entries.toList().takeLast(100).associate {it.toPair()}}
    }
    /** At most one metadata-only image recovery. Independent of audio extraction and keyed by source ID. */
    fun requestArtwork(song:OnlineSong,repair:Boolean=false) {
        if(!valid(song.videoId))return
        synchronized(artJobs) {
            val now=android.os.SystemClock.elapsedRealtime()
            if(song.id in artJobs || artJobs.size>=5 || artAttempts[song.id]?.let {now-it<300000}==true)return
            artJobs+=song.id
            artAttempts[song.id]=now
            if(artAttempts.size>100)artAttempts.remove(artAttempts.keys.first())
        }
        app.persistenceScope.launch {
            try {artPermit.withPermit {
                val known=lookup(song.id)
                if(!repair && known?.artwork?.isNotBlank()==true) {publishMetadata(known);return@withPermit}
                val root=withTimeout(22000) {app.musicCatalog.request(JSONObject().put("operation","artwork").put("video_id",song.videoId))}
                val url=root.optString("artwork")
                if(root.optString("video_id")==song.videoId && SearchContract.https(url,SearchContract.artHosts))
                    metadataMutex.withLock {store((lookup(song.id) ?: song).copy(artwork=url))}
            }}catch(e:CancellationException){throw e}
            catch(_:Exception) { /* Missing images must never fail playback or expose raw source errors. */ }
            finally {synchronized(artJobs){artJobs-=song.id}}
        }
    }
    private fun extractedArtwork(id:String,json:JSONObject) {
        val supplied=json.optJSONObject("song") ?: return
        if(supplied.optString("youtube_id")!=id)return
        val url=supplied.optString("artwork_url")
        if(!SearchContract.https(url,SearchContract.artHosts))return
        app.persistenceScope.launch {
            metadataMutex.withLock {
                val old=lookup("engine:$id") ?: return@withLock
                if(old.artwork!=url)store(old.copy(artwork=url))
            }
        }
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
    private val cache=StreamResolutionCache<Resolved>({android.os.SystemClock.elapsedRealtime()})
    override fun invalidate(id:String){cache.invalidate(id)}
    /** Called only by ExoPlayer loader threads. Never stores the signed URL in Room/preferences/logs. */
    override fun resolve(id:String,cancelled:AtomicBoolean,formatId:String?,timeoutMillis:Long):Resolved {
        require(valid(id))
        val deadline=android.os.SystemClock.elapsedRealtime()+timeoutMillis.coerceIn(1,22000)
        fun active() {
            if(cancelled.get() || Thread.currentThread().isInterrupted)throw StreamFailure(StreamIssue.CANCELLED)
            if(android.os.SystemClock.elapsedRealtime()>=deadline)throw StreamFailure(StreamIssue.TIMEOUT)
        }
        return cache.resolve(id,{formatId==null || it.formatId==formatId},::active,
            {StreamRange.cacheMillis(it.url,System.currentTimeMillis())}) {
        val remaining=deadline-android.os.SystemClock.elapsedRealtime()
        if(remaining<5000)throw StreamFailure(StreamIssue.TIMEOUT)
        val config=JSONObject().put("job_id",java.util.UUID.randomUUID().toString()).put("timeout_seconds",(remaining/1000).coerceIn(5,22))
            .put("network",AndroidNetworkContext.options(app,"https://www.youtube.com"))
        val qjs=File(app.applicationInfo.nativeLibraryDir,"libqjs.so")
        if(qjs.isFile)config.put("runtime",JSONObject().put("quickjs",JSONObject().put("path",qjs.canonicalPath)))
        val raw=PythonRuntime.module(app,"yono_stream").callAttr("execute",JSONObject().put("video_id",id).apply {if(formatId!=null)put("format_id",formatId)}.toString(),config.toString(),Cancellation(cancelled,Thread.currentThread())).toString()
        val j=JSONObject(raw);if(!j.optBoolean("ok"))throw StreamFailure(runCatching {StreamIssue.valueOf(j.optString("code","SOURCE"))}.getOrDefault(StreamIssue.SOURCE))
        val url=j.getString("url");runCatching {StreamPolicy.url(url)}.getOrElse {throw StreamFailure(StreamIssue.UNSAFE)}
        val headers=mutableMapOf<String,String>();j.optJSONObject("headers")?.let {h->h.keys().forEach {k->val v=h.optString(k);if(k.lowercase() in setOf("user-agent","accept","accept-language") && v.length<1500 && !v.contains('\r') && !v.contains('\n'))headers[k]=v}}
        extractedArtwork(id,j)
        val result=Resolved(url,headers,((j.optJSONObject("song")?.optDouble("duration",0.0) ?: 0.0)*1000).toLong().coerceIn(0,7*86400000L),j.optString("format_id").take(100),j.optString("ext").take(20),j.optString("acodec").take(100))
        result
        }
    }

    companion object {
        fun valid(id:String)=id.matches(Regex("[A-Za-z0-9_-]{11}"))
        fun encode(s:OnlineSong)=JSONObject().put("id",s.videoId).put("title",s.title.take(250)).put("credit",s.credit.take(1000)).put("artist",s.artistVerified).put("album",s.album.take(250)).put("duration",s.durationMs).put("art",s.artwork)
        fun decode(j:JSONObject):OnlineSong {
            val id=j.getString("id");require(valid(id))
            return OnlineSong("engine:$id",SearchProvider.ENGINE,SearchContract.text(j.getString("title")),SearchContract.text(j.optString("credit"),1000),SearchContract.text(j.optString("album")),j.optLong("duration").coerceIn(0,7*86400000L),j.optString("art").takeIf {SearchContract.https(it,SearchContract.artHosts)}.orEmpty(),"https://www.youtube.com/watch?v=$id",id,j.optBoolean("artist"))
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
