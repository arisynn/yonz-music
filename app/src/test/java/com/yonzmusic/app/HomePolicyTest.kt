package com.yonzmusic.app

import com.yonzmusic.app.data.*
import com.yonzmusic.app.search.*
import com.yonzmusic.app.home.*
import org.junit.Assert.*
import org.junit.Test

class HomePolicyTest {
    private val now=1800000000000L
    private fun song(n:Int,artist:String="Artist $n")=OnlineSong("engine:${n.toString().padStart(11,'0')}",SearchProvider.ENGINE,"Song $n",artist,page="https://www.youtube.com/watch?v=${n.toString().padStart(11,'0')}",videoId=n.toString().padStart(11,'0'),durationMs=100000)
    private fun build(songs:List<OnlineSong>,signals:List<ListeningSignal> = emptyList(),favorites:Set<String> = emptySet(),related:List<OnlineSong> = emptyList())=
        HomePolicy.build(emptyList(),songs,favorites,emptyList(),emptyList(),ListeningProfile(signals),related,now)
    @Test fun newUserHasHonestLibraryFallbackWithoutInventedGenresMoodsOrReleases() {
        val state=build((1..8).map {song(it)})
        assertEquals(6,state.quick.size);assertTrue(state.repeat.isEmpty());assertTrue(state.top.isEmpty())
        assertTrue(state.genres.isEmpty());assertTrue(state.moods.isEmpty());assertTrue(state.releases.isEmpty());assertTrue(state.daily.isEmpty())
        assertTrue(HomePolicy.build(emptyList(),emptyList(),emptySet(),emptyList(),emptyList(),ListeningProfile(),emptyList(),now).quick.isEmpty())
    }
    @Test fun openingAndEarlySkippingCannotBecomeMeaningfulPlays() {
        val s=song(1)
        val state=build(listOf(s),listOf(ListeningSignal(s.id,"artist 1",FeedbackKind.STARTED,now),ListeningSignal(s.id,"artist 1",FeedbackKind.EARLY_SKIP,now)))
        assertEquals(0,state.stats[s.id]!!.plays);assertTrue(state.top.isEmpty());assertTrue(state.repeat.isEmpty())
    }
    @Test fun thirtyDayCountsCompletionAndSkipPenaltyAreDerivedFromActualEvents() {
        val a=song(1);val b=song(2)
        val events=listOf(ListeningSignal(a.id,"artist 1",FeedbackKind.LISTEN,now),ListeningSignal(a.id,"artist 1",FeedbackKind.LISTEN,now-1000),
            ListeningSignal(a.id,"artist 1",FeedbackKind.PROGRESS,now,"a",90000,100000),ListeningSignal(a.id,"artist 1",FeedbackKind.LISTEN,now-31L*86400000),
            ListeningSignal(b.id,"artist 2",FeedbackKind.STARTED,now),ListeningSignal(b.id,"artist 2",FeedbackKind.EARLY_SKIP,now))
        val state=build(listOf(a,b),events)
        assertEquals(2,state.stats[a.id]!!.plays);assertEquals(.9,state.stats[a.id]!!.completion!!,.001)
        assertEquals(a.id,state.repeat.single().id);assertEquals(a.id,state.made.first().id)
    }
    @Test fun sourceIdentityPrefersPlayableLocalFileAndPreservesRemoteFavorite() {
        val s=song(1);val local=Track("local","content://fixture/1",s.title,s.credit,sourceIdentity="youtube:${s.videoId}")
        val state=HomePolicy.build(listOf(local),listOf(s),setOf(s.id),emptyList(),listOf(ListenEvent(trackId="local",startedAt=now,listenedMs=30000)),ListeningProfile(listOf(ListeningSignal(s.id,"artist 1",FeedbackKind.LISTEN,now))),emptyList(),now)
        assertEquals(1,state.made.size);assertEquals("local",state.favorites.single().id);assertEquals(1,state.stats["local"]!!.plays)
    }
    @Test fun downloadingALaterLocalCopyDoesNotDiscardPreviousOnlinePlays() {
        val s=song(1);val local=Track("local","content://fixture/1",s.title,s.credit,sourceIdentity="youtube:${s.videoId}")
        val signals=listOf(ListeningSignal(s.id,"artist 1",FeedbackKind.LISTEN,now-60000),ListeningSignal("local","artist 1",FeedbackKind.LISTEN,now))
        val state=HomePolicy.build(listOf(local),listOf(s),emptySet(),emptyList(),listOf(ListenEvent(trackId="local",startedAt=now,listenedMs=30000)),ListeningProfile(signals),emptyList(),now)
        assertEquals(2,state.stats["local"]!!.plays)
    }
    @Test fun dailyMixUsesSeventyTwentyTenWhenEligiblePoolsAreAvailable() {
        val familiar=(1..7).map {song(it)};val similar=(8..9).map {song(it,"Artist ${it-7}")};val exploration=listOf(song(10,"New Artist"))
        val signals=familiar.map {ListeningSignal(it.id,it.credit.lowercase(),FeedbackKind.LISTEN,now)}
        val state=build(familiar+similar+exploration,signals,related=similar+exploration)
        val mix=state.daily.first();assertEquals(10,mix.tracks.size)
        assertEquals(7,mix.tracks.count {t->t.id in familiar.map {it.id}})
        assertTrue(mix.subtitle.contains("7 familiar · 2 serupa · 1 penemuan"))
        assertEquals(mix.tracks.size,mix.tracks.map {it.key}.toSet().size)
    }
    @Test fun dailySelectionIsStableWithinADayAndActuallyRotatesOnTheNextDay() {
        val songs=(1..30).map {song(it,"Artist ${it%10}")}
        val signals=songs.map {ListeningSignal(it.id,it.credit.lowercase(),FeedbackKind.LISTEN,now)}
        fun daily(at:Long)=HomePolicy.build(emptyList(),songs,emptySet(),emptyList(),emptyList(),ListeningProfile(signals),emptyList(),at).daily.first().tracks.map {it.id}
        assertEquals(daily(now),daily(now))
        assertNotEquals(daily(now).toSet(),daily(now+86400000).toSet())
    }
    @Test fun hiddenRecommendationsDoNotLeakBackIntoQuickPicksThroughHistory() {
        val a=song(1);val b=song(2)
        val state=HomePolicy.build(emptyList(),listOf(a,b),setOf(a.id),listOf(History(a.id,now)),emptyList(),ListeningProfile(hidden=mapOf(a.id to now+10000)),emptyList(),now)
        assertEquals(listOf(b.id),state.quick.map {it.id})
        assertEquals(a.id,state.favorites.single().id) // Explicit library/history records stay intact.
        assertEquals(a.id,state.recent.single().id)
    }
    @Test fun explicitGenresAndDatesAreUsedButTitleKeywordsAreNotMoodEvidence() {
        val dated=song(1).copy(title="Happy Rock Chill",genres=listOf("Rock"),releaseDate="2099-01-01")
        val tagged=song(2).copy(tags=listOf("chill"))
        val state=build(listOf(dated,tagged))
        assertEquals("Rock",state.genres.single().title)
        assertEquals(listOf(tagged.id),state.moods.single().tracks.map {it.id})
        assertTrue(state.releases.isEmpty()) // A future date is not a release.
    }
    @Test fun audibleProgressIgnoresPauseTimeAndHasStableOccurrenceIdentity() {
        val events=mutableListOf<ListeningSignal>();val tracker=OnlineListeningTracker(events::add);val s=song(1)
        tracker.transition(s,"a",false,false,0,now);tracker.tick("a",true,100000,0,now)
        tracker.tick("a",true,100000,15000,now+15000);tracker.tick("a",false,100000,20000,now+20000)
        tracker.tick("a",false,100000,120000,now+120000)
        assertEquals(20000L,events.last {it.kind==FeedbackKind.PROGRESS}.listenedMs)
        assertEquals(1,events.filter {it.kind==FeedbackKind.PROGRESS}.map {it.session}.toSet().size)
        assertFalse(events.any {it.kind==FeedbackKind.LISTEN})
    }
}
