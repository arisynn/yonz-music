package com.yonzmusic.app.importer

import java.util.Locale

/** Offline formatting suggestions; never resolves media. Review and undo stay in the editor. */
object PasteListFormatter {
    data class Result(val text: String, val changed: Boolean, val needsReview: Boolean, val lines: Int)
    private val pair = Regex("\\s+[-–—]\\s+")
    private val words = Regex("\\S+")
    private val numbered = Regex("(?:^|\\s+)(\\d{1,4})[.)]\\s+")
    private val urls = Regex("(?:https?://|spotify:playlist:)[^\\s]+", RegexOption.IGNORE_CASE)
    private fun key(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun detectKind(raw: String): String {
        val start = raw.trimStart('\uFEFF', ' ', '\t', '\n', '\r')
        if (start.startsWith('{') || Regex("^\\[\\s*(?:\\{|\"|\\])").containsMatchIn(start)) return "json"
        if (start.startsWith("#EXTM3U", true)) return "m3u"
        val header = start.lineSequence().firstOrNull().orEmpty().lowercase(Locale.ROOT)
        for (delimiter in listOf(',', ';', '\t')) {
            val fields = header.split(delimiter).map { it.trim(' ', '"') }
            if (fields.any { it in setOf("title", "track name", "name") } && fields.any { it in setOf("artist", "artists", "artist name(s)", "artist name") }) return "csv"
        }
        return "text"
    }

    /** Bulk edits/paste only: IME composing text and single-key typing are handled by the field. */
    fun isBulkEdit(before: String, after: String): Boolean {
        var start = 0
        while (start < minOf(before.length, after.length) && before[start] == after[start]) start++
        var oldEnd = before.length; var newEnd = after.length
        while (oldEnd > start && newEnd > start && before[oldEnd - 1] == after[newEnd - 1]) { oldEnd--; newEnd-- }
        return newEnd - start > 1
    }

    fun format(raw: String, knownArtists: Collection<String> = emptyList()): Result {
        fun result(value: String, review: Boolean) = Result(value, value != raw, review, value.lineSequence().count { it.isNotBlank() })
        if (detectKind(raw) != "text") return result(raw, false)
        // Auto-formatting is bounded; large files still go through the normal full import path.
        if (raw.length > 65536) return result(raw, true)
        val known = knownArtists.filter { it.length in 1..160 && !it.equals("Unknown artist", true) }.map(::key).toSet()
        var review = false
        val output = mutableListOf<String>()
        for (line in raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if (line.isBlank() || line.trimStart().startsWith('#')) { output += line; continue }
            val foundUrls = urls.findAll(line).toList()
            if (foundUrls.isNotEmpty()) {
                if (foundUrls.size > 1 && urls.replace(line, "").isBlank()) output += foundUrls.map { it.value }
                else output += line
                continue // Never rewrite URL paths, signatures or Markdown link captions.
            }
            val positions = topLevel(line)
            val numberedItems = numbered.findAll(line).filter { positions[it.range.first] }.toList()
            val numberedList = numberedItems.size > 1 && numberedItems.first().range.first == 0 && numberedItems.map { it.groupValues[1].toInt() }.zipWithNext().all { (a,b) -> b == a + 1 }
            val explicit = if (numberedList) numberedItems.mapIndexed { index, match ->
                line.substring(match.range.last + 1, numberedItems.getOrNull(index + 1)?.range?.first ?: line.length).trim()
            } else splitExplicit(line, positions)
            val chunks = if (explicit.size > 1 && explicit.all { pair.containsMatchIn(it) }) explicit else listOf(line)
            for (chunk in chunks) {
                if (chunk.length > 4096) { output += chunk; review = true; continue }
                val separators = pair.findAll(chunk).toList()
                if (separators.size < 2) { output += chunk; continue }
                val firstArtist = chunk.substring(0, separators[0].range.first).replace(Regex("^\\s*(?:\\d+[.)]|[-*+•])\\s+"), "").trim()
                val artists = known + key(firstArtist)
                val top = topLevel(chunk)
                var start = 0; var titleStart = separators[0].range.last + 1
                for (separator in separators.drop(1)) {
                    val prefix = chunk.substring(titleStart, separator.range.first)
                    val tokens = words.findAll(prefix).toList()
                    val artistStart = (1..minOf(12, tokens.size)).map { n -> titleStart + tokens[tokens.size - n].range.first }
                        .filter { key(chunk.substring(it, separator.range.first)) in artists }.minOrNull()
                    val previous = if (artistStart != null) chunk.substring(titleStart, artistStart).trimEnd() else ""
                    val feature = Regex("(?:feat\\.?|ft\\.?|featuring|with|by|to|&)\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(previous)
                    if (artistStart != null && previous.isNotBlank() && top[artistStart] && top[separator.range.first] && !feature) {
                        output += chunk.substring(start, artistStart).trimEnd()
                        start = artistStart; titleStart = separator.range.last + 1
                    } else review = true
                }
                output += chunk.substring(start)
            }
        }
        return result(output.joinToString("\n"), review)
    }
    private fun topLevel(line: String): BooleanArray {
        var depth = 0; var quoted = false
        return BooleanArray(line.length + 1).also { levels ->
            line.forEachIndexed { i, c ->
                levels[i] = depth == 0 && !quoted
                if (c == '"' && (i == 0 || line[i-1] != '\\')) quoted = !quoted
                if (!quoted) { if (c == '(' || c == '[') depth++; if (c == ')' || c == ']') depth = (depth - 1).coerceAtLeast(0) }
            }
            levels[line.length] = depth == 0 && !quoted
        }
    }
    private fun splitExplicit(line: String, top: BooleanArray): List<String> {
        val boundaries = line.indices.filter { top[it] && line[it] in ";|•" }
        if (boundaries.isEmpty()) return listOf(line)
        var start = 0
        return (boundaries + line.length).map { end -> line.substring(start, end).trim().also { start = end + 1 } }
    }
}
