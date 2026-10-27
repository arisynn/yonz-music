package com.yonzmusic.app.search

import android.content.Context
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.yonzmusic.app.playback.PlaybackConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

data class PreviewState(val id: String?=null,val loading: Boolean=false,val playing: Boolean=false,val error: String="",val ended: Boolean=false)

/** Foreground, user-initiated previews. Never touches local MediaSession queue/Room/history. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OnlinePreview(private val context: Context,private val local: PlaybackConnection,private val scope: CoroutineScope) {
    val state=MutableStateFlow(PreviewState())
    private var player: ExoPlayer?=null
    private var startJob: Job?=null
    private var timeout: Job?=null
    private fun engine(): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory {PreviewDataSource()})
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2500,10000,500,1000).build())
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true)
        .setHandleAudioBecomingNoisy(true).build().also { p ->
            player=p
            p.addListener(object: Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if(state.value.id==null)return
                    val ended=player.playbackState==Player.STATE_ENDED
                    state.value=state.value.copy(loading=player.playbackState==Player.STATE_BUFFERING,playing=player.isPlaying,ended=ended)
                    if(player.playbackState==Player.STATE_READY || ended)timeout?.cancel()
                    if(ended)player.pause()
                }
                override fun onPlaybackSuppressionReasonChanged(reason: Int) {if(reason!=Player.PLAYBACK_SUPPRESSION_REASON_NONE)p.pause()}
                override fun onPlayerError(error: PlaybackException) {
                    timeout?.cancel();state.value=state.value.copy(loading=false,playing=false,error="Cuplikan belum dapat diputar. Coba lagi atau buka sumber aslinya.")
                }
            })
        }
    fun play(song: OnlineSong) {
        require(song.provider!=SearchProvider.YOUTUBE && SearchContract.https(song.preview,SearchContract.audioHosts))
        stop();local.pause();state.value=PreviewState(song.id,loading=true)
        startJob=scope.launch {
            try {
                withTimeout(3000) {local.state.first {it.ready && !it.playing}}
                ensureActive()
                val p=engine()
                var item=MediaItem.Builder().setMediaId(song.id).setUri(song.preview)
                // Commons allows full audio, but this UI deliberately auditions a short excerpt.
                if(song.provider==SearchProvider.COMMONS)item=item.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(30000).build())
                p.setMediaItem(item.build());p.prepare();p.play()
                timeout=scope.launch {delay(20000);if(state.value.loading){p.stop();state.value=PreviewState(song.id,error="Sumber terlalu lama merespons. Coba lagi.")}}
            }catch(e: CancellationException) {
                if(e is TimeoutCancellationException)state.value=PreviewState(song.id,error="Player lokal belum terhubung. Coba lagi sebentar.") else throw e
            }catch(_: Exception) {
                state.value=PreviewState(song.id,error="Cuplikan belum dapat diputar. Coba lagi atau buka sumber aslinya.")
            }
        }
    }
    fun pause() {player?.pause()}
    fun stop() {startJob?.cancel();startJob=null;timeout?.cancel();timeout=null;state.value=PreviewState();player?.stop();player?.clearMediaItems()}
    fun position(): Long=player?.currentPosition?.coerceAtLeast(0) ?: 0
    fun duration(): Long=player?.duration?.takeIf {it>0} ?: 0
    fun close() {stop();player?.release();player=null}
}
