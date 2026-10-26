package com.yonzmusic.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import com.yonzmusic.app.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import android.os.Build
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import com.yonzmusic.app.data.Track
import com.yonzmusic.app.data.UserPreferences
import com.yonzmusic.app.playback.*
import kotlinx.coroutines.delay

@Composable fun MiniPlayer(track: Track, playing: Boolean, glass: Boolean, connection: PlaybackConnection, open: () -> Unit) {
    GlassPanel(track, glass, Modifier.fillMaxWidth().padding(horizontal = Design.md, vertical = Design.xs)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open now playing", onClick = open).padding(start = Design.md, top = Design.sm, bottom = Design.sm, end = Design.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.md)) {
                Artwork(track, Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ActionIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", connection::toggle, tint = MaterialTheme.colorScheme.primary)
                if (LocalConfiguration.current.screenWidthDp >= 360 && LocalDensity.current.fontScale <= 1.3f) ActionIcon(Icons.Rounded.SkipNext, "Next song", connection::next)
            }
            MiniProgress(track, connection)
        }
    }
}
@Composable private fun playbackPosition(track: Track, connection: PlaybackConnection): State<Long> {
    val position = remember(track.id) { mutableLongStateOf(connection.position()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(track.id, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { position.longValue = connection.position(); delay(400) } } }
    return position
}
@Composable private fun MiniProgress(track: Track, connection: PlaybackConnection) {
    val position by playbackPosition(track, connection)
    LinearProgressIndicator(progress = { (position.toFloat() / track.duration.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f), gapSize = 0.dp, drawStopIndicator = {})
}
@Composable fun PlayerScreen(track: Track, state: PlaybackUi, connection: PlaybackConnection, favorite: Boolean, preferences: UserPreferences, sleepEnd: Long, close: () -> Unit, toggleFavorite: () -> Unit, queue: () -> Unit, options: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window
    SideEffect { dialogWindow?.let { window ->
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = c.background.luminance() > .5f
            isAppearanceLightNavigationBars = c.background.luminance() > .5f
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) { window.isStatusBarContrastEnforced = false; window.isNavigationBarContrastEnforced = false }
    } }
    Surface(Modifier.fillMaxSize(), color = c.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val compact = maxHeight < 650.dp || LocalDensity.current.fontScale > 1.3f
            val artBudget = if (LocalDensity.current.fontScale > 1.3f) 120.dp else (maxHeight - if (compact) 372.dp else 434.dp).coerceIn(120.dp, 360.dp)
            val artSize = minOf(maxWidth - Design.page * 2, artBudget).coerceAtLeast(120.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = if (compact) Design.sm else Design.gap), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Design.md, vertical = Design.xs), verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(Icons.Rounded.KeyboardArrowDown, "Close now playing", close)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = c.primary)
                        Text(track.album, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onSurfaceVariant)
                    }
                    ActionIcon(Icons.Rounded.MoreHoriz, "Song options", options)
                }
                Spacer(Modifier.height(Design.sm))
                Box(Modifier.size(artSize + Design.sm).background(Brush.radialGradient(listOf(c.primary.copy(alpha = .10f), c.background)), CircleShape), contentAlignment = Alignment.Center) {
                    Artwork(track, Modifier.size(artSize).shadow(16.dp, Design.large, ambientColor = c.primary.copy(alpha = .15f), spotColor = c.primary.copy(alpha = .15f)).clip(Design.large), "Album artwork for ${track.album}")
                }
                Spacer(Modifier.height(if (compact) Design.md else Design.gap))
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = Design.page)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                        Column(Modifier.weight(1f)) {
                            Text(track.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(Design.xs))
                            Text(track.artist, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        val scale by animateFloatAsState(if (favorite) 1.08f else 1f, if (preferences.reduceMotion) snap() else tween(Design.motion), label = "favorite")
                        ActionIcon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (favorite) "Remove favorite" else "Favorite song", toggleFavorite, Modifier.graphicsLayer { scaleX = scale; scaleY = scale }, if (favorite) c.primary else c.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(if (compact) Design.sm else Design.md))
                    SeekControls(track, connection)
                    Spacer(Modifier.height(if (compact) Design.sm else Design.md))
                    GlassPanel(track, preferences.glass, Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(vertical = Design.sm, horizontal = Design.sm), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            ActionIcon(Icons.Rounded.SkipPrevious, "Previous song", connection::previous)
                            FilledIconButton(connection::toggle, modifier = Modifier.size(if (compact) 64.dp else 72.dp), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.primary, contentColor = c.onPrimary)) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(36.dp)) }
                            ActionIcon(Icons.Rounded.SkipNext, "Next song", connection::next)
                        }
                    }
                    Spacer(Modifier.height(if (compact) Design.xs else Design.sm))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                        ActionIcon(Icons.Rounded.Shuffle, if (state.shuffle) "Shuffle on" else "Shuffle off", connection::shuffle, tint = if (state.shuffle) c.primary else c.onSurfaceVariant)
                        ActionIcon(if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, when (state.repeat) { Player.REPEAT_MODE_ALL -> "Repeat all"; Player.REPEAT_MODE_ONE -> "Repeat one"; else -> "Repeat off" }, connection::repeat, tint = if (state.repeat != Player.REPEAT_MODE_OFF) c.primary else c.onSurfaceVariant)
                        ActionIcon(Icons.Rounded.QueueMusic, "Open playback queue", queue)
                    }
                    if (state.error != null) Text(state.error, style = MaterialTheme.typography.bodySmall, color = c.error, modifier = Modifier.padding(vertical = Design.sm))
                    if (sleepEnd > 0) SleepRemaining(sleepEnd, Modifier.align(Alignment.CenterHorizontally))
                    if (!compact) Text("LOCAL AUDIO · YOUR SPACE", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = Design.gap))
                }
            }
        }
    }
}
@Composable private fun SeekControls(track: Track, connection: PlaybackConnection) {
    val position by playbackPosition(track, connection)
    var dragging by remember(track.id) { mutableStateOf<Float?>(null) }
    val duration = track.duration.coerceAtLeast(1).toFloat()
    val value = dragging ?: position.toFloat().coerceIn(0f, duration)
    Column {
        Slider(value, { dragging = it }, onValueChangeFinished = { dragging?.let { connection.seek(it.toLong()) }; dragging = null }, valueRange = 0f..duration, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" })
        Row(Modifier.fillMaxWidth()) { Text(time(value.toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.weight(1f)); Text(time(track.duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable fun SleepRemaining(end: Long, modifier: Modifier = Modifier) {
    var remaining by remember(end) { mutableLongStateOf((end - System.currentTimeMillis()).coerceAtLeast(0)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(end, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { remaining = (end - System.currentTimeMillis()).coerceAtLeast(0); delay(1000) } } }
    Text("Sleep timer · ${time(remaining)} remaining", modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}
