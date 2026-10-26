package com.yonzmusic.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.yonzmusic.app.MainActivity
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.data.History
import com.yonzmusic.app.data.ListenAccumulator
import com.yonzmusic.app.data.ListenEvent
import kotlinx.coroutines.flow.collectLatest
import com.yonzmusic.app.data.PlaybackSnapshot
import com.yonzmusic.app.data.Track
import kotlinx.coroutines.*

fun Track.mediaItem(): MediaItem = MediaItem.Builder().setMediaId(id).setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setExtras(Bundle().apply { putString("yono.queue.key", java.util.UUID.randomUUID().toString()) })
        .setArtworkUri(artUri.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)).build()).build()

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var snapshotJob: Job? = null
    private var sleepJob: Job? = null
    private val app get() = application as YonzApp
    private var consecutiveErrors = 0
    private var wasShuffling = false
    private var previousQueueKeys = emptySet<String>()
    private fun updateShuffleCycle() {
        val keys = (0 until player.mediaItemCount).map {
            player.getMediaItemAt(it).mediaMetadata.extras?.getString("yono.queue.key") ?: "legacy:$it"
        }.toSet()
        val newQueue = keys.isNotEmpty() && keys.none { it in previousQueueKeys }
        val startCycle = player.shuffleModeEnabled && (!wasShuffling || newQueue)
        // Update guards before setShuffleOrder emits another timeline event.
        wasShuffling = player.shuffleModeEnabled
        previousQueueKeys = keys
        if (startCycle && player.mediaItemCount > 0) {
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(
                ShuffleCycle.indices(player.mediaItemCount, player.currentMediaItemIndex), kotlin.random.Random.nextLong()))
        }
    }
    private val listens=ListenAccumulator()
    private fun listenTick(restart: Boolean = false) {
        val result=listens.tick(player.currentMediaItem?.mediaId,player.duration.coerceAtLeast(0),player.isPlaying,android.os.SystemClock.elapsedRealtime(),restart)
        result?.let { q -> app.persistenceScope.launch {
            val canonical=app.repository.dao.state("track_alias:${q.trackId}")?.value ?: q.trackId
            if(app.repository.dao.track(canonical)!=null) {
                app.repository.dao.listen(ListenEvent(trackId=canonical,startedAt=System.currentTimeMillis(),listenedMs=q.listenedMs))
                app.repository.dao.remember(History(canonical,System.currentTimeMillis(),0))
            }
        } }
    }
    companion object { const val TIMER = "com.yonzmusic.app.SLEEP_TIMER"; const val AUDIO_SESSION = "com.yonzmusic.app.AUDIO_SESSION" }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SessionCommand(TIMER, Bundle.EMPTY)).build()).build()
            }
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
                val result = SettableFuture.create<MutableList<MediaItem>>()
                scope.launch {
                    try {
                        val local = withContext(Dispatchers.IO) { mediaItems.take(10000).mapNotNull { requested ->
                            app.repository.dao.track(requested.mediaId)?.takeIf { t -> t.active && t.available && android.net.Uri.parse(t.uri).scheme == "content" }?.mediaItem()?.let { resolved ->
                                val key=requested.mediaMetadata.extras?.getString("yono.queue.key")?.takeIf { it.matches(Regex("[a-fA-F0-9-]{36}")) }
                                if(key==null)resolved else resolved.buildUpon().setMediaMetadata(resolved.mediaMetadata.buildUpon()
                                    .setExtras(Bundle().apply {putString("yono.queue.key",key)}).build()).build()
                            }
                        }.toMutableList() }
                        result.set(local)
                    } catch (e: Exception) { result.setException(e) }
                }
                return result
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (customCommand.customAction != TIMER) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                val minutes = args.getInt("minutes").coerceIn(0, 180)
                sleepJob?.cancel()
                app.sleepEnd.value = if (minutes > 0) System.currentTimeMillis() + minutes * 60000L else 0L
                if (minutes > 0) sleepJob = scope.launch { delay(minutes * 60000L); player.pause(); app.sleepEnd.value = 0; persist() }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }).build()
        session?.setSessionExtras(Bundle().apply { putInt(AUDIO_SESSION, player.audioSessionId) })
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { listenTick(reason==Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) }
            override fun onAudioSessionIdChanged(audioSessionId: Int) { session?.setSessionExtras(Bundle().apply { putInt(AUDIO_SESSION, audioSessionId) }) }
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) updateShuffleCycle()
                listenTick()
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) persist()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listenTick()
                snapshotJob?.cancel()
                if (isPlaying) {
                    consecutiveErrors = 0
                    persist()
                    snapshotJob = scope.launch { while (isActive) { delay(5000);listenTick();persist() } }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val failed = player.currentMediaItem?.mediaId
                if (failed != null) scope.launch(Dispatchers.IO) { app.repository.playbackFailure(failed,error.errorCode) }
                consecutiveErrors++
                if (player.hasNextMediaItem() && consecutiveErrors < player.mediaItemCount) { player.seekToNextMediaItem(); player.prepare() }
                else player.pause()
            }
        })
        // Rebind queue items after a committed relocation, keeping position and paused/playing state.
        scope.launch {
            app.repository.dao.collectionTracks().collectLatest { rows ->
                val byId=rows.associateBy { it.id }
                val aliases=withContext(Dispatchers.IO){app.repository.dao.states("track_alias:%").associate { it.name.substringAfter("track_alias:") to it.value }}
                for(index in 0 until player.mediaItemCount) {
                    val old=player.getMediaItemAt(index);val track=byId[aliases[old.mediaId] ?: old.mediaId] ?: continue
                    if(!track.available)continue
                    val fresh=track.mediaItem()
                    val updated=fresh.buildUpon().setMediaMetadata(fresh.mediaMetadata.buildUpon().setExtras(old.mediaMetadata.extras).build()).build()
                    if(old.mediaId!=updated.mediaId || old.localConfiguration?.uri!=updated.localConfiguration?.uri || old.mediaMetadata!=updated.mediaMetadata) {
                        val current=index==player.currentMediaItemIndex;val position=player.currentPosition;val wasPlaying=player.playWhenReady
                        player.replaceMediaItem(index,updated)
                        if(current) { player.seekTo(index,position.coerceAtLeast(0));if(player.playbackState==Player.STATE_IDLE)player.prepare();player.playWhenReady=wasPlaying }
                    }
                }
            }
        }
        scope.launch {
            val saved = app.preferences.snapshot() ?: return@launch
            val restoredIds = withContext(Dispatchers.IO) { saved.ids.map { app.repository.dao.state("track_alias:$it")?.value ?: it } }
            val tracks = withContext(Dispatchers.IO) { restoredIds.mapNotNull { app.repository.dao.track(it)?.takeIf { it.active && it.available } }.distinctBy { it.id } }
            if (tracks.isNotEmpty() && player.mediaItemCount == 0) {
                val currentId = restoredIds.getOrNull(saved.index)
                val index = tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                val position = if (tracks[index].id == currentId) com.yonzmusic.app.data.PlaybackResume.position(saved.position, tracks[index].duration) else 0L
                player.setMediaItems(tracks.map(Track::mediaItem), index, position)
                player.shuffleModeEnabled = saved.shuffle
                player.repeatMode = saved.repeat.coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)
                // Restore context, never autoplay after process recreation.
                player.playWhenReady = false
            }
        }
    }
    private fun persist() {
        if (!::player.isInitialized) return
        val snapshot = PlaybackSnapshot((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0), player.shuffleModeEnabled, player.repeatMode)
        val played = player.isPlaying
        app.persistenceScope.launch {
            app.preferences.save(snapshot)
            val id = snapshot.ids.getOrNull(snapshot.index)
            if(id!=null) app.repository.dao.historyRow(id)?.let { old -> app.repository.dao.remember(old.copy(position=snapshot.position)) }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) { if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) stopSelf() }
    override fun onDestroy() {
        listenTick()
        snapshotJob?.cancel(); sleepJob?.cancel(); app.sleepEnd.value = 0
        session?.release(); player.release(); scope.cancel()
        super.onDestroy()
    }
}
