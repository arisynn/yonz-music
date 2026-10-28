package com.yonzmusic.app.data

import androidx.room.withTransaction
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.playback.StreamRepository
import com.yonzmusic.app.search.OnlineSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

data class OnlineCollection(val id:String,val name:String,val songs:List<OnlineSong> = emptyList(),val origin:String="")

/** Canonical public references only. Never stores expiring audio URLs or creates Library tracks. */
object OnlineCollectionContract {
    const val MAX_SONGS=500
    const val PREFIX="online-playlist:"
    fun playlistUrl(value:String):String {
        require(value.length<=4096){"Tautan terlalu panjang."}
        val u=URI(value.trim());require(u.scheme=="https" && u.host in setOf("music.youtube.com","www.youtube.com","youtube.com") && u.userInfo==null && u.port in setOf(-1,443) && u.fragment==null && u.path in setOf("/playlist","/watch")){"Gunakan tautan playlist publik YouTube Music / YouTube."}
        val params=u.rawQuery.orEmpty().split('&').filter{it.isNotBlank()}.map{it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=',""),"UTF-8")}
        val ids=params.filter{it.first=="list"}.map{it.second}
        require(ids.size==1 && ids[0].matches(Regex("[A-Za-z0-9_-]{10,200}"))){"ID playlist tidak valid."}
        val id=ids.single();require(listOf("RD","UL","WL","LL").none(id::startsWith)){"Radio/mix dinamis dan daftar akun privat tidak dapat disimpan sebagai playlist tetap."}
        return "https://music.youtube.com/playlist?list=$id"
    }
    fun validate(p:OnlineCollection):OnlineCollection {
        require(runCatching{UUID.fromString(p.id).toString()==p.id}.getOrDefault(false))
        require(p.name.isNotBlank() && p.name.length<=100 && p.name.none{it.isISOControl()}){"Nama playlist tidak valid."}
        require(p.songs.size<=MAX_SONGS && p.songs.distinctBy{it.id}.size==p.songs.size){"Playlist maksimal500 lagu unik."}
        p.songs.forEach {s->require(StreamRepository.valid(s.videoId) && s.id=="engine:${s.videoId}" && s.page=="https://www.youtube.com/watch?v=${s.videoId}" && s.title.isNotBlank())}
        require(p.origin.isBlank() || playlistUrl(p.origin)==p.origin)
        return p
    }
    fun encode(p:OnlineCollection):JSONObject {validate(p);return JSONObject().put("schema",1).put("id",p.id).put("name",p.name).put("origin",p.origin).put("songs",JSONArray().apply{p.songs.forEach{put(StreamRepository.encode(it))}})}
    fun decode(j:JSONObject):OnlineCollection {
        require(j.getInt("schema")==1);val rows=j.getJSONArray("songs");require(rows.length()<=MAX_SONGS)
        return validate(OnlineCollection(j.getString("id"),j.getString("name"),(0 until rows.length()).map{StreamRepository.decode(rows.getJSONObject(it))},j.optString("origin")))
    }
}

/** One Room-backed manager for online collections, transactional edits, no new schema/table. */
class OnlineCollections(private val app:YonzApp) {
    private val dao get()=app.repository.dao
    val flow get()=dao.statesFlow(OnlineCollectionContract.PREFIX+"%").distinctUntilChanged().map {rows->rows.mapNotNull {runCatching{OnlineCollectionContract.decode(JSONObject(it.value))}.getOrNull()}.sortedBy{it.name.lowercase(java.util.Locale.ROOT)}}.flowOn(Dispatchers.Default)
    suspend fun get(id:String)=dao.state(OnlineCollectionContract.PREFIX+id)?.let{OnlineCollectionContract.decode(JSONObject(it.value))}
    suspend fun save(p:OnlineCollection)=app.database.withTransaction {
        val existing=get(p.id)
        require(existing!=null || dao.states(OnlineCollectionContract.PREFIX+"%").size<100){"Maksimal100 playlist online."}
        dao.state(AppState(OnlineCollectionContract.PREFIX+p.id,OnlineCollectionContract.encode(p).toString()))
    }
    suspend fun edit(id:String,change:(OnlineCollection)->OnlineCollection)=app.database.withTransaction {
        val old=get(id) ?: error("Playlist sudah dihapus.");val next=change(old);require(next.id==old.id);save(next)
    }
    suspend fun append(id:String,song:OnlineSong)=edit(id){p->p.copy(songs=(p.songs+song).distinctBy{it.id})}
    suspend fun remove(id:String,index:Int)=edit(id){p->p.copy(songs=p.songs.filterIndexed{i,_->i!=index})}
    suspend fun removeSong(id:String,songId:String)=edit(id){p->p.copy(songs=p.songs.filterNot{it.id==songId})}
    suspend fun shift(id:String,songId:String,delta:Int)=edit(id){p->
        require(delta in setOf(-1,1))
        val from=p.songs.indexOfFirst{it.id==songId};require(from>=0){"Lagu sudah dihapus dari playlist."}
        val to=(from+delta).coerceIn(0,p.songs.lastIndex)
        p.copy(songs=p.songs.toMutableList().apply{add(to,removeAt(from))})
    }
    suspend fun move(id:String,from:Int,to:Int)=edit(id){p->
        require(from in p.songs.indices && to in p.songs.indices)
        p.copy(songs=p.songs.toMutableList().apply{add(to,removeAt(from))})
    }
    suspend fun delete(id:String)=dao.deleteState(OnlineCollectionContract.PREFIX+id)
}
