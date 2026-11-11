package com.yonzmusic.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.yonzmusic.app.network.SafeHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.yonzmusic.app.playback.StreamWork

/** Small shared disk cache keeps visited catalogue covers available offline. */
internal object CatalogArtBytes {
    private val lock=Any()
    fun load(context:android.content.Context,url:String,limit:Int=2*1024*1024):ByteArray? {
        require(com.yonzmusic.app.search.SearchContract.artwork(url))
        val directory=java.io.File(context.cacheDir,"catalog-art").apply {mkdirs()}
        val key=java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(""){"%02x".format(it)}
        val file=java.io.File(directory,key)
        val cached=synchronized(lock){if(file.isFile && file.length() in 1..limit.toLong())file.readBytes() else null}
        if(cached!=null)return cached
        val hosts=com.yonzmusic.app.search.SearchContract.artHosts+setOf("coverartarchive.org","archive.org")
        val response=SafeHttp.get(url,hosts,limit=limit)
        if(response.status!=200)return null
        synchronized(lock) {
            val atomic=android.util.AtomicFile(file);val out=atomic.startWrite()
            try {out.write(response.bytes);atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}
            var total=directory.listFiles().orEmpty().sumOf {it.length()}
            directory.listFiles().orEmpty().sortedBy {it.lastModified()}.forEach {old->if(total>24*1024*1024 && old!=file){val size=old.length();if(old.delete())total-=size}}
        }
        return response.bytes
    }
}
private val sourceImages=object:LruCache<String,Bitmap>(4*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
private val thumbnailWorkers=Dispatchers.IO.limitedParallelism(2)
/** Only shown after explicit source review. No tracking pixels, arbitrary hosts, or persistent failure cache. */
@Composable fun SourceThumbnail(url:String, modifier: Modifier = Modifier.size(44.dp)) {
    val context=LocalContext.current.applicationContext
    val image by produceState<Bitmap?>(sourceImages.get(url),url) {
        if(value==null && url.isNotBlank())value=withContext(thumbnailWorkers) {
            StreamWork.awaitAudio()
            try {
            val data=CatalogArtBytes.load(context,url) ?: return@withContext null
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(data,0,data.size,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=40_000_000)
            var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>256)sample*=2
            BitmapFactory.decodeByteArray(data,0,data.size,BitmapFactory.Options().apply {inSampleSize=sample})?.also {sourceImages.put(url,it)}
            } catch(e:CancellationException) {throw e} catch(_:Exception) {null}
        }
    }
    if(image!=null)Image(image!!.asImageBitmap(),null,modifier.clip(Design.small),contentScale=ContentScale.Crop)
    else DefaultArt(modifier.clip(Design.small))
}
