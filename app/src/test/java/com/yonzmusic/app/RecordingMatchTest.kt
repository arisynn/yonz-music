package com.yonzmusic.app

import com.yonzmusic.app.download.*
import com.yonzmusic.app.importer.SongRequest
import org.junit.Assert.*
import org.junit.Test

class RecordingMatchTest {
    private val request = SongRequest("", "Mangu", "Fourtwnty; Charita Utami", duration = 263000)
    private val candidate = AudioCandidate("id", "Mangu", "Fourtwnty Featuring Charita Utami", "", 263500, 123, "https://example.org/audio.mp3", "CC BY", "", "")
    @Test fun completeFeaturingCreditsMatch() { assertNull(RecordingMatch.rejection(request, candidate)) }
    @Test fun missingGuestIsRejected() { assertNotNull(RecordingMatch.rejection(request, candidate.copy(artist = "Fourtwnty"))) }
    @Test fun coverPerformerIsRejected() { assertNotNull(RecordingMatch.rejection(request, candidate.copy(artist = "Cover performer"))) }
    @Test fun liveRemixKaraokeAndSlowedAreRejected() {
        for (version in listOf("Live", "Remix", "Karaoke", "Slowed")) assertNotNull(RecordingMatch.rejection(request, candidate.copy(title = "Mangu ($version)")))
    }
    @Test fun truncatedAudioIsRejected() { assertNotNull(RecordingMatch.rejection(request, candidate.copy(duration = 31000))) }
    @Test fun ambiguousArtistInputIsRejected() { assertNotNull(RecordingMatch.rejection(request.copy(artist = ""), candidate)) }
    @Test fun punctuationDoesNotSplitBandNames() { assertEquals(setOf("earth wind fire"), RecordingMatch.artists("Earth, Wind & Fire")) }
    @Test fun titleFeaturingIsIncludedInArtistVerification() {
        assertEquals(setOf("primary", "guest"), RecordingMatch.artists("Primary", "Song (feat. Guest)"))
        assertEquals("song", RecordingMatch.title("Song (feat. Guest)"))
    }
    @Test fun safeNamesAreBoundedAndCannotTraverse() {
        val name = RecordingMatch.safeName("../../音楽😀".repeat(100))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 175); assertFalse(name.contains('/')); assertFalse(name.contains('\\'))
        assertFalse(name.first().isWhitespace()); assertFalse(Character.isHighSurrogate(name.last()))
        assertEquals("Music", RecordingMatch.safeName("../.."))
    }
}
