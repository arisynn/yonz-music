package com.yonzmusic.app.playback

import com.yonzmusic.app.search.OnlineSong

object RadioPolicy {
    fun eligible(online: Boolean, requested: Boolean, repeat: Int, remaining: Int, enabled: Boolean) =
        online && requested && repeat == 0 && remaining <= 2 && enabled
    fun select(songs: List<OnlineSong>, queued: Set<String>, failed: Set<String>): List<OnlineSong> =
        songs.distinctBy { it.id }.filter { it.id !in queued && it.id !in failed }.take(20)
}
