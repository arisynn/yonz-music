package com.yonzmusic.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.yonzmusic.app.network.SafeHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val sourceImages=object:LruCache<String,Bitmap>(4*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
/** Only shown after explicit source review. No tracking pixels, arbitrary hosts, or persistent failure cache. */
@Composable fun SourceThumbnail(url:String) {
    val allowed=setOf("i.ytimg.com","img.youtube.com","i.scdn.co","lh3.googleusercontent.com")
    val image by produceState<Bitmap?>(sourceImages.get(url),url) {
        if(value==null && url.isNotBlank())value=withContext(Dispatchers.IO) { runCatching {
            val response=SafeHttp.get(url,allowed,limit=2*1024*1024)
            require(response.status==200)
            val data=response.bytes
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(data,0,data.size,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=40_000_000)
            var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>256)sample*=2
            BitmapFactory.decodeByteArray(data,0,data.size,BitmapFactory.Options().apply {inSampleSize=sample})?.also {sourceImages.put(url,it)}
        }.getOrNull() }
    }
    if(image!=null)Image(image!!.asImageBitmap(),null,Modifier.size(44.dp).clip(Design.small),contentScale=ContentScale.Crop)
    else DefaultArt(Modifier.size(44.dp).clip(Design.small))
}
