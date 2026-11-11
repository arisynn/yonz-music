package com.yonzmusic.app

import com.yonzmusic.app.search.*
import org.junit.Assert.*
import org.junit.Test

class ForYouPolicyTest {
    private fun song(id:String,title:String=id,artist:String="Artist")=OnlineSong("engine:$id",SearchProvider.ENGINE,title,artist,page="https://www.youtube.com/watch?v=$id",videoId=id)
    @Test fun recommendationsExcludeSeedRecentListeningAndDuplicateRecordings() {
        val seed=song("a");val recent=song("b")
        val result=ForYouPolicy.select(seed,listOf(seed,recent,song("c","New"),song("d","New"),song("e")),listOf(recent))
        assertEquals(listOf("engine:c","engine:e"),result.map {it.id})
    }
    @Test fun sourceOrderAndIdentityArePreservedWithBoundedHomeResults() {
        val candidates=(0..30).map {song(it.toString(),artist="Artist ${it%10}")}
        val result=ForYouPolicy.select(song("seed"),candidates,emptyList())
        assertEquals(candidates.take(12),result)
        assertTrue(ForYouPolicy.select(song("seed"),emptyList(),emptyList()).isEmpty())
    }
}
