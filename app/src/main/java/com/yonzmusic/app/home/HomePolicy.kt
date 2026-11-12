package com.yonzmusic.app.home

import com.yonzmusic.app.data.*
import com.yonzmusic.app.search.*
import com.yonzmusic.app.playback.RadioQueuePolicy
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** View-only union. Local files retain Room identity; remote entries retain source identity. */
data class HomeTrack(val local:Track?=null,val online:OnlineSong?=null,val embeddedGenres:List<String> = emptyList()) {
    val id get()=local?.id ?: online!!.id
    val key get()=local?.sourceIdentity?.takeIf {it.isNotBlank()} ?: online?.videoId?.takeIf {it.isNotBlank()}?.let {"youtube:$it"} ?: id
    val title get()=local?.title ?: online!!.title
    val artist get()=local?.artist ?: online!!.credit
    val album get()=local?.album ?: online!!.album
    val duration get()=local?.duration ?: online!!.durationMs
    val primaryArtist get()=RecommendationEngine.artist(artist)
    val genres get()=(embeddedGenres+online?.genres.orEmpty()).distinct()
    val tags get()=online?.tags.orEmpty()
}
data class HomeStat(val plays:Int=0,val listenedMs:Long=0,val completion:Double?=null,val skips:Int=0,val lastPlayed:Long=0)
data class HomeMix(val id:String,val title:String,val subtitle:String,val tracks:List<HomeTrack>,val kind:String="mix")
data class HomeState(
    val ready:Boolean=false,val quick:List<HomeTrack> = emptyList(),val made:List<HomeTrack> = emptyList(),
    val daily:List<HomeMix> = emptyList(),val discover:List<HomeTrack> = emptyList(),val artists:List<HomeMix> = emptyList(),
    val recent:List<HomeTrack> = emptyList(),val favorites:List<HomeTrack> = emptyList(),val repeat:List<HomeTrack> = emptyList(),
    val top:List<HomeTrack> = emptyList(),val genres:List<HomeMix> = emptyList(),val moods:List<HomeMix> = emptyList(),
    val releases:List<HomeTrack> = emptyList(),val albums:List<HomeMix> = emptyList(),val stats:Map<String,HomeStat> = emptyMap(),
    val refreshing:Boolean=false,val message:String="",val updatedAt:Long=0
)
object HomePolicy {
    private val alternateTitle=Regex("(?i)(\\([^)]*\\b(cover|karaoke|remix|nightcore|slowed|sped up)\\b[^)]*\\)|\\bcover by\\b)")
    private fun normal(s:String)=s.lowercase(Locale.ROOT).trim()
    private fun knownArtist(s:String)=s.isNotBlank() && !s.startsWith("unknown",true) && !s.startsWith("kanal:",true)
    fun distinct(rows:List<HomeTrack>)=rows.distinctBy {it.key}.distinctBy {RadioQueuePolicy.recording(it.title,it.artist)}
    fun diverse(rows:List<HomeTrack>,limit:Int=30,cap:Int=5):List<HomeTrack> {
        val rest=distinct(rows).toMutableList();val result=mutableListOf<HomeTrack>();val counts=mutableMapOf<String,Int>()
        while(result.size<limit) {
            val eligible=rest.filter {(counts[it.primaryArtist] ?: 0)<cap}
            val next=eligible.firstOrNull {it.primaryArtist!=result.lastOrNull()?.primaryArtist} ?: eligible.firstOrNull() ?: break
            result+=next;rest.remove(next);counts[next.primaryArtist]=(counts[next.primaryArtist] ?: 0)+1
        }
        return result
    }
    /** Pure local composition, no network, extraction, file IO, guessed genres or fabricated dates. */
    fun build(local:List<Track>,online:List<OnlineSong>,favoriteIds:Set<String>,history:List<History>,listens:List<ListenEvent>,
        profile:ListeningProfile,related:List<OnlineSong>,now:Long,localGenres:Map<String,List<String>> = emptyMap()):HomeState {
        val since=now-30L*86400000
        val sourceMap=online.associateBy {"youtube:${it.videoId}"}
        val rows=distinct(local.filter {it.active && it.available && it.source!="stream"}.map {HomeTrack(it,sourceMap[it.sourceIdentity],localGenres[it.id].orEmpty())}+online.map {HomeTrack(online=it)})
        val byId=mutableMapOf<String,HomeTrack>();rows.forEach {t->byId[t.id]=t;t.online?.let {byId[it.id]=t}}
        val events=profile.signals.filter {it.at in since..now}
        val localEvents=listens.filter {it.startedAt in since..now}.groupBy {byId[it.trackId]?.key}
        val signalGroups=events.groupBy {byId[it.id]?.key}
        val stats=rows.associate {t->
            val old=localEvents[t.key].orEmpty();val signals=signalGroups[t.key].orEmpty()
            val progress=signals.filter {it.kind==FeedbackKind.PROGRESS && it.listenedMs>0}.distinctBy {it.session}
            val completion=progress.filter {it.durationMs>0}.map {(it.listenedMs.toDouble()/it.durationMs).coerceIn(0.0,1.0)}
            // Merge remote listening before a download with later local events. Pair only the
            // near-simultaneous qualification emitted by both observers for the same playback.
            val unmatched=old.toMutableList()
            val extraPlays=signals.filter {it.kind==FeedbackKind.LISTEN}.count {signal->
                val overlap=unmatched.indexOfFirst {kotlin.math.abs(it.startedAt-signal.at)<=10000}
                if(overlap<0)true else {unmatched.removeAt(overlap);false}
            }
            t.id to HomeStat(old.size+extraPlays,
                progress.sumOf {it.listenedMs}.takeIf {it>0} ?: old.sumOf {it.listenedMs},completion.takeIf {it.isNotEmpty()}?.average(),
                signals.count {it.kind==FeedbackKind.EARLY_SKIP},maxOf(old.maxOfOrNull {it.startedAt} ?: 0,
                    signals.filter {it.kind in setOf(FeedbackKind.STARTED,FeedbackKind.LISTEN,FeedbackKind.PROGRESS)}.maxOfOrNull {it.at} ?: 0))
        }
        fun stat(t:HomeTrack)=stats[t.id] ?: HomeStat()
        val favorites=rows.filter {it.id in favoriteIds || it.online?.id in favoriteIds}
        val liked=favorites.map {it.key}.toSet()
        val affinity=rows.filter {knownArtist(it.artist)}.groupBy {it.primaryArtist}.mapValues {(_,ts)->ts.sumOf {stat(it).plays*3+(if(it.key in liked)5 else 0)-stat(it).skips*3}}
        val genreAffinity=rows.flatMap {t->t.genres.map {normal(it) to (stat(t).plays*3+if(t.key in liked)5 else 0)}}.groupBy {it.first}.mapValues {it.value.sumOf {p->p.second}}
        val relatedKeys=related.map {"youtube:${it.videoId}"}.toSet()
        fun score(t:HomeTrack):Double {
            val s=stat(t);val age=if(s.lastPlayed>0)(now-s.lastPlayed).coerceAtLeast(0)/86400000.0 else 30.0
            val catalog=t.online?.musicType in setOf("MUSIC_VIDEO_TYPE_ATV","MUSIC_VIDEO_TYPE_OMV")
            val alternate=alternateTitle.containsMatchIn(t.title)
            return s.plays*2.5+kotlin.math.ln1p(s.listenedMs/60000.0).coerceAtMost(3.0)+(s.completion ?: .5)*2-s.skips*4+(if(t.key in liked)7 else 0)+(affinity[t.primaryArtist] ?: 0)*.3+
                t.genres.sumOf {(genreAffinity[normal(it)] ?: 0)*.2}+(if(t.key in relatedKeys)2 else 0)+(if(catalog)1.5 else 0.0)-(if(alternate && t.online!=null)2 else 0)+(3-age/10).coerceAtLeast(0.0)
        }
        val eligible=rows.filter {t->profile.hidden[t.online?.id ?: t.id]?.let {it>now}!=true}
        val eligibleKeys=eligible.map {it.key}.toSet()
        val ranked=eligible.sortedWith(compareByDescending<HomeTrack> {score(it)}.thenBy {it.key})
        val recentTimes=mutableMapOf<String,Long>()
        history.forEach {h->byId[h.trackId]?.let {t->recentTimes[t.id]=maxOf(recentTimes[t.id] ?: 0,h.playedAt)}}
        rows.forEach {t->if(stat(t).lastPlayed>0)recentTimes[t.id]=maxOf(recentTimes[t.id] ?: 0,stat(t).lastPlayed)}
        val recent=rows.filter {it.id in recentTimes}.sortedByDescending {recentTimes[it.id]}.take(20)
        val familiar=ranked.filter {stat(it).plays>0 || it.key in liked}
        val knownArtists=familiar.map {it.primaryArtist}.filter {knownArtist(it)}.toSet()
        val knownGenres=familiar.flatMap {it.genres.map(::normal)}.toSet()
        val novel=ranked.filter {stat(it).plays==0 && stat(it).lastPlayed==0L && it.key !in liked &&
            (it.key in relatedKeys || it.primaryArtist in knownArtists || it.genres.any {g->normal(g) in knownGenres})}
        val similar=novel.filter {it.primaryArtist in knownArtists || it.genres.any {g->normal(g) in knownGenres}}
        val exploration=novel.filter {it.primaryArtist !in knownArtists}
        val day=now/86400000
        fun rotate(ts:List<HomeTrack>)=ts.sortedByDescending {score(it)+kotlin.random.Random(day xor it.key.hashCode().toLong()).nextDouble()*3.0}
        fun mix(id:String,title:String,known:List<HomeTrack>,pool:List<HomeTrack>):HomeMix? {
            if(known.size<3)return null
            val size=if(known.size>=14)20 else 10
            val a=diverse(rotate(known),size*7/10);val aKeys=a.map {it.key}.toSet()
            val b=diverse(rotate(pool.filter {it.key !in aKeys}),size*2/10);val bKeys=b.map {it.key}.toSet()
            val c=diverse(rotate(exploration.filter {it.key !in aKeys && it.key !in bKeys}),size/10)
            val tracks=diverse(a+b+c,size)
            return tracks.takeIf {it.size>=3}?.let {HomeMix(id,title,"${a.count {x->x in it}} familiar · ${b.count {x->x in it}} serupa · ${c.count {x->x in it}} penemuan",it)}
        }
        val daily=mutableListOf<HomeMix>()
        mix("daily-artists","Daily Mix 01",familiar,similar)?.let {daily+=it.copy(subtitle="Artis favorit · ${it.subtitle}")}
        val genreNames=familiar.flatMap {it.genres}.distinctBy(::normal).sortedByDescending {genreAffinity[normal(it)] ?: 0}
        for(g in genreNames.take(2))mix("daily-${normal(g)}","Daily Mix 0${daily.size+1}",familiar.filter {it.genres.any {v->normal(v)==normal(g)}},similar.filter {it.genres.any {v->normal(v)==normal(g)}})?.let {daily+=it.copy(subtitle="$g · ${it.subtitle}")}
        if(daily.size<3) {
            val groups=familiar.groupBy {it.primaryArtist}.entries.sortedByDescending {affinity[it.key] ?: 0}
            for((artist,ts) in groups) {
                if(daily.size>=3)break
                mix("daily-artist:$artist","Daily Mix 0${daily.size+1}",ts,similar)?.let {m->
                    if(daily.none {old->old.tracks.map {it.key}.toSet()==m.tracks.map {it.key}.toSet()})daily+=m.copy(subtitle="${ts.first().artist} & pilihan terkait · ${m.subtitle}")
                }
            }
        }
        val artists=ranked.filter {knownArtist(it.artist)}.groupBy {it.primaryArtist}.entries.sortedByDescending {affinity[it.key] ?: 0}.take(6).map {(_,ts)->HomeMix("artist:${ts.first().primaryArtist}",ts.first().artist,"Radio artis",ts.take(12),"artist")}
        val genres=eligible.flatMap {t->t.genres.map {g->g to t}}.groupBy {normal(it.first)}.values.map {pairs->HomeMix("genre:${normal(pairs.first().first)}",pairs.first().first,"Dari metadata musik",diverse(pairs.map {it.second}))}.take(8)
        val moodTags=linkedMapOf("Chill & Relax" to setOf("chill","relax","relaxing"),"Happy Vibes" to setOf("happy","uplifting"),"Sad Songs" to setOf("sad","melancholic"),"Focus & Study" to setOf("focus","study"),"Workout Energy" to setOf("workout","energy"),"Late Night" to setOf("late night"))
        val moods=moodTags.mapNotNull {(name,tags)->eligible.filter {t->t.tags.any {normal(it) in tags}}.takeIf {it.isNotEmpty()}?.let {HomeMix("mood:$name",name,"Dari tag sumber",diverse(it))}}
        val releases=ranked.filter {t->runCatching {val d=LocalDate.parse(t.online?.releaseDate.orEmpty()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();d in (now-90L*86400000)..now}.getOrDefault(false)}
        val albums=familiar.filter {MetadataPolicy.known(it.album).isNotBlank()}.groupBy {normal(it.album)+"|"+it.primaryArtist}.entries.map {(k,ts)->HomeMix("album:$k",ts.first().album,ts.first().artist,ts,"album")}.take(6)
        return HomeState(ready=true,quick=distinct(recent.take(2)+favorites.take(2)+familiar+ranked).filter {it.key in eligibleKeys}.take(6),made=diverse(ranked,12,3),daily=daily,
            discover=diverse(novel,20,3),artists=artists,recent=recent,favorites=favorites,
            repeat=ranked.filter {stat(it).plays>=2}.sortedByDescending {stat(it).plays*3+(stat(it).completion ?: 0.0)*2-stat(it).skips*2}.take(20),
            top=ranked.filter {stat(it).plays>0}.sortedByDescending {stat(it).plays}.take(20),genres=genres,moods=moods,releases=releases.take(12),albums=albums,stats=stats)
    }
}
