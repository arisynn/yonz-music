package com.yonzmusic.app

import com.yonzmusic.app.data.Track
import com.yonzmusic.app.importer.*
import org.junit.Assert.*
import org.junit.Test

class MusicListParserTest {
    private fun track(id: String = "1", title: String = "Mangu", artist: String = "Fourtwnty; Charita Utami", file: String = "song.mp3", duration: Long = 263000) = Track(id, "content://media/external/audio/media/$id", title, artist, fileName = file, duration = duration)
    @Test fun numberedMarkdownAndArtistHyphen() {
        val rows = MusicListParser.parse("# A list\n```text\n1. **Jay-Z - Song With - A Subtitle**\n- Tulus — Diri\n```")
        assertEquals(2, rows.size); assertEquals("Jay-Z", rows[0].artist); assertEquals("Song With - A Subtitle", rows[0].title)
    }
    @Test fun accentsAndWhitespaceNormalize() { assertEquals("beyonce deja vu", MusicListParser.normalize("  Beyoncé — DÉJÀ vu! ")) }
    @Test fun officialLabelIsPresentation() { assertEquals("mangu", MusicListParser.titleKey("Mangu (Official Audio)")) }
    @Test fun recordingVersionsArePreserved() {
        val p = MusicListParser.match(MusicListParser.parse("Fourtwnty; Charita Utami - Mangu (Live)"), listOf(track()))
        assertEquals("Unmatched", p.rows.single().status)
    }
    @Test fun featuredCreditMatchesFullNames() {
        val p = MusicListParser.match(MusicListParser.parse("Fourtwnty feat. Charita Utami - Mangu"), listOf(track()))
        assertEquals(1, p.matched.size)
    }
    @Test fun wrongArtistNeverMatches() { assertEquals(0, MusicListParser.match(MusicListParser.parse("Another Artist - Mangu"), listOf(track())).matched.size) }
    @Test fun titleOnlyCanMatchUniqueLocalAudio() { assertEquals(1, MusicListParser.match(MusicListParser.parse("Mangu"), listOf(track())).matched.size) }
    @Test fun titleOnlyAmbiguityIsNotGuessed() {
        val p = MusicListParser.match(MusicListParser.parse("Mangu"), listOf(track(), track("2", artist = "Other Artist")))
        assertEquals("Unmatched", p.rows.single().status); assertTrue(p.rows.single().detail.startsWith("Ambiguous"))
    }
    @Test fun duplicateRowsAreCountedOnce() {
        val p = MusicListParser.match(MusicListParser.parse("Mangu\nMangu\nMissing"), listOf(track()))
        assertEquals(1, p.matched.size); assertEquals(1, p.count("Duplicate")); assertEquals(1, p.count("Unmatched"))
    }
    @Test fun unavailableFileNeverMatches() { assertEquals(0, MusicListParser.match(MusicListParser.parse("Mangu"), listOf(track().copy(available = false))).matched.size) }
    @Test fun unmatchedHasNoInventedId() { assertNull(MusicListParser.match(MusicListParser.parse("Not on disk"), emptyList()).rows.single().trackId) }
    @Test fun remoteLinkNeedsDownloadReviewAndIsNeverLocalAudio() { assertEquals("Unmatched", MusicListParser.match(MusicListParser.parse("https://example.org/audio.mp3"), emptyList()).rows.single().status) }
    @Test fun jsonSimpleAndArtistsArray() {
        val rows = MusicListParser.parse("""{"tracks":[{"title":"Mangu","artists":["Fourtwnty","Charita Utami"],"duration":263}]}""", "a.json")
        assertEquals(263000L, rows.single().duration); assertEquals(1, MusicListParser.match(rows, listOf(track())).matched.size)
    }
    @Test fun jsonNestedSpotifyExportMetadataOnly() {
        val rows = MusicListParser.parse("""{"tracks":{"items":[{"track":{"name":"Mangu","artists":[{"name":"Fourtwnty"},{"name":"Charita Utami"}],"duration_ms":263000}}]}}""", "a.json")
        assertEquals(1, MusicListParser.match(rows, listOf(track())).matched.size)
    }
    @Test fun jsonMissingTitleReportsInvalid() { assertEquals("Missing title", MusicListParser.parse("""[{"artist":"Someone"}]""", "a.json").single().error) }
    @Test fun jsonInvalidValueIsARowError() { assertTrue(MusicListParser.parse("[123]", "a.json").single().error.isNotEmpty()) }
    @Test(expected = Exception::class) fun malformedJsonIsRejected() { MusicListParser.parse("{bad", "a.json") }
    @Test fun csvExportHeadersAndMilliseconds() {
        val rows = MusicListParser.parse("Track Name,Artist Name(s),Duration (ms)\nMangu,Fourtwnty; Charita Utami,263000", "a.csv")
        assertEquals(263000L, rows.single().duration); assertEquals(1, MusicListParser.match(rows, listOf(track())).matched.size)
    }
    @Test fun csvQuotedCommasAndEscapedQuotes() {
        val rows = MusicListParser.parse("title,artist\n\"Hello, \"\"world\"\"\",Singer", "a.csv")
        assertEquals("Hello, \"world\"", rows.single().title)
    }
    @Test fun csvSemicolonDelimiter() { assertEquals("Diri", MusicListParser.parse("title;artist\nDiri;Tulus", "a.csv").single().title) }
    @Test fun csvQuotedMultiline() { assertEquals("A\nB", MusicListParser.parse("title,artist\n\"A\nB\",Singer", "a.csv").single().title) }
    @Test(expected = IllegalArgumentException::class) fun brokenCsvQuoteRejected() { MusicListParser.parse("title,artist\n\"unfinished,Singer", "a.csv") }
    @Test(expected = IllegalArgumentException::class) fun csvWithoutTitleHeaderRejected() { MusicListParser.parse("artist,album\nSinger,Album", "a.csv") }
    @Test fun durationMismatchIsUnmatched() { assertEquals(0, MusicListParser.match(listOf(SongRequest("Mangu", "Mangu", duration = 200000)), listOf(track())).matched.size) }
    @Test fun relativeM3uMatchesIndexedFilenameOnly() {
        val rows = MusicListParser.parse("#EXTM3U\n#EXTINF:263,Fourtwnty; Charita Utami - Mangu\n../music/song.mp3", "a.m3u8")
        assertEquals(1, MusicListParser.match(rows, listOf(track())).matched.size)
    }
    @Test fun m3uDoesNotOpenTraversalPaths() {
        val rows = MusicListParser.parse("../../private/secret.mp3", "a.m3u")
        assertEquals(0, MusicListParser.match(rows, listOf(track())).matched.size)
    }
    @Test fun m3uRemoteReferencesAreInvalid() { assertTrue(MusicListParser.parse("#EXTM3U\nhttps://example.org/a.mp3", "a.m3u").single().error.isNotBlank()) }
    @Test fun ambiguousFilenameNotGuessed() { val rows = MusicListParser.parse("song.mp3", "a.m3u"); assertEquals(0, MusicListParser.match(rows, listOf(track(), track("2"))).matched.size) }
    @Test fun exportRoundTripsOnSameLibrary() {
        val tracks = listOf(track(), track("2", title = "Diri", artist = "Tulus", file = "diri.mp3"))
        val p = MusicListParser.match(MusicListParser.parse(MusicListParser.export(tracks), "a.m3u8"), tracks)
        assertEquals(listOf("1", "2"), p.matched.map { it.trackId })
    }
    @Test fun reportKeepsErrorsAndStatus() { val p = MusicListParser.match(MusicListParser.parse("Not here"), emptyList()); assertTrue(p.json().contains("Unmatched")) }
    @Test(expected = IllegalArgumentException::class) fun oversizedInputRejected() { MusicListParser.parse("a".repeat(MusicListParser.MAX_BYTES + 1)) }
    @Test(expected = IllegalArgumentException::class) fun tooManyEntriesRejected() { MusicListParser.parse("Song\n".repeat(10001)) }
    @Test fun markdownSourceLinkPreservesReferenceForExplicitReview() { val row = MusicListParser.parse("[Song](https://example.org/a)").single(); assertEquals("https://example.org/a", row.reference); assertTrue(row.error.isBlank()); assertTrue(MusicListParser.match(listOf(row), emptyList()).matched.isEmpty()) }
    @Test(expected = IllegalArgumentException::class) fun excessiveJsonNestingRejected() { MusicListParser.parse("[".repeat(40) + "0" + "]".repeat(40), "a.json") }
    @Test fun tenThousandEntryLibraryMatchesWithoutFalseDuplicates() {
        val library = (1..10000).map { track(it.toString(), title = "Track $it", artist = "Artist $it", file = "$it.mp3") }
        val requests = MusicListParser.parse((1..10000).joinToString("\n") { "Artist $it - Track $it" })
        val result = MusicListParser.match(requests, library)
        assertEquals(10000, result.matched.size); assertEquals(0, result.count("Duplicate"))
    }
    @Test fun oversizedRowBecomesBoundedError() { val row = MusicListParser.parse("x".repeat(10000)).single(); assertTrue(row.error.isNotBlank()); assertTrue(row.raw.length <= 256) }
    @Test fun utf8BomAndCrlf() { assertEquals("Diri", MusicListParser.parse("\uFEFFTulus - Diri\r\n").single().title) }
    @Test fun inactiveLegacyAudioCannotMatchEvenIfAnOldAvailableFlagIsTrue() {
        assertTrue(MusicListParser.match(MusicListParser.parse("Mangu"), listOf(track().copy(active = false))).matched.isEmpty())
    }
    @Test fun sourceExportsPreserveUrlAndRequestedRecordingForVerification() {
        val row = MusicListParser.parse("""[{"title":"Mangu","artist":"Fourtwnty; Charita Utami","source_url":"https://example.org/audio.mp3","duration_ms":263000}]""", "source.json").single()
        assertEquals("https://example.org/audio.mp3", row.reference); assertEquals(263000L, row.duration)
        val csv = MusicListParser.parse("title,artist,source_url\nMangu,Fourtwnty,https://example.org/audio.mp3", "source.csv").single()
        assertEquals(row.reference, csv.reference)
    }
    @Test fun featuringInTitleStillRequiresAllArtistCredits() {
        val recording = track(title = "Mangu (feat. Charita Utami)", artist = "Fourtwnty")
        assertEquals(1, MusicListParser.match(MusicListParser.parse("Fourtwnty; Charita Utami - Mangu"), listOf(recording)).matched.size)
        assertTrue(MusicListParser.match(MusicListParser.parse("Fourtwnty - Mangu"), listOf(recording)).matched.isEmpty())
    }
}
