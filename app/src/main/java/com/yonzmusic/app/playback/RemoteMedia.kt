@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.yonzmusic.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.yonzmusic.app.search.OnlineSong

fun remoteMetadata(song:OnlineSong,extras:Bundle?,image:ByteArray?=null)=MediaMetadata.Builder()
    .setTitle(song.title).setDisplayTitle(song.title).setArtist(if(song.artistVerified)song.credit else song.credit.takeIf {it.isNotBlank()}?.let {"Kanal: $it"}.orEmpty())
    .setAlbumTitle(song.album).setAlbumArtist(song.albumArtist.takeIf {it.isNotBlank()}).setDurationMs(song.durationMs.takeIf {it>0})
    .setReleaseYear(song.releaseYear.takeIf {it>0}).setGenre(song.genre.takeIf {it.isNotBlank()})
    .setArtworkUri(song.artworkUri.ifBlank {song.artwork}.takeIf {it.isNotBlank()}?.let(Uri::parse))
    .setArtworkData(image,MediaMetadata.PICTURE_TYPE_FRONT_COVER)
    .setExtras(Bundle(extras ?: Bundle()).apply {putString("yono.remote",StreamRepository.encode(song).toString())}).build()

fun remoteMediaItem(song:OnlineSong,key:String?=null,next:Boolean=false):MediaItem=MediaItem.Builder().setMediaId(song.id)
    .setUri(if(song.id.startsWith("mb:"))"yono-catalog://recording/${Uri.encode(song.id)}" else "yono-stream://youtube/${song.videoId}")
    .setMediaMetadata(remoteMetadata(song,Bundle().apply {putString("yono.queue.key",key?.takeIf {it.matches(Regex("[a-fA-F0-9-]{36}"))} ?: java.util.UUID.randomUUID().toString());putBoolean("yono.next",next)})).build()
fun remoteSong(item:MediaItem):OnlineSong?=item.mediaMetadata.extras?.getString("yono.remote")?.let {runCatching {StreamRepository.decode(org.json.JSONObject(it)).let {s->s.copy(artworkUri=item.mediaMetadata.artworkUri?.toString()?.takeIf {it.startsWith("file:") && com.yonzmusic.app.YonzApp.instance.artwork.owns(it)}.orEmpty())}}.getOrNull()}
