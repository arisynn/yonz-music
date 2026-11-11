package com.yonzmusic.app.metadata

import com.yonzmusic.app.search.OnlineSong

/** OnlineSong is the normalized, stable-ID metadata record; audio URLs never enter it. */
enum class MetadataSource(val priority:Int) { SEARCH(0), CHART(1), EXTRACTION(2), CATALOG(3) }
enum class ArtworkSource(val priority:Int) { CHART(1), THUMBNAIL(2), CATALOG(3) }
enum class OfficialStatus(val label:String) { UNVERIFIED("Belum terverifikasi"), PLATFORM_OFFICIAL("Rilisan resmi · sumber platform"), VERIFIED_ARTIST("Kanal artis resmi"), VERIFIED_LABEL("Label resmi") }
enum class ContentKind(val label:String) { UNKNOWN("Musik"), VIDEO("Video musik"), AUDIO("Audio resmi"), LYRIC("Video lirik"), LIVE("Live"), REMIX("Remix"), COVER("Cover"), KARAOKE("Karaoke"), ALTERED("Versi diubah"), INSTRUMENTAL("Instrumental") }
enum class OfficialFilter(val label:String) {
    ALL("Semua musik"), OFFICIAL("Musik resmi"), VIDEO("Video musik resmi"), AUDIO("Audio resmi"), LYRIC("Lirik resmi"), INSTRUMENTAL("Instrumental");
    fun accepts(song:OnlineSong):Boolean {
        if(this==ALL)return true
        if(song.officialStatus==OfficialStatus.UNVERIFIED)return false
        if(song.contentKind in setOf(ContentKind.LIVE,ContentKind.REMIX,ContentKind.COVER,ContentKind.KARAOKE,ContentKind.ALTERED))return false
        return when(this){ALL,OFFICIAL->true;VIDEO->song.contentKind==ContentKind.VIDEO;AUDIO->song.contentKind==ContentKind.AUDIO;LYRIC->song.contentKind==ContentKind.LYRIC;INSTRUMENTAL->song.contentKind==ContentKind.INSTRUMENTAL}
    }
}
object MetadataPolicy {
    fun official(value:String)=runCatching {OfficialStatus.valueOf(value)}.getOrDefault(OfficialStatus.UNVERIFIED)
    fun kind(value:String)=runCatching {ContentKind.valueOf(value)}.getOrDefault(ContentKind.UNKNOWN)
    fun merge(old:OnlineSong?,fresh:OnlineSong):OnlineSong {
        if(old==null)return fresh
        require(old.id==fresh.id && (old.videoId.isBlank() || fresh.videoId.isBlank() || old.videoId==fresh.videoId))
        val prefer=fresh.metadataSource.priority>old.metadataSource.priority || (fresh.metadataSource==old.metadataSource && fresh.metadataUpdatedAt>=old.metadataUpdatedAt)
        val credits=(!old.artistVerified && fresh.artistVerified) || (fresh.artistVerified==old.artistVerified && prefer)
        val art=fresh.artwork.isNotBlank() && (old.artwork.isBlank() || fresh.artworkSource.priority>old.artworkSource.priority || (fresh.artworkSource==old.artworkSource && fresh.metadataUpdatedAt>=old.metadataUpdatedAt))
        val official=fresh.officialStatus!=OfficialStatus.UNVERIFIED
        return old.copy(title=if(prefer && fresh.title.isNotBlank())fresh.title else old.title,
            credit=if(credits && fresh.credit.isNotBlank())fresh.credit else old.credit,artistVerified=old.artistVerified || fresh.artistVerified,
            artists=if(credits && fresh.artists.isNotEmpty())fresh.artists else old.artists,
            album=if(fresh.album.isNotBlank() && (old.album.isBlank() || prefer))fresh.album else old.album,
            albumArtist=if(fresh.albumArtist.isNotBlank() && (old.albumArtist.isBlank() || prefer))fresh.albumArtist else old.albumArtist,
            durationMs=fresh.durationMs.takeIf {it>0 && (old.durationMs==0L || prefer)} ?: old.durationMs,
            videoId=fresh.videoId.ifBlank {old.videoId},page=fresh.page.ifBlank {old.page},catalogJson=fresh.catalogJson.ifBlank {old.catalogJson},
            uploader=fresh.uploader.ifBlank {old.uploader},channelId=fresh.channelId.ifBlank {old.channelId},
            releaseYear=fresh.releaseYear.takeIf {it>0 && (old.releaseYear==0 || prefer)} ?: old.releaseYear,genre=if(fresh.genre.isNotBlank() && (old.genre.isBlank() || prefer))fresh.genre else old.genre,
            officialStatus=if(official)fresh.officialStatus else old.officialStatus,
            contentKind=if(fresh.contentKind!=ContentKind.UNKNOWN && (prefer || official))fresh.contentKind else old.contentKind,
            metadataSource=if(prefer)fresh.metadataSource else old.metadataSource,
            artwork=if(art)fresh.artwork else old.artwork,artworkSource=if(art)fresh.artworkSource else old.artworkSource,
            artworkUri=if(art && fresh.artwork!=old.artwork)fresh.artworkUri else fresh.artworkUri.ifBlank {old.artworkUri},
            metadataUpdatedAt=maxOf(old.metadataUpdatedAt,fresh.metadataUpdatedAt))
    }
}
