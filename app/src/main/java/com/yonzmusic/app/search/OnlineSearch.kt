package com.yonzmusic.app.search

import android.content.Context
import com.yonzmusic.app.download.CommonsSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

/** API key is user-configured, private/no-backup, never bundled, logged or sent to media hosts. */
class SearchSettings(context: Context) {
    private val file = File(context.noBackupFilesDir,"youtube-search-key")
    private val historyFile=File(context.noBackupFilesDir,"online-search-history.json")
    private fun historyJson()=runCatching {if(historyFile.length()>16384)JSONObject()else JSONObject(historyFile.readText())}.getOrElse {JSONObject()}
    @Synchronized fun history(): List<String> {
        val root=historyJson();if(!root.optBoolean("enabled",true))return emptyList()
        val rows=root.optJSONArray("queries") ?: return emptyList()
        return (0 until minOf(rows.length(),10)).map {SearchContract.query(rows.optString(it))}.filter {it.isNotBlank()}
    }
    @Synchronized fun remembersHistory()=historyJson().optBoolean("enabled",true)
    @Synchronized fun clearHistory(enabled: Boolean=remembersHistory()) {historyFile.writeText(JSONObject().put("enabled",enabled).put("queries",org.json.JSONArray()).toString())}
    @Synchronized fun rememberQuery(value: String) {
        if(!remembersHistory() || value.contains("://") || value==key())return
        val rows=(listOf(SearchContract.query(value))+history()).distinctBy {it.lowercase(java.util.Locale.ROOT)}.take(10)
        historyFile.writeText(JSONObject().put("enabled",true).put("queries",org.json.JSONArray(rows)).toString())
    }
    @Synchronized fun key(): String = runCatching { file.readText().takeIf { valid(it) }.orEmpty() }.getOrDefault("")
    @Synchronized fun setKey(value: String) {
        val key=value.trim();require(key.isEmpty() || valid(key)) {"API key YouTube tidak valid."}
        if(key.isEmpty())file.delete() else {
            val pending=File(file.parentFile,file.name+".tmp")
            pending.writeText(key);check(pending.renameTo(file)) {"Konfigurasi belum tersimpan."}
        }
    }
    private fun valid(value: String) = value.matches(Regex("[A-Za-z0-9_-]{25,150}"))
}

interface SearchBackend { suspend fun search(provider: SearchProvider, query: String, page: String = ""): SearchPage }
class NetworkSearchBackend(private val settings: SearchSettings): SearchBackend {
    override suspend fun search(provider: SearchProvider, query: String, page: String): SearchPage = when(provider) {
        SearchProvider.APPLE -> SearchContract.apple(SearchHttp.get("https://itunes.apple.com/search?media=music&entity=song&country=ID&limit=20&term=${SearchContract.encoded(query)}",SearchContract.appleApi))
        SearchProvider.YOUTUBE -> {
            val id=SearchContract.youtubeId(query)
            if(id!=null && settings.key().isBlank()) SearchPage(listOf(OnlineSong("youtube:$id",provider,"Video YouTube","Lihat informasi rekaman di YouTube",page="https://www.youtube.com/watch?v=$id",videoId=id)))
            else {
                val key=settings.key();if(key.isBlank())throw SearchFailure("Tambahkan API key YouTube untuk mencari di dalam aplikasi, atau buka pencarian di YouTube.")
                // Credential is only an API header. It never appears in request URLs or logs.
                val headers=mapOf("X-Goog-Api-Key" to key)
                val url="https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&videoCategoryId=10&maxResults=15&q=${SearchContract.encoded(query)}"+
                    if(page.isNotBlank())"&pageToken=${SearchContract.encoded(page)}" else ""
                val raw=SearchHttp.get(url,SearchContract.youtubeApi,headers)
                val parsed=SearchContract.youtube(raw)
                val ids=parsed.songs.map { it.videoId }
                val durations=if(ids.isEmpty())emptyMap() else runCatching {
                    val details=JSONObject(SearchHttp.get("https://www.googleapis.com/youtube/v3/videos?part=contentDetails&id="+ids.joinToString(","),SearchContract.youtubeApi,headers)).getJSONArray("items")
                    (0 until details.length()).associate { i -> val row=details.getJSONObject(i);row.getString("id") to SearchContract.duration(row.getJSONObject("contentDetails").optString("duration")) }
                }.getOrElse { if(it is CancellationException)throw it;emptyMap() }
                SearchContract.youtube(raw,durations)
            }
        }
        SearchProvider.COMMONS -> withContext(Dispatchers.IO) {
            CommonsSource.search(query).map { c -> OnlineSong(c.id,provider,c.title,c.artist,c.album,c.duration,c.artUrl,c.page,c.url,license=c.license,licenseUrl=c.licenseUrl) }.let(::SearchPage)
        }
    }
}

/** Latest-query wins, bounded cache/rate budget. No persisted result/preview audio, no Room writes. */
class OnlineSearch(private val scope: CoroutineScope, private val backend: SearchBackend, private val hasKey: () -> Boolean,
    private val debounceMs: Long = 600, private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },private val onSuccess: (String)->Unit = {}) {
    val state = MutableStateFlow(OnlineSearchState())
    private var job: Job? = null
    private var generation=0L
    private data class Cached(val time: Long,val value: SearchPage)
    private val cache=object: LinkedHashMap<String,Cached>(32,.75f,true) {override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Cached>?)=size>30}
    private val requests=mutableMapOf<SearchProvider,ArrayDeque<Long>>()
    fun query(value: String, provider: SearchProvider, retry: Boolean = false) {
        val query=SearchContract.query(value);job?.cancel();val serial=++generation
        state.value=OnlineSearchState(query,provider,loading=query.length>=3)
        if(query.length<3)return
        job=scope.launch {
            delay(if(retry)0 else debounceMs)
            load(query,provider,"",serial,retry)
        }
    }
    fun more() {
        val previous=state.value;if(previous.loading || previous.next.isBlank())return
        job=scope.launch {load(previous.query,previous.provider,previous.next,generation,false)}
    }
    private suspend fun load(query: String,provider: SearchProvider,page: String,serial: Long,retry: Boolean) {
        val key="${provider.name}:$query:$page"
        val saved=cache[key]?.takeIf { now()-it.time<300000 }
        if(saved!=null && !retry) {publish(saved.value,query,provider,page,serial,true);return}
        if(serial!=generation)return
        state.value=state.value.copy(loading=true,error="",needsKey=false)
        try {
            val budget=requests.getOrPut(provider){ArrayDeque()};val time=now()
            while(budget.isNotEmpty() && time-budget.first()>=60000)budget.removeFirst()
            if(budget.size>=12)throw SearchFailure("Pencarian terlalu cepat. Tunggu sebentar; hasil sebelumnya tetap tersimpan sementara.")
            budget.addLast(time)
            val result=withTimeout(22000) {backend.search(provider,query,page)}
            currentCoroutineContext().ensureActive()
            if(serial!=generation)return
            cache[key]=Cached(now(),result);publish(result,query,provider,page,serial,false)
        } catch(e: TimeoutCancellationException) {
            if(serial==generation)state.value=state.value.copy(loading=false,error="Sumber terlalu lama merespons. Coba lagi.")
        } catch(e: CancellationException) {throw e}
        catch(e: Exception) {
            if(serial==generation)state.value=state.value.copy(loading=false,error=(e as? SearchFailure)?.userMessage ?: "Pencarian online belum tersedia. Periksa koneksi dan coba lagi.",needsKey=provider==SearchProvider.YOUTUBE && !hasKey())
        }
    }
    private fun publish(result: SearchPage,query: String,provider: SearchProvider,page: String,serial: Long,cached: Boolean) {
        if(serial!=generation)return
        val combined=if(page.isBlank())result.songs else (state.value.songs+result.songs).distinctBy { it.id }.take(100)
        state.value=OnlineSearchState(query,provider,songs=combined,next=result.next.takeIf { combined.size<100 }.orEmpty(),cached=cached)
        if(page.isBlank() && result.songs.isNotEmpty())runCatching {onSuccess(query)}
    }
    fun cancel() {job?.cancel();generation++;state.value=state.value.copy(loading=false)}
    fun clear() {cancel();cache.clear();state.value=OnlineSearchState()}
}
