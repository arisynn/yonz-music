package com.yonzmusic.app

import com.yonzmusic.app.playback.RadioQueuePolicy
import com.yonzmusic.app.search.OnlineSong
import com.yonzmusic.app.search.SearchProvider
import org.junit.Assert.*
import org.junit.Test

class RadioQueuePolicyTest {
    private fun song(id:String,title:String="Song $id")=OnlineSong("engine:$id",SearchProvider.ENGINE,title,"Artist",page="https://www.youtube.com/watch?v=$id",videoId=id)
    @Test fun currentQueueAndPreviouslyHeardRecordingsAreExcluded() {
        val result=RadioQueuePolicy.pick(listOf(song("a"),song("b","Same Song"),song("c","Same Song"),song("d")),
            setOf("engine:a"),setOf(RadioQueuePolicy.recording("Song d","Artist")),4)
        assertEquals(listOf("engine:b"),result.map {it.id})
    }
    @Test fun manualItemsStayBeforeRadioInTheActualShuffleOrder() {
        assertEquals(listOf(3,1,4,2,0),RadioQueuePolicy.manualFirst(listOf(3,1,2,0,4),1,setOf(0,2)))
        assertEquals(listOf(3,1,2),RadioQueuePolicy.manualFirst(listOf(3,1,2),1,emptySet()))
    }
    @Test fun batchNeverAddsMoreThanFourAndRespectsRemainingSlots() {
        val candidates=(0..10).map {song(it.toString())}
        assertEquals(4,RadioQueuePolicy.pick(candidates,emptySet(),emptySet(),99).size)
        assertEquals(1,RadioQueuePolicy.pick(candidates,emptySet(),emptySet(),1).size)
        assertTrue(RadioQueuePolicy.pick(candidates,emptySet(),emptySet(),0).isEmpty())
    }
}
