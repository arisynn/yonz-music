package com.yonzmusic.app.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.yonzmusic.app.YonzApp
import kotlinx.coroutines.*

/** Extends the existing queue only. Owns no player, source resolver, audio cache or playback session. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AutoRadio(private val app:YonzApp,private val player:ExoPlayer,private val scope:CoroutineScope,
    private val publish:()->Unit) {
    var available=false;private set
    var enabled=false;private set
    var loading=false;private set
    var error="";private set
    private var defaultEnabled=true
    private var origin=""
    private var session=""
    private var signature=emptyList<String>()
    private var activeKey:String?=null
    private var generation=0L
    private var job:Job?=null
    private var failedSeed=""
    private val seenIds=mutableSetOf<String>()
    private val seenRecordings=mutableSetOf<String>()

    fun defaults(value:Boolean) {
        defaultEnabled=value
        if(origin=="direct" && enabled!=value)setEnabled(value,persistDefault=false) else update()
    }
    private fun upcoming():List<Int> {
        val result=mutableListOf<Int>();val timeline=player.currentTimeline
        var index=player.currentMediaItemIndex
        while(index in 0 until timeline.windowCount && result.size<player.mediaItemCount) {
            index=timeline.getNextWindowIndex(index,Player.REPEAT_MODE_OFF,player.shuffleModeEnabled)
            if(index !in 0 until player.mediaItemCount || index in result)break
            result+=index
        }
        return result
    }
    private fun cancelPending() {
        generation++;job?.cancel();job=null;loading=false
        app.forYou.state.value.seed?.videoId?.let(app.forYou::finish)
    }
    fun setEnabled(value:Boolean,persistDefault:Boolean=true) {
        if(!available)return
        enabled=value;failedSeed="";error="";cancelPending()
        if(persistDefault && origin=="direct")scope.launch {app.preferences.onlineRadio(value)}
        if(!value)upcoming().filter {player.getMediaItemAt(it).generatedRadio()}.sortedDescending().forEach(player::removeMediaItem)
        publish();update()
    }
    fun refreshRecommendations() {app.forYou.refresh();failedSeed="";update()}
    fun update() {
        val items=(0 until player.mediaItemCount).map(player::getMediaItemAt)
        val keys=items.map {it.mediaMetadata.extras?.getString("yono.queue.key") ?: it.mediaId}
        val context=items.firstOrNull {it.mediaMetadata.extras?.getString(RADIO_SESSION)!=null}?.mediaMetadata?.extras
        val nextSession=context?.getString(RADIO_SESSION) ?: keys.firstOrNull().orEmpty()
        val nextOrigin=context?.getString(RADIO_ORIGIN).orEmpty()
        val nextAvailable=items.any {it.mediaId.startsWith("engine:") || StreamRepository.valid(it.mediaMetadata.extras?.getString(RADIO_SEED).orEmpty())}
        var changed=available!=nextAvailable
        available=nextAvailable
        if(nextSession!=session) {
            session=nextSession;origin=nextOrigin;enabled=available && origin=="direct" && defaultEnabled
            seenIds.clear();seenRecordings.clear();failedSeed="";error="";changed=true
        }
        val nextKey=player.currentMediaItem?.mediaMetadata?.extras?.getString("yono.queue.key")
        if(signature!=keys || activeKey!=nextKey) {
            signature=keys;activeKey=nextKey;cancelPending();changed=true
        }
        items.forEach {item->
            seenIds+=item.mediaId
            item.mediaMetadata.extras?.getString(RADIO_SEED)?.let {seenIds+="engine:$it"}
            val metadata=item.mediaMetadata
            if(!metadata.title.isNullOrBlank() && !metadata.artist.isNullOrBlank())
                seenRecordings+=RadioQueuePolicy.recording(metadata.title.toString(),metadata.artist.toString())
            remoteSong(item)?.let {seenRecordings+=RadioQueuePolicy.recording(it.title,it.credit)}
        }
        if(changed)publish()
        if(job?.isActive==true && ((!player.isPlaying && player.playbackState!=Player.STATE_ENDED))) {
            cancelPending();publish()
        }
        val current=player.currentMediaItem ?: return
        val song=remoteSong(current)
        if(player.isPlaying && !current.generatedRadio()) {
            if(song!=null)app.forYou.remember(song)
            else current.mediaMetadata.extras?.getString(RADIO_SEED)?.let {id->app.streams.metadata.value["engine:$id"]?.let(app.forYou::remember)}
        }
        if(!available || !player.isPlaying || job?.isActive==true)return
        val seed=song?.videoId ?: current.mediaMetadata.extras?.getString(RADIO_SEED) ?: return
        val needsHome=app.forYou.needs(seed)
        val ahead=upcoming()
        if((!enabled || player.repeatMode!=Player.REPEAT_MODE_OFF || ahead.size>=2) && !needsHome)return
        if(!StreamRepository.valid(seed) || seed==failedSeed)return
        val serial=generation;val currentKey=current.mediaMetadata.extras?.getString("yono.queue.key")
        val currentSession=session
        loading=true;error="";app.forYou.begin(seed);publish()
        job=scope.launch(start=CoroutineStart.LAZY) {
            try {
                // Metadata requests wait for stable playback; current audio keeps network priority.
                delay(700)
                var attempts=0
                fun stable():Boolean {
                    val remaining=if(player.duration>0)(player.duration-player.currentPosition).coerceAtLeast(0) else 5000L
                    return player.isPlaying && player.totalBufferedDuration>=minOf(5000L,remaining)
                }
                while(!stable() && attempts++<12) {
                    ensureActive()
                    if(!player.playWhenReady || serial!=generation)return@launch
                    delay(500)
                }
                if(!stable())return@launch
                val candidates=withTimeout(22000) {app.musicCatalog.radio(seed)}
                ensureActive()
                if(!valid(serial,currentSession,currentKey))return@launch
                app.forYou.offer(seed,candidates)
                if(!enabled || player.repeatMode!=Player.REPEAT_MODE_OFF || upcoming().size>=2)return@launch
                val slots=4-upcoming().size
                val chosen=RadioQueuePolicy.pick(candidates,seenIds,seenRecordings,slots)
                if(chosen.isEmpty()) {failedSeed=seed;error="Rekomendasi baru belum tersedia.";return@launch}
                withContext(Dispatchers.IO) {chosen.forEach {ensureActive();app.streams.register(it)}}
                ensureActive()
                if(!valid(serial,currentSession,currentKey))return@launch
                val ended=player.playbackState==Player.STATE_ENDED
                player.addMediaItems(chosen.map {radioItem(remoteMediaItem(it),origin.ifBlank {"playlist"},session,it.videoId,true)})
                if(ended && player.playbackState==Player.STATE_ENDED && player.playWhenReady && player.currentMediaItem?.mediaMetadata?.extras?.getString("yono.queue.key")==currentKey && player.hasNextMediaItem()) {player.seekToNextMediaItem();player.prepare()}
            }catch(exception:CancellationException){throw exception}
            catch(_:Exception) {
                if(serial==generation){failedSeed=seed;error="Rekomendasi belum tersedia. Lagu dan antreanmu tetap bisa diputar.";app.forYou.fail(seed,error)}
            }finally {
                if(job==currentCoroutineContext()[Job]) {loading=false;job=null;app.forYou.finish(seed);publish()}
            }
        }
        job?.start()
    }
    private fun valid(serial:Long,expectedSession:String,key:String?)=serial==generation && session==expectedSession &&
        player.playWhenReady && player.playerError==null &&
        player.currentMediaItem?.mediaMetadata?.extras?.getString("yono.queue.key")==key
}
