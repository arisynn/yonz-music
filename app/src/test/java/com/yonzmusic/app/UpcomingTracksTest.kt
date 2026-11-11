package com.yonzmusic.app

import com.yonzmusic.app.playback.UpcomingTracks
import org.junit.Assert.*
import org.junit.Test

class UpcomingTracksTest {
    @Test fun preparesFourSuccessorsAtMost() {
        assertEquals(listOf(1,2,3,4),UpcomingTracks.indices(0){it+1})
        assertEquals(listOf(1,2),UpcomingTracks.indices(0){if(it<2)it+1 else -1})
    }
    @Test fun followsActualShuffleOrderAndStopsOnRepeatCycle() {
        val order=mapOf(2 to 0,0 to 4,4 to 1,1 to 3,3 to 2)
        assertEquals(listOf(0,4,1,3),UpcomingTracks.indices(2){order[it] ?: -1})
        assertEquals(listOf(1,2),UpcomingTracks.indices(0){(it+1)%3})
    }
    @Test fun neverPreparesTheCurrentWindowAgainOrAnEmptyQueue() {
        assertTrue(UpcomingTracks.indices(3){3}.isEmpty());assertTrue(UpcomingTracks.indices(-1){0}.isEmpty())
    }
}
