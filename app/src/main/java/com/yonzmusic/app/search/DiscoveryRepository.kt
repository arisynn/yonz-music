package com.yonzmusic.app.search

import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.data.*
import com.yonzmusic.app.network.AndroidNetworkContext
import com.yonzmusic.app.playback.StreamRepository
import com.yonzmusic.app.playback.StreamWork
import com.yonzmusic.engine.PythonRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CatalogItem(val id:String,val kind:String,val title:String,val credit:String="",val artwork:String="",val provider:String="MusicBrainz",val json:String="") {
    fun song():OnlineSong {
        val j=JSONObject(json)
        return OnlineSong(id,SearchProvider.ENGINE,title,credit,j.optString("album"),j.optLong("duration_ms"),artwork,"",artistVerified=credit.isNotBlank(),catalogJson=json,
            artists=j.optJSONArray("artists")?.let {a->(0 until minOf(a.length(),20)).map {SearchContract.text(a.optString(it))}} ?: emptyList(),
            metadataSource=com.yonzmusic.app.metadata.MetadataSource.CATALOG,artworkSource=com.yonzmusic.app.metadata.ArtworkSource.CATALOG)
    }
}
data class DiscoveryPage(val items:List<CatalogItem> = emptyList(),val songs:List<OnlineSong> = emptyList(),val cached:Boolean=false)
interface MetadataProvider {suspend fun search(query:String,kind:String):DiscoveryPage;suspend fun detail(item:CatalogItem,force:Boolean=false):DiscoveryPage}
interface ChartsProvider {suspend fun charts(country:String,force:Boolean=false):DiscoveryPage}
interface AudioResolver {suspend fun audio(song:OnlineSong):OnlineSong}

/** Cache stores catalogue IDs and metadata only. Audio URLs stay in StreamRepository's memory cache. */
class DiscoveryRepository(private val app:YonzApp):MetadataProvider,ChartsProvider,AudioResolver {
    private val worker=Dispatchers.IO.limitedParallelism(1)
    private val audioWorker=Dispatchers.IO.limitedParallelism(1)
    private val dao get()=app.repository.dao
    val saved=dao.savedCatalog().map {rows->rows.mapNotNull {runCatching {parseItem(JSONObject(it.metadata))}.getOrNull()}}.flowOn(Dispatchers.Default)
    var country:String
        get()=app.getSharedPreferences("discovery",0).getString("country","ID")?.takeIf {it in MusicCharts.regions} ?: "ID"
        set(value){require(value in MusicCharts.regions);app.getSharedPreferences("discovery",0).edit().putString("country",value).apply()}
    override suspend fun search(query:String,kind:String):DiscoveryPage {
        require(query.trim().length>=2)
        return request(JSONObject().put("operation","search").put("query",SearchContract.query(query)).put("kind",kind),300000)
    }
    override suspend fun detail(item:CatalogItem,force:Boolean):DiscoveryPage {
        if(item.kind!="radio")return request(JSONObject().put("operation","detail").put("id",item.id),86400000,force)
        val j=JSONObject(item.json)
        if(!force)return DiscoveryPage(songs=snapshot(j))
        val seed=audio(StreamRepository.decode(j.getJSONObject("seed")))
        val songs=app.catalog.related(seed.videoId)
        require(songs.isNotEmpty()){"Radio belum mendapatkan lagu baru."}
        val updated=item.copy(json=j.put("snapshot",JSONArray().apply {songs.forEach {put(StreamRepository.encode(it))}}).toString())
        save(updated)
        return DiscoveryPage(songs=songs)
    }
    suspend fun radio(seed:OnlineSong):CatalogItem {
        val related=if(seed.videoId.isNotBlank())try {app.catalog.related(seed.videoId)}catch(e:CancellationException){throw e}catch(_:Exception){emptyList()}else emptyList()
        val songs=listOf(seed)+related
        val item=CatalogItem("radio:${UUID.randomUUID()}","radio","Radio ${seed.title}",seed.credit,seed.artwork,"YouTube Music")
        return item.copy(json=encodeItem(item).put("seed",StreamRepository.encode(seed)).put("snapshot",JSONArray().apply {songs.distinctBy {it.id}.forEach {put(StreamRepository.encode(it))}}).toString())
    }
    private fun snapshot(j:JSONObject):List<OnlineSong> {val a=j.optJSONArray("snapshot") ?: JSONArray();return (0 until minOf(a.length(),100)).mapNotNull {runCatching {StreamRepository.decode(a.getJSONObject(it))}.getOrNull()}}
    override suspend fun charts(country:String,force:Boolean):DiscoveryPage {
        val page=app.charts.load(ChartCategory.DAILY_VIDEOS,country,force)
        return DiscoveryPage(page.items,page.entries.map {it.song},page.cached)
    }
    suspend fun save(item:CatalogItem) {require(item.kind in setOf("album","artist","playlist","radio"));dao.saveCatalog(SavedCatalog(item.id,item.kind,encodeItem(item).toString(),System.currentTimeMillis()))}
    suspend fun remove(item:CatalogItem)=dao.removeCatalog(item.id)
    suspend fun cachedAudio(song:OnlineSong):OnlineSong? {
        if(song.videoId.isNotBlank())return song
        return dao.catalogCache("audio:${song.id}")?.let {cached->runCatching {StreamRepository.decode(JSONObject(cached.metadata))}.getOrNull()?.let {song.copy(videoId=it.videoId,page=it.page)}}
    }
    override suspend fun audio(song:OnlineSong):OnlineSong {
        cachedAudio(song)?.let {return it}
        val key="audio:${song.id}"
        val speculativeSource=StreamWork.sourceId.takeIf {StreamWork.isSpeculative}
        val response=request(JSONObject().put("operation","resolve").put("item",JSONObject(song.catalogJson)),0,backgroundSource=speculativeSource)
        val found=response.songs.firstOrNull() ?: throw SearchFailure("Rekaman audio yang cocok belum ditemukan.")
        // Keep the canonical identity separate from the selected recording's source ID.
        val result=song.copy(videoId=found.videoId,page=found.page,officialStatus=found.officialStatus,contentKind=found.contentKind)
        dao.catalogCache(CatalogCache(key,StreamRepository.encode(result).toString(),System.currentTimeMillis()))
        app.streams.register(result)
        return result
    }
    suspend fun enrich(song:OnlineSong):OnlineSong {
        if(song.catalogJson.isNotBlank() || !song.artistVerified)return song
        val source=JSONObject().put("video_id",song.videoId).put("title",song.title).put("artists",JSONArray(song.artists.ifEmpty {song.credit.split(", ")})).put("duration_ms",song.durationMs).put("album",song.album).put("artwork",song.artwork).put("page",song.page)
        return request(JSONObject().put("operation","enrich").put("song",source),7*86400000L).songs.firstOrNull() ?: song
    }
    private suspend fun request(payload:JSONObject,ttl:Long,force:Boolean=false,backgroundSource:String?=null):DiscoveryPage=withContext(Dispatchers.IO) {
        val key=payload.toString()
        val cache=dao.catalogCache(key)
        if(!force && cache!=null && System.currentTimeMillis()-cache.updated<ttl)return@withContext parse(JSONObject(cache.metadata),true)
        try {
            val raw=withTimeout(42000) {
                if(payload.optString("operation")!="resolve")StreamWork.awaitAudio()
                runInterruptible(if(payload.optString("operation")=="resolve")audioWorker else worker) {
                    val config=JSONObject().put("job_id",UUID.randomUUID().toString()).put("timeout_seconds",if(backgroundSource!=null)8 else 38).put("background",backgroundSource!=null)
                        .put("network",AndroidNetworkContext.options(app,if(payload.optString("operation") in setOf("charts","resolve") || payload.optString("id").startsWith("yt:") || payload.optString("kind")=="playlist")"https://music.youtube.com" else "https://musicbrainz.org"))
                    PythonRuntime.module(app,"yono_discovery").callAttr("execute",payload.toString(),config.toString(),if(payload.optString("operation")=="resolve")StreamRepository.Cancellation(java.util.concurrent.atomic.AtomicBoolean(false),Thread.currentThread(),backgroundSource) else MusicCatalog.Callback()).toString()
                }
            }
            require(raw.toByteArray().size<=2*1024*1024)
            val root=JSONObject(raw)
            if(!root.optBoolean("ok"))throw SearchFailure(root.optJSONObject("error")?.optString("message") ?: "Katalog belum tersedia.")
            val result=parse(root,false)
            if(ttl>0){dao.catalogCache(CatalogCache(key,raw,System.currentTimeMillis()));dao.trimCatalogCache()}
            result
        } catch(e:CancellationException){throw e} catch(e:Exception){if(cache!=null)parse(JSONObject(cache.metadata),true)else throw e}
    }
    companion object {
        fun parseItem(j:JSONObject):CatalogItem {
            val id=j.getString("id");require(id.matches(Regex("(?:mb:(?:recording|album|artist):[a-f0-9-]{36}|yt:(?:artist|album|playlist):[A-Za-z0-9_-]{2,200}|radio:[a-f0-9-]{36})")))
            val kind=j.getString("kind");require(kind in setOf("song","album","artist","playlist","radio"))
            return CatalogItem(id,kind,SearchContract.text(j.getString("title")),SearchContract.text(j.optString("credit"),1000),j.optString("artwork").takeIf {SearchContract.artwork(it)}.orEmpty(),j.optString("provider","MusicBrainz"),j.toString())
        }
        fun encodeItem(item:CatalogItem):JSONObject=if(item.json.isNotBlank())JSONObject(item.json) else JSONObject().put("id",item.id).put("kind",item.kind).put("title",item.title).put("credit",item.credit).put("artwork",item.artwork).put("provider",item.provider)
        fun parse(j:JSONObject,cached:Boolean):DiscoveryPage {
            val values=j.optJSONArray("items") ?: JSONArray()
            val items=(0 until minOf(values.length(),500)).mapNotNull {runCatching {parseItem(values.getJSONObject(it))}.getOrNull()}
            val valuesSongs=j.optJSONArray("songs") ?: JSONArray()
            val songs=(0 until minOf(valuesSongs.length(),500)).mapNotNull {i->runCatching {
                val row=valuesSongs.getJSONObject(i)
                if(row.optString("id").startsWith("mb:"))parseItem(row).song()
                else SearchContract.engine(JSONObject().put("ok",true).put("songs",JSONArray().put(row)).toString(),1).songs.firstOrNull()?.let {song->
                    if(row.optString("metadata_id").matches(Regex("mb:recording:[a-f0-9-]{36}")))song.copy(metadataSource=com.yonzmusic.app.metadata.MetadataSource.CATALOG,artworkSource=com.yonzmusic.app.metadata.ArtworkSource.CATALOG)else song
                }
            }.getOrNull()}
            return DiscoveryPage(items,if(songs.isNotEmpty())songs else items.filter {it.kind=="song"}.map {it.song()},cached)
        }
    }
}
