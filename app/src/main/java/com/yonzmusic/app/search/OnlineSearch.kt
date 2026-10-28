package com.yonzmusic.app.search

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

/** Private optional query history; obsolete user API credentials are removed. */
class SearchSettings(context: Context) {
    init {listOf("youtube-search-key","youtube-search-key.tmp").forEach {File(context.noBackupFilesDir,it).delete()}}
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
        if(!remembersHistory() || value.contains("://") || value.startsWith("AIza") || value.startsWith("ghp_"))return
        val rows=(listOf(SearchContract.query(value))+history()).distinctBy {it.lowercase(java.util.Locale.ROOT)}.take(10)
        historyFile.writeText(JSONObject().put("enabled",true).put("queries",org.json.JSONArray(rows)).toString())
    }

}

interface SearchBackend { suspend fun search(provider: SearchProvider, query: String, page: String = ""): SearchPage }
/** Latest-query wins, bounded cache/rate budget. No persisted result/preview audio, no Room writes. */
class OnlineSearch(private val scope: CoroutineScope, private val backend: SearchBackend, private val debounceMs: Long = 600, private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },private val onSuccess: (String)->Unit = {}) {
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
        state.value=state.value.copy(loading=true,error="")
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
            if(serial==generation)state.value=state.value.copy(loading=false,error=(e as? SearchFailure)?.userMessage ?: "Pencarian online belum tersedia. Periksa koneksi dan coba lagi.")
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
