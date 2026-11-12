package com.yonzmusic.app.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.search.*
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
    private var radioSeed:OnlineSong?=null
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
    fun exclude(id:String) {
        app.forYou.hide(id);cancelPending();failedSeed=""
        upcoming().filter {player.getMediaItemAt(it).generatedRadio() && player.getMediaItemAt(it).mediaId==id}.sortedDescending().forEach(player::removeMediaItem)
        update()
    }
    fun refreshRecommendations() {app.forYou.refresh();failedSeed="";update()}
    fun update() {
        val timeline=player.currentTimeline
        val order=mutableListOf<Int>();var cursor=timeline.getFirstWindowIndex(player.shuffleModeEnabled)
        while(cursor in 0 until player.mediaItemCount && cursor !in order){order+=cursor;cursor=timeline.getNextWindowIndex(cursor,Player.REPEAT_MODE_OFF,player.shuffleModeEnabled)}
        val before=order.take(order.indexOf(player.currentMediaItemIndex).coerceAtLeast(0)).filter {player.getMediaItemAt(it).generatedRadio()}
        if(before.size>20){before.take(before.size-20).sortedDescending().forEach(player::removeMediaItem);return}
        val items=(0 until player.mediaItemCount).map(player::getMediaItemAt)
        val keys=items.map {it.mediaMetadata.extras?.getString("yono.queue.key") ?: it.mediaId}
        val context=items.firstOrNull {it.mediaMetadata.extras?.getString(RADIO_SESSION)!=null}?.mediaMetadata?.extras
        val nextSession=context?.getString(RADIO_SESSION) ?: keys.firstOrNull().orEmpty()
        val nextOrigin=context?.getString(RADIO_ORIGIN).orEmpty()
        val nextAvailable=items.any {it.mediaId.startsWith("engine:") || StreamRepository.valid(it.mediaMetadata.extras?.getString(RADIO_SEED).orEmpty())}
        var changed=available!=nextAvailable
        available=nextAvailable
        if(nextSession!=session) {
            session=nextSession;origin=nextOrigin;enabled=available && (origin in setOf("song-radio","artist-radio") || origin=="direct" && defaultEnabled)
            seenIds.clear();seenRecordings.clear();failedSeed="";radioSeed=null;error="";changed=true
        }
        val nextKey=player.currentMediaItem?.mediaMetadata?.extras?.getString("yono.queue.key")
        if(signature!=keys || activeKey!=nextKey) {
            signature=keys;activeKey=nextKey;cancelPending();changed=true
        }
        seenIds.clear();seenRecordings.clear()
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
            if(song!=null){
                if(radioSeed?.id!=null && radioSeed?.id!=song.id) {
                    radioSeed=song;app.forYou.remember(song);cancelPending()
                    val stale=upcoming().filter {player.getMediaItemAt(it).generatedRadio()}
                    if(stale.isNotEmpty()) {stale.sortedDescending().forEach(player::removeMediaItem);return}
                }
                app.forYou.remember(song);radioSeed=song
            }
            else current.mediaMetadata.extras?.getString(RADIO_SEED)?.let {id->app.streams.metadata.value["engine:$id"]?.let {app.forYou.remember(it);radioSeed=it}}
        }
        if(!available || !player.isPlaying || job?.isActive==true)return
        val selectedSeed=radioSeed ?: song ?: return
        val seed=selectedSeed.videoId
        val needsHome=app.forYou.needs(seed)
        val ahead=upcoming()
        if((!enabled || player.repeatMode!=Player.REPEAT_MODE_OFF || ahead.size>=RecommendationEngine.REFILL_BELOW) && !needsHome)return
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
                val profile=app.forYou.profile
                val recent=app.forYou.state.value.recent
                val candidates=app.recommendations.candidates(selectedSeed,profile,recent,seenIds,seenRecordings)
                ensureActive()
                if(!valid(serial,currentSession,currentKey))return@launch
                val ranked=RecommendationEngine.rank(selectedSeed,candidates,app.forYou.profile,app.forYou.state.value.recent)
                app.forYou.offer(seed,ranked,ranked=true)
                if(!enabled || player.repeatMode!=Player.REPEAT_MODE_OFF || upcoming().size>=RecommendationEngine.REFILL_BELOW)return@launch
                val slots=RecommendationEngine.TARGET-upcoming().size
                val chosen=RecommendationEngine.rank(selectedSeed,candidates,app.forYou.profile,app.forYou.state.value.recent,slots,seenIds,seenRecordings,
                    upcomingArtists=upcoming().map {player.getMediaItemAt(it).mediaMetadata.artist?.toString().orEmpty()},artistRadio=if(origin=="artist-radio")selectedSeed.credit else "")
                if(chosen.isEmpty()) {failedSeed=seed;error="Pilihan relevan baru belum tersedia.";return@launch}
                withContext(Dispatchers.IO) {chosen.forEach {ensureActive();app.streams.register(it)}}
                ensureActive()
                if(!valid(serial,currentSession,currentKey))return@launch
                val ended=player.playbackState==Player.STATE_ENDED
                player.addMediaItems(chosen.filter {app.forYou.profile.hidden[it.id]?.let {until->until>System.currentTimeMillis()}!=true}.map {radioItem(remoteMediaItem(it),origin.ifBlank {"playlist"},session,it.videoId,true)})
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
