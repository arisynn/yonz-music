package com.yonzmusic.app

import com.yonzmusic.app.data.Track
import com.yonzmusic.app.ui.HomeArtCandidates
import org.junit.Assert.*
import org.junit.Test

class HomeArtCandidatesTest {
    @Test fun actualCoversBeyondFirstPageRemainInTheBoundedDeck() {
        val bare = (0..50).map { Track("$it", "content://audio/$it", "Song $it", "Artist") }
        val cover = Track("covered", "content://audio/covered", "Covered song", "Artist", artUri = "content://covers/one")
        val deck = HomeArtCandidates.select(bare + cover)
        assertEquals(cover, deck.first())
        assertEquals(24, deck.size)
    }
    @Test fun duplicateCoversAndInaccessibleAudioDoNotFillTheDeck() {
        val one = Track("one", "content://audio/one", "One", "Artist", artUri = "content://covers/one")
        val two = one.copy(id = "two", uri = "content://audio/two")
        val gone = one.copy(id = "gone", artUri = "content://covers/gone", available = false)
        val hidden = one.copy(id = "hidden", artUri = "content://covers/hidden", active = false)
        assertEquals(listOf(one), HomeArtCandidates.select(listOf(one, two, gone, hidden)))
        assertTrue(HomeArtCandidates.select(emptyList()).isEmpty())
    }
}
