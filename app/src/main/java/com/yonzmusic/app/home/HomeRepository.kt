package com.yonzmusic.app.home

import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.data.*
import com.yonzmusic.app.search.*
import com.yonzmusic.app.playback.StreamRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

/** Only metadata is fetched, on explicit open/refresh/radio actions; composition never owns network jobs. */
class HomeRepository(private val app:YonzApp,private val scope:CoroutineScope,private val playbackBusy:()->Boolean={false}) {
    private data class Library(val tracks:List<Track>,val favorites:Set<String>,val history:List<History>,val listens:List<ListenEvent>)
    private data class Discovery(val songs:List<OnlineSong> = emptyList(),val seed:String="",val updated:Long=0)
    private data class RequestState(val loading:Boolean=false,val message:String="")
    private fun connected():Boolean=runCatching {
        val manager=app.getSystemService(android.net.ConnectivityManager::class.java)
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
    }.getOrDefault(false)
    private val requestState=MutableStateFlow(RequestState())
    private val localTags=HomeLocalTags(app,scope)
    private val extra=MutableStateFlow(Discovery())
    private val clock=flow {while(true){emit(System.currentTimeMillis());delay(15*60000)}}
    private val dao=app.repository.dao
    private val library=combine(dao.tracks(),dao.favorites(),dao.history(),dao.homeListens(System.currentTimeMillis()-30L*86400000)) {t,f,h,l->Library(t,f.map {it.trackId}.toSet(),h,l)}
    private val catalogue=combine(dao.homeStreams().distinctUntilChanged(),app.forYou.state,app.onlineCollections.flow,app.streams.metadata) {stored,personal,collections,current->
        val rows=stored.mapNotNull {runCatching {StreamRepository.decode(JSONObject(it.value))}.getOrNull()}
        (current.values+personal.recent+personal.songs+collections.flatMap {it.songs}+rows).distinctBy {it.id}.take(1500)
    }.flowOn(Dispatchers.Default)
    private val personal=combine(app.forYou.profileState,extra,clock) {p,e,n->Triple(p,e,n)}
    private val composed=combine(library,catalogue,personal,localTags.state) {lib,songs,context,genres->
        val (profile,discovery,now)=context
        HomePolicy.build(lib.tracks,(songs+discovery.songs).distinctBy {it.id},lib.favorites,lib.history,lib.listens,profile,
            (app.forYou.state.value.songs+discovery.songs).distinctBy {it.id},now,genres).copy(updatedAt=maxOf(discovery.updated,app.forYou.state.value.updatedAt))
    }.flowOn(Dispatchers.Default)
    val state=combine(composed,requestState) {value,request->value.copy(refreshing=request.loading,message=request.message)}
        .stateIn(scope,SharingStarted.WhileSubscribed(5000),HomeState())
    private var job:Job?=null
    private var attemptedSeed=""
    private var attemptedAt=0L
    private val restored=scope.async(Dispatchers.IO) {
        runCatching {dao.state("home-discovery:v1")?.value?.let {raw->
            if(raw.length>512*1024)return@let
            val root=JSONObject(raw);val rows=root.optJSONArray("songs") ?: JSONArray()
            extra.value=Discovery((0 until minOf(rows.length(),60)).mapNotNull {i->runCatching {StreamRepository.decode(rows.getJSONObject(i))}.getOrNull()},root.optString("seed"),root.optLong("updated"))
        }}
    }
    fun open(force:Boolean=false) {
        if(job?.isActive==true)return
        job=scope.launch {
            restored.await()
            val snapshot=state.first {it.ready}
            localTags.inspect((snapshot.quick+snapshot.favorites+snapshot.made+snapshot.top).mapNotNull {it.local})
            val seed=app.forYou.state.value.seed ?: snapshot.favorites.firstNotNullOfOrNull {it.online}
                ?: snapshot.top.firstNotNullOfOrNull {it.online}
            val localSeed=(snapshot.favorites+snapshot.top+snapshot.recent).firstOrNull {
                it.artist.isNotBlank() && !it.artist.startsWith("Unknown",true) && !it.artist.startsWith("Kanal:",true)
            }
            if(seed==null && (localSeed==null || (!force && snapshot.discover.size>=4))) {
                if(force)requestState.value=RequestState(message="Cari dan dengarkan lagu, atau tambahkan favorit untuk memulai rekomendasi.")
                return@launch
            }
            if(!connected()){requestState.value=RequestState(message="Sedang offline. Pilihan tersimpan tetap tersedia; gunakan lagu offline untuk memutar.");return@launch}
            val seedKey=seed?.id ?: "local:${localSeed!!.primaryArtist}"
            val now=System.currentTimeMillis()
            if(!force && ((extra.value.seed==seedKey && now-extra.value.updated in 0 until 6*3600000L) ||
                (attemptedSeed==seedKey && now-attemptedAt in 0 until 15*60000L)))return@launch
            // The service already supplied fresh related metadata: use it without another source request.
            val supplied=app.forYou.state.value
            if(!force && seed!=null && supplied.seed?.id==seed.id && supplied.songs.isNotEmpty() && now-supplied.updatedAt in 0 until 6*3600000L)return@launch
            // Let current audio establish its buffer before a Home background request.
            var waits=0
            while(playbackBusy() && waits++<20){delay(500);ensureActive()}
            if(playbackBusy())return@launch
            attemptedSeed=seedKey;attemptedAt=now;requestState.value=RequestState(true)
            try {
                // If a local-only profile needs discovery, match its real artist in existing song search.
                // This is a metadata lookup; the local audio stays local and is never replaced.
                val remoteSeed=seed ?: SearchContract.engine(app.musicCatalog.request(JSONObject().put("operation","search")
                    .put("query",SearchContract.query(localSeed!!.artist))).toString()).songs
                    .firstOrNull {RecommendationEngine.artist(it.credit)==localSeed.primaryArtist}
                if(remoteSeed==null){requestState.value=RequestState(message="Padanan katalog artis belum tersedia. Pilihan lokal tetap bisa diputar.");return@launch}
                val pool=app.recommendations.candidates(remoteSeed,app.forYou.profile,app.forYou.state.value.recent,emptySet(),emptySet()).map {it.song}.take(60)
                ensureActive();extra.value=Discovery(pool,seedKey,System.currentTimeMillis())
                withContext(Dispatchers.IO) {dao.state(AppState("home-discovery:v1",JSONObject().put("seed",seedKey).put("updated",extra.value.updated).put("songs",JSONArray(pool.map(StreamRepository::encode))).toString()))}
                requestState.value=RequestState(message=if(pool.isEmpty())"Pilihan baru belum tersedia. Koleksi tersimpan tetap bisa diputar."else "")
            }catch(e:CancellationException){throw e}
            catch(_:Exception){requestState.value=RequestState(message="Katalog belum terhubung. Pilihan tersimpan tetap tersedia; lagu bertanda unduhan dapat diputar offline.")}
            finally {requestState.value=requestState.value.copy(loading=false)}
        }
    }
    suspend fun radio(seed:HomeTrack,artist:Boolean):List<HomeTrack> {
        val snapshot=state.value
        val known=(snapshot.quick+snapshot.favorites+snapshot.recent+snapshot.made+snapshot.top+snapshot.artists.flatMap {it.tracks}).let(HomePolicy::distinct)
        val online=seed.online
        var candidates=emptyList<OnlineSong>()
        if(online!=null && connected()) {
            candidates=try {app.recommendations.candidates(online,app.forYou.profile,app.forYou.state.value.recent,emptySet(),emptySet()).map {it.song}}
                catch(e:CancellationException){throw e}catch(_:Exception){emptyList()}
        } else if(artist && connected()) {
            // Explicit artist action only, using the existing anonymous song/artist catalogue.
            candidates=try {SearchContract.engine(app.musicCatalog.request(JSONObject().put("operation","search").put("query",seed.artist)).toString()).songs}
                catch(e:CancellationException){throw e}catch(_:Exception){emptyList()}
        }
        val hidden=app.forYou.profile.hidden
        val related=(candidates.map {HomeTrack(online=it)}+known.filter {it.primaryArtist==seed.primaryArtist || it.genres.any {g->g in seed.genres}})
            .filter {it.key!=seed.key && hidden[it.id]?.let {until->until>System.currentTimeMillis()}!=true}
        val pool=if(artist)known.filter {it.primaryArtist==seed.primaryArtist}+related else related
        val selected=HomePolicy.diverse(pool.filter {it.key!=seed.key},29,if(artist)8 else 3)
        if(selected.isEmpty() && online==null)return listOf(seed)
        return HomePolicy.distinct(listOf(seed)+selected).take(30)
    }
}
