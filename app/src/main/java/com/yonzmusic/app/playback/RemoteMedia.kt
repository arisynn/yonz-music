package com.yonzmusic.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.yonzmusic.app.search.OnlineSong

fun remoteMediaItem(song:OnlineSong,key:String?=null,next:Boolean=false):MediaItem=MediaItem.Builder().setMediaId(song.id)
    .setUri("yono-stream://youtube/${song.videoId}")
    .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(if(song.artistVerified)song.credit else song.credit.takeIf {it.isNotBlank()}?.let {"Kanal: $it"}.orEmpty()).setAlbumTitle(song.album)
        .setArtworkUri(song.artwork.takeIf {it.isNotBlank()}?.let(Uri::parse))
        .setExtras(Bundle().apply {putString("yono.queue.key",key?.takeIf {it.matches(Regex("[a-fA-F0-9-]{36}"))} ?: java.util.UUID.randomUUID().toString());putBoolean("yono.next",next);putString("yono.remote",StreamRepository.encode(song).toString())}).build()).build()
fun remoteSong(item:MediaItem):OnlineSong?=item.mediaMetadata.extras?.getString("yono.remote")?.let {runCatching {StreamRepository.decode(org.json.JSONObject(it))}.getOrNull()}
