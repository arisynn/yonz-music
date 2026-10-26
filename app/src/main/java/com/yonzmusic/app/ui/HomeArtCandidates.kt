package com.yonzmusic.app.ui

import com.yonzmusic.app.data.MetadataPolicy
import com.yonzmusic.app.data.Track

internal object HomeArtCandidates {
    fun select(tracks: List<Track>): List<Track> = tracks.asSequence()
        .filter { it.active && it.available }
        // Known sidecar covers must not be crowded out by songs with no artwork.
        .sortedByDescending { it.artUri.isNotBlank() }
        .distinctBy {
            it.artUri.ifBlank {
                if (MetadataPolicy.known(it.album).isNotBlank()) "album:${it.artist}:${it.album}" else "embedded:${it.uri}"
            }
        }.take(24).toList()
}
