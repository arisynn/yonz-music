@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.yonzmusic.app.playback

import androidx.media3.exoplayer.DefaultLoadControl

internal object StreamingLoadControls {
    // Candidate is measured against the old 1000/2500 profile before final selection.
    const val INITIAL_MS=750
    const val REBUFFER_MS=2000
    fun create(initialMs:Int=INITIAL_MS,rebufferMs:Int=REBUFFER_MS)=DefaultLoadControl.Builder()
        .setBufferDurationsMsForStreaming(5000,30000,initialMs,rebufferMs)
        .setBufferDurationsMsForLocalPlayback(5000,30000,1000,2500)
        .setPrioritizeTimeOverSizeThresholdsForStreaming(false)
        .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(false)
        .build()
}
