package com.yonzmusic.app

import com.yonzmusic.app.data.Track
import com.yonzmusic.app.data.UserPreferences
import com.yonzmusic.app.i18n.Bahasa
import com.yonzmusic.app.importer.MusicListParser
import com.yonzmusic.app.ui.sortedTracks
import org.junit.Assert.*
import org.junit.Test

class BahasaTest {
    @Test fun existingSortKeysStillDriveOrderingWhileLabelsAreIndonesian() {
        val preferences = UserPreferences(sort = "Artist", theme = "Dark")
        val tracks = listOf(Track("a", "content://a", "First", "Zulu"), Track("b", "content://b", "Second", "Alpha"))
        assertEquals("Artis", Bahasa.pilihan(preferences.sort))
        assertEquals("Selalu gelap", Bahasa.pilihan(preferences.theme))
        assertEquals(listOf("b", "a"), sortedTracks(tracks, preferences.sort).map { it.id })
        assertEquals("Artist", preferences.sort); assertEquals("Dark", preferences.theme)
    }
    @Test fun importStateAndEnglishRecordingContentRemainUntouched() {
        val track = Track("a", "content://a", "Home", "The Search")
        val preview = MusicListParser.match(MusicListParser.parse("The Search - Home\nMissing"), listOf(track))
        assertEquals("Matched", preview.rows[0].status); assertEquals("Unmatched", preview.rows[1].status)
        assertEquals("Cocok", Bahasa.pilihan(preview.rows[0].status))
        assertEquals("Belum cocok", Bahasa.pilihan(preview.rows[1].status))
        assertEquals("The Search", Bahasa.artis(track.artist)); assertEquals("Home", track.title)
        assertEquals(listOf(track.id), preview.matched.map { it.trackId })
    }
    @Test fun oldStoredErrorsAndSourceStatusesAreLocalizedOnlyOnDisplay() {
        assertEquals("Belum disinkronkan", Bahasa.pesan("Not refreshed"))
        assertEquals("Audio yang disimpan tidak dapat dibaca.", Bahasa.pesan("Saved audio could not be read."))
        assertEquals("Versi terbaru", Bahasa.pilihan("UpToDate"))
        assertEquals("Entri 3: Judul belum diisi.", Bahasa.pesan("Entry 3: Missing title"))
        assertEquals("Sumber pilihan disinkronkan · 2 sumber perlu diperiksa melalui Impor", Bahasa.pesan("Selected sources refreshed · 2 need attention in Import"))
    }
    @Test fun dynamicMessagesKeepUserCollectionNames() {
        assertEquals("2 lagu ditambahkan ke Home / Search", Bahasa.pesan("2 tracks added to Home / Search"))
        assertEquals("Koleksi dibuat. 3 unduhan masuk antrean; audio yang sudah ada digunakan kembali.", Bahasa.pesan("Collection created. 3 downloads queued; existing audio is reused."))
        assertEquals("Sumber merespons HTTP 403. Coba lagi nanti.", Bahasa.pesan("Source returned HTTP 403. Retry later."))
    }
    @Test fun translationIsIdempotentAndUnknownContentIsNotGuessed() {
        val message = Bahasa.pesan("The source is missing (HTTP 404). Check the link.")
        assertEquals(message, Bahasa.pesan(message))
        assertEquals("Berkas belum tersedia.", Bahasa.pesan("Berkas belum tersedia."))
        assertEquals("Official Audio — Live at Home", Bahasa.pesan("Official Audio — Live at Home"))
        assertEquals("id-unknown", Bahasa.pilihan("id-unknown"))
    }
    @Test fun missingMetadataLabelsDoNotRewriteStoredFields() {
        val track = Track("a", "content://a", "English Title")
        assertEquals("Artis belum diketahui", Bahasa.artis(track.artist))
        assertEquals("Album belum diketahui", Bahasa.album(track.album))
        assertEquals("Unknown artist", track.artist); assertEquals("Unknown album", track.album)
        assertEquals("Unknown Mortal Orchestra", Bahasa.artis("Unknown Mortal Orchestra"))
        assertEquals("The Dark Side of the Moon", Bahasa.album("The Dark Side of the Moon"))
    }
}
