package com.yonzmusic.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.artwork.ArtworkPolicy
import com.yonzmusic.app.search.OnlineSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun SongThumbnail(song:OnlineSong,modifier:Modifier=Modifier.size(44.dp)) {
    val app=LocalContext.current.applicationContext as YonzApp
    val metadata by app.streams.metadata.collectAsStateWithLifecycle()
    val latest=metadata[song.id]?.let {it.copy(artworkCandidates=(it.artworkCandidates+song.artworkCandidates).distinctBy {c->c.url})} ?: song
    ThumbnailFrame(listOf(latest.id,latest.artwork,latest.artworkCandidates),modifier) {pixels->app.artwork.song(latest,pixels)}
}
@Composable fun SourceThumbnail(url:String,modifier:Modifier=Modifier.size(44.dp),onFailure:(()->Unit)?=null) {
    val app=LocalContext.current.applicationContext as YonzApp
    val failure by rememberUpdatedState(onFailure)
    ThumbnailFrame(url,modifier) {pixels->app.artwork.url(url,pixels).also {if(it==null && url.isNotBlank())failure?.invoke()}}
}
@Composable private fun ThumbnailFrame(identity:Any,modifier:Modifier,load:suspend (Int)->Bitmap?) {
    val density=LocalDensity.current
    BoxWithConstraints(modifier.clip(Design.small)) {
        val pixels=with(density){maxWidth.takeIf {it.value.isFinite()}?.roundToPx() ?: 512}.coerceIn(64,1536)
        val bucket=ArtworkPolicy.bucket(pixels)
        key(identity,bucket) {
            val bitmap by produceState<Bitmap?>(null) {value=withContext(Dispatchers.IO) {load(bucket)}}
            if(bitmap!=null)Image(bitmap!!.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            else DefaultArt(Modifier.fillMaxSize())
        }
    }
}
