package com.yonzmusic.app.search

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

enum class SearchProvider(val label: String) { APPLE("iTunes"), YOUTUBE("YouTube"), COMMONS("Commons") }
data class OnlineSong(val id: String, val provider: SearchProvider, val title: String, val credit: String,
    val album: String = "", val durationMs: Long = 0, val artwork: String = "", val page: String,
    val preview: String = "", val videoId: String = "", val license: String = "", val licenseUrl: String = "")
data class SearchPage(val songs: List<OnlineSong>, val next: String = "")
data class OnlineSearchState(val query: String = "", val provider: SearchProvider = SearchProvider.APPLE,
    val loading: Boolean = false, val songs: List<OnlineSong> = emptyList(), val next: String = "",
    val error: String = "", val needsKey: Boolean = false, val cached: Boolean = false)

/** Remote results are never local Track rows or evidence that an audio file exists. */
object SearchContract {
    val appleApi = setOf("itunes.apple.com")
    val applePages = setOf("music.apple.com", "itunes.apple.com")
    val appleAudio = setOf("audio-ssl.itunes.apple.com")
    val youtubeApi = setOf("www.googleapis.com")
    val youtubePages = setOf("www.youtube.com", "youtube.com", "music.youtube.com", "youtu.be", "www.youtube-nocookie.com")
    val audioHosts = appleAudio + "upload.wikimedia.org"
    val artHosts = (1..5).map { "is$it-ssl.mzstatic.com" }.toSet() + setOf("i.ytimg.com", "upload.wikimedia.org")
    fun https(value: String, hosts: Set<String>): Boolean = runCatching {
        val u = URI(value)
        value.length <= 4096 && u.scheme == "https" && u.host?.lowercase() in hosts && u.userInfo == null && u.port in setOf(-1,443) && u.fragment == null
    }.getOrDefault(false)
    fun text(value: String, limit: Int = 250) = value.filter { !it.isISOControl() }.trim().take(limit)
    fun query(value: String) = text(value.replace(Regex("\\s+"), " "), 200)
    fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8")
    fun apple(json: String): SearchPage {
        val rows = JSONObject(json).getJSONArray("results")
        val songs = (0 until minOf(rows.length(), 25)).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optLong("trackId"); val title = text(row.optString("trackName"))
            val page = row.optString("trackViewUrl")
            if (row.optString("kind") != "song" || id <= 0 || title.isBlank() || !https(page, applePages)) return@mapNotNull null
            OnlineSong("apple:$id", SearchProvider.APPLE, title, text(row.optString("artistName")), text(row.optString("collectionName")),
                row.optLong("trackTimeMillis").coerceAtLeast(0), row.optString("artworkUrl100").takeIf { https(it,artHosts) }.orEmpty(), page,
                row.optString("previewUrl").takeIf { https(it,appleAudio) }.orEmpty())
        }.distinctBy { it.id }
        return SearchPage(songs)
    }
    fun youtubeId(value: String): String? = runCatching {
        val u = URI(value)
        if (!https(value, youtubePages) || u.host == "www.youtube-nocookie.com") return@runCatching null
        val id = if (u.host == "youtu.be") u.path.removePrefix("/") else if(u.path.startsWith("/shorts/")) u.path.substringAfter("/shorts/")
            else u.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.substringAfter("v=")
        id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }.getOrNull()
    fun youtube(json: String, durations: Map<String, Long> = emptyMap()): SearchPage {
        val root = JSONObject(json); val rows = root.getJSONArray("items")
        val songs = (0 until minOf(rows.length(), 15)).mapNotNull { i ->
            val r = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = r.optJSONObject("id")?.optString("videoId").orEmpty()
            val s = r.optJSONObject("snippet") ?: return@mapNotNull null
            if(!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return@mapNotNull null
            val thumb = s.optJSONObject("thumbnails")?.let { it.optJSONObject("medium") ?: it.optJSONObject("default") }?.optString("url").orEmpty()
            OnlineSong("youtube:$id",SearchProvider.YOUTUBE,text(s.optString("title")),text(s.optString("channelTitle")),
                durationMs=durations[id] ?: 0,artwork=thumb.takeIf { https(it,artHosts) }.orEmpty(),page="https://www.youtube.com/watch?v=$id",videoId=id)
        }.distinctBy { it.id }
        return SearchPage(songs,root.optString("nextPageToken").takeIf { it.matches(Regex("[A-Za-z0-9_=-]{1,512}")) }.orEmpty())
    }
    fun duration(value: String): Long = runCatching { java.time.Duration.parse(value).toMillis().coerceIn(0, 7 * 86400000L) }.getOrDefault(0)
}
