package com.yonzmusic.app.search

import com.yonzmusic.app.playback.RadioQueuePolicy
import java.util.Locale

/** Public source ranks + private, on-device listening signals. No guessed genre, mood or BPM. */
enum class FeedbackKind { STARTED, CHOICE, LISTEN, REPLAY, EARLY_SKIP, PROGRESS }
data class ListeningSignal(val id:String,val artist:String,val kind:FeedbackKind,val at:Long,val session:String="",val listenedMs:Long=0,val durationMs:Long=0)
data class ListeningProfile(val signals:List<ListeningSignal> = emptyList(),val hidden:Map<String,Long> = emptyMap())
data class RecommendationCandidate(val song:OnlineSong,val relevance:Double)
object RecommendationEngine {
    const val TARGET=30
    const val REFILL_BELOW=15
    fun artist(credit:String)=credit.substringBefore(',').substringBefore(';').trim().lowercase(Locale.ROOT)
    fun rank(seed:OnlineSong,candidates:List<RecommendationCandidate>,profile:ListeningProfile,recent:List<OnlineSong>,
        limit:Int=TARGET,excludedIds:Set<String> = emptySet(),excludedRecordings:Set<String> = emptySet(),now:Long=System.currentTimeMillis(),upcomingArtists:List<String> = emptyList(),artistRadio:String=""):List<OnlineSong> {
        val signals=profile.signals.filter {now-it.at in 0..30L*86400000}
        val last20=signals.asReversed().filter {it.kind==FeedbackKind.STARTED}.map {it.id}.distinct().take(20)
        val ids=(last20+recent.take(20).map {it.id}+seed.id+excludedIds).toMutableSet()
        val records=(recent.take(20)+seed).map {RadioQueuePolicy.recording(it.title,it.credit)}.toMutableSet().apply {addAll(excludedRecordings)}
        val affinity=signals.groupBy {it.artist}.mapValues {(_,events)->events.fold(0) {score,event->score+when(event.kind){FeedbackKind.CHOICE->3;FeedbackKind.LISTEN->2;FeedbackKind.REPLAY->1;FeedbackKind.EARLY_SKIP->-3;else->0}}}
        val heard=signals.filter {it.kind==FeedbackKind.LISTEN}.map {it.id}.toSet()
        val knownArtists=signals.filter {it.kind==FeedbackKind.CHOICE || it.kind==FeedbackKind.LISTEN}.map {it.artist}.toSet()
        val scored=candidates.withIndex().filter {profile.hidden[it.value.song.id]?.let {until->until>now}!=true}
            .sortedWith(compareByDescending<IndexedValue<RecommendationCandidate>> {row->
                val song=row.value.song;val a=artist(song.credit)
                .60*row.value.relevance.coerceIn(0.0,1.0)+.30*(((affinity[a] ?: 0)+3)/15.0).coerceIn(0.0,1.0)+
                    .10*(if(song.id !in heard && a !in knownArtists)1.0 else .25)+(if(artistRadio.isNotBlank() && a==artist(artistRadio)).2 else 0.0)
            }.thenBy {it.index}).map {it.value.song}.toMutableList()
        val selected=mutableListOf<OnlineSong>();val counts=upcomingArtists.groupingBy {artist(it)}.eachCount().toMutableMap();val target=limit.coerceIn(0,TARGET)
        for(cap in listOf(3,5)) {
            while(selected.size<target) {
                val eligible=scored.filter {s->s.id !in ids && RadioQueuePolicy.recording(s.title,s.credit) !in records && (counts[artist(s.credit)] ?: 0)<(if(artistRadio.isNotBlank() && artist(s.credit)==artist(artistRadio))8 else cap)}
                if(eligible.isEmpty())break
                val previous=selected.lastOrNull()?.let {artist(it.credit)} ?: upcomingArtists.lastOrNull()?.let(::artist)
                val next=eligible.firstOrNull {artist(it.credit)!=previous} ?: eligible.first()
                selected+=next;ids+=next.id;records+=RadioQueuePolicy.recording(next.title,next.credit)
                val a=artist(next.credit);counts[a]=(counts[a] ?: 0)+1;scored.remove(next)
            }
            if(selected.size>=target)break
        }
        return selected
    }
}
/** Counts audible elapsed time only. Position seeks, pause and network errors cannot count as listening. */
class OnlineListeningTracker(private val emit:(ListeningSignal)->Unit) {
    private var song:OnlineSong?=null;private var key:String?=null;private var heard=0L
    private var session="";private var reported=0L
    private var duration=0L;private var last=-1L;private var playing=false;private var started=false;private var qualified=false;private var chosen=false;private var failed=false
    fun transition(next:OnlineSong?,nextKey:String?,manualSkip:Boolean,generated:Boolean,now:Long,wall:Long,repeat:Boolean=false,chosenByUser:Boolean=true) {
        if(nextKey==key && !repeat)return
        advance(now,wall);progress(wall)
        if(!failed && playing && manualSkip && next!=null && heard in 1 until 10000)song?.let {emit(ListeningSignal(it.id,RecommendationEngine.artist(it.credit),FeedbackKind.EARLY_SKIP,wall))}
        song=next;key=nextKey;heard=0;reported=0;session=java.util.UUID.randomUUID().toString();duration=next?.durationMs ?: 0;last=now;playing=false;started=false;qualified=false;chosen=!generated && chosenByUser;failed=false
    }
    fun failure(now:Long,wall:Long) {advance(now,wall);progress(wall);failed=true;playing=false}
    fun tick(currentKey:String?,isPlaying:Boolean,durationMs:Long,now:Long,wall:Long) {
        if(currentKey!=key)return
        advance(now,wall)
        if(durationMs>0)duration=durationMs
        if(heard-reported>=15000 || (playing && !isPlaying))progress(wall)
        playing=isPlaying
        if(isPlaying && !started) {
            started=true;song?.let {s->
                emit(ListeningSignal(s.id,RecommendationEngine.artist(s.credit),FeedbackKind.STARTED,wall))
                if(chosen)emit(ListeningSignal(s.id,RecommendationEngine.artist(s.credit),FeedbackKind.CHOICE,wall))
            }
        }
    }
    fun flush(now:Long,wall:Long) {advance(now,wall);progress(wall)}
    private fun progress(wall:Long) {
        if(heard<=0 || !started || heard==reported)return
        reported=heard
        song?.let {emit(ListeningSignal(it.id,RecommendationEngine.artist(it.credit),FeedbackKind.PROGRESS,wall,session,heard,duration))}
    }
    private fun advance(now:Long,wall:Long) {
        if(playing && last>=0)heard+=(now-last).coerceAtLeast(0)
        last=now
        val threshold=minOf(30000L,(duration/2).coerceAtLeast(5000L)).coerceAtMost(duration)
        if(!qualified && duration>0 && threshold>0 && heard>=threshold) {
            qualified=true;song?.let {emit(ListeningSignal(it.id,RecommendationEngine.artist(it.credit),FeedbackKind.LISTEN,wall))}
        }
    }
}
