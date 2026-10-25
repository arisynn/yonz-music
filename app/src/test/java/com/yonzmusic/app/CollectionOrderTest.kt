package com.yonzmusic.app

import com.yonzmusic.app.data.CollectionOrder
import org.junit.Assert.*
import org.junit.Test

class CollectionOrderTest {
    @Test fun appendPreservesOrderAndSuppressesDuplicates() { assertEquals(listOf("a", "b", "c"), CollectionOrder.append(listOf("a", "b"), listOf("b", "c", "c"))) }
    @Test fun movingDownKeepsMembership() { assertEquals(listOf("b", "c", "a"), CollectionOrder.move(listOf("a", "b", "c"), 0, 2)) }
    @Test fun movingUpKeepsMembership() { assertEquals(listOf("c", "a", "b"), CollectionOrder.move(listOf("a", "b", "c"), 2, 0)) }
    @Test fun invalidMovesDoNotLoseTracks() { val items = listOf("a", "b"); assertEquals(items, CollectionOrder.move(items, -1, 0)); assertEquals(items, CollectionOrder.move(items, 0, 2)) }
    @Test fun selfMoveKeepsOrder() { assertEquals(listOf("a"), CollectionOrder.move(listOf("a"), 0, 0)) }
    @Test fun resumeHandlesFinishedUnknownAndMidTrackPositions() {
        assertEquals(0L, com.yonzmusic.app.data.PlaybackResume.position(20000, 20000))
        assertEquals(0L, com.yonzmusic.app.data.PlaybackResume.position(19950, 20000))
        assertEquals(5000L, com.yonzmusic.app.data.PlaybackResume.position(5000, 20000))
        assertEquals(0L, com.yonzmusic.app.data.PlaybackResume.position(-1, 20000))
        assertEquals(0L, com.yonzmusic.app.data.PlaybackResume.position(1000, 0))
    }
    @Test fun emptyPlaylistAcceptsNewTracks() { assertEquals(listOf("a"), CollectionOrder.append(emptyList(), listOf("a", "a"))) }
}
