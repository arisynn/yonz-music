package com.yonzmusic.app

import com.yonzmusic.app.data.*
import com.yonzmusic.app.search.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OnlineCollectionContractTest {
    private val id=UUID.randomUUID().toString()
    private fun song()=OnlineSong("engine:aqz-KE-bpKQ",SearchProvider.ENGINE,"Owned fixture","Karisyono",page="https://www.youtube.com/watch?v=aqz-KE-bpKQ",videoId="aqz-KE-bpKQ",artistVerified=true)
    @Test fun shareLinkAndWatchLinkCanonicalizeToSamePlaylist() {
        val expected="https://music.youtube.com/playlist?list=PL123456789012345"
        assertEquals(expected,OnlineCollectionContract.playlistUrl("https://music.youtube.com/playlist?list=PL123456789012345&si=tracking"))
        assertEquals(expected,OnlineCollectionContract.playlistUrl("https://www.youtube.com/watch?v=aqz-KE-bpKQ&list=PL123456789012345"))
    }
    @Test fun unsafeDynamicAndAmbiguousLinksAreRejected() {
        listOf("http://music.youtube.com/playlist?list=PL1234567890","https://music.youtube.com.evil/playlist?list=PL1234567890","https://x@music.youtube.com/playlist?list=PL1234567890","https://music.youtube.com:444/playlist?list=PL1234567890","https://music.youtube.com/playlist?list=RD1234567890","https://music.youtube.com/playlist?list=PL1234567890&list=PLabcdefghij","https://music.youtube.com/playlist?list=PL1234567890#secret").forEach {
            try{OnlineCollectionContract.playlistUrl(it);fail(it)}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun roundTripOnlyContainsCanonicalMetadata() {
        val p=OnlineCollection(id,"My online songs",listOf(song()))
        assertEquals(p,OnlineCollectionContract.decode(OnlineCollectionContract.encode(p)))
        val j=OnlineCollectionContract.encode(p);j.getJSONArray("songs").getJSONObject(0).put("url","https://signed.invalid/audio?secret=neverpersist")
        assertFalse(OnlineCollectionContract.encode(OnlineCollectionContract.decode(j)).toString().contains("neverpersist"))
    }
    @Test fun duplicatesForgedIdsAndEmptyNamesCannotBeSaved() {
        for(p in listOf(OnlineCollection(id," "),OnlineCollection("unsafe","name"),OnlineCollection(id,"name",listOf(song(),song())),OnlineCollection(id,"name",listOf(song().copy(id="local:123"))))) {
            try{OnlineCollectionContract.validate(p);fail()}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun oversizedOrUnrecognizedSchemaCannotBeRestored() {
        val j=OnlineCollectionContract.encode(OnlineCollection(id,"name"));j.put("schema",99)
        try{OnlineCollectionContract.decode(j);fail()}catch(_:IllegalArgumentException){}
        try{OnlineCollectionContract.validate(OnlineCollection(id,"a".repeat(101)));fail()}catch(_:IllegalArgumentException){}
    }
}
