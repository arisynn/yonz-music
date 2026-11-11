package com.yonzmusic.app.search

import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.data.AppState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Two independent shelves. Persist normalized public metadata, never playable/signed audio URLs. */
data class DiscoveryShelf(val songs:List<OnlineSong> = emptyList(),val artists:List<OnlineArtist> = emptyList(),
    val loading:Boolean=false,val error:String="",val source:String="YouTube Music",val fetchedAt:Long=0)
class DiscoveryRepository(private val app:YonzApp,private val scope:CoroutineScope) {
    val region=MutableStateFlow("ID")
    val songs=MutableStateFlow(DiscoveryShelf())
    val artists=MutableStateFlow(DiscoveryShelf())
    private val jobs=mutableMapOf<String,Job>()
    fun selectRegion(value:String) {
        if(value !in setOf("ID","ZZ") || value==region.value)return
        jobs.values.forEach {it.cancel()};jobs.clear();region.value=value
        songs.value=DiscoveryShelf();artists.value=DiscoveryShelf()
    }
    fun load(kind:String,refresh:Boolean=false) {
        if(kind !in setOf("songs","artists"))return
        val state=if(kind=="songs")songs else artists
        if(jobs[kind]?.isActive==true)return
        val country=region.value
        if(!refresh && fresh(state.value.fetchedAt))return
        jobs[kind]=scope.launch {
            state.value=state.value.copy(loading=true,error="")
            try {
                val key="discovery:v1:$country:$kind"
                if(!refresh && state.value.fetchedAt==0L) {
                    val saved=withContext(Dispatchers.IO) {app.repository.dao.state(key)?.value}
                    if(saved!=null && saved.length<=256*1024)runCatching {
                        val root=JSONObject(saved);val age=System.currentTimeMillis()-root.optLong("fetched_at")
                        if(age in 0..7*86400000L)decode(root)
                        else null
                    }.getOrNull()?.let {state.value=it.copy(loading=!fresh(it.fetchedAt))}
                }
                if(!refresh && fresh(state.value.fetchedAt)){state.value=state.value.copy(loading=false);return@launch}
                val root=withTimeout(22000) {app.musicCatalog.request(JSONObject().put("operation","popular_$kind").put("region",country))}
                root.put("fetched_at",System.currentTimeMillis())
                val shelf=decode(root)
                ensureActive()
                withContext(Dispatchers.IO) {app.repository.dao.state(AppState(key,root.toString()))}
                ensureActive();if(region.value==country)state.value=shelf
            }catch(error:CancellationException){throw error}
            catch(error:Exception) {
                if(region.value==country)state.value=state.value.copy(loading=false,error=(error as? SearchFailure)?.userMessage ?: "Daftar populer belum tersedia. Coba lagi.")
            }
        }
    }
    private fun decode(root:JSONObject):DiscoveryShelf {
        val page=SearchContract.engine(root.toString())
        return DiscoveryShelf(page.songs,page.artists,source=SearchContract.text(root.optString("source")),fetchedAt=root.optLong("fetched_at"))
    }
    private fun fresh(time:Long)=time>0 && System.currentTimeMillis()-time in 0 until 6*3600000L
}
