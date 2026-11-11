package com.yonzmusic.app.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.data.Track
import com.yonzmusic.app.network.SafeHttp
import com.yonzmusic.app.search.OnlineSong
import com.yonzmusic.app.search.SearchContract
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Shared 32 MB decoded cache + 100 MB encoded LRU. Audio resolution never awaits this repository. */
class ArtworkRepository(private val app:YonzApp) {
    private val memory=object:LruCache<String,Bitmap>(32*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
    private val disk=ImageDiskCache(File(app.cacheDir,"artwork-v1"))
    private val permits=Semaphore(2)
    private val epoch=AtomicLong()
    fun clear() {epoch.incrementAndGet();memory.evictAll();disk.clear()}
    private fun bitmap(bytes:ByteArray,target:Int):Bitmap? {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=40_000_000)
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=ArtworkPolicy.sample(bounds.outWidth,bounds.outHeight,target)})
    }
    private suspend fun remote(sourceId:String,url:String,pixels:Int):Bitmap?=permits.withPermit {
        val target=ArtworkPolicy.bucket(pixels);val version=ArtworkPolicy.hash(url);val rawKey="$sourceId|$version";val key="$rawKey|$target"
        memory.get(key)?.let {return@withPermit it}
        val generation=epoch.get()
        var bytes=disk.get(rawKey)
        var image=bytes?.let {runCatching {bitmap(it,target)}.getOrNull()}
        if(image==null) {
            disk.remove(rawKey)
            val response=SafeHttp.get(url,SearchContract.artHosts+setOf("i.scdn.co"),limit=4*1024*1024)
            require(response.status==200)
            bytes=response.bytes;image=bitmap(bytes,target)
            if(image!=null && epoch.get()==generation)disk.put(rawKey,bytes)
        }
        image?.also {if(epoch.get()==generation)memory.put(key,it)}
    }
    suspend fun song(song:OnlineSong,pixels:Int):Bitmap? {
        val target=ArtworkPolicy.bucket(pixels)
        val images=ArtworkPolicy.ordered(song.artworkCandidates+listOf(ArtworkCandidate(song.artwork)),target)
        var best:Bitmap?=null
        for(candidate in images.take(4)) {
            val image=try {remote(song.id,candidate.url,target)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){null} ?: continue
            if(minOf(image.width,image.height)>=target)return image
            if(best==null || minOf(image.width,image.height)>minOf(best.width,best.height))best=image
        }
        app.streams.requestArtwork(song,repair=best==null,targetPixels=target)
        return best
    }
    suspend fun url(url:String,pixels:Int):Bitmap?=try {remote("image:"+ArtworkPolicy.hash(url),url,pixels)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){null}
    suspend fun track(track:Track,pixels:Int):Bitmap? {
        if(track.source=="stream") {
            val song=app.streams.metadata.value[track.id] ?: app.streams.lookup(track.id) ?: return null
            return this.song(song,pixels)
        }
        val target=ArtworkPolicy.bucket(pixels);val key="local:${track.id}|${track.uri}|${track.artUri}|${track.sha256}|${track.artRevision}|$target"
        memory.get(key)?.let {return it}
        return permits.withPermit {
            memory.get(key)?.let {return@withPermit it}
            val generation=epoch.get()
            val bytes=runCatching {
                track.artUri.takeIf {it.isNotBlank()}?.let {uri->app.contentResolver.openInputStream(Uri.parse(uri))?.use {input->
                    val data=input.readBytesBounded(8*1024*1024);data
                }}
            }.getOrNull() ?: runCatching {MediaMetadataRetriever().let {r->try {r.setDataSource(app,Uri.parse(track.uri));r.embeddedPicture}finally {r.release()}}}.getOrNull()
            bytes?.takeIf {it.size<=8*1024*1024}?.let {runCatching {bitmap(it,target)}.getOrNull()}?.also {if(epoch.get()==generation)memory.put(key,it)}
        }
    }
    private fun java.io.InputStream.readBytesBounded(limit:Int):ByteArray {
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val size=read(buffer);if(size<0)break;require(output.size()+size<=limit);output.write(buffer,0,size)}
        return output.toByteArray()
    }
}
