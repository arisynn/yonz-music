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
data class PlaybackUi(val id: String? = null, val playing: Boolean = false, val queue: List<String> = emptyList(), val index: Int = 0, val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF, val error: String? = null, val ready: Boolean = false, val queueKeys: List<String> = emptyList(), val playbackOrder: List<Int> = emptyList(), val remoteTracks: List<Track> = emptyList(), val buffering: Boolean = false, val requested: Boolean = false)
class PlaybackConnection(context: Context) {
    val state = MutableStateFlow(PlaybackUi())
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var closed = false
    private val firstFrame = Choreographer.FrameCallback {
        android.os.Handler(android.os.Looper.getMainLooper()).post { if (!closed) connect(context) }
    }
    private fun streamingError(p:MediaController?):String {
        val code=p?.sessionExtras?.takeIf {it.getString(PlaybackService.STREAM_ISSUE_ID)==p.currentMediaItem?.mediaId}?.getString(PlaybackService.STREAM_ISSUE)
        return runCatching {StreamIssue.valueOf(code.orEmpty()).text}.getOrDefault(StreamIssue.SOURCE.text)
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
        override fun onPlayerError(error: PlaybackException) { state.value = state.value.copy(error = if(controller?.currentMediaItem?.mediaId?.startsWith("engine:")==true)streamingError(controller)else "Audio belum dapat diputar. Periksa lokasi file, izin folder, atau penyimpanan; rescan untuk menghubungkan ulang.") }
    }
    init { Choreographer.getInstance().postFrameCallback(firstFrame) }
    private fun connect(context: Context) {
        val pending = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).setListener(object:MediaController.Listener {
            override fun onExtrasChanged(controller:MediaController,extras:Bundle) {refresh()}
        }).buildAsync()
        future = pending
        pending.addListener({
            if (!closed) runCatching { controller = pending.get(); controller?.addListener(listener); refresh() }
                .onFailure { state.value = state.value.copy(error = "Playback service unavailable. Reopen the app to retry.") }
        }, ContextCompat.getMainExecutor(context))
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun refresh() { controller?.let { p ->
        val order = ArrayList<Int>(p.mediaItemCount)
        val timeline = p.currentTimeline
        var next = timeline.getFirstWindowIndex(p.shuffleModeEnabled)
        val seen = BooleanArray(p.mediaItemCount)
        // Repeat-one/all must not duplicate rows or loop forever in the queue sheet.
        while (next in seen.indices && !seen[next]) {
            seen[next] = true; order.add(next)
            next = timeline.getNextWindowIndex(next, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        }
        state.value = PlaybackUi(p.currentMediaItem?.mediaId, p.isPlaying, (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }, p.currentMediaItemIndex.coerceAtLeast(0), p.shuffleModeEnabled, p.repeatMode,
            if (p.playerError != null) if(p.currentMediaItem?.mediaId?.startsWith("engine:")==true)streamingError(p)else "Audio unavailable. Check file access and rescan your library." else null, true,
            (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaMetadata.extras?.getString("yono.queue.key") ?: "legacy:$it" },
            order.takeIf { it.size == p.mediaItemCount } ?: (0 until p.mediaItemCount).toList(),
            (0 until p.mediaItemCount).mapNotNull {i->remoteSong(p.getMediaItemAt(i))?.let {s->StreamRepository.display(s).copy(duration=if(i==p.currentMediaItemIndex && p.duration>0)p.duration else s.durationMs)}},
            p.playbackState==Player.STATE_BUFFERING,p.playWhenReady)
    } }
    fun play(tracks: List<Track>, id: String) {
        val available = tracks.filter { it.available }.distinctBy { it.id }.take(10000)
        val index = available.indexOfFirst { it.id == id }
        if (index < 0) return
        queueGeneration++
        controller?.apply { setMediaItems(available.map(Track::mediaItem), index, 0); prepare(); play() }
    }
    fun playRemote(song:com.yonzmusic.app.search.OnlineSong,next:Boolean?=null) {
        controller?.let {p->
            val item=remoteMediaItem(song,next=next==true)
            if(next==null){queueGeneration++;p.setMediaItem(item);p.prepare();p.play()}
            else {p.addMediaItem(if(next && p.mediaItemCount>0)p.currentMediaItemIndex+1 else p.mediaItemCount,item);if(p.playbackState==Player.STATE_IDLE)p.prepare()}
        }
    }
    fun toggle() { controller?.let {
        if(it.playerError!=null && it.currentMediaItem?.mediaId?.startsWith("engine:")==true) {
            val code=it.sessionExtras.getString(PlaybackService.STREAM_ISSUE)
            val position=if(code in setOf("FORMAT_CHANGED","SEEK","FORMAT"))0 else it.currentPosition.coerceAtLeast(0)
            val items=(0 until it.mediaItemCount).map(it::getMediaItemAt);val index=it.currentMediaItemIndex
            it.setMediaItems(items,index,position);it.prepare();it.play();return@let
        }
        if (it.playWhenReady && it.playbackState != Player.STATE_ENDED) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun pause() {controller?.pause()}
    fun previous() { controller?.seekToPrevious() }
    fun next() { controller?.seekToNext() }
    fun seek(position: Long) { controller?.seekTo(position.coerceAtLeast(0)) }
    fun audioSessionId(): Int = controller?.sessionExtras?.getInt(PlaybackService.AUDIO_SESSION, -1) ?: -1
    fun position(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: 0
    fun duration(): Long = controller?.duration?.coerceAtLeast(0) ?: 0
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun repeat() { controller?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } } }
    fun enqueue(track: Track, next: Boolean) { if (track.available) controller?.let { p -> val original=track.mediaItem();val item=original.buildUpon().setMediaMetadata(original.mediaMetadata.buildUpon().setExtras(android.os.Bundle(original.mediaMetadata.extras ?: android.os.Bundle()).apply {putBoolean("yono.next",next)}).build()).build();p.addMediaItem(if (next && p.mediaItemCount > 0) p.currentMediaItemIndex + 1 else p.mediaItemCount,item); if (p.playbackState == Player.STATE_IDLE) p.prepare() } }
    fun remove(index: Int) { controller?.let { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) } }
    fun move(from: Int, to: Int) { controller?.let { if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount) it.moveMediaItem(from, to) } }
    private var queueGeneration = 0
    class RemovedQueueItem internal constructor(val item: MediaItem, val before: String?, val after: String?, val index: Int, val generation: Int)
    private fun key(item: MediaItem) = item.mediaMetadata.extras?.getString("yono.queue.key")
    fun moveKey(from: String, to: String) { controller?.let { p ->
        if(p.shuffleModeEnabled)return@let
        val keys=(0 until p.mediaItemCount).map { key(p.getMediaItemAt(it)) }
        move(keys.indexOf(from), keys.indexOf(to))
    } }
    fun removeKey(value: String): RemovedQueueItem? = controller?.let { p ->
        val index=(0 until p.mediaItemCount).firstOrNull { key(p.getMediaItemAt(it))==value } ?: return@let null
        val receipt=RemovedQueueItem(p.getMediaItemAt(index), if(index>0)key(p.getMediaItemAt(index-1))else null,
            if(index+1<p.mediaItemCount)key(p.getMediaItemAt(index+1))else null,index,queueGeneration)
        p.removeMediaItem(index);receipt
    }
    fun restore(item: RemovedQueueItem) { controller?.let { p ->
        if(item.generation!=queueGeneration)return@let
        val keys=(0 until p.mediaItemCount).map { key(p.getMediaItemAt(it)) }
        if(key(item.item) in keys)return@let
        val after=keys.indexOf(item.after).takeIf { item.after!=null && it>=0 }
        val before=keys.indexOf(item.before).takeIf { item.before!=null && it>=0 }
        p.addMediaItem(after ?: before?.plus(1) ?: item.index.coerceIn(0,p.mediaItemCount),item.item)
    } }
    fun jump(index: Int) { controller?.let { if (index in 0 until it.mediaItemCount) { it.seekToDefaultPosition(index); it.prepare(); it.play() } } }
    fun timer(minutes: Int) { controller?.sendCustomCommand(SessionCommand(PlaybackService.TIMER, Bundle.EMPTY), Bundle().apply { putInt("minutes", minutes) }) }
    fun close() { closed = true; Choreographer.getInstance().removeFrameCallback(firstFrame); controller?.removeListener(listener); future?.let { MediaController.releaseFuture(it) } }
}
