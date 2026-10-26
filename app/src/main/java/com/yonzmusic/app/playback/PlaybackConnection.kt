package com.yonzmusic.app.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.view.Choreographer
import com.google.common.util.concurrent.ListenableFuture
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.session.*
import com.yonzmusic.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow

/** A single controller mirrors the service. Progress is read only by visible player composables. */
data class PlaybackUi(val id: String? = null, val playing: Boolean = false, val queue: List<String> = emptyList(), val index: Int = 0, val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF, val error: String? = null, val ready: Boolean = false)
class PlaybackConnection(context: Context) {
    val state = MutableStateFlow(PlaybackUi())
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var closed = false
    private val firstFrame = Choreographer.FrameCallback {
        android.os.Handler(android.os.Looper.getMainLooper()).post { if (!closed) connect(context) }
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
        override fun onPlayerError(error: PlaybackException) { state.value = state.value.copy(error = "Audio belum dapat diputar. Periksa lokasi file, izin folder, atau penyimpanan; rescan untuk menghubungkan ulang.") }
    }
    init { Choreographer.getInstance().postFrameCallback(firstFrame) }
    private fun connect(context: Context) {
        val pending = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        future = pending
        pending.addListener({
            if (!closed) runCatching { controller = pending.get(); controller?.addListener(listener); refresh() }
                .onFailure { state.value = state.value.copy(error = "Playback service unavailable. Reopen the app to retry.") }
        }, ContextCompat.getMainExecutor(context))
    }
    private fun refresh() { controller?.let { p ->
        state.value = PlaybackUi(p.currentMediaItem?.mediaId, p.playWhenReady && p.playbackState != Player.STATE_ENDED, (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }, p.currentMediaItemIndex.coerceAtLeast(0), p.shuffleModeEnabled, p.repeatMode,
            if (p.playerError != null) "Audio unavailable. Check file access and rescan your library." else null, true)
    } }
    fun play(tracks: List<Track>, id: String) {
        val available = tracks.filter { it.available }.distinctBy { it.id }.take(10000)
        val index = available.indexOfFirst { it.id == id }
        if (index < 0) return
        controller?.apply { setMediaItems(available.map(Track::mediaItem), index, 0); prepare(); play() }
    }
    fun toggle() { controller?.let { if (it.playWhenReady && it.playbackState != Player.STATE_ENDED) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun previous() { controller?.seekToPrevious() }
    fun next() { controller?.seekToNext() }
    fun seek(position: Long) { controller?.seekTo(position.coerceAtLeast(0)) }
    fun audioSessionId(): Int = controller?.sessionExtras?.getInt(PlaybackService.AUDIO_SESSION, -1) ?: -1
    fun position(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: 0
    fun duration(): Long = controller?.duration?.coerceAtLeast(0) ?: 0
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun repeat() { controller?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } } }
    fun enqueue(track: Track, next: Boolean) { if (track.available) controller?.let { p -> p.addMediaItem(if (next && p.mediaItemCount > 0) p.currentMediaItemIndex + 1 else p.mediaItemCount, track.mediaItem()); if (p.playbackState == Player.STATE_IDLE) p.prepare() } }
    fun remove(index: Int) { controller?.let { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) } }
    fun move(from: Int, to: Int) { controller?.let { if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount) it.moveMediaItem(from, to) } }
    fun jump(index: Int) { controller?.let { if (index in 0 until it.mediaItemCount) { it.seekToDefaultPosition(index); it.prepare(); it.play() } } }
    fun timer(minutes: Int) { controller?.sendCustomCommand(SessionCommand(PlaybackService.TIMER, Bundle.EMPTY), Bundle().apply { putInt("minutes", minutes) }) }
    fun close() { closed = true; Choreographer.getInstance().removeFrameCallback(firstFrame); controller?.removeListener(listener); future?.let { MediaController.releaseFuture(it) } }
}
