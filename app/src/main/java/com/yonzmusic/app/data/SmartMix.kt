package com.yonzmusic.app.data

/** Counts elapsed audible playing time, not seeks, taps, or wall time spent paused/buffering. */
class ListenAccumulator {
    data class Qualified(val trackId: String, val listenedMs: Long)
    private var id: String? = null
    private var duration = 0L
    private var last = -1L
    private var heard = 0L
    private var playing = false
    private var counted = false
    fun tick(nextId: String?, nextDuration: Long, isPlaying: Boolean, now: Long, restart: Boolean = false): Qualified? {
        if(playing && last>=0) heard+=(now-last).coerceAtLeast(0)
        val threshold=minOf(30000L,(duration/2).coerceAtLeast(5000L)).coerceAtMost(duration)
        val result=if(!counted && id!=null && duration>0 && threshold>0 && heard>=threshold) Qualified(id!!,heard).also { counted=true } else null
        if(nextId!=id || restart) { id=nextId;duration=nextDuration;heard=0;counted=false }
        if(nextDuration>0)duration=nextDuration
        last=now;playing=isPlaying
        return result
    }
}
object SmartMix {
    data class Mix(val key: String, val title: String, val reason: String, val tracks: List<Track>)
    fun build(tracks: List<Track>, favorites: Set<String>, stats: List<ListenStats>, now: Long): List<Mix> {
        if(stats.sumOf { it.playCount }<5 || stats.size<3)return emptyList()
        val available=tracks.filter { it.available && it.active };val byId=stats.associateBy { it.trackId }
        val result=mutableListOf<Mix>()
        fun add(key:String,title:String,reason:String,items:List<Track>) { if(items.size>=3)result+=Mix(key,title,reason,items.take(30)) }
        add("frequent","Sering diputar","Minimal dua pemutaran bermakna per lagu, dihitung sejak fitur ini tersedia.",
            available.filter { (byId[it.id]?.playCount ?: 0)>=2 }.sortedByDescending { byId[it.id]?.playCount })
        add("favorites","Favorit untuk diputar lagi","Favorit dengan paling banyak satu pemutaran bermakna dalam catatan lokal saat ini.",
            available.filter { it.id in favorites && (byId[it.id]?.playCount ?: 0)<=1 }.sortedBy { byId[it.id]?.lastPlayed ?: 0 })
        add("rediscover","Dengarkan kembali","Ditambahkan lebih dari 30 hari lalu dan terakhir didengar lebih dari 14 hari lalu.",
            available.filter { it.added<=now-30L*86400000 && byId[it.id]?.lastPlayed?.let { t -> t<=now-14L*86400000 }==true }.sortedBy { byId[it.id]?.lastPlayed })
        val artists=available.filter { MetadataPolicy.known(it.artist).isNotBlank() }.groupBy { it.artist.split(';').first().trim() }
            .mapValues { (_,songs) -> songs.sumOf { byId[it.id]?.playCount ?: 0 } }.filterValues { it>=3 }.entries.sortedByDescending { it.value }.take(3).map { it.key }.toSet()
        if(artists.isNotEmpty())add("artists","Artis yang sering didengar","Campuran dari artis utama (kredit pertama) dengan minimal tiga pemutaran bermakna dalam riwayat lokal.",
            available.filter { it.artist.split(';').first().trim() in artists }.sortedBy { byId[it.id]?.lastPlayed ?: 0 })
        return result
    }
}
