package com.yonzmusic.app.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** Retry only our classified transient failures, never authorization/format/local-file errors. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamLoadPolicy:DefaultLoadErrorHandlingPolicy(2) {
    override fun getRetryDelayMsFor(info:LoadErrorHandlingPolicy.LoadErrorInfo):Long {
        var cause:Throwable?=info.exception
        repeat(12) {
            val failure=cause as? StreamFailure
            if(failure!=null && !failure.retryAllowed)return C.TIME_UNSET
            if(failure!=null)return StreamIssue.retryDelay(failure.issue,info.errorCount) ?: C.TIME_UNSET
            cause=cause?.cause
        }
        return C.TIME_UNSET
    }
}
