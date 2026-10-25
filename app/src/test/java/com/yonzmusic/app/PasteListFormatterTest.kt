package com.yonzmusic.app

import com.yonzmusic.app.importer.PasteListFormatter as F
import org.junit.Assert.*
import org.junit.Test

class PasteListFormatterTest {
    @Test fun repeatedArtistMatchesRequestedExample() {
        val r=F.format("Artis - judul lagu Artis - judul lagu")
        assertEquals("Artis - judul lagu\nArtis - judul lagu",r.text);assertTrue(r.changed);assertEquals(2,r.lines)
    }
    @Test fun threeTracksAndDashVariants() {
        assertEquals("Adele – Hello\nAdele — Easy On Me\nAdele - Skyfall",F.format("Adele – Hello Adele — Easy On Me Adele - Skyfall").text)
    }
    @Test fun distinctArtistsFromSelectedLibrary() {
        assertEquals("Adele - Hello\nEd Sheeran - Perfect\nTulus - Diri",F.format("Adele - Hello Ed Sheeran - Perfect Tulus - Diri",listOf("Ed Sheeran","Tulus")).text)
    }
    @Test fun unknownArtistsNeverInvented() {
        val raw="Adele - Hello Ed Sheeran - Perfect"
        val r=F.format(raw);assertEquals(raw,r.text);assertFalse(r.changed);assertTrue(r.needsReview)
    }
    @Test fun semicolonPipeAndBulletWithCompletePairs() {
        for(separator in listOf(" ; "," | "," • ")) assertEquals("Adele - Hello\nTulus - Diri",F.format("Adele - Hello${separator}Tulus - Diri").text)
    }
    @Test fun artistCollaborationSemicolonNotSplit() {
        val raw="Fourtwnty; Charita Utami - Mangu"
        assertEquals(raw,F.format(raw).text)
    }
    @Test fun numberedRunRetainsOrder() {
        assertEquals("Adele - Hello\nTulus - Diri",F.format("1. Adele - Hello 2. Tulus - Diri").text)
    }
    @Test fun decimalsAndNonSequentialNumbersNotGuessed() {
        val raw="Adele - Version 2.0 - Live"
        assertEquals(raw,F.format(raw).text)
    }
    @Test fun titleHyphenAndAcDcPreserved() {
        assertEquals("AC/DC - Back in Black\nAC/DC - Thunderstruck",F.format("AC/DC - Back in Black AC/DC - Thunderstruck").text)
        val r=F.format("Adele - Hello - Live at Wembley");assertFalse(r.changed);assertTrue(r.needsReview)
    }
    @Test fun artistMentionsInsideVersionOrFeaturedCreditsNotSplit() {
        for(raw in listOf("Adele - Song (Adele - Live)","Adele - Song feat. Tulus - Live","Adele - Tribute to Adele - Live","Adele - Song \"Adele - Live\"")) assertEquals(raw,F.format(raw,listOf("Tulus")).text)
    }
    @Test fun existingNewlinesAndNonAsciiPreserved() {
        val raw="Björk - Jóga\n宇多田ヒカル - 光"
        assertEquals(raw,F.format(raw).text)
    }
    @Test fun unicodeRepeatedArtistSplits() {
        assertEquals("宇多田ヒカル - 光\n宇多田ヒカル - 桜流し",F.format("宇多田ヒカル - 光 宇多田ヒカル - 桜流し").text)
    }
    @Test fun signedLinksNeverChanged() {
        val raw="https://example.test/Artist_-_Title.opus?signature=a;b|c&not=1"
        assertEquals(raw,F.format(raw).text)
    }
    @Test fun linkListSplitsOnlyWhenWholeLineIsUrls() {
        assertEquals("https://example.test/a.opus\nhttps://example.test/b.webm",F.format("https://example.test/a.opus https://example.test/b.webm").text)
        val caption="Listen https://example.test/a.opus then https://example.test/b.webm"
        assertEquals(caption,F.format(caption).text)
    }
    @Test fun markdownLinkNotTreatedAsJsonOrSongBoundaries() {
        val raw="[Adele - Hello](https://example.test/a.opus)"
        assertEquals("text",F.detectKind(raw));assertEquals(raw,F.format(raw).text)
    }
    @Test fun structuredInputNotMutated() {
        for(raw in listOf("[{\"title\":\"Adele - Hello Adele - Skyfall\"}]","{\"tracks\":[]}","#EXTM3U\r\nArtist - Title.opus","title;artist\r\nHello;Adele","Track Name,Artist Name(s)\nHello,Adele")) assertEquals(raw,F.format(raw).text)
    }
    @Test fun bomAndCrlfNormalizedForPlainText() {
        assertEquals("Adele - Hello\nTulus - Diri",F.format("\uFEFFAdele - Hello\r\nTulus - Diri").text)
    }
    @Test fun formattingIsIdempotent() {
        val formatted=F.format("Adele - Hello Adele - Skyfall").text
        assertEquals(formatted,F.format(formatted).text)
    }
    @Test fun partialFormattingDoesNotDropAmbiguousTail() {
        val r=F.format("Adele - Hello Adele - Skyfall Other Artist - Unknown")
        assertEquals("Adele - Hello\nAdele - Skyfall Other Artist - Unknown",r.text);assertTrue(r.needsReview)
    }
    @Test fun normalTypingNotBulkButPasteAndSelectionReplaceAre() {
        assertFalse(F.isBulkEdit("Adele - Hell","Adele - Hello"));assertFalse(F.isBulkEdit("Hello","Hell"))
        assertTrue(F.isBulkEdit("","Adele - Hello Adele - Skyfall"));assertTrue(F.isBulkEdit("A long selected text","Adele - Hello"))
    }
    @Test fun largePlainInputRetainedWithoutExpensiveGuessing() {
        val raw="Adele - Hello ".repeat(6000);val r=F.format(raw);assertEquals(raw,r.text);assertTrue(r.needsReview)
    }
    @Test fun differentArtistOnlyGuessedWithExactFullName() {
        val raw="Adele - Song Not Tulus - Different"
        assertEquals(raw,F.format(raw,listOf("Not" )).text)
    }
}
