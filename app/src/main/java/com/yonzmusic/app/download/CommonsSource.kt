package com.yonzmusic.app.download

import android.text.Html
import com.yonzmusic.app.network.SafeHttp
import org.json.JSONObject
import java.net.URLEncoder
import com.yonzmusic.app.importer.SongRequest

data class AudioCandidate(val id: String, val title: String, val artist: String, val album: String,
    val duration: Long, val size: Long, val url: String, val license: String, val licenseUrl: String,
    val page: String, val description: String = "", val artUrl: String = "", val request: SongRequest? = null)

/** Commons publishes these files for downloading under the returned free licenses. No streaming extraction. */
object CommonsSource {
    val hosts = setOf("commons.wikimedia.org", "upload.wikimedia.org")
    private fun text(value: String) = Html.fromHtml(value.take(12000), Html.FROM_HTML_MODE_LEGACY).toString().trim().take(1500)
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
    fun search(query: String): List<AudioCandidate> {
        require(query.isNotBlank() && query.length <= 250) { "Enter a title or artist (up to 250 characters)." }
        val exact = query.takeIf { it.startsWith("https://commons.wikimedia.org/wiki/File:") }?.substringAfter("/wiki/")?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: query.takeIf { it.startsWith("File:") }
        val parameters = if (exact != null) "titles=${encoded(exact)}" else "generator=search&gsrnamespace=6&gsrlimit=12&gsrsearch=${encoded("filetype:audio $query")}"
        val response = SafeHttp.get("https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=videoinfo&viprop=url%7Csize%7Cmime%7Cextmetadata&$parameters", hosts)
        require(response.status == 200) { "Commons is unavailable (HTTP ${response.status}). Try again later." }
        val json = JSONObject(String(response.bytes, Charsets.UTF_8))
        require(!json.has("error")) { "Commons could not search this request." }
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        return pages.keys().asSequence().mapNotNull { key ->
            val page = pages.getJSONObject(key)
            val info = page.optJSONArray("videoinfo")?.optJSONObject(0) ?: return@mapNotNull null
            val metadata = info.optJSONObject("extmetadata") ?: return@mapNotNull null
            fun meta(name: String) = text(metadata.optJSONObject(name)?.optString("value").orEmpty())
            val license = meta("LicenseShortName"); val licenseUrl = meta("LicenseUrl")
            val permitted = license == "CC0" || license.startsWith("Public domain", true) ||
                license.startsWith("CC BY ") || license.startsWith("CC BY-SA ")
            val url = info.optString("url").substringBefore('?')
            val mime = info.optString("mime")
            val size = info.optLong("size")
            val duration = (info.optDouble("duration", 0.0) * 1000).toLong()
            if (!permitted || meta("Restrictions").isNotBlank() || duration <= 0 || size !in 1..com.yonzmusic.app.data.Repository.MAX_AUDIO_BYTES ||
                !(mime.startsWith("audio/") || mime == "application/ogg")) return@mapNotNull null
            if (runCatching { SafeHttp.safeUrl(url, setOf("upload.wikimedia.org")) }.isFailure) return@mapNotNull null
            AudioCandidate("commons:${page.optLong("pageid")}", meta("ObjectName").ifBlank { page.optString("title").removePrefix("File:").substringBeforeLast('.') },
                meta("Artist").ifBlank { "Unspecified credits" }, "Wikimedia Commons", duration, size, url, license,
                licenseUrl, info.optString("descriptionurl"), meta("ImageDescription"))
        }.toList()
    }
}
