package com.yonzmusic.app.importer

import com.yonzmusic.app.data.Track
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Metadata import only: this class never opens a path or a URL. */
data class SongRequest(val raw: String, val title: String = "", val artist: String = "", val reference: String = "", val duration: Long = 0, val error: String = "")
data class ImportRow(val input: String, val status: String, val detail: String, val trackId: String? = null)
data class ImportPreview(val rows: List<ImportRow>) {
    val matched get() = rows.filter { it.status == "Matched" }
    fun count(status: String) = rows.count { it.status == status }
    fun json(): String = JSONArray().apply { rows.forEach { put(JSONObject().put("input", it.input).put("status", it.status).put("detail", it.detail).put("trackId", it.trackId)) } }.toString()
}

object MusicListParser {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_ROWS = 10000
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    fun titleKey(text: String): String = normalize(text.replace(Regex("""[\[(]\s*(?:official\s+)?(?:audio|music video|video|lyrics?)\s*[\])]""", RegexOption.IGNORE_CASE), ""))
    fun artistKey(text: String): Set<String> = text.split(Regex("""\s*;\s*|\s+(?:featuring|feat\.?|ft\.?)\s+""", RegexOption.IGNORE_CASE)).map(::normalize).filter { it.isNotEmpty() }.toSet()
    private fun remote(value: String) = Regex("""^(?:https?://|spotify:|ftp://|data:|javascript:)""", RegexOption.IGNORE_CASE).containsMatchIn(value.trim())
    private fun textRequest(raw: String): SongRequest? {
        var line = raw.trim().trimStart('\uFEFF')
        if (line.isEmpty() || line.startsWith('#') || line.startsWith("```")) return null
        if (line.length > 2048) return SongRequest(line.take(256), error = "Entry exceeds 2,048 characters")
        line = line.replace(Regex("""^(?:\d+[.)]|[-*+•])\s+"""), "").replace("**", "").trim()
        if (line.endsWith(':') && !remote(line)) return null
        if (line.startsWith("https://", true)) return SongRequest(line, reference = line)
        val sourceLink = Regex("""^\[([^\]]+)\]\((https://[^\s)]+)\)$""", RegexOption.IGNORE_CASE).matchEntire(line)
        if (sourceLink != null) return (textRequest(sourceLink.groupValues[1]) ?: SongRequest(line)).copy(raw = line, reference = sourceLink.groupValues[2])
        if (remote(line) || line.startsWith("[https", true) || Regex("""\]\(https?://""", RegexOption.IGNORE_CASE).containsMatchIn(line)) return SongRequest(line, error = "Use an HTTPS source in Downloads, or local audio/metadata.")
        val parts = line.split(Regex("""\s+(?:-|–|—|\|)\s+"""), limit = 2)
        return if (parts.size == 2) SongRequest(line, parts[1].trim(), parts[0].trim()) else SongRequest(line, line)
    }
    fun parse(text: String, fileName: String = "pasted.txt"): List<SongRequest> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Lists are limited to 2 MB." }
        val clean = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val extension = fileName.substringAfterLast('.').lowercase(Locale.ROOT)
        val rows = when {
            extension in setOf("m3u", "m3u8") || clean.startsWith("#EXTM3U") -> m3u(clean)
            extension == "json" || Regex("""^\[\s*(?:\{|"|\])""").containsMatchIn(clean) || clean.startsWith('{') -> json(clean)
            extension == "csv" -> csv(clean)
            else -> clean.lineSequence().mapNotNull(::textRequest).take(MAX_ROWS + 1).toList()
        }
        require(rows.size <= MAX_ROWS) { "Lists are limited to 10,000 entries." }
        return rows.map { row ->
            if (listOf(row.raw, row.title, row.artist, row.reference).any { it.length > 2048 }) SongRequest(row.raw.take(256), error = "Entry exceeds 2,048 characters")
            else row
        }
    }
    private fun m3u(text: String): List<SongRequest> {
        val result = mutableListOf<SongRequest>()
        var info: SongRequest? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF:", true)) {
                info = textRequest(line.substringAfter(','))?.copy(duration = (line.substringAfter(':').substringBefore(',').toDoubleOrNull()?.coerceAtLeast(0.0)?.times(1000))?.toLong() ?: 0)
            } else if (line.isNotBlank() && !line.startsWith('#')) {
                require(result.size < MAX_ROWS) { "Lists are limited to 10,000 entries." }
                result += if (remote(line)) SongRequest(line, error = "Remote audio URLs are unsupported.")
                    else (info ?: SongRequest(line)).copy(raw = info?.raw ?: line, reference = line)
                info = null
            }
        }
        return result
    }
    private fun json(text: String): List<SongRequest> {
        // Bound nesting before passing untrusted content to the platform JSON parser.
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (escaped) { escaped = false; continue }
            if (quoted && c == '\\') { escaped = true; continue }
            if (c == '\"') { quoted = !quoted; continue }
            if (!quoted && (c == '{' || c == '[')) { depth++; require(depth <= 32) { "JSON nesting is limited to 32 levels." } }
            if (!quoted && (c == '}' || c == ']')) depth--
        }
        val root: Any = if (text.startsWith('[')) JSONArray(text) else JSONObject(text)
        val values = when (root) {
            is JSONArray -> root
            is JSONObject -> when (val tracks = root.opt("tracks") ?: root.opt("songs") ?: root.opt("items")) {
                is JSONArray -> tracks
                is JSONObject -> tracks.optJSONArray("items") ?: error("JSON tracks must contain an items array.")
                else -> error("JSON needs a tracks, songs, or items array.")
            }
            else -> error("Unsupported JSON.")
        }
        require(values.length() <= MAX_ROWS) { "Lists are limited to 10,000 entries." }
        return (0 until values.length()).map { n ->
            when (val value = values.opt(n)) {
                is String -> textRequest(value) ?: SongRequest("Row ${n + 1}", error = "Empty entry")
                is JSONObject -> {
                    val row = value.optJSONObject("track") ?: value
                    val title = row.optString("title").ifBlank { row.optString("name") }
                    val artists = when (val a = row.opt("artists") ?: row.opt("artist")) {
                        is JSONArray -> (0 until a.length()).map { i -> val item = a.opt(i); if (item is JSONObject) item.optString("name") else item?.toString().orEmpty() }.joinToString("; ")
                        is String -> a
                        else -> ""
                    }
                    val duration = if (row.has("duration_ms")) row.optLong("duration_ms") else seconds(row.optString("duration"))
                    val source = row.optString("source_url").ifBlank { row.optString("audio_url") }.ifBlank { row.optString("uri") }
                    SongRequest(listOf(artists, title).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { "Row ${n + 1}" }, title, artists, reference = source, duration = duration, error = if (title.isBlank() && source.isBlank()) "Missing title" else "")
                }
                else -> SongRequest("Row ${n + 1}", error = "Expected a song object or text entry")
            }
        }
    }
    private fun seconds(text: String): Long {
        var result = 0.0
        for (part in text.split(':')) result = result * 60 + (part.toDoubleOrNull() ?: return 0)
        return if (result.isFinite() && result > 0 && result < 8640000) (result * 1000).toLong() else 0
    }
    private fun csv(text: String): List<SongRequest> {
        val header = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = listOf(',', ';', '\t').maxBy { d -> header.count { it == d } }
        val table = csvTable(text, delimiter)
        require(table.isNotEmpty()) { "CSV is empty." }
        val names = table.first().map(::normalize)
        fun field(row: List<String>, vararg keys: String): String {
            val index = names.indexOfFirst { it in keys.map(::normalize) }
            return row.getOrNull(index).orEmpty().trim()
        }
        require(names.any { it in setOf("title", "name", "track name") }) { "CSV needs a title or Track Name header." }
        return table.drop(1).filter { row -> row.any { it.isNotBlank() } }.mapIndexed { i, row ->
            val title = field(row, "title", "name", "Track Name")
            val artist = field(row, "artist", "artists", "Artist Name", "Artist Name(s)")
            val ms = field(row, "duration_ms", "Duration (ms)", "Track Duration (ms)").toLongOrNull()
            val source = field(row, "source_url", "audio_url", "Source URL", "Audio URL", "uri")
            SongRequest("${artist.ifBlank { "Row ${i + 2}" }} — $title", title, artist, reference = source, duration = ms ?: seconds(field(row, "duration", "Duration (s)", "duration_seconds")), error = if (title.isBlank() && source.isBlank()) "Missing title" else "")
        }
    }
    internal fun csvTable(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>(); val row = mutableListOf<String>(); val value = StringBuilder()
        var quoted = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && text.getOrNull(i + 1) == '"' -> { value.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> { row.add(value.toString()); value.clear() }
                c == '\n' && !quoted -> { row.add(value.toString().trimEnd('\r')); value.clear(); rows.add(row.toList()); row.clear() }
                else -> value.append(c)
            }
            require(rows.size <= MAX_ROWS + 1) { "Too many CSV rows." }
            i++
        }
        require(!quoted) { "Unclosed CSV quote." }
        if (value.isNotEmpty() || row.isNotEmpty()) { row.add(value.toString().trimEnd('\r')); rows.add(row.toList()) }
        return rows
    }
    fun match(requests: List<SongRequest>, library: List<Track>): ImportPreview {
        val local = library.filter { it.active && it.available }
        val titles = local.groupBy { com.yonzmusic.app.download.RecordingMatch.title(it.title) }
        val filenames = local.filter { it.fileName.isNotBlank() }.groupBy { it.fileName.lowercase(Locale.ROOT) }
        val uris = local.associateBy { it.uri }
        val seen = HashSet<String>()
        val rows = requests.map { request ->
            val candidates: List<Track> = when {
                request.error.isNotBlank() -> emptyList()
                request.reference.startsWith("content://") -> listOfNotNull(uris[request.reference])
                request.reference.startsWith("https://") -> titles[com.yonzmusic.app.download.RecordingMatch.title(request.title)].orEmpty()
                request.reference.isNotBlank() -> {
                    val basename = runCatching { java.net.URLDecoder.decode(request.reference.replace('+', '\u0000'), "UTF-8").replace('\u0000', '+') }.getOrDefault(request.reference).replace('\\', '/').substringAfterLast('/')
                    val byFile = filenames[basename.lowercase(Locale.ROOT)].orEmpty()
                    if (byFile.isNotEmpty()) byFile else titles[com.yonzmusic.app.download.RecordingMatch.title(request.title)].orEmpty()
                }
                else -> titles[com.yonzmusic.app.download.RecordingMatch.title(request.title)].orEmpty()
            }
            val compatible = candidates.filter { t ->
                (request.title.isBlank() || com.yonzmusic.app.download.RecordingMatch.title(t.title) == com.yonzmusic.app.download.RecordingMatch.title(request.title)) &&
                    (request.artist.isBlank() || com.yonzmusic.app.download.RecordingMatch.artists(t.artist, t.title) == com.yonzmusic.app.download.RecordingMatch.artists(request.artist, request.title)) &&
                    (request.duration <= 0 || abs(t.duration - request.duration) <= 2500)
            }
            when {
                request.error.isNotBlank() -> ImportRow(request.raw, "Invalid", request.error)
                compatible.isEmpty() -> ImportRow(request.raw, "Unmatched", "No compatible local audio found")
                compatible.size > 1 -> ImportRow(request.raw, "Unmatched", "Ambiguous: ${compatible.size} local recordings; specify artist/duration or use an exact content URI")
                !seen.add(compatible.single().id) -> ImportRow(request.raw, "Duplicate", "Already included in this import", compatible.single().id)
                else -> ImportRow(request.raw, "Matched", "${compatible.single().artist} — ${compatible.single().title}", compatible.single().id)
            }
        }
        return ImportPreview(rows)
    }
    fun export(tracks: List<Track>): String = buildString {
        appendLine("#EXTM3U")
        tracks.forEach { track ->
            val label = "${track.artist} - ${track.title}".replace('\n', ' ').replace('\r', ' ')
            appendLine("#EXTINF:${track.duration / 1000},$label")
            appendLine(track.uri)
        }
    }
}
