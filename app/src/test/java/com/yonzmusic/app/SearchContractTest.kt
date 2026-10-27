package com.yonzmusic.app

import com.yonzmusic.app.search.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchContractTest {
    private fun appleRow()=JSONObject().put("kind","song").put("trackId",123).put("trackName","All I Ask").put("artistName","Adele").put("collectionName","25")
        .put("trackViewUrl","https://music.apple.com/id/album/all-i-ask/1?i=123").put("previewUrl","https://audio-ssl.itunes.apple.com/test.m4a").put("trackTimeMillis",271800)
    private fun parse(row:JSONObject)=SearchContract.apple(JSONObject().put("results",JSONArray().put(row)).toString()).songs
    @Test fun appleMetadataRemainsRemoteAndPreviewIsSeparateFromFullDuration() {
        val row=parse(appleRow()).single();assertEquals("All I Ask",row.title);assertEquals("Adele",row.credit);assertEquals("25",row.album)
        assertEquals(271800L,row.durationMs);assertEquals(SearchProvider.APPLE,row.provider);assertEquals("apple:123",row.id)
    }
    @Test fun unsafeApplePreviewAndArtworkAreNotUsable() {
        for(url in listOf("http://audio-ssl.itunes.apple.com/a","https://audio-ssl.itunes.apple.com.evil.test/a","https://u:p@audio-ssl.itunes.apple.com/a","https://127.0.0.1/a","file:///etc/passwd")) {
            val row=parse(appleRow().put("previewUrl",url).put("artworkUrl100",url)).single();assertTrue(row.preview.isEmpty());assertTrue(row.artwork.isEmpty())
        }
        assertTrue(parse(appleRow().put("trackViewUrl","https://evil.test/123")).isEmpty())
    }
    @Test fun missingPreviewDoesNotInventAudioOrCredits() {
        val row=parse(appleRow().remove("previewUrl").let {appleRow().put("previewUrl","").put("artistName","")}).single()
        assertTrue(row.preview.isEmpty());assertTrue(row.credit.isEmpty())
    }
    @Test fun youtubeVideoLinksAreValidatedAndMusicIdsAreNotGuessed() {
        val id="aqz-KE-bpKQ"
        for(url in listOf("https://youtu.be/$id","https://www.youtube.com/watch?v=$id&si=test","https://music.youtube.com/watch?v=$id","https://www.youtube.com/shorts/$id"))assertEquals(id,SearchContract.youtubeId(url))
        for(url in listOf("https://youtube.com.evil.test/watch?v=$id","http://youtube.com/watch?v=$id","https://www.youtube.com/watch?v=bad","https://u:p@youtube.com/watch?v=$id"))assertNull(SearchContract.youtubeId(url))
    }
    @Test fun youtubeChannelIsKeptAsCreditNotFabricatedArtist() {
        val root=JSONObject().put("items",JSONArray().put(JSONObject().put("id",JSONObject().put("videoId","aqz-KE-bpKQ"))
            .put("snippet",JSONObject().put("title","Original video").put("channelTitle","Uploader Channel"))))
        val row=SearchContract.youtube(root.toString()).songs.single();assertEquals("Uploader Channel",row.credit);assertEquals("",row.album);assertEquals(0,row.durationMs)
    }
    @Test fun durationParsingAndQuerySanitizingAreBounded() {
        assertEquals(221000L,SearchContract.duration("PT3M41S"));assertEquals(0,SearchContract.duration("NaN"));assertEquals(200,SearchContract.query("x".repeat(400)).length)
        assertEquals("Adele All I Ask",SearchContract.query(" Adele  All I Ask "))
    }
    @Test fun localSearchHandlesAccentsPunctuationAndDifferentWordOrder() {
        assertNotNull(LocalSearch.score("adele ask","All I Ask","Adele","25"))
        assertNotNull(LocalSearch.score("beyonce halo","Halo","Beyoncé"))
        assertNotNull(LocalSearch.score("guns roses","Sweet Child O' Mine","Guns N’ Roses"))
        assertNull(LocalSearch.score("Coldplay","All I Ask","Adele"));assertNull(LocalSearch.score(" ","All I Ask"))
        assertTrue(LocalSearch.score("All I Ask","All I Ask","Adele")!!<LocalSearch.score("All I Ask","All I Ask (Live)","Adele")!!)
    }
}
