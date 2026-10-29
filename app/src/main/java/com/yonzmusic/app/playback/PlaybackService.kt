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
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(com.yonzmusic.app.i18n.Bahasa.artis(artist)).setAlbumTitle(com.yonzmusic.app.i18n.Bahasa.album(album))
        .setExtras(Bundle().apply { putString("yono.queue.key", java.util.UUID.randomUUID().toString()) })
        .setArtworkUri(artUri.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)).build()).build()

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.yonzmusic.app.i18n.BahasaLocale.wrap(base))
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var snapshotJob: Job? = null
    private var sleepJob: Job? = null
    private var warmJob: Job? = null
    private var warmIds = emptyList<String>()
    private val app get() = application as YonzApp
    private var streamIssue:StreamIssue?=null
    private var issueMediaId:String?=null
    private fun publishExtras() {
        session?.setSessionExtras(Bundle().apply {
            putInt(AUDIO_SESSION,player.audioSessionId)
            streamIssue?.let {putString(STREAM_ISSUE,it.name);putString(STREAM_ISSUE_ID,issueMediaId)}
        })
    }
    private var consecutiveErrors = 0
    private var wasShuffling = false
    private var previousQueueKeys = emptySet<String>()
    /** Follow the player's actual shuffle/repeat order; never crawl search/history on startup. */
    private fun warmUpcoming() {
        val ids = mutableListOf<String>()
        if (player.isPlaying && app.streamTestDataSource == null) {
            val timeline = player.currentTimeline
            var index = player.currentMediaItemIndex
            val visited = mutableSetOf(index)
            repeat(2) {
                index = if (index in 0 until timeline.windowCount)
                    timeline.getNextWindowIndex(index, player.repeatMode, player.shuffleModeEnabled) else C.INDEX_UNSET
                if (index in 0 until player.mediaItemCount && visited.add(index)) {
                    player.getMediaItemAt(index).mediaId.takeIf { it.startsWith("engine:") }
                        ?.removePrefix("engine:")?.let(ids::add)
                }
            }
        }
        if (ids == warmIds) return
        warmJob?.cancel(); warmIds = ids
        warmJob = scope.launch {
            // Current audio has priority over speculative extraction.
            delay(400)
            for (id in ids) {
                ensureActive()
                try {
                    runInterruptible(Dispatchers.IO) {
                        app.streams.resolve(id, java.util.concurrent.atomic.AtomicBoolean(false), timeoutMillis = 18000)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* A warm-up failure must never stop current playback. */ }
            }
        }
    }
    private fun updateShuffleCycle() {
        val keys = (0 until player.mediaItemCount).map {
            player.getMediaItemAt(it).mediaMetadata.extras?.getString("yono.queue.key") ?: "legacy:$it"
        }.toSet()
        val inserted=(0 until player.mediaItemCount).filter {i->player.getMediaItemAt(i).mediaMetadata.extras?.getString("yono.queue.key") !in previousQueueKeys}
        val insertedNext=inserted.filter {i->
            val item=player.getMediaItemAt(i)
            item.mediaMetadata.extras?.getBoolean("yono.next")==true && item.mediaMetadata.extras?.getString("yono.queue.key") !in previousQueueKeys
        }
        val newQueue = keys.isNotEmpty() && keys.none { it in previousQueueKeys }
        val startCycle = player.shuffleModeEnabled && (!wasShuffling || newQueue)
        // Update guards before setShuffleOrder emits another timeline event.
        wasShuffling = player.shuffleModeEnabled
        previousQueueKeys = keys
        if(player.shuffleModeEnabled && !newQueue && inserted.isNotEmpty()) {
            val order=mutableListOf<Int>();val timeline=player.currentTimeline
            var i=timeline.getFirstWindowIndex(true)
            while(i in 0 until player.mediaItemCount && i !in order){order.add(i);i=timeline.getNextWindowIndex(i,Player.REPEAT_MODE_OFF,true)}
            if(order.size==player.mediaItemCount)player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder((ShuffleCycle.insertNext(order.filter {it !in inserted},player.currentMediaItemIndex,insertedNext).toList()+inserted.filter {it !in insertedNext}).toIntArray(),kotlin.random.Random.nextLong()))
        }else if (startCycle && player.mediaItemCount > 0) {
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
    companion object { const val TIMER = "com.yonzmusic.app.SLEEP_TIMER"; const val AUDIO_SESSION = "com.yonzmusic.app.AUDIO_SESSION";const val STREAM_ISSUE="yono.stream.issue";const val STREAM_ISSUE_ID="yono.stream.issue.id" }

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(com.yonzmusic.app.R.string.playback_channel).build())
        // Rename the existing channel without changing the user's importance/sound preferences.
        val notifications = getSystemService(android.app.NotificationManager::class.java)
        runCatching { notifications.getNotificationChannel(androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID)?.let {
            it.name = getString(com.yonzmusic.app.R.string.playback_channel)
            notifications.createNotificationChannel(it)
        } }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this).setLoadErrorHandlingPolicy(StreamLoadPolicy()).setDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, app.streamTestDataSource ?: androidx.media3.datasource.DataSource.Factory {StreamDataSource(app.streams)})))
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(3000,20000,700,1500).build())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        // Media3 targets five seconds of the next item after the current buffer is full.
        // Same player, source validation and retry policy; memory only, no offline file claims.
        player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(5_000_000L))
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
                            if(requested.mediaId.startsWith("engine:")) {
                                val remote=app.streams.lookup(requested.mediaId) ?: return@mapNotNull null
                                return@mapNotNull remoteMediaItem(remote,requested.mediaMetadata.extras?.getString("yono.queue.key"),requested.mediaMetadata.extras?.getBoolean("yono.next")==true)
                            }
                            app.repository.dao.track(requested.mediaId)?.takeIf { t -> t.active && t.available && android.net.Uri.parse(t.uri).scheme == "content" }?.mediaItem()?.let { resolved ->
                                val key=requested.mediaMetadata.extras?.getString("yono.queue.key")?.takeIf { it.matches(Regex("[a-fA-F0-9-]{36}")) }
                                if(key==null)resolved else resolved.buildUpon().setMediaMetadata(resolved.mediaMetadata.buildUpon()
                                    .setExtras(Bundle().apply {putString("yono.queue.key",key);putBoolean("yono.next",requested.mediaMetadata.extras?.getBoolean("yono.next")==true)}).build()).build()
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
        publishExtras()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { listenTick(reason==Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) }
            override fun onAudioSessionIdChanged(audioSessionId: Int) { publishExtras() }
            override fun onEvents(player: Player, events: Player.Events) {
                if(streamIssue!=null && (player.currentMediaItem?.mediaId!=issueMediaId || (player.playerError==null && player.playbackState!=Player.STATE_IDLE))) {streamIssue=null;issueMediaId=null;publishExtras()}
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) updateShuffleCycle()
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED)) warmUpcoming()
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
                if(failed?.startsWith("engine:")==true) {streamIssue=if(error.errorCode in setOf(PlaybackException.ERROR_CODE_DECODING_FAILED,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))StreamIssue.FORMAT else StreamIssue.from(error.cause);issueMediaId=failed;publishExtras()}
                if (failed != null && !failed.startsWith("engine:")) scope.launch(Dispatchers.IO) { app.repository.playbackFailure(failed,error.errorCode) }
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
            val restored = withContext(Dispatchers.IO) { restoredIds.mapNotNull {id->
                if(id.startsWith("engine:"))app.streams.lookup(id)?.let {remoteMediaItem(it)}
                else app.repository.dao.track(id)?.takeIf {it.active && it.available}?.mediaItem()
            } }
            if (restored.isNotEmpty() && player.mediaItemCount == 0) {
                val currentId = restoredIds.getOrNull(saved.index)
                val index = restored.indexOfFirst { it.mediaId == currentId }.coerceAtLeast(0)
                val position = saved.position.coerceAtLeast(0)
                player.setMediaItems(restored, index, position)
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
        snapshotJob?.cancel(); sleepJob?.cancel(); warmJob?.cancel(); app.sleepEnd.value = 0
        session?.release(); player.release(); scope.cancel()
        super.onDestroy()
    }
}
