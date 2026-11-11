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
import com.yonzmusic.app.data.UserPreferences
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
    private var performance:StreamPlayerMetrics?=null
    private var metadataCoordinator:PlaybackMetadataCoordinator?=null
    private var snapshotJob: Job? = null
    private var sleepJob: Job? = null
    private var warmJob: Job? = null
    private var warmingSourceId:String?=null
    private var warmIds = emptyList<String>()
    private var preloadEnabled = false
    private var settings = UserPreferences()
    private var radioJob: Job? = null
    private var attemptedTail: String? = null
    private val failedRadio = linkedSetOf<String>()
    private var radioNotice = ""
    private var startupAt = 0L
    private var bufferingAt = 0L
    private val app get() = application as YonzApp
    private var streamIssue:StreamIssue?=null
    private var issueMediaId:String?=null
    private fun publishExtras() {
        session?.setSessionExtras(Bundle().apply {
            putInt(AUDIO_SESSION,player.audioSessionId)
            putString(RADIO_NOTICE,radioNotice)
            streamIssue?.let {putString(STREAM_ISSUE,it.name);putString(STREAM_ISSUE_ID,issueMediaId)}
        })
    }
    private var consecutiveErrors = 0
    private var wasShuffling = false
    private var previousQueueKeys = emptySet<String>()
    /** Four successors resolved serially; native byte preloading stays on the immediate next item. */
    private fun warmUpcoming() {
        val tailReady=!player.isLoading && player.duration>0 && player.bufferedPosition>=player.duration-100 && player.totalBufferedDuration>=750
        val sufficient=player.totalBufferedDuration>=5000 || tailReady
        val enablePreload=player.isPlaying && sufficient
        if(enablePreload!=preloadEnabled) {
            preloadEnabled=enablePreload
            // DefaultLoadControl additionally refuses byte preloading while current loading is active.
            player.setPreloadConfiguration(if(enablePreload)ExoPlayer.PreloadConfiguration(2_000_000L) else ExoPlayer.PreloadConfiguration.DEFAULT)
        }
        fun safe()=player.isPlaying && !player.isLoading && !StreamWork.urgent
        val next=if((app.streamTestDataSource==null || app.streamTestResolver!=null) && enablePreload && safe() && player.mediaItemCount>0) {
            val repeat=if(player.repeatMode==Player.REPEAT_MODE_ONE)Player.REPEAT_MODE_OFF else player.repeatMode
            UpcomingTracks.indices(player.currentMediaItemIndex){index->player.currentTimeline.getNextWindowIndex(index,repeat,player.shuffleModeEnabled)}
                .map(player::getMediaItemAt).filter {it.mediaId.isRemoteId() && it.mediaId!=player.currentMediaItem?.mediaId}.distinctBy {it.mediaId}
        }else emptyList()
        val keys=if(next.isEmpty())emptyList() else next.flatMap {listOf(it.mediaId,it.mediaMetadata.extras?.getString("yono.queue.key").orEmpty())}+"quality:${app.streams.quality}"
        if(keys==warmIds)return
        // An extraction promoted to current playback may finish; its obsolete window stops afterward.
        if(warmingSourceId!=player.currentMediaItem?.mediaId || warmJob?.isActive!=true)warmJob?.cancel()
        warmIds=keys
        if(next.isEmpty())return
        warmJob=scope.launch {
            var complete=false;var prepared=0;val started=android.os.SystemClock.elapsedRealtime()
            try {
                delay(400)
                for(item in next) {
                    if(keys!=warmIds || !safe())return@launch
                    val song=app.streams.lookup(item.mediaId) ?: continue
                    warmingSourceId=item.mediaId
                    try {
                        runInterruptible(Dispatchers.IO) {StreamWork.forSource(item.mediaId) {
                            val mapped=runBlocking {app.discovery.audio(song)}
                            (app.streamTestResolver ?: app.streams).resolve(mapped.videoId,java.util.concurrent.atomic.AtomicBoolean(false),timeoutMillis=8000)
                        }}
                        prepared++
                    }catch(e:CancellationException){throw e}catch(_:Exception){ /* Current playback can retry authoritatively. */ }
                    finally {if(warmingSourceId==item.mediaId)warmingSourceId=null}
                }
                complete=true
                StreamPerformance.mark(player.currentMediaItem?.mediaId,StreamPerformance.Stage.UPCOMING_READY,android.os.SystemClock.elapsedRealtime()-started,count=prepared.toLong())
            }finally {if(!complete && warmIds==keys)warmIds=emptyList()}
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
        result?.let { q ->
            val historyToken=if(q.trackId.isRemoteId())app.onlineActivity.historyToken() else 0L
            app.persistenceScope.launch {
            if(q.trackId.isRemoteId()) {
                app.onlineActivity.listened(q.trackId,q.listenedMs,historyToken)
                return@launch
            }
            val canonical=app.repository.dao.state("track_alias:${q.trackId}")?.value ?: q.trackId
            if(app.repository.dao.track(canonical)!=null) {
                app.repository.dao.listen(ListenEvent(trackId=canonical,startedAt=System.currentTimeMillis(),listenedMs=q.listenedMs))
                app.repository.dao.remember(History(canonical,System.currentTimeMillis(),0))
            }
        } }
    }
    companion object { const val TIMER = "com.yonzmusic.app.SLEEP_TIMER"; const val AUDIO_SESSION = "com.yonzmusic.app.AUDIO_SESSION";const val RADIO_NOTICE="yono.radio.notice";const val STREAM_ISSUE="yono.stream.issue";const val STREAM_ISSUE_ID="yono.stream.issue.id" }

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
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this).setLoadErrorHandlingPolicy(StreamLoadPolicy()).setDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, app.streamTestDataSource ?: androidx.media3.datasource.DataSource.Factory { CatalogDataSource(app, app.streamCache.factory().createDataSource()) })))
            .setLoadControl(StreamingLoadControls.create())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        performance=StreamPlayerMetrics(player)
        metadataCoordinator=PlaybackMetadataCoordinator(app,player,scope)
        // Media3 targets five seconds of the next item after the current buffer is full.
        // Same player, source validation and retry policy; memory only, no offline file claims.
        player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration.DEFAULT)
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
                            if(requested.mediaId.isRemoteId()) {
                                val remote=app.streams.lookup(requested.mediaId) ?: return@mapNotNull null
                                val admitted=remoteMediaItem(remote,requested.mediaMetadata.extras?.getString("yono.queue.key"),requested.mediaMetadata.extras?.getBoolean("yono.next")==true)
                                return@mapNotNull admitted.buildUpon().setMediaMetadata(admitted.mediaMetadata.buildUpon().setExtras(Bundle(admitted.mediaMetadata.extras).apply {
                                    putBoolean("yono.radio",requested.mediaMetadata.extras?.getBoolean("yono.radio")==true)
                                }).build()).build()
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
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if(playWhenReady && !player.isPlaying && startupAt==0L)startupAt=android.os.SystemClock.elapsedRealtime()
                if(!playWhenReady)startupAt=0L
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                StreamWork.currentMediaId=mediaItem?.mediaId
                listenTick(reason==Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                startupAt=android.os.SystemClock.elapsedRealtime()
                if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {radioJob?.cancel();radioJob=null;attemptedTail=null;failedRadio.clear()}
                if(mediaItem?.mediaMetadata?.extras?.getBoolean("yono.generated")!=true) {radioNotice="";publishExtras()}
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) { publishExtras() }
            override fun onEvents(player: Player, events: Player.Events) {
                if(streamIssue!=null && (player.currentMediaItem?.mediaId!=issueMediaId || (player.playerError==null && player.playbackState!=Player.STATE_IDLE))) {streamIssue=null;issueMediaId=null;publishExtras()}
                StreamWork.currentMediaId=player.currentMediaItem?.mediaId
                StreamWork.foregroundLoading=player.currentMediaItem?.mediaId?.isRemoteId()==true && player.isLoading
                StreamWork.buffering=player.currentMediaItem?.mediaId?.isRemoteId()==true && player.playbackState==Player.STATE_BUFFERING && player.playWhenReady
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) updateShuffleCycle()
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED,Player.EVENT_IS_LOADING_CHANGED,Player.EVENT_PLAY_WHEN_READY_CHANGED,Player.EVENT_PLAYBACK_STATE_CHANGED,Player.EVENT_POSITION_DISCONTINUITY)) warmUpcoming()
                StreamWork.buffering=player.currentMediaItem?.mediaId?.isRemoteId()==true && player.playbackState==Player.STATE_BUFFERING && player.playWhenReady
                if(player.playbackState==Player.STATE_BUFFERING && bufferingAt==0L)bufferingAt=android.os.SystemClock.elapsedRealtime()
                if(player.playbackState!=Player.STATE_BUFFERING && bufferingAt>0) {StreamTimings.record("buffering",android.os.SystemClock.elapsedRealtime()-bufferingAt);bufferingAt=0}
                if(!player.playWhenReady) {radioJob?.cancel();radioJob=null;attemptedTail=null}
                listenTick()
                maybeRadio()
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) persist()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listenTick()
                snapshotJob?.cancel()
                if (isPlaying) {
                    startupAt=0
                    consecutiveErrors = 0
                    persist()
                    snapshotJob = scope.launch {
                        var ticks=0
                        while (isActive) {delay(1000);listenTick();warmUpcoming();maybeRadio();if(++ticks%5==0)persist()}
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val failed = player.currentMediaItem?.mediaId
                if(failed?.isRemoteId()==true) {streamIssue=if(error.errorCode in setOf(PlaybackException.ERROR_CODE_DECODING_FAILED,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))StreamIssue.FORMAT else StreamIssue.from(error.cause);issueMediaId=failed;publishExtras()}
                if(failed?.isRemoteId()==true) {failedRadio.add(failed);if(failedRadio.size>200)failedRadio.remove(failedRadio.first())}
                if (failed != null && !failed.isRemoteId()) scope.launch(Dispatchers.IO) { app.repository.playbackFailure(failed,error.errorCode) }
                consecutiveErrors++
                if (player.hasNextMediaItem() && consecutiveErrors < player.mediaItemCount) { player.seekToNextMediaItem(); player.prepare() }
                else player.pause()
            }
        })
        scope.launch { app.preferences.flow.collectLatest { updated ->
            val was=settings;settings=updated
            if(!updated.autoplay && was.autoplay) {
                radioJob?.cancel();radioJob=null
                for(i in player.mediaItemCount-1 downTo player.currentMediaItemIndex+1) {
                    val extras=player.getMediaItemAt(i).mediaMetadata.extras
                    if(extras?.getBoolean("yono.generated")==true && !extras.getBoolean("yono.radio"))player.removeMediaItem(i)
                }
            }
            if(updated.autoplay!=was.autoplay)attemptedTail=null
            maybeRadio()
        } }
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
                if(id.isRemoteId())app.streams.lookup(id)?.let {remoteMediaItem(it)}
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
    private fun maybeRadio() {
        if(!::player.isInitialized)return
        val current=player.currentMediaItem ?: return
        val explicit=current.mediaMetadata.extras?.getBoolean("yono.radio")==true
        val index=player.currentMediaItemIndex
        val remaining=player.mediaItemCount-index-1
        if(!RadioPolicy.eligible(current.mediaId.isRemoteId(),player.playWhenReady,player.repeatMode,remaining,settings.autoplay || explicit))return
        if((index until player.mediaItemCount).any {!player.getMediaItemAt(it).mediaId.isRemoteId()})return
        if(radioJob?.isActive==true || player.mediaItemCount>=500 || failedRadio.size>=20)return
        val tail=player.getMediaItemAt(player.mediaItemCount-1)
        val token=tail.mediaMetadata.extras?.getString("yono.queue.key") ?: return
        if(attemptedTail==token)return
        attemptedTail=token
        val originalKeys=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaMetadata.extras?.getString("yono.queue.key")}
        radioJob=scope.launch {
            try {
                val seed=app.streams.lookup(tail.mediaId)?.let {app.discovery.audio(it)}?.videoId ?: return@launch
                val candidates=app.catalog.related(seed)
                ensureActive()
                val keys=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaMetadata.extras?.getString("yono.queue.key")}
                if(keys!=originalKeys || !player.playWhenReady || player.repeatMode!=Player.REPEAT_MODE_OFF || (!settings.autoplay && !explicit)) {attemptedTail=null;return@launch}
                val songs=RadioPolicy.select(candidates,(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}.toSet(),failedRadio).take(500-player.mediaItemCount)
                if(songs.isEmpty()) {radioNotice="Radio belum menemukan lagu berikutnya.";publishExtras();return@launch}
                withContext(Dispatchers.IO) {songs.forEach {app.streams.register(it)}}
                ensureActive()
                // Registration may suspend: recheck the queue and pause state before committing.
                if((0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaMetadata.extras?.getString("yono.queue.key")}!=originalKeys || !player.playWhenReady || player.repeatMode!=Player.REPEAT_MODE_OFF || (!settings.autoplay && !explicit))return@launch
                val ended=player.playbackState==Player.STATE_ENDED
                val nextIndex=player.mediaItemCount
                player.addMediaItems(songs.map {song->val item=remoteMediaItem(song);item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(Bundle(item.mediaMetadata.extras).apply {putBoolean("yono.generated",true);putBoolean("yono.radio",explicit)}).build()).build()})
                radioNotice="";publishExtras()
                if(ended) {player.seekTo(nextIndex,0);player.prepare();player.play()}
            } catch(e: CancellationException) {throw e}
            catch(_: Exception) {radioNotice="Radio belum tersedia. Antrean yang ada tetap bisa diputar.";publishExtras()}
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
        snapshotJob?.cancel(); sleepJob?.cancel(); warmJob?.cancel(); radioJob?.cancel(); StreamWork.buffering=false;StreamWork.foregroundLoading=false;StreamWork.currentMediaId=null; app.sleepEnd.value = 0
        metadataCoordinator?.close();performance?.close();session?.release(); player.release(); scope.cancel()
        super.onDestroy()
    }
}
