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
        return RecommendationEngine.rank(seed,candidates.mapIndexed {i,s->RecommendationCandidate(s,1.0-i.toDouble()/maxOf(candidates.size,1))},ListeningProfile(),recent).take(12)
    }
}
class ForYouRepository(private val app:YonzApp) {
    val state=MutableStateFlow(ForYouState())
    val profileState=MutableStateFlow(ListeningProfile())
    var profile:ListeningProfile
        get()=profileState.value
        private set(value){profileState.value=value}
    fun record(signal:ListeningSignal) {
        val now=System.currentTimeMillis()
        val extra=if(signal.kind==FeedbackKind.STARTED && profile.signals.any {it.id==signal.id && it.kind==FeedbackKind.LISTEN})listOf(signal.copy(kind=FeedbackKind.REPLAY))else emptyList()
        val previous=if(signal.kind==FeedbackKind.PROGRESS)profile.signals.filterNot {it.kind==FeedbackKind.PROGRESS && it.session==signal.session}else profile.signals
        profile=profile.copy(signals=(previous+signal+extra).filter {now-it.at in 0..30L*86400000}.takeLast(500),hidden=profile.hidden.filterValues {it>now})
        save()
    }
    fun hide(id:String) {
        val now=System.currentTimeMillis()
        profile=profile.copy(hidden=(profile.hidden.filterValues {it>now}-id+mapOf(id to now+30L*86400000)).entries.toList().takeLast(100).associate {it.toPair()})
        state.value=state.value.copy(songs=state.value.songs.filterNot {it.id==id})
        save()
    }
    fun resetTaste() {profile=ListeningProfile();refresh();save()}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    init {scope.launch {
        val saved=withContext(Dispatchers.IO) {app.repository.dao.state("for-you:v1")?.value}
        if(saved!=null && saved.length<1024*1024)runCatching {
            val root=JSONObject(saved)
            val signals=root.optJSONArray("signals")?.let {a->(0 until minOf(a.length(),500)).mapNotNull {i->runCatching {
                val r=a.getJSONObject(i);ListeningSignal(r.getString("id"),r.getString("artist"),FeedbackKind.valueOf(r.getString("kind")),r.getLong("at"),r.optString("session"),r.optLong("heard").coerceAtLeast(0),r.optLong("duration").coerceAtLeast(0))
            }.getOrNull()}}.orEmpty()
            val hidden=root.optJSONObject("hidden")?.let {obj->obj.keys().asSequence().take(100).associateWith {obj.optLong(it)}}.orEmpty()
            profile=ListeningProfile((signals+profile.signals).distinct().sortedBy {it.at}.takeLast(500),(hidden+profile.hidden).filterValues {it>System.currentTimeMillis()})
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
    fun offer(id:String,candidates:List<OnlineSong>,ranked:Boolean=false) {
        val old=state.value;val seed=old.seed?.takeIf {it.videoId==id} ?: return
        val songs=if(ranked)candidates.take(12)else ForYouPolicy.select(seed,candidates,old.recent)
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
            .put("signals",JSONArray(profile.signals.map {JSONObject().put("id",it.id).put("artist",it.artist).put("kind",it.kind.name).put("at",it.at).put("session",it.session).put("heard",it.listenedMs).put("duration",it.durationMs)}))
            .put("hidden",JSONObject(profile.hidden))
            .put("recent",JSONArray(value.recent.map(StreamRepository::encode))).put("songs",JSONArray(value.songs.map(StreamRepository::encode)))
        app.persistenceScope.launch {app.repository.dao.state(AppState("for-you:v1",json.toString()))}
    }
}
