package com.yonzmusic.app

import com.yonzmusic.app.search.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SearchContractTest {
    private fun row()=JSONObject().put("video_id","aqz-KE-bpKQ").put("title","Original audio").put("page","https://www.youtube.com/watch?v=aqz-KE-bpKQ").put("channel","Uploader")
    private fun parse(row:JSONObject)=SearchContract.engine(JSONObject().put("ok",true).put("songs",JSONArray().put(row)).toString()).songs
    @Test fun channelIsNotFabricatedArtist() {val r=parse(row()).single();assertEquals("Uploader",r.credit);assertFalse(r.artistVerified);assertEquals("",r.album)}
    @Test fun actualArtistCreditsArePreserved() {val r=parse(row().put("artists",JSONArray(listOf("One","Two")))).single();assertEquals("One, Two",r.credit);assertTrue(r.artistVerified)}
    @Test fun hostileOrInconsistentSourceIdsAreRejected() {
        for(url in listOf("https://evil.test/watch?v=aqz-KE-bpKQ","https://www.youtube.com/watch?v=other123456","http://www.youtube.com/watch?v=aqz-KE-bpKQ"))assertTrue(parse(row().put("page",url)).isEmpty())
        assertTrue(parse(row().put("video_id","../bad")).isEmpty())
    }
    @Test fun artworkAndDurationAreBounded() {
        assertEquals("",parse(row().put("artwork","https://i.ytimg.com.evil.test/a")).single().artwork)
        assertEquals("",parse(row().put("artwork","https://u:p@i.ytimg.com/a")).single().artwork)
        assertEquals(7*86400000L,parse(row().put("duration_ms",Long.MAX_VALUE)).single().durationMs)
        assertEquals(0,parse(row().put("duration_ms",-100)).single().durationMs)
    }
    @Test fun sourceFailureIsNeverPresentedAsEmptyResults() {
        try {SearchContract.engine("""{"ok":false,"error":{"code":"SOURCE_NETWORK","message":"Periksa koneksi"}}""");fail("must reject")}
        catch(e:SearchFailure){assertEquals("Periksa koneksi",e.userMessage)}
    }
    @Test fun querySanitizingAndPaginationAreBounded() {
        assertEquals("Adele All I Ask",SearchContract.query(" Adele  All I Ask "));assertEquals(200,SearchContract.query("x".repeat(400)).length)
        val j=JSONObject().put("ok",true).put("songs",JSONArray()).put("next","malicious");assertEquals("",SearchContract.engine(j.toString()).next)
    }
    @Test fun localSearchHandlesAccentsPunctuationAndDifferentWordOrder() {
        assertNotNull(LocalSearch.score("adele ask","All I Ask","Adele","25"));assertNotNull(LocalSearch.score("beyonce halo","Halo","Beyoncé"))
        assertNotNull(LocalSearch.score("guns roses","Sweet Child O' Mine","Guns N’ Roses"));assertNull(LocalSearch.score("Coldplay","All I Ask","Adele"))
        assertTrue(LocalSearch.score("All I Ask","All I Ask","Adele")!!<LocalSearch.score("All I Ask","All I Ask (Live)","Adele")!!)
    }
}
