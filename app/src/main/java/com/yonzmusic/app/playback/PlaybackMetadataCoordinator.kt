@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.yonzmusic.app.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.yonzmusic.app.YonzApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Main-thread queue updates preserve the audio source, position and all queue controls. */
internal class PlaybackMetadataCoordinator(private val app:YonzApp,private val player:ExoPlayer,private val scope:CoroutineScope) {
    private var generation=0L
    private var activeKey=""
    private var artworkJob:Job?=null
    private var enrichmentJob:Job?=null
    private val images=object:android.util.LruCache<String,ByteArray>(2*1024*1024){override fun sizeOf(key:String,value:ByteArray)=value.size}
    private val listener=object:Player.Listener {
        override fun onEvents(p:Player,events:Player.Events) {
            if(events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION,Player.EVENT_TIMELINE_CHANGED))reconcile()
        }
    }
    private val observer:Job
    init {player.addListener(listener);observer=scope.launch {app.metadata.state.collectLatest {reconcile()}}}
    private fun reconcile() {
        for(i in 0 until player.mediaItemCount) {
            val old=player.getMediaItemAt(i);val seed=remoteSong(old) ?: continue
            val song=app.metadata.current(seed)
            val imageKey=com.yonzmusic.app.metadata.ArtworkRepository.key(song.id,song.artwork)
            val image=images.get(imageKey) ?: old.mediaMetadata.artworkData.takeIf {seed.artwork==song.artwork}
            val metadata=remoteMetadata(song,old.mediaMetadata.extras,image)
            // Identical localConfiguration lets ProgressiveMediaSource update metadata without reset.
            if(metadata!=old.mediaMetadata)player.replaceMediaItem(i,old.buildUpon().setMediaMetadata(metadata).build())
        }
        val current=player.currentMediaItem;val seed=current?.let(::remoteSong)
        val song=seed?.let {app.metadata.current(it)}
        val key=if(song==null)"" else "${song.id}:${current.mediaMetadata.extras?.getString("yono.queue.key")}:${song.artwork}"
        if(key==activeKey)return
        activeKey=key;generation++;val token=generation
        artworkJob?.cancel();enrichmentJob?.cancel()
        if(song==null)return
        // Test transports stay isolated unless an explicit artwork fixture is supplied.
        if(app.streamTestDataSource!=null && app.artwork.fetch==null)return
        val queueKey=current.mediaMetadata.extras?.getString("yono.queue.key")
        fun valid()=generation==token && player.currentMediaItem?.mediaId==song.id && player.currentMediaItem?.mediaMetadata?.extras?.getString("yono.queue.key")==queueKey && app.metadata.current(song).artwork==song.artwork
        artworkJob=scope.launch {
            val image=app.artwork.resolve(song.id,song.artwork,foreground=true) ?: return@launch
            if(!valid())return@launch
            images.put(com.yonzmusic.app.metadata.ArtworkRepository.key(song.id,song.artwork),image.bytes)
            app.metadata.publish(app.metadata.current(song).copy(artworkUri=image.uri))
            reconcile()
        }
        if(app.streamTestDataSource==null && song.catalogJson.isBlank() && song.artistVerified)enrichmentJob=scope.launch {
            try {
                val enriched=app.discovery.enrich(song).copy(metadataUpdatedAt=System.currentTimeMillis())
                if(valid()){val value=app.metadata.publish(enriched);app.metadata.persist(value)}
            }catch(e:CancellationException){throw e}catch(_:Exception){}
        }
    }
    fun close(){generation++;artworkJob?.cancel();enrichmentJob?.cancel();observer.cancel();player.removeListener(listener);images.evictAll()}
}
