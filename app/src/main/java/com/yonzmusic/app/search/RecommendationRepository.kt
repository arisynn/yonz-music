package com.yonzmusic.app.search

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** A cancelled playback job cancels the catalogue call too. Only normalized metadata is cached. */
class RecommendationRepository(private val catalog:MusicCatalog) {
    private data class Cached(val at:Long,val songs:List<OnlineSong>)
    private val cache=linkedMapOf<String,Cached>()
    private suspend fun source(seed:OnlineSong,force:Boolean=false):List<OnlineSong> {
        val cached=cache[seed.id]
        if(!force && cached!=null && System.currentTimeMillis()-cached.at in 0 until 30*60000)return cached.songs
        val rows=withTimeout(22000) {catalog.radio(seed.videoId)}
        currentCoroutineContext().ensureActive()
        cache.remove(seed.id);cache[seed.id]=Cached(System.currentTimeMillis(),rows)
        while(cache.size>6)cache.remove(cache.keys.first())
        return rows
    }
    suspend fun candidates(seed:OnlineSong,profile:ListeningProfile,recent:List<OnlineSong>,excludedIds:Set<String>,excludedRecords:Set<String>):List<RecommendationCandidate> {
        val hadCache=cache[seed.id]?.let {System.currentTimeMillis()-it.at in 0 until 30*60000}==true
        val primary=source(seed)
        val pool=primary.mapIndexed {index,song->RecommendationCandidate(song,1.0-index.toDouble()/maxOf(primary.size,1))}.toMutableList()
        if(hadCache && RecommendationEngine.rank(seed,pool,profile,recent,excludedIds=excludedIds,excludedRecordings=excludedRecords).size<25) {
            val fresh=source(seed,force=true)
            pool+=fresh.mapIndexed {i,s->RecommendationCandidate(s,1.0-i.toDouble()/maxOf(fresh.size,1))}
        }
        val relatedArtists=(primary.map {RecommendationEngine.artist(it.credit)}+RecommendationEngine.artist(seed.credit)).toSet()
        val extras=recent.asSequence().filter {it.id!=seed.id && RecommendationEngine.artist(it.credit) in relatedArtists}.distinctBy {it.id}.take(2)
        for(extra in extras) {
            if(RecommendationEngine.rank(seed,pool,profile,recent,excludedIds=excludedIds,excludedRecordings=excludedRecords).size>=25)break
            currentCoroutineContext().ensureActive()
            val rows=try {source(extra)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){emptyList()}
            pool+=rows.mapIndexed {i,s->RecommendationCandidate(s,.85*(1.0-i.toDouble()/maxOf(rows.size,1)))}
        }
        return pool.distinctBy {it.song.id}
    }
}
