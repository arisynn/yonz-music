package com.yonzmusic.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import com.yonzmusic.app.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.yonzmusic.app.R
import com.yonzmusic.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore

private object ArtCache {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount }
    private val misses = object : LruCache<String, Boolean>(2048) {}
    private val permits = Semaphore(2)
    fun load(context: Context, track: Track): Bitmap? {
        val key = track.uri + track.artUri
        cache.get(key)?.let { return it }
        if (misses.get(key) == true) return null
        permits.acquire()
        return try {
            cache.get(key) ?: run {
                val bytes = runCatching {
                    if (track.artUri.isNotEmpty()) context.contentResolver.openInputStream(Uri.parse(track.artUri))?.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > 8 * 1024 * 1024) return@use null; out.write(buffer, 0, n) }
                        out.toByteArray()
                    } else null
                }.getOrNull() ?: runCatching { MediaMetadataRetriever().let { r -> try { r.setDataSource(context, Uri.parse(track.uri)); r.embeddedPicture } finally { r.release() } } }.getOrNull()
                if (bytes == null || bytes.size > 8 * 1024 * 1024) { misses.put(key, true); null }
                else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) sample *= 2
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    if (bitmap != null) cache.put(key, bitmap) else misses.put(key, true)
                    bitmap
                }
            }
        } finally { permits.release() }
    }
}

@Composable fun Artwork(track: Track?, modifier: Modifier = Modifier, description: String? = null) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(null, track?.uri, track?.artUri) {
        value = if (track != null) withContext(Dispatchers.IO) { runCatching { ArtCache.load(context, track) }.getOrNull() } else null
    }
    Box(modifier.clip(Design.small).semantics { if (description != null) contentDescription = description }) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else DefaultArt(Modifier.fillMaxSize(), track?.album ?: "")
    }
}
@Composable fun DefaultArt(modifier: Modifier = Modifier, seed: String = "") {
    val colors = MaterialTheme.colorScheme
    val accent = if (seed.hashCode() % 2 == 0) colors.primary else colors.secondary
    Canvas(modifier.background(Brush.linearGradient(listOf(colors.primaryContainer, colors.surfaceVariant, colors.secondaryContainer)))) {
        val s = size.minDimension
        val center = Offset(size.width * .56f, size.height * .49f)
        for (i in 0..4) drawCircle(accent.copy(alpha = .08f + i * .025f), s * (.25f + i * .085f), center, style = Stroke(s * .008f))
        drawCircle(colors.surface.copy(alpha = .45f), s * .23f, center)
        drawCircle(accent.copy(alpha = .7f), s * .045f, center)
        val y = Path().apply { moveTo(size.width * .2f, size.height * .19f); lineTo(size.width * .3f, size.height * .32f); lineTo(size.width * .4f, size.height * .19f); moveTo(size.width * .3f, size.height * .32f); lineTo(size.width * .3f, size.height * .47f) }
        drawPath(y, accent.copy(alpha = .8f), style = Stroke(s * .023f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
@Composable fun BrandMark(modifier: Modifier = Modifier) { Image(painterResource(R.drawable.brand_y), "Yon'z Music symbol", modifier, colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)) }

@Composable fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, enabled: Boolean = true) {
    IconButton(onClick, modifier.sizeIn(minWidth = Design.touch, minHeight = Design.touch), enabled = enabled) { Icon(icon, label, tint = if (enabled) tint else tint.copy(alpha = .38f), modifier = Modifier.size(Design.icon)) }
}
@Composable fun SectionTitle(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Design.page, vertical = Design.md), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}
@Composable fun EmptyState(title: String, message: String, kind: String = "library", action: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(Design.page), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Design.gap)) {
        Box(Modifier.size(136.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(colors.primary.copy(alpha = .04f), size.width * .48f)
                drawCircle(colors.primary.copy(alpha = .16f), size.width * .40f, style = Stroke(1.dp.toPx()))
                drawCircle(colors.secondary.copy(alpha = .16f), size.width * .30f, style = Stroke(1.dp.toPx()))
                drawCircle(colors.primary.copy(alpha = .5f), 3.dp.toPx(), Offset(size.width * .19f, size.height * .24f))
                drawCircle(colors.secondary.copy(alpha = .65f), 4.dp.toPx(), Offset(size.width * .84f, size.height * .65f))
            }
            Icon(when (kind) { "search" -> Icons.Rounded.Search; "playlist" -> Icons.Rounded.QueueMusic; else -> Icons.Rounded.GraphicEq }, null, Modifier.size(44.dp), tint = colors.primary)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
        action?.invoke()
    }
}
@Composable fun TrackRow(track: Track, active: Boolean, favorite: Boolean, onPlay: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = Design.page, end = Design.md, top = Design.sm, bottom = Design.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.md)) {
        Box {
            Artwork(track, Modifier.size(52.dp))
            if (active) Box(Modifier.size(52.dp).clip(Design.small).background(c.surface.copy(alpha = .6f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GraphicEq, "Current track", tint = c.primary, modifier = Modifier.size(24.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, color = if (active) c.primary else c.onSurface)
            Text(if (track.available) track.artist else "File unavailable · rescan to retry", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = if (track.available) c.onSurfaceVariant else c.error)
        }
        if (favorite) Icon(Icons.Rounded.Favorite, "Favorite", Modifier.size(14.dp), tint = c.primary)
        if (trailing != null) trailing() else ActionIcon(Icons.Rounded.MoreHoriz, "Options for ${track.title}", onMenu)
    }
}
@Composable fun GlassPanel(track: Track?, enabled: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(modifier.clip(Design.card).background(c.surfaceVariant.copy(alpha = if (enabled) Design.glassOpacity else 1f)).border(1.dp, c.outlineVariant.copy(alpha = .5f), Design.card)) {
        if (enabled && Build.VERSION.SDK_INT >= 31) {
            // Blur only the cached artwork layer; foreground text and controls stay sharp.
            Artwork(track, Modifier.matchParentSize().blur(28.dp).alpha(.14f))
        } else Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(c.primary.copy(alpha = .06f), Color.Transparent))))
        content()
    }
}
fun time(milliseconds: Long): String { val seconds = milliseconds.coerceAtLeast(0) / 1000; return "%d:%02d".format(seconds / 60, seconds % 60) }
fun sortedTracks(tracks: List<Track>, sort: String): List<Track> = when (sort) {
    "Artist" -> tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
    "Album" -> tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.album })
    "Recently added" -> tracks.sortedByDescending { it.added }
    "Duration" -> tracks.sortedByDescending { it.duration }
    else -> tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
}

fun songCount(count: Int): String = if (count == 1) "1 song" else "$count songs"
