package com.yonzmusic.app.search

import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.data.AppState
import com.yonzmusic.app.playback.RadioQueuePolicy
import com.yonzmusic.app.playback.StreamRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Listening choices stay on device. The service supplies radio results after audio is ready. */
data class ForYouState(val seed:OnlineSong?=null,val recent:List<OnlineSong> = emptyList(),
    val songs:List<OnlineSong> = emptyList(),val updatedAt:Long=0,val loading:Boolean=false,val error:String="")
object ForYouPolicy {
    fun select(seed:OnlineSong,candidates:List<OnlineSong>,recent:List<OnlineSong>):List<OnlineSong> {
        val ids=(recent.take(5).map {it.id}+seed.id).toMutableSet()
        val recordings=(recent.take(5)+seed).map {RadioQueuePolicy.recording(it.title,it.credit)}.toMutableSet()
        return candidates.filter {it.id !in ids && RadioQueuePolicy.recording(it.title,it.credit) !in recordings &&
            ids.add(it.id) && recordings.add(RadioQueuePolicy.recording(it.title,it.credit))}.take(12)
    }
}
class ForYouRepository(private val app:YonzApp) {
    val state=MutableStateFlow(ForYouState())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    init {scope.launch {
        val saved=withContext(Dispatchers.IO) {app.repository.dao.state("for-you:v1")?.value}
        if(saved!=null && saved.length<256*1024 && state.value.seed==null)runCatching {
            val root=JSONObject(saved)
            fun rows(key:String,limit:Int)=root.optJSONArray(key)?.let {a->(0 until minOf(a.length(),limit)).mapNotNull {i->runCatching {StreamRepository.decode(a.getJSONObject(i))}.getOrNull()}} ?: emptyList()
            ForYouState(root.optJSONObject("seed")?.let(StreamRepository::decode),rows("recent",12),rows("songs",12),root.optLong("updated"))
        }.getOrNull()?.let {if(state.value.seed==null)state.value=it}
    }}
    /** Main-thread service callbacks only; radio-generated tracks do not replace the user's seed. */
    fun remember(song:OnlineSong) {
        val old=state.value
        if(old.seed?.id==song.id)return
        state.value=ForYouState(song,(listOf(song)+old.recent).distinctBy {it.id}.take(12))
        save()
    }
    fun needs(id:String):Boolean {
        val value=state.value
        return value.seed?.videoId==id && value.error.isBlank() && !value.loading &&
            (value.updatedAt==0L || System.currentTimeMillis()-value.updatedAt !in 0 until 6*3600000L)
    }
    fun begin(id:String) {if(state.value.seed?.videoId==id)state.value=state.value.copy(loading=true,error="")}
    fun offer(id:String,candidates:List<OnlineSong>) {
        val old=state.value;val seed=old.seed?.takeIf {it.videoId==id} ?: return
        val songs=ForYouPolicy.select(seed,candidates,old.recent)
        state.value=old.copy(songs=songs,updatedAt=System.currentTimeMillis(),loading=false,
            error=if(songs.isEmpty())"Rekomendasi baru belum tersedia. Coba putar lagu lain."else "")
        save()
    }
    fun fail(id:String,message:String) {if(state.value.seed?.videoId==id)state.value=state.value.copy(loading=false,error=message)}
    fun finish(id:String) {if(state.value.seed?.videoId==id && state.value.loading)state.value=state.value.copy(loading=false)}
    fun refresh() {state.value=state.value.copy(updatedAt=0,error="",loading=false)}
    private fun save() {
        val value=state.value
        val json=JSONObject().put("seed",value.seed?.let(StreamRepository::encode)).put("updated",value.updatedAt)
            .put("recent",JSONArray(value.recent.map(StreamRepository::encode))).put("songs",JSONArray(value.songs.map(StreamRepository::encode)))
        app.persistenceScope.launch {app.repository.dao.state(AppState("for-you:v1",json.toString()))}
    }
}
