package com.yonzmusic.app.metadata

import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.search.*
import com.yonzmusic.app.data.AppState
import com.yonzmusic.app.playback.StreamRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray

/** One process-wide authority; persistence uses existing AppState, never resolved audio URLs. */
class MetadataRepository(private val app:YonzApp) {
    private val mutable=MutableStateFlow<Map<String,OnlineSong>>(emptyMap())
    val state=mutable.asStateFlow()
    @Synchronized fun publish(song:OnlineSong):OnlineSong {
        val merged=MetadataPolicy.merge(mutable.value[song.id],song)
        val rows=LinkedHashMap(mutable.value);rows.remove(song.id);rows[song.id]=merged
        while(rows.size>500)rows.remove(rows.keys.first())
        mutable.value=rows
        return merged
    }
    fun current(song:OnlineSong)=mutable.value[song.id]?.let {MetadataPolicy.merge(song,it)} ?: song
    fun persist(song:OnlineSong) {app.persistenceScope.launch {
        val latest=state.value[song.id] ?: song
        app.repository.dao.state(AppState("stream:${if(latest.id.startsWith("mb:"))latest.id else latest.videoId}",StreamRepository.encode(latest).toString()))
    }}
    fun extracted(videoId:String,song:JSONObject) {
        val artists=song.optJSONArray("artists") ?: JSONArray()
        val row=JSONObject(song.toString()).put("video_id",videoId).put("page","https://www.youtube.com/watch?v=$videoId")
            .put("duration_ms",(song.optDouble("duration",0.0)*1000).toLong()).put("artwork",song.optString("artwork_url")).put("metadata_source","EXTRACTION")
        val found=runCatching {SearchContract.engine(JSONObject().put("ok",true).put("songs",JSONArray().put(row)).toString(),1).songs.firstOrNull()}.getOrNull() ?: return
        val full=found.copy(artists=(0 until artists.length()).map {artists.optString(it)},metadataUpdatedAt=System.currentTimeMillis())
        // Canonical queue entries only share enrichment when their selected video identity is exact.
        val matches=state.value.values.filter {it.videoId==videoId}
        (matches.ifEmpty {listOf(full)}).forEach {old->
            val value=publish(full.copy(id=old.id,catalogJson=old.catalogJson));persist(value)
        }
    }
}
