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
        val merged=app.metadata.publish(song)
        app.repository.dao.state(AppState("stream:${if(merged.id.startsWith("mb:"))merged.id else merged.videoId}",encode(merged).toString()))
    }
    suspend fun lookup(mediaId:String):OnlineSong?=withContext(Dispatchers.IO) {
        val id=mediaId.removePrefix("engine:");if(!mediaId.isRemoteId() || (!mediaId.startsWith("mb:") && !valid(id)))return@withContext null
        app.metadata.state.value[mediaId] ?: app.repository.dao.state("stream:$id")?.value?.let {runCatching {app.metadata.publish(decode(JSONObject(it)))}.getOrNull()}
    }
    @Keep class Cancellation(private val flag:AtomicBoolean,private val thread:Thread,private val backgroundSource:String?=null) {
        @Keep fun isCancelled()=flag.get() || thread.isInterrupted || (backgroundSource!=null && backgroundSource!=StreamWork.currentMediaId && (StreamWork.urgent || StreamWork.foregroundLoading))
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
        val backgroundSource=StreamWork.sourceId.takeIf {StreamWork.isSpeculative}
        val selectedQuality=quality
        val deadline=android.os.SystemClock.elapsedRealtime()+timeoutMillis.coerceIn(1,22000)
        fun active() {
            if(cancelled.get() || Thread.currentThread().isInterrupted || (backgroundSource!=null && backgroundSource!=StreamWork.currentMediaId && (StreamWork.urgent || StreamWork.foregroundLoading)))throw StreamFailure(StreamIssue.CANCELLED)
            if(android.os.SystemClock.elapsedRealtime()>=deadline)throw StreamFailure(StreamIssue.TIMEOUT)
        }
        var loaded=false
        return cache.resolve("$id:$selectedQuality",{formatId==null || it.formatId==formatId},::active,
            {StreamRange.cacheMillis(it.url,System.currentTimeMillis())}) {
        loaded=true
        StreamPerformance.mark("engine:$id",StreamPerformance.Stage.RESOLVER_LOAD,0)
        val remaining=deadline-android.os.SystemClock.elapsedRealtime()
        if(remaining<5000)throw StreamFailure(StreamIssue.TIMEOUT)
        val config=JSONObject().put("job_id",java.util.UUID.randomUUID().toString()).put("timeout_seconds",(remaining/1000).coerceIn(5,if(backgroundSource!=null)8 else 22)).put("background",backgroundSource!=null)
            .put("network",AndroidNetworkContext.options(app,"https://www.youtube.com"))
        val qjs=File(app.applicationInfo.nativeLibraryDir,"libqjs.so")
        if(qjs.isFile)config.put("runtime",JSONObject().put("quickjs",JSONObject().put("path",qjs.canonicalPath)))
        val module=PythonRuntime.module(app,"yono_stream")
        val extractAt=android.os.SystemClock.elapsedRealtime()
        val raw=try {module.callAttr("execute",JSONObject().put("video_id",id).put("quality",selectedQuality).apply {if(formatId!=null)put("format_id",formatId)}.toString(),config.toString(),Cancellation(cancelled,Thread.currentThread(),backgroundSource)).toString()}finally{StreamPerformance.mark("engine:$id",StreamPerformance.Stage.YTDLP_EXTRACT,android.os.SystemClock.elapsedRealtime()-extractAt)}
        val j=JSONObject(raw);if(!j.optBoolean("ok"))throw StreamFailure(runCatching {StreamIssue.valueOf(j.optString("code","SOURCE"))}.getOrDefault(StreamIssue.SOURCE))
        val url=j.getString("url");runCatching {StreamPolicy.url(url)}.getOrElse {throw StreamFailure(StreamIssue.UNSAFE)}
        val headers=mutableMapOf<String,String>();j.optJSONObject("headers")?.let {h->h.keys().forEach {k->val v=h.optString(k);if(k.lowercase() in setOf("user-agent","accept","accept-language") && v.length<1500 && !v.contains('\r') && !v.contains('\n'))headers[k]=v}}
        val result=Resolved(url,headers,((j.optJSONObject("song")?.optDouble("duration",0.0) ?: 0.0)*1000).toLong().coerceIn(0,7*86400000L),j.optString("format_id").take(100),j.optString("ext").take(20),j.optString("acodec").take(100),j.optLong("bitrate").coerceIn(0,10000),j.optString("revision").takeIf {it.matches(Regex("[a-f0-9]{64}"))} ?: "$sessionRevision:${java.util.UUID.randomUUID()}")
        // Optional metadata must never turn a valid audio resolution into a playback failure.
        j.optJSONObject("song")?.let {runCatching {app.metadata.extracted(id,it)}}
        StreamTimings.record("resolve",android.os.SystemClock.elapsedRealtime()-started)
        result
        }.also {if(!loaded)StreamPerformance.mark("engine:$id",StreamPerformance.Stage.RESOLVER_HIT,android.os.SystemClock.elapsedRealtime()-started)}
    }

    companion object {
        fun valid(id:String)=id.matches(Regex("[A-Za-z0-9_-]{11}"))
        fun encode(s:OnlineSong)=JSONObject().put("id",s.videoId).put("mediaId",s.id).put("catalog",s.catalogJson).put("title",s.title.take(250)).put("credit",s.credit.take(1000)).put("artist",s.artistVerified).put("album",s.album.take(250)).put("duration",s.durationMs).put("art",s.artwork.takeIf {SearchContract.artwork(it)}.orEmpty())
            .put("artists",org.json.JSONArray(s.artists.take(20))).put("albumArtist",s.albumArtist.take(250)).put("uploader",s.uploader.take(250)).put("channelId",s.channelId.take(100)).put("year",s.releaseYear).put("genre",s.genre.take(250))
            .put("official",s.officialStatus.name).put("kind",s.contentKind.name).put("metadataSource",s.metadataSource.name).put("artworkSource",s.artworkSource.name).put("updated",s.metadataUpdatedAt)
        fun decode(j:JSONObject):OnlineSong {
            val id=j.getString("id");val mediaId=j.optString("mediaId","engine:$id");val catalog=j.optString("catalog");require(valid(id) || (mediaId.matches(Regex("mb:recording:[a-f0-9-]{36}")) && catalog.isNotBlank()))
            return OnlineSong(mediaId,SearchProvider.ENGINE,SearchContract.text(j.getString("title")),SearchContract.text(j.optString("credit"),1000),SearchContract.text(j.optString("album")),j.optLong("duration").coerceIn(0,7*86400000L),j.optString("art").takeIf {SearchContract.artwork(it)}.orEmpty(),"https://www.youtube.com/watch?v=$id",id,j.optBoolean("artist"),catalog,
                artists=j.optJSONArray("artists")?.let {a->(0 until minOf(a.length(),20)).map {SearchContract.text(a.optString(it))}} ?: emptyList(),albumArtist=SearchContract.text(j.optString("albumArtist")),uploader=SearchContract.text(j.optString("uploader")),channelId=SearchContract.text(j.optString("channelId"),100),releaseYear=j.optInt("year").takeIf {it in 1900..2200} ?: 0,genre=SearchContract.text(j.optString("genre")),
                officialStatus=com.yonzmusic.app.metadata.MetadataPolicy.official(j.optString("official")),contentKind=com.yonzmusic.app.metadata.MetadataPolicy.kind(j.optString("kind")),metadataSource=runCatching {com.yonzmusic.app.metadata.MetadataSource.valueOf(j.optString("metadataSource"))}.getOrDefault(com.yonzmusic.app.metadata.MetadataSource.SEARCH),artworkSource=runCatching {com.yonzmusic.app.metadata.ArtworkSource.valueOf(j.optString("artworkSource"))}.getOrDefault(com.yonzmusic.app.metadata.ArtworkSource.THUMBNAIL),metadataUpdatedAt=j.optLong("updated").coerceAtLeast(0))
        }
        /** View-only adapter for existing artwork/player composables; never inserted into Room. */
        fun display(s:OnlineSong)=Track(s.id,"",s.title,if(s.artistVerified)s.credit else s.credit.takeIf {it.isNotBlank()}?.let {"Kanal: $it"}.orEmpty(),s.album.ifBlank {"Pemutaran daring"},duration=s.durationMs,artUri=s.artworkUri.ifBlank {s.artwork},source="stream")
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
