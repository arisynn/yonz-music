@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.yonzmusic.app.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** Sink advancement when available, otherwise positive audio-clock progress; isPlaying alone is not a sample. */
internal class StreamPlayerMetrics(private val player:ExoPlayer):Player.Listener,AnalyticsListener {
    private val handler=Handler(Looper.getMainLooper())
    private var previousId:String?=null
    private var previousPosition=0L
    private val poll=object:Runnable {override fun run(){
        val id=player.currentMediaItem?.mediaId;val position=player.currentPosition
        if(player.isPlaying && id==previousId && position>previousPosition+10)StreamPerformance.advancing(id)
        previousId=id;previousPosition=position;handler.postDelayed(this,100)
    }}
    init {player.addListener(this);player.addAnalyticsListener(this);handler.post(poll)}
    override fun onMediaItemTransition(item:MediaItem?,reason:Int){item?.let {StreamPerformance.activate(it.mediaId)};previousId=null;if(player.playbackState==Player.STATE_READY)StreamPerformance.ready(item?.mediaId)}
    override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int){previousId=null}
    override fun onPlaybackStateChanged(state:Int){val id=player.currentMediaItem?.mediaId
        if(state==Player.STATE_READY)StreamPerformance.ready(id)
        else if(state==Player.STATE_BUFFERING && player.playWhenReady)StreamPerformance.buffering(id)
    }
    override fun onAudioPositionAdvancing(eventTime:AnalyticsListener.EventTime,playoutStartSystemTimeMs:Long){StreamPerformance.advancing(player.currentMediaItem?.mediaId)}
    fun close(){handler.removeCallbacks(poll);player.removeListener(this);player.removeAnalyticsListener(this)}
}
