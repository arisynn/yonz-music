package com.yonzmusic.app.search

import org.json.JSONObject
import java.net.URI

enum class SearchProvider(val label: String) { ENGINE("Mesin yt-dlp") }
data class OnlineSong(val id: String,val provider: SearchProvider,val title: String,val credit: String,
    val album: String="",val durationMs: Long=0,val artwork: String="",val page: String,
    val videoId: String="",val artistVerified: Boolean=false)
data class SearchPage(val songs: List<OnlineSong>,val next: String="")
data class OnlineSearchState(val query: String="",val provider: SearchProvider=SearchProvider.ENGINE,
    val loading: Boolean=false,val songs: List<OnlineSong> = emptyList(),val next: String="",
    val error: String="",val cached: Boolean=false)
class SearchFailure(val userMessage: String): Exception(userMessage)

/** Remote metadata never becomes a local track or evidence of a licensed download. */
object SearchContract {
    val sourceHosts=setOf("www.youtube.com","youtube.com","music.youtube.com","youtu.be")
    val artHosts=setOf("i.ytimg.com","img.youtube.com")
    fun https(value: String,hosts: Set<String>): Boolean=runCatching {
        val u=URI(value);value.length<=4096 && u.scheme=="https" && u.host?.lowercase() in hosts && u.userInfo==null && u.port in setOf(-1,443) && u.fragment==null
    }.getOrDefault(false)
    fun text(value:String,limit:Int=250)=value.filter {!it.isISOControl()}.trim().take(limit)
    fun query(value:String)=text(value.replace(Regex("\\s+")," "),200)
    fun engine(json:String): SearchPage {
        val root=JSONObject(json)
        if(!root.optBoolean("ok"))throw SearchFailure(root.optJSONObject("error")?.optString("message")?.let {text(it)}?.ifBlank {null} ?: "Mesin belum dapat membaca sumber.")
        val rows=root.getJSONArray("songs")
        val songs=(0 until minOf(rows.length(),12)).mapNotNull {i->
            val r=rows.optJSONObject(i) ?: return@mapNotNull null
            val id=r.optString("video_id");val title=text(r.optString("title"));val page=r.optString("page")
            if(!id.matches(Regex("[A-Za-z0-9_-]{11}")) || title.isBlank() || page!="https://www.youtube.com/watch?v=$id")return@mapNotNull null
            val artists=r.optJSONArray("artists")?.let {a->(0 until minOf(a.length(),20)).map {text(a.optString(it))}.filter {it.isNotBlank()}} ?: emptyList()
            OnlineSong("engine:$id",SearchProvider.ENGINE,title,artists.joinToString(", ").ifBlank {text(r.optString("channel"))},text(r.optString("album")),
                r.optLong("duration_ms").coerceIn(0,7*86400000L),r.optString("artwork").takeIf {https(it,artHosts)}.orEmpty(),page,id,artists.isNotEmpty())
        }.distinctBy {it.id}
        return SearchPage(songs,root.optString("next").takeIf {it in listOf("1","2")}.orEmpty())
    }
}
