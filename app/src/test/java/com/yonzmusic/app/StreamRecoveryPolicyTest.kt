package com.yonzmusic.app

import com.yonzmusic.app.playback.*
import com.yonzmusic.engine.SelectedDownloadResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class StreamRecoveryPolicyTest {
    @Test fun partialResponsesMustMatchPositionLengthAndTotal() {
        val slice=StreamRange.parse("bytes 100-199/500",100,100)
        assertEquals(100,slice.length);assertEquals(500L,slice.total)
        for(header in listOf("bytes 0-99/500","bytes 100-199/150","bytes 100-199/9223372036854775808","bytes 100-99/500","bytes 100-200/500","nonsense")) {
            try{StreamRange.parse(header,100,100);fail(header)}catch(_:StreamFailure){}
        }
        assertEquals(500L,StreamRange.eof("bytes */500",500));assertNull(StreamRange.eof("bytes */500",499))
    }
    @Test fun expiredOrNearExpiryLinksAreNotReusedForTwoMinutes() {
        assertEquals(0,StreamRange.cacheMillis("https://r1.googlevideo.com/a?expire=100",100000))
        assertEquals(10000,StreamRange.cacheMillis("https://r1.googlevideo.com/a?expire=140",100000))
        assertEquals(120000,StreamRange.cacheMillis("https://r1.googlevideo.com/a?expire=1000",100000))
        assertEquals(120000,StreamRange.cacheMillis("https://r1.googlevideo.com/a",100000))
    }
    @Test fun transientRetryIsBoundedAndNeverRetriesAccessOrChangedFormats() {
        assertEquals(1000L,StreamIssue.retryDelay(StreamIssue.NETWORK,1));assertEquals(2000L,StreamIssue.retryDelay(StreamIssue.TIMEOUT,2))
        assertNull(StreamIssue.retryDelay(StreamIssue.NETWORK,3))
        for(issue in listOf(StreamIssue.ACCESS,StreamIssue.RATE_LIMIT,StreamIssue.FORMAT_CHANGED,StreamIssue.SEEK,StreamIssue.TLS))assertNull(StreamIssue.retryDelay(issue,1))
        assertEquals(StreamIssue.TIMEOUT,StreamIssue.from(IOException("outer",SocketTimeoutException("private URL"))))
        assertEquals(StreamIssue.ACCESS,StreamIssue.http(403));assertEquals(StreamIssue.RATE_LIMIT,StreamIssue.http(429))
    }
    @Test fun selectedFailureKeepsItsOwnReasonAndCannotAcceptAnotherRecordingReceipt() {
        fun row(id:String,status:String)=JSONObject().put("status",status).put("uri","content://owned/audio").put("song",JSONObject().put("youtube_id",id))
        val other=row("abcdefghijk","downloaded")
        val mine=row("aqz-KE-bpKQ","failed").put("error",JSONObject().put("code","DURATION_MISMATCH").put("message","private trace"))
        val plan=JSONObject().put("entries",JSONArray().put(other).put(mine))
        assertNull(SelectedDownloadResult.receipt(plan,"aqz-KE-bpKQ"))
        assertEquals("DURATION_MISMATCH",SelectedDownloadResult.failure(plan,"aqz-KE-bpKQ").code)
        assertTrue(SelectedDownloadResult.failure(plan,"aqz-KE-bpKQ").message.contains("durasi"))
        mine.put("status","downloaded");assertEquals(mine,SelectedDownloadResult.receipt(plan,"aqz-KE-bpKQ"))
    }
}
