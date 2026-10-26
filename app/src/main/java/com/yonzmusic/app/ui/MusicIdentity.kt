package com.yonzmusic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.yonzmusic.app.data.Track
import com.yonzmusic.app.playback.PlaybackUi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.sin

internal val LocalPlayingState = compositionLocalOf<StateFlow<PlaybackUi>?> { null }

/** Status animation, not an audio spectrum analyser. Only the visible drawing reads frame state. */
@Composable fun PlayingBars(modifier: Modifier = Modifier) {
    val playback = LocalPlayingState.current?.collectAsStateWithLifecycle()?.value
    val playing = playback?.playing == true
    val reduced = LocalReducedMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, reduced, lifecycle) {
        if (playing && !reduced) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var start = 0L
            while (true) withFrameNanos { now ->
                if(start == 0L) start = now
                phase.floatValue = ((now-start) % 3_000_000_000L).toFloat()/3_000_000_000f * 6.283185f
            }
        }
    }
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription = if(playing) "Sedang diputar" else "Lagu aktif dijeda" }) {
        for(i in 0..3) {
            val height = size.height * if(playing && !reduced) (.28f + .62f * ((sin(phase.floatValue * (if(i%2==0)1 else 2) + i*1.7f)+1)/2)) else (.3f + .12f * (i%3))
            val x = size.width * (.2f + .2f*i)
            drawLine(color,Offset(x,(size.height-height)/2),Offset(x,(size.height+height)/2),size.width*.09f,StrokeCap.Round)
        }
    }
}

@Composable fun HomeIdentity(tracks: List<Track>) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(horizontal=Design.page,vertical=Design.sm).clip(Design.card)
        .background(Brush.linearGradient(listOf(c.primaryContainer,c.surfaceVariant,c.secondaryContainer)))) {
        Canvas(Modifier.matchParentSize()) {
            for(i in 0..4) {
                val path=Path().apply {
                    moveTo(size.width*.4f,size.height*(.8f+i*.12f))
                    cubicTo(size.width*.7f,-size.height*.5f,size.width*.7f,size.height*1.6f,size.width*1.2f,size.height*(.15f+i*.1f))
                }
                drawPath(path,c.primary.copy(alpha=.10f),style=Stroke(2.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min=128.dp).padding(Design.gap),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Design.md)) {
            Column(Modifier.weight(1f)) {
                Text("Musikmu,\nruangmu.",style=MaterialTheme.typography.headlineMedium,color=c.onPrimaryContainer)
                Spacer(Modifier.height(Design.xs))
                Text("Dekat dengan yang kamu suka.",style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)
            }
            if(LocalDensity.current.fontScale<=1.3f) HomeCoverCarousel(tracks,Modifier.size(76.dp))
        }
    }
}

/** Decorative only: bounded local artwork cache, no navigation, network or playback action. */
@Composable private fun HomeCoverCarousel(tracks: List<Track>, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reduced = LocalReducedMotion.current
    val pixels = with(LocalDensity.current) { 76.dp.roundToPx() }
    val candidates = remember(tracks) { HomeArtCandidates.select(tracks) }
    var visible by remember { mutableStateOf(false) }
    var cover by remember(candidates, pixels) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(candidates, pixels, lifecycle, reduced, visible) {
        if (!visible || candidates.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var cursor = 0
            val missing = mutableSetOf<Int>()
            while (true) {
                if (cover != null) delay(6000)
                var next: android.graphics.Bitmap? = null
                // Missing covers are skipped, rather than flashing a placeholder between photos.
                repeat(candidates.size) {
                    if (next == null) {
                        val index = cursor % candidates.size
                        val track = candidates[index]
                        cursor++
                        if (index !in missing) {
                            next = withContext(Dispatchers.IO) { runCatching { ArtCache.load(context, track, pixels) }.getOrNull() }
                            if (next == null) missing += index
                        }
                    }
                }
                if (next == null) return@repeatOnLifecycle
                cover = next
                if (reduced || candidates.size - missing.size < 2) return@repeatOnLifecycle
            }
        }
    }
    androidx.compose.animation.Crossfade(cover,
        modifier = modifier.clip(Design.small).onGloballyPositioned {
            val bounds = it.boundsInWindow()
            visible = bounds.width > 0 && bounds.height > 0
        }.clearAndSetSemantics { },
        animationSpec = androidx.compose.animation.core.tween(if (reduced) 0 else 600), label = "home local covers") { bitmap ->
        if (bitmap != null) androidx.compose.foundation.Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else DefaultArt(Modifier.fillMaxSize())
    }
}
