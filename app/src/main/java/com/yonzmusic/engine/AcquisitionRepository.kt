package com.yonzmusic.engine

import android.net.Uri
import androidx.room.withTransaction
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import java.io.File
import java.util.UUID
import java.security.MessageDigest

/** App-owned durable operations; Compose observes, never owns Python work. */
class AcquisitionRepository(private val app: YonzApp) {
    val queuedDownloads = MutableStateFlow<List<JSONObject>>(emptyList())
    val activeRequest = MutableStateFlow("")
    private val queueMutex=kotlinx.coroutines.sync.Mutex()
    private val queueReady=CompletableDeferred<Unit>()
    suspend fun refreshQueued() {
        val rows=dao.states("stream-download:%").mapNotNull {runCatching {JSONObject(it.value)}.getOrNull()}.sortedBy {it.optLong("created")}
        queuedDownloads.value=(rows.filter {it.optString("status") in setOf("queued","running","cancelling")}+rows.filter {it.optString("status") !in setOf("queued","running","cancelling")}.takeLast(200)).sortedBy {it.optLong("created")}
    }
    private suspend fun updateQueued(id:String,edit:(JSONObject)->Unit) {
        app.database.withTransaction {val j=dao.state("stream-download:$id")?.value?.let(::JSONObject) ?: return@withTransaction;edit(j);dao.state(AppState("stream-download:$id",j.toString()))};refreshQueued()
    }
    fun enqueueSelected(song:com.yonzmusic.app.search.OnlineSong,destination:String,playlist:Boolean) {
        app.persistenceScope.launch {
            queueReady.await();queueMutex.lock()
            try {
                require(com.yonzmusic.app.playback.StreamRepository.valid(song.videoId) && song.page=="https://www.youtube.com/watch?v=${song.videoId}")
                require(app.contentResolver.persistedUriPermissions.any {it.uri.toString()==destination && it.isReadPermission && it.isWritePermission}) {"Hubungkan ulang folder tujuan unduhan."}
                refreshQueued()
                if(queuedDownloads.value.any {it.optString("source_id")==song.videoId && it.optString("destination")==destination && it.optString("status") in setOf("queued","running","cancelling")}) {message.value="Lagu ini sudah berada di antrean.";return@launch}
                val id=UUID.randomUUID().toString()
                val row=JSONObject().put("id",id).put("source_id",song.videoId).put("song",com.yonzmusic.app.playback.StreamRepository.encode(song)).put("destination",destination).put("playlist",playlist).put("status","queued").put("created",System.currentTimeMillis()).put("work_id",id)
                app.database.withTransaction {
                    dao.state(AppState("stream-download:$id",row.toString()))
                    dao.state(AppState("engine-request:$id",JSONObject().put("action","selected_download").put("payload",JSONObject().put("video_id",song.videoId)).put("destination",destination).toString()))
                }
                scheduleSelected(id,id);refreshQueued();message.value="Ditambahkan ke daftar unduhan."
            }catch(e:Exception){message.value=e.message?.take(200) ?: "Antrean belum dapat disimpan."}finally{queueMutex.unlock()}
        }
    }
    internal var selectedScheduler:((String,String)->Unit)?=null // Dependency injection for instrumentation; no IPC/config access.
    private fun scheduleSelected(id:String,workId:String) {
        selectedScheduler?.let {it(id,workId);return}
        androidx.work.WorkManager.getInstance(app).enqueueUniqueWork("yono-engine",androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<EngineWorker>().setId(UUID.fromString(workId)).setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .setInputData(androidx.work.workDataOf("request" to id)).build())
    }
    internal suspend fun recoverSelected() {
        refreshQueued()
        for(row in queuedDownloads.value.filter {it.optString("status") in setOf("queued","running","cancelling")}) {
            val workId=row.optString("work_id",row.getString("id"))
            val info=try {androidx.work.WorkManager.getInstance(app).getWorkInfoById(UUID.fromString(workId)).get(5,java.util.concurrent.TimeUnit.SECONDS)}catch(_:Exception){continue}
            if(info==null)scheduleSelected(row.getString("id"),workId)
            else if(info.state.isFinished)updateQueued(row.getString("id")){it.put("status","failed").put("error","Pekerjaan terhenti. Coba ulang untuk melanjutkan.")}
        }
    }
    fun cancelQueued(id:String) {app.persistenceScope.launch {updateQueued(id){it.put("cancel_requested",true).put("status",if(activeRequest.value==id)"cancelling"else "cancelled")};if(activeRequest.value==id){cancelled=true;task?.cancel()}}}
    fun retryQueued(id:String) {app.persistenceScope.launch {
        if(activeRequest.value==id){message.value="Pekerjaan sedang berhenti. Coba lagi sebentar.";return@launch}
        refreshQueued();if(queuedDownloads.value.firstOrNull {it.optString("id")==id}?.optString("status") !in setOf("failed","cancelled"))return@launch
        val work=UUID.randomUUID().toString();updateQueued(id){it.put("status","queued").put("cancel_requested",false).put("error","").put("work_id",work)};scheduleSelected(id,work)
    }}
    internal suspend fun failQueued(id:String,message:String){updateQueued(id){if(!it.optBoolean("cancel_requested"))it.put("status","failed").put("error",message.take(200))}}

    val busy = MutableStateFlow(false)
    val event = MutableStateFlow(JSONObject())
    val plan = MutableStateFlow<JSONObject?>(null)
    val message = MutableStateFlow("")
    val activeEntry=MutableStateFlow("")
    val activePlan=MutableStateFlow("")
    val currentAction=MutableStateFlow("")
    val history = MutableStateFlow<List<JSONObject>>(emptyList())
    val reviewDraft = MutableStateFlow<JSONObject?>(null)
    fun saveReview(value: JSONObject) {
        val snapshot=JSONObject(value.toString())
        reviewDraft.value=snapshot
        app.persistenceScope.launch { dao.state(AppState("engine-review:${snapshot.getString("plan_id")}",snapshot.toString())) }
    }
    private val pendingCancels = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var executing=false
    @Volatile private var enqueueing=false
    @Volatile private var workActive=false
    init {
        app.persistenceScope.launch {
            try {recoverSelected()}finally{queueReady.complete(Unit)}
            refreshHistory()
            val last=dao.state("engine-last-plan")?.value
            history.value.firstOrNull { it.optString("id")==last }?.let {
                plan.value=it
                reviewDraft.value=dao.state("engine-review:$last")?.value?.let { value -> runCatching { JSONObject(value) }.getOrNull() }
            }
        }
        app.persistenceScope.launch {
            androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow("yono-engine").collect { infos ->
                workActive=infos.any { !it.state.isFinished };busy.value=workActive || executing || enqueueing
            }
        }
    }
    suspend fun refreshHistory() = withContext(Dispatchers.IO) {
        var bytes=0L
        history.value = File(app.filesDir,"yono-engine/plans").listFiles().orEmpty()
            .filter { it.extension=="json" && it.length()<=16*1024*1024 }.sortedByDescending { it.lastModified() }.take(100)
            .filter { bytes+=it.length();bytes<=24*1024*1024 }
            .mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }
    }
    fun cancelItem(planId: String, entryId: String) {
        if(busy.value) { pendingCancels.add(entryId);task?.cancelEntry(entryId) }
        else submit("cancel_entries",JSONObject().put("plan_id",planId).put("entry_ids",JSONArray(listOf(entryId))))
    }
    fun retryItem(planId: String, row: JSONObject) {
        if(row.has("song")) submit("acquire",JSONObject().put("plan_id",planId).put("entry_ids",JSONArray(listOf(row.getString("id")))).put("format","original"))
        else submit("retry",JSONObject().put("plan_id",planId))
    }
    fun repairTrack(id: String) {
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            refreshHistory()
            val track=dao.track(id) ?: return@launch
            val match=history.value.asSequence().flatMap { p -> val rows=p.optJSONArray("entries") ?: JSONArray();(0 until rows.length()).asSequence().map { p to rows.getJSONObject(it) } }
                .firstOrNull { (_,r) -> r.optString("uri")==track.uri || (track.sha256.isNotBlank() && r.optJSONObject("audio")?.optString("sha256")==track.sha256) }
            if(match==null) { message.value="Sumber sampul lama tidak diketahui. Gunakan Edit metadata untuk memilih gambar sendiri.";return@launch }
            submit("repair_artwork",JSONObject().put("plan_id",match.first.getString("id")).put("entry_id",match.second.getString("id")).put("local_uri",track.uri).put("sha256",track.sha256))
        }
    }
    private var task: YonoEngineClient.Task? = null
    @Volatile private var cancelled = false
    private val dao get() = app.repository.dao
    fun cancel() { app.persistenceScope.launch {refreshQueued();queuedDownloads.value.filter {it.optString("status") in setOf("queued","running","cancelling")}.forEach {row->updateQueued(row.getString("id")){it.put("cancel_requested",true).put("status",if(activeRequest.value==row.getString("id"))"cancelling"else "cancelled")}}};cancelled = true; event.value = JSONObject().put("stage", "cancelling"); task?.cancel() }
    @Synchronized fun submit(action: String, payload: JSONObject = JSONObject()) {
        if (busy.value) return
        busy.value = true; cancelled = false;enqueueing=true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                check(androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork("yono-engine").get(5,java.util.concurrent.TimeUnit.SECONDS).none { !it.state.isFinished }) { "Pekerjaan sebelumnya masih aktif. Buka Daftar unduhan untuk membatalkan atau menunggu." }
                check(dao.nextTransfer() == null) { "Tunggu antrean unduhan sebelumnya selesai." }
                val id = UUID.randomUUID().toString()
                dao.state(AppState("engine-request:$id", JSONObject().put("action", action).put("payload", payload).put("destination", dao.state("download_destination")?.value.orEmpty()).toString()))
                androidx.work.WorkManager.getInstance(app).enqueueUniqueWork("yono-engine", androidx.work.ExistingWorkPolicy.KEEP,
                    androidx.work.OneTimeWorkRequestBuilder<EngineWorker>().setInputData(androidx.work.workDataOf("request" to id)).build())
            } catch (e: Exception) { message.value=e.message.orEmpty() }
            finally { enqueueing=false;busy.value=workActive || executing }
        }
    }
    suspend fun execute(requestId: String) = withContext(Dispatchers.IO) {
        busy.value = true;executing=true
        val operationContext = currentCoroutineContext()
        val request = JSONObject(dao.state("engine-request:$requestId")!!.value)
        currentAction.value=request.getString("action")
        val selectedJob=request.optString("action")=="selected_download"
        if(selectedJob) {
            val row=dao.state("stream-download:$requestId")?.value?.let(::JSONObject)
            if(row==null || row.optBoolean("cancel_requested")) {
                if(row!=null) {
                    val planId=row.optString("plan_id")
                    val recovered=if(planId.matches(Regex("[a-f0-9]{32}")))runCatching {
                        val file=File(app.filesDir,"yono-engine/plans/$planId.json");if(file.length()>16*1024*1024)throw IllegalStateException()
                        JSONObject(file.readText())
                    }.getOrNull()else null
                    val rows=recovered?.optJSONArray("entries") ?: JSONArray()
                    val receipt=(0 until rows.length()).map {rows.getJSONObject(it)}.firstOrNull {it.optJSONObject("song")?.optString("youtube_id")==row.optString("source_id") && it.optString("status") in setOf("downloaded","exists","duplicate") && it.optString("uri").isNotBlank()}
                    if(recovered!=null && receipt!=null && runCatching {app.contentResolver.openFileDescriptor(Uri.parse(receipt.getString("uri")),"r")?.use {true} ?: false}.getOrDefault(false)) {
                        importResult(recovered);updateQueued(requestId){it.put("status","complete").put("uri",receipt.getString("uri")).put("error","Audio sudah tersimpan sebelum pembatalan.")}
                    }else updateQueued(requestId){it.put("status","cancelled")}
                }
                executing=false;busy.value=workActive;return@withContext
            }
            cancelled=false;pendingCancels.clear();activeRequest.value=requestId;updateQueued(requestId){it.put("status","running").put("error","")}
        }
        activePlan.value=request.optJSONObject("payload")?.optString("plan_id").orEmpty()
        val savedDestination=dao.state("engine-destination:${activePlan.value}")?.value
        val recoveredDestination=if(savedDestination==null && activePlan.value.matches(Regex("[a-f0-9]{32}")))runCatching {
            val stored=JSONObject(File(app.filesDir,"yono-engine/plans/${activePlan.value}.json").readText()).getJSONArray("entries")
            (0 until stored.length()).map { stored.getJSONObject(it).optString("uri") }.firstOrNull { it.isNotBlank() }?.let { value ->
                val uri=Uri.parse(value);if(android.provider.DocumentsContract.isTreeUri(uri))android.provider.DocumentsContract.buildTreeDocumentUri(uri.authority,android.provider.DocumentsContract.getTreeDocumentId(uri)).toString() else null
            }
        }.getOrNull() else null
        val destination = savedDestination ?: recoveredDestination ?: request.optString("destination").ifBlank { dao.state("download_destination")?.value.orEmpty() }
        if(destination.isBlank()) { if(selectedJob)failQueued(requestId,"Pilih tujuan unduhan dahulu.");executing=false;busy.value=false;message.value="Pilih tujuan unduhan dahulu.";return@withContext }
        val client = YonoEngineClient(app, SafDocumentStore(app, Uri.parse(destination)), YonoEngineClient.RuntimePaths(
            quickJs = File(app.applicationInfo.nativeLibraryDir, "libqjs.so")), accessToken = {
                val input=request.optJSONObject("payload")?.let { it.optString("content")+it.optString("url") }.orEmpty()
                if(request.optString("action")=="prepare" && Regex("(?:open\\.spotify\\.com|spotify\\.link|spoti\\.fi|spotify:playlist:)",RegexOption.IGNORE_CASE).containsMatchIn(input))app.spotify.accessToken()else ""
            })
        suspend fun call(action: String, payload: JSONObject): JSONObject {
            if (!operationContext.isActive || cancelled) error("Dibatalkan")
            val current = client.begin(action, payload, if (action == "acquire") 7200 else 900); task = current
            pendingCancels.forEach(current::cancelEntry)
            val observer = launch { current.events.collect { update ->
                event.value = update
                if(update.optString("stage") in setOf("track_started","matching") && update.optString("entry_id").isNotBlank()) { activeEntry.value=update.optString("entry_id");activePlan.value=update.optString("plan_id") }
                if(update.optString("stage")=="item_result")activeEntry.value=""
                if(update.optString("stage")=="playlist_ready")activePlan.value=update.optString("plan_id")
                if(update.optString("stage")=="item_result" || update.optString("stage")=="playlist_ready") refreshHistory()
            } }
            val result = try { current.result.await() } catch (e: CancellationException) { current.cancel(); withContext(NonCancellable) { current.result.await() }; throw e } finally { observer.cancel(); task = null }
            if (!result.optBoolean("ok")) error(result.optJSONObject("error")?.optString("message") ?: "Operasi gagal")
            return result.getJSONObject("result")
        }
        try {
            val action = request.getString("action"); val payload = request.getJSONObject("payload")
            if(selectedJob)check(dao.nextTransfer()==null){"Selesaikan atau batalkan antrean unduhan versi lama dahulu."}
            if (action in setOf("prepare","acquire","selected_download")) {
                // Explicit review action only: complete selected-source snapshot; never an output-folder scan.
                val rows = JSONArray()
                for (track in dao.allTracks().filter { it.active && it.available }) {
                    if (!operationContext.isActive || cancelled) error("Dibatalkan")
                    val hash = MessageDigest.getInstance("SHA-256")
                    val readable = runCatching { app.contentResolver.openInputStream(Uri.parse(track.uri))?.use { input ->
                        val buffer = ByteArray(262144)
                        while (true) { if (!operationContext.isActive || cancelled) error("Dibatalkan"); val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
                    } != null }.getOrDefault(false)
                    if(!readable)continue // Unavailable references cannot establish a duplicate; never delete their collection rows.
                    val digest=hash.digest().joinToString(""){"%02x".format(it)}
                    if(track.sha256.isNotBlank() && digest!=track.sha256)continue // Changed bytes must not inherit the old recording identity.
                    rows.put(JSONObject().put("uri", track.uri).put("title", track.title).put("artists", JSONArray(track.artist.split(';').map { it.trim() }))
                        .put("duration", track.duration / 1000.0).put("album", track.album).put("relative_path", track.fileName)
                        .put("source_url",track.sourceUrl).put("youtube_id",track.sourceIdentity.removePrefix("youtube:").takeIf { track.sourceIdentity.startsWith("youtube:") && it.matches(Regex("[A-Za-z0-9_-]{11}")) }.orEmpty())
                        .put("sha256", digest))
                    event.value = JSONObject().put("stage", "snapshot").put("completed", rows.length())
                }
                call("snapshot", JSONObject().put("scope", "app-selected-sources").put("rows", rows))
            }
            if (action == "acquire") dao.state(AppState("engine-consent:${payload.getString("plan_id")}", "1"))
            val result = if(selectedJob) {
                val queued=JSONObject(dao.state("stream-download:$requestId")!!.value)
                val planId=queued.optString("plan_id")
                val prepared=if(planId.isNotBlank())call("get_plan",JSONObject().put("plan_id",planId))else call("prepare",JSONObject().put("kind","selected_video").put("video_id",payload.getString("video_id")).put("create_playlist",queued.optBoolean("playlist")))
                updateQueued(requestId){it.put("plan_id",prepared.getString("id"))}
                check(!JSONObject(dao.state("stream-download:$requestId")!!.value).optBoolean("cancel_requested")){"Dibatalkan"}
                val ids=JSONArray();val entries=prepared.getJSONArray("entries")
                for(i in 0 until entries.length()){val e=entries.getJSONObject(i);if(e.has("song"))ids.put(e.getString("id"))}
                check(ids.length()>0){"Sumber belum dapat disiapkan."}
                dao.state(AppState("engine-consent:${prepared.getString("id")}","1"));dao.state(AppState("engine-destination:${prepared.getString("id")}",destination))
                call("acquire",JSONObject().put("plan_id",prepared.getString("id")).put("entry_ids",ids).put("create_playlist",queued.optBoolean("playlist")).put("format","original"))
            }else call(action, payload)
            if (result.has("entries")) {
                plan.value = result; dao.state(AppState("engine-last-plan", result.getString("id")))
                if(reviewDraft.value?.optString("plan_id")!=result.getString("id"))reviewDraft.value=dao.state("engine-review:${result.getString("id")}")?.value?.let { runCatching { JSONObject(it) }.getOrNull() }
                if(action=="prepare" && dao.state("engine-destination:${result.getString("id")}")==null)dao.state(AppState("engine-destination:${result.getString("id")}",destination))
                if (dao.state("engine-consent:${result.getString("id")}")?.value == "1") importResult(result)
            }
            if(selectedJob) {
                val rows=result.getJSONArray("entries");val receipt=(0 until rows.length()).map {rows.getJSONObject(it)}.firstOrNull {it.optString("status") in setOf("downloaded","exists","duplicate") && it.optString("uri").isNotBlank()}
                check(receipt!=null && runCatching {app.contentResolver.openFileDescriptor(Uri.parse(receipt.getString("uri")),"r")?.use {true} ?: false}.getOrDefault(false)){"Audio belum berhasil disimpan. Periksa detail kegagalan dan coba ulang."}
                updateQueued(requestId){it.put("status","complete").put("uri",receipt!!.getString("uri")).put("error","")}
            }
            message.value = "Selesai"
        } catch (e: CancellationException) {if(selectedJob)updateQueued(requestId){if(!it.optBoolean("cancel_requested"))it.put("status","queued")};throw e
        } catch (e: Exception) {
            message.value = if (cancelled) "Dibatalkan" else e.message?.take(240) ?: "Operasi gagal"
            if(selectedJob)updateQueued(requestId){it.put("status",if(it.optBoolean("cancel_requested"))"cancelled"else "failed").put("error",message.value)}
            // A process can die after SAF commit: reload the durable plan on the next review.
            runCatching {
                val expected=if(selectedJob)dao.state("stream-download:$requestId")?.value?.let {JSONObject(it).optString("plan_id")}else null
                if(selectedJob && expected.isNullOrBlank())return@runCatching
                val recovered = client.begin(if(selectedJob)"get_plan"else "get_last",if(selectedJob)JSONObject().put("plan_id",expected)else JSONObject()).result.await()
                if (recovered.optBoolean("ok")) { val result=recovered.getJSONObject("result");plan.value=result;if(dao.state("engine-consent:${result.getString("id")}")?.value=="1")importResult(result)
                    if(selectedJob) {
                        val receipts=result.optJSONArray("entries") ?: JSONArray()
                        val sourceId=dao.state("stream-download:$requestId")?.value?.let {JSONObject(it).optString("source_id")}
                        val saved=(0 until receipts.length()).map {receipts.getJSONObject(it)}.firstOrNull {it.optJSONObject("song")?.optString("youtube_id")==sourceId && it.optString("status") in setOf("downloaded","exists","duplicate") && it.optString("uri").isNotBlank()}
                        if(saved!=null && runCatching {app.contentResolver.openFileDescriptor(Uri.parse(saved.getString("uri")),"r")?.use {true} ?: false}.getOrDefault(false))updateQueued(requestId){it.put("status","complete").put("uri",saved.getString("uri")).put("error","Audio sudah tersimpan sebelum proses berhenti.")}
                    }
                } }
        } finally { client.close();refreshHistory();executing=false;busy.value = workActive; cancelled = false;pendingCancels.clear();activeEntry.value="";activePlan.value="";currentAction.value="";activeRequest.value="";refreshQueued() }
    }
    internal suspend fun importResult(result: JSONObject) {
        val id = result.getString("id")
        val createPlaylist = result.optBoolean("create_playlist", true) // Legacy receipts keep their behavior.
        val accepted = result.optJSONArray("accepted_ids")?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        val knownByUri = dao.allTracks().filter { it.availability!=Availability.ALIAS }.associateBy { it.uri }.toMutableMap()
        val rows = result.getJSONArray("entries"); val ids = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val uri = row.optString("uri"); if (uri.isBlank() || row.optString("status") !in setOf("downloaded","exists","duplicate") || (accepted != null && row.getString("id") !in accepted)) continue
            val known = knownByUri[uri]
            val readable=runCatching { app.contentResolver.openFileDescriptor(Uri.parse(uri),"r")?.use {true} ?: false }.getOrDefault(false)
            if(!readable)continue
            val song = row.getJSONObject("song"); val artists = song.getJSONArray("artists")
            val artist = (0 until artists.length()).joinToString("; ") { artists.getString(it) }
            val audio=row.optJSONObject("audio") ?: JSONObject()
            val folderLabel=if(result.has("folder"))runCatching {
                android.provider.DocumentsContract.getDocumentId(Uri.parse(uri)).substringAfter(':').substringBeforeLast('/',"").ifBlank { "Folder tujuan" }
            }.getOrDefault("Folder tujuan")else result.getString("name")
            val job=DownloadJob(row.getString("id"),id,i,0,folderLabel,"",song.getString("title"),artist,song.optString("album"),
                song.optString("youtube_id").takeIf(String::isNotBlank)?.let { "youtube:$it" } ?: "sha256:${audio.optString("sha256")}",song.optString("source_url"),"User-authorized source","",
                received=audio.optLong("size"),sha256=audio.optString("sha256"),trackId=known?.id.orEmpty())
            val track=if(known==null || (row.optString("status")=="downloaded" && known.metadataOrigin!="user")) app.repository.indexDownloaded(Uri.parse(uri),job,song.optString("metadata_origin","source")) else known
            val art = row.optString("artwork_uri")
            val indexed = if(art.isNotBlank()) track.copy(artUri=art,artRevision=System.currentTimeMillis()).also { dao.upsert(listOf(it)) } else track
            dao.state(AppState("engine-row:${indexed.id}",JSONObject().put("plan_id",id).put("entry_id",row.getString("id")).toString()))
            knownByUri[uri] = indexed
            ids += indexed.id
        }
        if (createPlaylist && ids.isNotEmpty()) app.database.withTransaction {
            val playlist = dao.state("engine-playlist:$id")?.value?.toLongOrNull()?.takeIf { dao.playlistExists(it) }
                ?: app.repository.create(result.getString("name")).also { dao.state(AppState("engine-playlist:$id", it.toString())) }
            app.repository.reconcileDownloadOrder(playlist, ids)
        }
    }
}
