@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.yonzmusic.app

import androidx.media3.datasource.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.playback.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class StreamCacheCapacityTest {
    @Test fun decimal100MbLruAndRevisionKeysPreserveBytesAndClear() {
        val app=ApplicationProvider.getApplicationContext<YonzApp>();val dir=File(app.cacheDir,"perf-capacity-${System.nanoTime()}")
        var revision="a".repeat(64);val size=104_000_000L
        val resolver=object:StreamResolver {
            override fun invalidate(id:String){}
            override fun resolve(id:String,cancelled:AtomicBoolean,formatId:String?,timeoutMillis:Long)=StreamRepository.Resolved("https://r1.googlevideo.com/owned?signature=not-persistent",emptyMap(),60000,"251","webm","opus",128,revision)
        }
        val cache=StreamAudioCache(app,dir,resolver){DataSource.Factory {object:BaseDataSource(true) {
            var position=0L;var end=size
            override fun open(spec:DataSpec):Long {position=spec.position;end=if(spec.length>=0)minOf(size,position+spec.length)else size;return end-position}
            override fun read(b:ByteArray,offset:Int,length:Int):Int {if(position>=end)return -1;val n=minOf(length.toLong(),end-position).toInt();java.util.Arrays.fill(b,offset,offset+n,61.toByte());position+=n;return n}
            override fun getUri()=android.net.Uri.parse("yono-stream://youtube/QA100cache1")
            override fun close(){}
        }}}
        try {
            cache.configure(100);assertEquals(100_000_000L,cache.capacityBytes())
            val source=cache.factory().createDataSource();var bytes=0L
            try {source.open(DataSpec.Builder().setUri("yono-stream://youtube/QA100cache1").setLength(size).build());val b=ByteArray(65536);while(true){val n=source.read(b,0,b.size);if(n<0)break;assertEquals(61.toByte(),b[0]);assertEquals(61.toByte(),b[n-1]);bytes+=n}}finally{source.close()}
            assertEquals(size,bytes);cache.refreshSize();assertTrue(cache.bytes.value in 1..100_000_000L)
            cache.clear();assertEquals(0L,cache.bytes.value)
            cache.configure(64);assertEquals(64L*1024*1024,cache.capacityBytes())
            cache.configure(0);assertEquals(0L,cache.capacityBytes())
        }finally{cache.release();dir.deleteRecursively()}
    }
}
