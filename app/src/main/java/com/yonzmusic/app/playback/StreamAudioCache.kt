package com.yonzmusic.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import com.yonzmusic.app.YonzApp
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicBoolean

/** A single private cache. It never publishes files, promises offline availability, or stores source URLs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamAudioCache(private val app: YonzApp,
    private val directory: File = File(app.cacheDir,"stream-audio-v1"),
    private val resolver: StreamResolver = app.streams,
    private val transport: (StreamResolver)->DataSource.Factory = { source -> DataSource.Factory {StreamDataSource(source)} }) {
    @Volatile private var limit = 64L * 1024 * 1024
    val bytes = MutableStateFlow(0L)
    private var instance: SimpleCache? = null
    private val eviction = object : CacheEvictor {
        private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it.key }.thenBy { it.position })
        private var size = 0L
        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() {}
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) { trim(cache, if(length == C.LENGTH_UNSET.toLong()) 0 else length) }
        override fun onSpanAdded(cache: Cache, span: CacheSpan) { if(spans.add(span)) size += span.length; trim(cache, 0); bytes.value = size }
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) { if(spans.remove(span)) size -= span.length; bytes.value = size }
        override fun onSpanTouched(cache: Cache, old: CacheSpan, fresh: CacheSpan) { spans.remove(old); spans.add(fresh) }
        fun trim(cache: Cache, incoming: Long) {
            while(size + incoming > limit && spans.isNotEmpty()) cache.removeSpan(spans.first())
            bytes.value = size
        }
    }
    @Synchronized private fun cache(): SimpleCache = instance ?: SimpleCache(directory, eviction, StandaloneDatabaseProvider(app)).also { instance=it }
    // Called on IO from the preferences collector; cache operations are synchronized by SimpleCache.
    fun configure(megabytes: Int) {
        limit = megabytes.takeIf { it in setOf(0,32,64) }?.times(1024L*1024) ?: 64L*1024*1024
        val current = synchronized(this) { instance }
        if(current != null) synchronized(current) { eviction.trim(current, 0) }
    }
    fun clear() { val current=cache(); synchronized(current) { current.keys.toList().forEach(current::removeResource) }; bytes.value=current.cacheSpace }
    fun refreshSize() { bytes.value=cache().cacheSpace }
    @Synchronized fun release() { instance?.release(); instance=null }
    fun factory(): DataSource.Factory = DataSource.Factory { CachedStream() }

    private inner class CachedStream : DataSource {
        private var trace=0L
        private var cacheRead=0L
        private var totalRead=0L
        private var delegate: DataSource? = null
        private val listeners = mutableListOf<TransferListener>()
        private var anchor: StreamRepository.Resolved? = null
        private var source: String? = null
        private val stopped=AtomicBoolean(false)
        override fun addTransferListener(listener: TransferListener) { listeners += listener; delegate?.addTransferListener(listener) }
        override fun open(spec: DataSpec): Long {
            stopped.set(false);cacheRead=0;totalRead=0
            StreamWork.begin()
            try {
                val id=StreamPolicy.source(spec.uri);trace=StreamPerformance.number("engine:$id")
                if(source!=id) { source=id;anchor=null }
                val info=resolver.resolve(id, stopped, anchor?.formatId)
                if(stopped.get())throw StreamFailure(StreamIssue.CANCELLED)
                val old=anchor
                if(old!=null && (old.formatId!=info.formatId || old.extension!=info.extension || old.codec!=info.codec || old.revision!=info.revision)) throw StreamFailure(StreamIssue.FORMAT_CHANGED)
                anchor=info
                val pinned=object: StreamResolver {
                    override fun invalidate(id: String) = resolver.invalidate(id)
                    override fun resolve(id: String, cancelled: AtomicBoolean, formatId: String?, timeoutMillis: Long): StreamRepository.Resolved {
                        val result=resolver.resolve(id,cancelled,info.formatId,timeoutMillis)
                        if(result.revision!=info.revision) throw StreamFailure(StreamIssue.FORMAT_CHANGED)
                        return result
                    }
                }
                val upstream=transport(pinned)
                val key="audio-v1:$id:${info.formatId}:${info.extension}:${info.codec}:${info.revision}"
                val selected = if(limit==0L) upstream.createDataSource() else CacheDataSource.Factory().setCache(cache())
                    .setEventListener(object:CacheDataSource.EventListener {
                        override fun onCacheIgnored(reason:Int){}
                        override fun onCachedBytesRead(cacheSizeBytes:Long,cachedBytesRead:Long){cacheRead+=cachedBytesRead}
                    }).setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache()).setFragmentSize(512*1024L)).createDataSource()
                delegate=selected; listeners.forEach(selected::addTransferListener)
                if(stopped.get())throw StreamFailure(StreamIssue.CANCELLED)
                return selected.open(spec.buildUpon().setKey(key).setFlags(spec.flags or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build())
            } catch(e: Exception) { close(); throw e }
            finally { StreamWork.end() }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int):Int {val n=delegate?.read(buffer,offset,length) ?: C.RESULT_END_OF_INPUT;if(n>0)totalRead+=n;return n}
        override fun getUri(): Uri? = delegate?.uri
        override fun getResponseHeaders(): Map<String,List<String>> = delegate?.responseHeaders ?: emptyMap()
        override fun close() { stopped.set(true);val old=delegate;delegate=null;old?.close();if(totalRead>0){StreamPerformance.emit(trace,StreamPerformance.Stage.CACHE,0,bytes=cacheRead,count=totalRead);totalRead=0;cacheRead=0} }
    }
}
