package com.yonzmusic.app.home

import android.media.MediaMetadataRetriever
import android.net.Uri
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.data.AppState
import com.yonzmusic.app.data.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Read real embedded genre tags off the playback path. Cached failures prevent repeated SAF reads. */
internal class HomeLocalTags(private val app:YonzApp,private val scope:CoroutineScope) {
    val state=MutableStateFlow<Map<String,List<String>>>(emptyMap())
    private val versions=linkedMapOf<String,String>()
    private var job:Job?=null
    private val restore=scope.async(Dispatchers.IO) {
        runCatching {app.repository.dao.state("home-local-genres:v1")?.value?.takeIf {it.length<=256*1024}?.let {raw->
            val root=JSONObject(raw);val tags=mutableMapOf<String,List<String>>()
            root.keys().asSequence().take(1000).forEach {id->val row=root.getJSONObject(id);versions[id]=row.optString("version");tags[id]=com.yonzmusic.app.search.SearchContract.labels(row.optJSONArray("genres"))}
            state.value=tags
        }}
    }
    fun inspect(tracks:List<Track>) {
        if(job?.isActive==true)return
        job=scope.launch(Dispatchers.IO) {
            restore.await()
            try {
            // At most sixteen small metadata reads per Home visit, serially; never extract audio.
            for(t in tracks.distinctBy {it.id}.take(1000)) {
                ensureActive()
                val version="${t.uri}|${t.modified}|${t.fileSize}|${t.artRevision}"
                if(versions[t.id]==version)continue
                if(versions.size>=1000 && t.id !in versions)continue
                val genre=runCatching {
                    val r=MediaMetadataRetriever()
                    try {r.setDataSource(app,Uri.parse(t.uri));r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty()}finally {r.release()}
                }.getOrDefault("")
                val genres=genre.split(';','/').map {it.trim().take(60)}.filter {it.any(Char::isLetter) && !it.equals("unknown",true)}.distinct().take(8)
                versions[t.id]=version;state.value=state.value+(t.id to genres)
                reads++
                if(reads>=16)break
                delay(80)
            }
            val result=JSONObject();versions.forEach {(id,version)->result.put(id,JSONObject().put("version",version).put("genres",org.json.JSONArray(state.value[id].orEmpty())))}
            app.repository.dao.state(AppState("home-local-genres:v1",result.toString()))
            }catch(e:CancellationException){throw e}catch(_:Exception){/* Disposable metadata cache must not fail Home. */}finally {reads=0}
        }
    }
    private var reads=0
}
