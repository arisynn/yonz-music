package com.yonzmusic.app.metadata

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.network.SafeHttp
import com.yonzmusic.app.search.SearchContract
import com.yonzmusic.app.playback.StreamWork
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Independent, bounded image requests. Files contain resized image bytes, never audio URLs. */
class ArtworkRepository(private val app:YonzApp,private val directory:File=File(app.cacheDir,"music-art-v2")) {
    data class Image(val uri:String,val bytes:ByteArray)
    private val worker=Dispatchers.IO.limitedParallelism(1)
    private val currentWorker=Dispatchers.IO.limitedParallelism(1)
    private val mutex=Mutex()
    internal var fetch:((String)->SafeHttp.Response)?=null // In-process fixture DI only.
    companion object {
        const val CAPACITY=100_000_000L
        fun key(id:String,url:String)=MessageDigest.getInstance("SHA-256").digest("art-v2:$id:$url:512".toByteArray()).joinToString(""){"%02x".format(it)}
    }
    fun owns(uri:String):Boolean=runCatching {val u=Uri.parse(uri);u.scheme=="file" && File(u.path!!).canonicalFile.parentFile==directory.canonicalFile && File(u.path!!).name.matches(Regex("[a-f0-9]{64}\\.jpg"))}.getOrDefault(false)
    suspend fun resolve(id:String,url:String,foreground:Boolean=false):Image?=withContext(if(foreground)currentWorker else worker) {
        if(id.isBlank() || !SearchContract.artwork(url))return@withContext null
        try {
            val file=File(directory,key(id,url)+".jpg")
            mutex.withLock {read(file)}?.let {return@withContext it}
            // Current artwork can run alongside audio; shelf artwork waits while audio is urgent.
            if(!foreground)StreamWork.awaitAudio()
            val response=runInterruptible {fetch?.invoke(url) ?: SafeHttp.get(url,SearchContract.artHosts+setOf("coverartarchive.org","archive.org"),limit=4*1024*1024,imageOnly=true)}
            ensureActive()
            if(response.status!=200 || response.contentType.substringBefore(';').lowercase() !in setOf("image/jpeg","image/png","image/webp"))return@withContext null
            val resized=resize(response.bytes) ?: return@withContext null
            mutex.withLock {
                directory.mkdirs();val atomic=AtomicFile(file);val output=atomic.startWrite()
                try {output.write(resized);atomic.finishWrite(output)}catch(e:Exception){atomic.failWrite(output);throw e}
                file.setLastModified(System.currentTimeMillis());trim();Image(Uri.fromFile(file).toString(),resized)
            }
        }catch(e:CancellationException){throw e}catch(_:Exception){null}
    }
    private fun read(file:File):Image? {
        if(!file.isFile || file.length() !in 1..512_000L)return null
        val bytes=file.readBytes();val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512){file.delete();return null}
        file.setLastModified(System.currentTimeMillis());return Image(Uri.fromFile(file).toString(),bytes)
    }
    internal fun resize(data:ByteArray):ByteArray? {
        if(data.isEmpty() || data.size>4*1024*1024)return null
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(data,0,data.size,bounds)
        if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth.toLong()*bounds.outHeight>40_000_000)return null
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1024)sample*=2
        val bitmap=BitmapFactory.decodeByteArray(data,0,data.size,BitmapFactory.Options().apply {inSampleSize=sample}) ?: return null
        val ratio=minOf(1.0,512.0/maxOf(bitmap.width,bitmap.height));val scaled=Bitmap.createScaledBitmap(bitmap,maxOf(1,(bitmap.width*ratio).toInt()),maxOf(1,(bitmap.height*ratio).toInt()),true)
        return try {ByteArrayOutputStream().use {scaled.compress(Bitmap.CompressFormat.JPEG,85,it);it.toByteArray().takeIf {b->b.size<=512_000}}}finally{if(scaled!==bitmap)scaled.recycle();bitmap.recycle()}
    }
    private fun trim() {
        val files=directory.listFiles().orEmpty().filter {it.extension=="jpg"};var total=files.sumOf {it.length()}
        for(file in files.sortedBy {it.lastModified()}) {if(total<=CAPACITY)break;val size=file.length();if(file.delete())total-=size}
    }
    suspend fun clear()=withContext(worker){mutex.withLock {directory.listFiles().orEmpty().forEach {it.delete()}}}
}
