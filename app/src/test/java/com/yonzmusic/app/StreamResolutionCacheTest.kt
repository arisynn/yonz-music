package com.yonzmusic.app

import com.yonzmusic.app.playback.StreamResolutionCache
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StreamResolutionCacheTest {
    @Test fun playbackJoinsWarmupWithoutDuplicateExtraction() {
        val cache=StreamResolutionCache<String>({0L})
        val started=CountDownLatch(1);val finish=CountDownLatch(1);val calls=AtomicInteger()
        val pool=Executors.newFixedThreadPool(2)
        try {
            val warm=pool.submit<String>{cache.resolve("song",{true},{},{1000}){calls.incrementAndGet();started.countDown();check(finish.await(3,TimeUnit.SECONDS));"original"}}
            assertTrue(started.await(3,TimeUnit.SECONDS))
            val play=pool.submit<String>{cache.resolve("song",{true},{},{1000}){calls.incrementAndGet();"duplicate"}}
            finish.countDown()
            assertEquals("original",warm.get(3,TimeUnit.SECONDS));assertEquals("original",play.get(3,TimeUnit.SECONDS));assertEquals(1,calls.get())
        } finally {finish.countDown();pool.shutdownNow()}
    }
    @Test fun expiryFormatPinAndInvalidationForceFreshResolution() {
        var now=0L;var calls=0
        val cache=StreamResolutionCache<String>({now})
        fun get(format:String?=null)=cache.resolve("song",{format==null || it==format},{},{100}){calls++;format ?: "opus"}
        assertEquals("opus",get());assertEquals("opus",get());assertEquals(1,calls)
        assertEquals("aac",get("aac"));assertEquals(2,calls)
        now=100;assertEquals("opus",get());assertEquals(3,calls)
        cache.invalidate("song");get();assertEquals(4,calls)
    }
    @Test fun boundedLruAndFailuresCannotPoisonLaterPlayback() {
        val cache=StreamResolutionCache<String>({0},2);var calls=0
        fun get(id:String)=cache.resolve(id,{true},{},{100}){calls++;id}
        get("a");get("b");get("a");get("c");get("b");assertEquals(4,calls)
        try{cache.resolve("bad",{true},{},{100}){throw IllegalStateException("fixture")};fail()}catch(_:IllegalStateException){}
        assertEquals("bad",get("bad"))
        val cancelled=AtomicBoolean(true)
        try{cache.resolve("a",{true},{check(!cancelled.get())},{100}){fail("cancelled must not load");""};fail()}catch(_:IllegalStateException){}
        cancelled.set(false);assertEquals("a",get("a"))
    }
    @Test fun cancelledWaiterDoesNotCancelProducerOrHoldGate() {
        val cache=StreamResolutionCache<String>({0});val started=CountDownLatch(1);val finish=CountDownLatch(1)
        val checking=CountDownLatch(1);val cancel=AtomicBoolean(false);val pool=Executors.newFixedThreadPool(2)
        try {
            val producer=pool.submit<String>{cache.resolve("a",{true},{},{100}){started.countDown();check(finish.await(3,TimeUnit.SECONDS));"ready"}}
            assertTrue(started.await(3,TimeUnit.SECONDS))
            val waiter=pool.submit<String>{cache.resolve("a",{true},{checking.countDown();check(!cancel.get())},{100}){error("must join")}}
            assertTrue(checking.await(3,TimeUnit.SECONDS));cancel.set(true)
            try{waiter.get(3,TimeUnit.SECONDS);fail()}catch(e:ExecutionException){assertTrue(e.cause is IllegalStateException)}
            finish.countDown();assertEquals("ready",producer.get(3,TimeUnit.SECONDS))
            assertEquals("ready",cache.resolve("a",{true},{},{100}){error("must reuse")})
        } finally {finish.countDown();pool.shutdownNow()}
    }
}
