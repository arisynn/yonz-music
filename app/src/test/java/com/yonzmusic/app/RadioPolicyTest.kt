package com.yonzmusic.app

import com.yonzmusic.app.playback.RadioPolicy
import com.yonzmusic.app.search.*
import org.junit.Assert.*
import org.junit.Test

class RadioPolicyTest {
    private fun song(n: Int): OnlineSong {
        val id=n.toString().padStart(11,'0')
        return OnlineSong("engine:$id",SearchProvider.ENGINE,"Fixture $n","Owner",page="https://www.youtube.com/watch?v=$id",videoId=id)
    }
    @Test fun pauseRepeatLocalAndDisabledAutoplayPreventExpansion() {
        assertTrue(RadioPolicy.eligible(true,true,0,2,true))
        assertFalse(RadioPolicy.eligible(false,true,0,0,true))
        assertFalse(RadioPolicy.eligible(true,false,0,0,true))
        assertFalse(RadioPolicy.eligible(true,true,1,0,true))
        assertFalse(RadioPolicy.eligible(true,true,2,0,true))
        assertFalse(RadioPolicy.eligible(true,true,0,0,false))
        assertFalse(RadioPolicy.eligible(true,true,0,3,true))
    }
    @Test fun queueAndFailureExclusionsAreAppliedBeforeTheBatchLimit() {
        val songs=(0..40).map(::song)
        val selected=RadioPolicy.select(songs+songs,setOf(song(0).id,song(1).id),setOf(song(2).id))
        assertEquals((3..22).map(::song),selected)
        assertTrue(RadioPolicy.select(songs,songs.map {it.id}.toSet(),emptySet()).isEmpty())
    }
}
