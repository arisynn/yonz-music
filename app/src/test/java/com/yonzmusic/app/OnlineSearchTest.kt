package com.yonzmusic.app

import com.yonzmusic.app.search.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OnlineSearchTest {
    private fun row(name: String)=OnlineSong(name,SearchProvider.APPLE,name,"Artist",page="https://music.apple.com/id/song/123")
    @Test fun automaticTypingDebouncesAndDoesNotNeedSubmit()=runBlocking {
        val calls=mutableListOf<String>()
        val backend=object: SearchBackend {override suspend fun search(provider: SearchProvider,query: String,page: String): SearchPage {calls+=query;return SearchPage(listOf(row(query)))}}
        val search=OnlineSearch(this,backend,{false},debounceMs=30,now={0})
        search.query("ad",SearchProvider.APPLE);delay(40);assertTrue(calls.isEmpty())
        search.query("ade",SearchProvider.APPLE);delay(5);search.query("adele",SearchProvider.APPLE);delay(60)
        assertEquals(listOf("adele"),calls);assertEquals("adele",search.state.value.songs.single().title);search.cancel()
    }
    @Test fun cancelledSlowResultCannotReplaceNewQueryOrSource()=runBlocking {
        val backend=object:SearchBackend {override suspend fun search(provider: SearchProvider,query: String,page: String):SearchPage {
            if(query=="old")withContext(NonCancellable){delay(80)}else delay(5)
            return SearchPage(listOf(row(query)))
        }}
        val search=OnlineSearch(this,backend,{true},0,now={0})
        search.query("old",SearchProvider.APPLE);delay(10);search.query("new",SearchProvider.YOUTUBE);delay(110)
        assertEquals("new",search.state.value.songs.single().title);assertEquals(SearchProvider.YOUTUBE,search.state.value.provider);search.cancel()
    }
    @Test fun cachedResultsAvoidExtraNetworkRequestsAndExpire()=runBlocking {
        var now=0L;var calls=0
        val backend=object:SearchBackend {override suspend fun search(provider:SearchProvider,query:String,page:String):SearchPage {calls++;return SearchPage(listOf(row(query)))}}
        val search=OnlineSearch(this,backend,{false},0,now={now})
        search.query("song",SearchProvider.APPLE);delay(5);search.query("song",SearchProvider.APPLE);delay(5)
        assertEquals(1,calls);assertTrue(search.state.value.cached)
        now=300001;search.query("song",SearchProvider.APPLE);delay(5);assertEquals(2,calls);search.cancel()
    }
    @Test fun rateLimitIsBoundedAndRetryDoesNotBypassIt()=runBlocking {
        var now=0L;var calls=0
        val backend=object:SearchBackend {override suspend fun search(provider:SearchProvider,query:String,page:String):SearchPage {calls++;return SearchPage(emptyList())}}
        val search=OnlineSearch(this,backend,{true},0,now={now})
        repeat(13){search.query("song$it",SearchProvider.YOUTUBE,true);delay(2)}
        assertEquals(12,calls);assertTrue(search.state.value.error.contains("terlalu cepat"))
        search.query("song12",SearchProvider.YOUTUBE,true);delay(2);assertEquals(12,calls)
        now=60001;search.query("song12",SearchProvider.YOUTUBE,true);delay(2);assertEquals(13,calls);search.cancel()
    }
    @Test fun pagingDeduplicatesAndErrorsAreNotEmptyResults()=runBlocking {
        val backend=object:SearchBackend {override suspend fun search(provider:SearchProvider,query:String,page:String):SearchPage {
            if(query=="failure")throw SearchFailure("Periksa koneksi")
            return if(page.isBlank())SearchPage(listOf(row("one")),"next")else SearchPage(listOf(row("one"),row("two")))
        }}
        val search=OnlineSearch(this,backend,{true},0,now={0})
        search.query("song",SearchProvider.YOUTUBE);delay(5);search.more();delay(5)
        assertEquals(listOf("one","two"),search.state.value.songs.map {it.id})
        search.query("failure",SearchProvider.YOUTUBE);delay(5);assertEquals("Periksa koneksi",search.state.value.error);assertFalse(search.state.value.loading);search.cancel()
    }
    @Test fun clearingAndLeavingCancelsPendingSearch()=runBlocking {
        var calls=0
        val backend=object:SearchBackend {override suspend fun search(provider:SearchProvider,query:String,page:String):SearchPage {calls++;return SearchPage(emptyList())}}
        val search=OnlineSearch(this,backend,{true},30,now={0});search.query("song",SearchProvider.APPLE);search.cancel();delay(50)
        assertEquals(0,calls);assertFalse(search.state.value.loading);search.clear();assertEquals(OnlineSearchState(),search.state.value)
    }
}
