package com.yonzmusic.app.download

import com.yonzmusic.app.importer.MusicListParser
import com.yonzmusic.app.importer.SongRequest
import kotlin.math.abs

/** Ports the CLI's conservative version/all-credit/duration checks; no fuzzy artist substitution. */
object RecordingMatch {
    private val versionWords = setOf("live", "acoustic", "remix", "remixed", "instrumental", "karaoke", "cover", "sped", "slowed", "nightcore", "remaster", "remastered", "demo", "edit", "rerecorded", "version", "mono", "stereo", "clean", "explicit")
    private val featured = Regex("""[\[(]\s*(?:featuring|feat\.?|ft\.?)\s+([^\])]+)[\])]""", RegexOption.IGNORE_CASE)
    fun title(value: String) = MusicListParser.titleKey(value.replace(featured, ""))
    fun artists(value: String, title: String = ""): Set<String> = MusicListParser.artistKey(value) +
        featured.findAll(title).flatMap { MusicListParser.artistKey(it.groupValues[1]) }.toSet()
    fun variants(value: String) = MusicListParser.normalize(value).split(' ').toSet().intersect(versionWords)
    fun rejection(request: SongRequest, candidate: AudioCandidate): String? = when {
        title(request.title) != title(candidate.title) -> "Title differs"
        variants(request.title) != variants(candidate.title) -> "Recording/version differs"
        request.artist.isBlank() -> "Artist credits are missing; review an exact source instead"
        artists(request.artist, request.title) != artists(candidate.artist, candidate.title) -> "Artist credits differ or are incomplete"
        request.duration > 0 && abs(request.duration - candidate.duration) > minOf(6000, maxOf(2500, (request.duration * .015).toLong())) -> "Duration differs"
        else -> null
    }
    fun safeName(value: String, fallback: String = "Music"): String {
        val clean = value.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim(' ', '.')
        var end = clean.length.coerceAtMost(90)
        if (end > 0 && Character.isHighSurrogate(clean[end - 1])) end--
        while (clean.substring(0, end).toByteArray(Charsets.UTF_8).size > 175) {
            end -= if (end > 1 && Character.isLowSurrogate(clean[end - 1])) 2 else 1
        }
        return clean.substring(0, end).trim(' ', '.').ifBlank { fallback }
    }
}
