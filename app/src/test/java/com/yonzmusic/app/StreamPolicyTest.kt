package com.yonzmusic.app

import com.yonzmusic.app.playback.StreamPolicy
import com.yonzmusic.app.playback.StreamRepository
import org.junit.Assert.*
import org.junit.Test

class StreamPolicyTest {
    @Test fun signedAudioIsLimitedToHttpsSourceCdn() {
        assertEquals("r1.googlevideo.com",StreamPolicy.url("https://r1.googlevideo.com/videoplayback?sig=public-fixture").host)
        for(u in listOf("http://r1.googlevideo.com/a","https://googlevideo.com.evil.test/a","https://u:p@r1.googlevideo.com/a","https://127.0.0.1/a","https://r1.googlevideo.com:8443/a","https://r1.googlevideo.com/a#fragment")) {
            try{StreamPolicy.url(u);fail("must reject")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun explicitPlayNextPreservesTheOtherShuffledOccurrences() {
        assertArrayEquals(intArrayOf(2,4,3,0,1),com.yonzmusic.app.playback.ShuffleCycle.insertNext(listOf(2,4,0,3,1),4,listOf(3)))
        assertArrayEquals(intArrayOf(0,3,4,2,1),com.yonzmusic.app.playback.ShuffleCycle.insertNext(listOf(0,2,3,1,4),0,listOf(3,4)))
    }
    @Test fun sourceIdentitiesAreNotFilePathsOrRawUrls() {
        assertTrue(StreamRepository.valid("aqz-KE-bpKQ"));assertFalse(StreamRepository.valid("../private"));assertFalse(StreamRepository.valid("https://example.test/a"))
    }
}
