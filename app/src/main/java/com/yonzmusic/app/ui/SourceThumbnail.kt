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

/** Compatibility bridge for existing artwork consumers; storage belongs to the shared resolver. */
internal object CatalogArtBytes {
    fun load(context:android.content.Context,url:String,limit:Int=2*1024*1024,identity:String=url):ByteArray? = kotlinx.coroutines.runBlocking {
        (context.applicationContext as com.yonzmusic.app.YonzApp).artwork.resolve(identity,url)?.bytes?.takeIf {it.size<=limit}
    }
}
private val sourceImages=object:LruCache<String,Bitmap>(4*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
private val thumbnailWorkers=Dispatchers.IO.limitedParallelism(2)
/** Only shown after explicit source review. No tracking pixels, arbitrary hosts, or persistent failure cache. */
@Composable fun SourceThumbnail(url:String, modifier: Modifier = Modifier.size(44.dp),identity:String=url) {
    val context=LocalContext.current.applicationContext
    val requestKey="$identity:$url"
    val image by produceState<Pair<String,Bitmap>?>(null,requestKey) {
        value=null
        if(url.isNotBlank())value=withContext(thumbnailWorkers) {
            StreamWork.awaitAudio()
            try {
                val cached=sourceImages.get(requestKey)
                val bitmap=cached ?: (context as com.yonzmusic.app.YonzApp).artwork.resolve(identity,url)?.bytes?.let {BitmapFactory.decodeByteArray(it,0,it.size)}?.also {sourceImages.put(requestKey,it)}
                bitmap?.let {requestKey to it}
            }catch(e:CancellationException){throw e}catch(_:Exception){null}
        }
    }
    val bitmap=image?.takeIf {it.first==requestKey}?.second
    if(bitmap!=null)Image(bitmap.asImageBitmap(),null,modifier.clip(Design.small),contentScale=ContentScale.Crop)
    else DefaultArt(modifier.clip(Design.small))
}
