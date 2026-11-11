package com.yonzmusic.app

import com.yonzmusic.app.search.*
import org.junit.Assert.*
import org.junit.Test

class RecommendationEngineTest {
    private fun song(id:String,artist:String="Artist $id",title:String=id)=OnlineSong("engine:$id",SearchProvider.ENGINE,title,artist,page="https://www.youtube.com/watch?v=$id",videoId=id)
    @Test fun thirtyDistinctSongsHaveControlledArtistDiversity() {
        val pool=(0..99).map {RecommendationCandidate(song(it.toString(),"Artist ${it%10}"),1.0-it/100.0)}
        val result=RecommendationEngine.rank(song("seed"),pool,ListeningProfile(),emptyList())
        assertEquals(30,result.size);assertEquals(30,result.map {it.id}.toSet().size)
        assertTrue(result.groupingBy {it.credit}.eachCount().values.all {it<=3})
        assertTrue(result.zipWithNext().all {(a,b)->a.credit!=b.credit})
    }
    @Test fun shortagesAreReportedAsRealResultsRatherThanRandomFillers() {
        val pool=(0..99).map {RecommendationCandidate(song(it.toString(),"One Artist"),1.0)}
        assertEquals(5,RecommendationEngine.rank(song("seed"),pool,ListeningProfile(),emptyList()).size)
    }
    @Test fun recentListeningHiddenTracksAndDuplicateRecordingsAreExcluded() {
        val seed=song("seed");val heard=song("heard");val hidden=song("hidden")
        val profile=ListeningProfile(listOf(ListeningSignal(heard.id,heard.credit,FeedbackKind.STARTED,1000)),mapOf(hidden.id to 2000))
        val pool=listOf(seed,heard,hidden,song("a",title="Same"),song("b",artist="Artist a",title="Same")).map {RecommendationCandidate(it,1.0)}
        assertEquals(listOf("engine:a"),RecommendationEngine.rank(seed,pool,profile,emptyList(),now=1000).map {it.id})
    }
    @Test fun listeningAffinityAndEarlySkipsAffectNearRankedCandidates() {
        val a=song("a","Liked");val b=song("b","Other")
        val pool=listOf(RecommendationCandidate(b,.82),RecommendationCandidate(a,.80))
        val liked=(0..2).map {ListeningSignal("old$it","liked",FeedbackKind.CHOICE,1000)}
        assertEquals(a,RecommendationEngine.rank(song("seed"),pool,ListeningProfile(liked),emptyList(),now=1000).first())
        val skipped=(0..4).map {ListeningSignal("skip$it","liked",FeedbackKind.EARLY_SKIP,1000)}
        assertEquals(b,RecommendationEngine.rank(song("seed"),pool,ListeningProfile(liked+skipped),emptyList(),now=1000).first())
    }
    @Test fun pauseAndBufferTimeDoNotCreateListeningOrEarlySkipSignals() {
        val events=mutableListOf<ListeningSignal>();val tracker=OnlineListeningTracker(events::add)
        tracker.transition(song("a"),"a",false,false,0,0)
        tracker.tick("a",true,180000,0,0);tracker.tick("a",false,180000,3000,3000)
        tracker.transition(song("b"),"b",true,false,60000,60000)
        assertFalse(events.any {it.kind==FeedbackKind.LISTEN || it.kind==FeedbackKind.EARLY_SKIP})
    }
    @Test fun meaningfulAudioManualSkipAndNetworkFailureAreSeparated() {
        val events=mutableListOf<ListeningSignal>();val tracker=OnlineListeningTracker(events::add)
        tracker.transition(song("a"),"a",false,false,0,0);tracker.tick("a",true,180000,0,0)
        tracker.tick("a",true,180000,30000,30000)
        assertEquals(1,events.count {it.kind==FeedbackKind.LISTEN})
        tracker.transition(song("b"),"b",true,false,31000,31000);tracker.tick("b",true,180000,31000,31000)
        tracker.transition(song("c"),"c",true,false,34000,34000)
        assertEquals(listOf("engine:b"),events.filter {it.kind==FeedbackKind.EARLY_SKIP}.map {it.id})
        tracker.tick("c",true,180000,34000,34000);tracker.failure(35000,35000);tracker.transition(song("d"),"d",true,false,36000,36000)
        assertEquals(1,events.count {it.kind==FeedbackKind.EARLY_SKIP})
    }
}
