package com.yonzmusic.engine

import android.net.Uri
import androidx.room.withTransaction
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
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
        val activeStatuses=setOf("queued","running","cancelling")
        // History is bounded, but every active request must remain visible to cancel/dedup/recovery.
        queuedDownloads.value=rows.filter {it.optString("status") in activeStatuses}+
            rows.filter {it.optString("status") !in activeStatuses}.takeLast(200).asReversed()
    }
    private suspend fun updateQueued(id:String,expectedWork:String?=null,edit:(JSONObject)->Unit) {
        app.database.withTransaction {val j=dao.state("stream-download:$id")?.value?.let(::JSONObject) ?: return@withTransaction;if(expectedWork!=null && j.optString("work_id")!=expectedWork)return@withTransaction;edit(j);dao.state(AppState("stream-download:$id",j.toString()))};refreshQueued()
    }
    fun enqueueSelected(song:com.yonzmusic.app.search.OnlineSong,destination:String,playlist:Boolean,onResult:((String)->Unit)?=null) {
        app.persistenceScope.launch {
            queueReady.await();queueMutex.lock()
            try {
                require(com.yonzmusic.app.playback.StreamRepository.valid(song.videoId) && song.page=="https://www.youtube.com/watch?v=${song.videoId}")
                require(app.contentResolver.persistedUriPermissions.any {it.uri.toString()==destination && it.isReadPermission && it.isWritePermission}) {"Hubungkan ulang folder tujuan unduhan."}
                refreshQueued()
                if(queuedDownloads.value.any {it.optString("source_id")==song.videoId && it.optString("destination")==destination && it.optString("status") in setOf("queued","running","cancelling")}) {message.value="Lagu ini sudah berada di antrean.";onResult?.invoke(message.value);return@launch}
                val id=UUID.randomUUID().toString()
                val row=JSONObject().put("id",id).put("source_id",song.videoId).put("song",com.yonzmusic.app.playback.StreamRepository.encode(song)).put("destination",destination).put("playlist",playlist).put("status","queued").put("created",System.currentTimeMillis()).put("work_id",id)
                app.database.withTransaction {
                    dao.state(AppState("stream-download:$id",row.toString()))
                    dao.state(AppState("engine-request:$id",JSONObject().put("action","selected_download").put("payload",JSONObject().put("video_id",song.videoId)).put("destination",destination).toString()))
                }
                scheduleSelected(id,id);refreshQueued();message.value="Ditambahkan ke daftar unduhan.";onResult?.invoke(message.value)
            }catch(e:Exception){message.value=e.message?.take(200) ?: "Antrean belum dapat disimpan.";onResult?.invoke(message.value)}finally{queueMutex.unlock()}
        }
    }
    internal var selectedScheduler:((String,String)->Unit)?=null // Dependency injection for instrumentation; no IPC/config access.
    private fun scheduleSelected(id:String,workId:String) {
        selectedScheduler?.let {it(id,workId);return}
        androidx.work.WorkManager.getInstance(app).enqueueUniqueWork("yono-engine",androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<EngineWorker>().setId(UUID.fromString(workId)).setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .setInputData(androidx.work.workDataOf("request" to id)).build())
    }
    internal suspend fun recoverSelected() = queueMutex.withLock {
        refreshQueued()
        for(row in queuedDownloads.value.filter {it.optString("status") in setOf("queued","running","cancelling")}) {
            val workId=row.optString("work_id",row.getString("id"));val id=row.getString("id")
            val info=try {androidx.work.WorkManager.getInstance(app).getWorkInfoById(UUID.fromString(workId)).get(5,java.util.concurrent.TimeUnit.SECONDS)}catch(_:Exception){continue}
            if(info==null)scheduleSelected(id,workId)
            else if(info.state.isFinished && !reconcilePublished(row,workId))updateQueued(id,workId){it.put("status",if(it.optBoolean("cancel_requested"))"cancelled"else "failed").put("error","Pekerjaan terhenti. Coba ulang; audio yang sudah tersimpan akan diperiksa dahulu.")}
        }
    }
    private val cancelledRequests=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun cancelQueued(id:String) {app.persistenceScope.launch {queueReady.await();queueMutex.withLock {
        updateQueued(id){if(it.optString("status") in setOf("queued","running","cancelling")) {
            cancelledRequests.add(id);it.put("cancel_requested",true).put("status",if(activeRequest.value==id)"cancelling"else "cancelled")
        }}
        if(id in cancelledRequests)boundTask?.takeIf {it.request==id}?.task?.cancel()
    }}}
    fun retryQueued(id:String) {app.persistenceScope.launch {queueReady.await();queueMutex.withLock {
        if(activeRequest.value==id){message.value="Pekerjaan sedang berhenti. Coba lagi sebentar.";return@withLock}
        refreshQueued();val row=queuedDownloads.value.firstOrNull {it.optString("id")==id} ?: return@withLock
        if(row.optString("status") !in setOf("failed","cancelled"))return@withLock
        if(queuedDownloads.value.any {it.optString("id")!=id && it.optString("source_id")==row.optString("source_id") && it.optString("destination")==row.optString("destination") && it.optString("status") in setOf("queued","running","cancelling")}) {message.value="Rekaman ini sudah memiliki unduhan aktif.";return@withLock}
        val work=UUID.randomUUID().toString();cancelledRequests.remove(id)
        updateQueued(id){it.put("status","queued").put("cancel_requested",false).put("error","").put("error_code","").put("work_id",work)};scheduleSelected(id,work)
    }}}
    internal suspend fun failQueued(id:String,message:String,workId:String?=null){updateQueued(id,workId){if(!it.optBoolean("cancel_requested"))it.put("status","failed").put("error",message.take(200))}}
    private suspend fun verifiedReceipt(plan:JSONObject,source:String):JSONObject? {
        val receipt=SelectedDownloadResult.receipt(plan,source) ?: return null
        if(Uri.parse(receipt.optString("uri")).scheme!="content")return null
        val expected=receipt.optJSONObject("audio")?.optString("sha256").orEmpty().ifBlank {
            dao.allTracks().firstOrNull {it.uri==receipt.optString("uri") && it.sourceIdentity=="youtube:$source"}?.sha256.orEmpty()
        }
        if(!expected.matches(Regex("[a-f0-9]{64}")))return null
        val readable=runCatching {
            val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
            app.contentResolver.openInputStream(Uri.parse(receipt.getString("uri")))!!.use {input->
                val b=ByteArray(262144);while(true){val n=input.read(b);if(n<0)break;bytes+=n;require(bytes<=Repository.MAX_AUDIO_BYTES);digest.update(b,0,n)}
            };bytes>0 && digest.digest().joinToString(""){"%02x".format(it)}==expected
        }.getOrDefault(false)
        return receipt.takeIf {readable}
    }
    /** Validate exact bytes before recovering an already-finished publication; never scan folders. */
    private suspend fun reconcilePublished(row:JSONObject,workId:String?):Boolean {
        val planId=row.optString("plan_id");if(!planId.matches(Regex("[a-f0-9]{32}")))return false
        val plan=runCatching {val f=File(app.filesDir,"yono-engine/plans/$planId.json");require(f.isFile && f.length() in 1..16*1024*1024);JSONObject(f.readText())}.getOrNull() ?: return false
        val receipt=verifiedReceipt(plan,row.optString("source_id")) ?: return false
        importResult(plan)
        updateQueued(row.getString("id"),workId){it.put("status","complete").put("uri",receipt.getString("uri")).put("error","Audio yang sudah tersimpan berhasil diperiksa kembali.").put("error_code","")}
        return true
    }

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
    @Volatile private var executingRequest:String?=null
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
    private data class BoundTask(val request:String,val task:YonoEngineClient.Task)
    @Volatile private var boundTask:BoundTask?=null
    private val task get()=boundTask?.task
    @Volatile private var cancelled = false
    private val dao get() = app.repository.dao
    fun cancel() {executingRequest?.let {cancelledRequests.add(it)};val running=boundTask;running?.let {cancelledRequests.add(it.request);it.task.cancel()};app.persistenceScope.launch {queueReady.await();queueMutex.withLock {
        refreshQueued();queuedDownloads.value.filter {it.optString("status") in setOf("queued","running","cancelling")}.forEach {row->val id=row.getString("id");cancelledRequests.add(id);updateQueued(id){if(it.optString("status") in setOf("queued","running","cancelling"))it.put("cancel_requested",true).put("status",if(activeRequest.value==id)"cancelling"else "cancelled")}}
    }};event.value=JSONObject().put("stage","cancelling") }
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
    internal fun finishExecution(id:String) {
        if(executingRequest==id) {executing=false;executingRequest=null;activeRequest.value="";currentAction.value="";activeEntry.value="";activePlan.value="";cancelledRequests.remove(id)}
        busy.value=workActive || executing || enqueueing
    }
    suspend fun execute(requestId: String,workId:String?=null) = withContext(Dispatchers.IO) {
        busy.value = true;executing=true;executingRequest=requestId
        try {
        val operationContext = currentCoroutineContext()
        val request = JSONObject(dao.state("engine-request:$requestId")!!.value)
        currentAction.value=request.getString("action")
        val selectedJob=request.optString("action")=="selected_download"
        fun stopped()=requestId in cancelledRequests || (!selectedJob && cancelled)
        suspend fun job(edit:(JSONObject)->Unit)=updateQueued(requestId,workId,edit)
        if(selectedJob) {
            var row:JSONObject?=null;var superseded=false
            app.database.withTransaction {
                row=dao.state("stream-download:$requestId")?.value?.let(::JSONObject)
                superseded=row==null || (workId!=null && row!!.optString("work_id")!=workId)
                if(!superseded && !row!!.optBoolean("cancel_requested") && row!!.optString("status") in setOf("queued","running","cancelling")) {
                    activeRequest.value=requestId;row!!.put("status","running").put("error","").put("error_code","")
                    dao.state(AppState("stream-download:$requestId",row.toString()))
                }
            }
            if(superseded){executing=false;busy.value=workActive;currentAction.value="";return@withContext}
            if(row!!.optString("status")=="complete") {
                if(!reconcilePublished(row!!,workId))job {it.put("status","failed").put("error","Hasil unduhan berubah atau tidak tersedia. Hubungkan ulang lokasinya sebelum mencoba lagi.")}
                return@withContext
            }
            if(row!!.optBoolean("cancel_requested")) {
                if(!reconcilePublished(row!!,workId))job {it.put("status","cancelled")}
                executing=false;busy.value=workActive;currentAction.value="";return@withContext
            }
            if(row!!.optString("status") !in setOf("queued","running","cancelling"))return@withContext
            cancelled=false;pendingCancels.clear();refreshQueued()
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
        if(destination.isBlank()) { if(selectedJob)failQueued(requestId,"Pilih tujuan unduhan dahulu.",workId);executing=false;busy.value=false;message.value="Pilih tujuan unduhan dahulu.";return@withContext }
        val client = YonoEngineClient(app, SafDocumentStore(app, Uri.parse(destination)), YonoEngineClient.RuntimePaths(
            quickJs = File(app.applicationInfo.nativeLibraryDir, "libqjs.so")))
        suspend fun call(action: String, payload: JSONObject): JSONObject {
            if (!operationContext.isActive || stopped()) error("Dibatalkan")
            val current = client.begin(action, payload, if (action == "acquire") 7200 else 900); boundTask=BoundTask(requestId,current)
            pendingCancels.forEach(current::cancelEntry)
            val observer = launch { current.events.collect { update ->
                event.value = update
                if(update.optString("stage") in setOf("track_started","matching") && update.optString("entry_id").isNotBlank()) { activeEntry.value=update.optString("entry_id");activePlan.value=update.optString("plan_id") }
                if(update.optString("stage")=="item_result")activeEntry.value=""
                if(update.optString("stage")=="playlist_ready")activePlan.value=update.optString("plan_id")
                if(update.optString("stage")=="item_result" || update.optString("stage")=="playlist_ready") refreshHistory()
            } }
            val result = try { current.result.await() } catch (e: CancellationException) { current.cancel(); withContext(NonCancellable) { current.result.await() }; throw e } finally { observer.cancel(); boundTask=null }
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
                    if (!operationContext.isActive || stopped()) error("Dibatalkan")
                    val hash = MessageDigest.getInstance("SHA-256")
                    val readable = runCatching { app.contentResolver.openInputStream(Uri.parse(track.uri))?.use { input ->
                        val buffer = ByteArray(262144)
                        while (true) { if (!operationContext.isActive || stopped()) error("Dibatalkan"); val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
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
                job {it.put("plan_id",prepared.getString("id"))}
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
                if (!selectedJob && dao.state("engine-consent:${result.getString("id")}")?.value == "1") importResult(result)
            }
            if(selectedJob) {
                val receipt=SelectedDownloadResult.receipt(result,payload.getString("video_id"))
                if(receipt==null) {val failure=SelectedDownloadResult.failure(result,payload.getString("video_id"));job {it.put("error_code",failure.code)};error(failure.message)}
                check(verifiedReceipt(result,payload.getString("video_id"))!=null){"Hasil belum dapat diverifikasi. Periksa izin folder; berkas mungkin berubah atau tidak lengkap."}
                importResult(result)
                job {it.put("status","complete").put("uri",receipt!!.getString("uri")).put("error","")}
            }
            message.value = "Selesai"
        } catch (e: CancellationException) {if(selectedJob)job {if(!it.optBoolean("cancel_requested"))it.put("status","queued")};throw e
        } catch (e: Exception) {
            message.value = if (stopped()) "Dibatalkan" else e.message?.take(240) ?: "Operasi gagal"
            if(selectedJob)job {it.put("status",if(it.optBoolean("cancel_requested"))"cancelled"else "failed").put("error",message.value)}
            // A process can die after SAF commit: reload the durable plan on the next review.
            runCatching {
                val expected=if(selectedJob)dao.state("stream-download:$requestId")?.value?.let {JSONObject(it).optString("plan_id")}else null
                if(selectedJob && expected.isNullOrBlank())return@runCatching
                val recovered = client.begin(if(selectedJob)"get_plan"else "get_last",if(selectedJob)JSONObject().put("plan_id",expected)else JSONObject()).result.await()
                if (recovered.optBoolean("ok")) {
                    val result=recovered.getJSONObject("result");plan.value=result
                    if(selectedJob)dao.state("stream-download:$requestId")?.value?.let {reconcilePublished(JSONObject(it),workId)}
                    else if(dao.state("engine-consent:${result.getString("id")}")?.value=="1")importResult(result)
                }
            }
        } finally { client.close();refreshHistory();executing=false;busy.value = workActive; cancelled = false;pendingCancels.clear();activeEntry.value="";activePlan.value="";currentAction.value="";activeRequest.value="";cancelledRequests.remove(requestId);refreshQueued() }
        }finally {finishExecution(requestId)}
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
