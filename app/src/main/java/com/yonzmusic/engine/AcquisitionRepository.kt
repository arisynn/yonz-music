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
    fun cancel() { cancelled = true; event.value = JSONObject().put("stage", "cancelling"); task?.cancel() }
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
        activePlan.value=request.optJSONObject("payload")?.optString("plan_id").orEmpty()
        val savedDestination=dao.state("engine-destination:${activePlan.value}")?.value
        val recoveredDestination=if(savedDestination==null && activePlan.value.matches(Regex("[a-f0-9]{32}")))runCatching {
            val stored=JSONObject(File(app.filesDir,"yono-engine/plans/${activePlan.value}.json").readText()).getJSONArray("entries")
            (0 until stored.length()).map { stored.getJSONObject(it).optString("uri") }.firstOrNull { it.isNotBlank() }?.let { value ->
                val uri=Uri.parse(value);if(android.provider.DocumentsContract.isTreeUri(uri))android.provider.DocumentsContract.buildTreeDocumentUri(uri.authority,android.provider.DocumentsContract.getTreeDocumentId(uri)).toString() else null
            }
        }.getOrNull() else null
        val destination = savedDestination ?: recoveredDestination ?: request.optString("destination").ifBlank { dao.state("download_destination")?.value.orEmpty() }
        if(destination.isBlank()) { executing=false;busy.value=false;message.value="Pilih tujuan unduhan dahulu.";return@withContext }
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
            if (action in setOf("prepare","acquire")) {
                // Explicit review action only: complete selected-source snapshot; never an output-folder scan.
                val rows = JSONArray()
                for (track in dao.allTracks().filter { it.active && it.available }) {
                    if (!operationContext.isActive || cancelled) error("Dibatalkan")
                    val hash = MessageDigest.getInstance("SHA-256")
                    val readable = runCatching { app.contentResolver.openInputStream(Uri.parse(track.uri))?.use { input ->
                        val buffer = ByteArray(262144)
                        while (true) { if (!operationContext.isActive || cancelled) error("Dibatalkan"); val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
                    } != null }.getOrDefault(false)
                    check(readable) { "Sumber tidak terbaca. Pindai ulang folder pilihan sebelum memeriksa daftar." }
                    rows.put(JSONObject().put("uri", track.uri).put("title", track.title).put("artists", JSONArray(track.artist.split(';').map { it.trim() }))
                        .put("duration", track.duration / 1000.0).put("album", track.album).put("relative_path", track.fileName)
                        .put("source_url",track.sourceUrl).put("youtube_id",track.sourceIdentity.removePrefix("youtube:").takeIf { track.sourceIdentity.startsWith("youtube:") && it.matches(Regex("[A-Za-z0-9_-]{11}")) }.orEmpty())
                        .put("sha256", hash.digest().joinToString("") { "%02x".format(it) }))
                    event.value = JSONObject().put("stage", "snapshot").put("completed", rows.length())
                }
                call("snapshot", JSONObject().put("scope", "app-selected-sources").put("rows", rows))
            }
            if (action == "acquire") dao.state(AppState("engine-consent:${payload.getString("plan_id")}", "1"))
            val result = call(action, payload)
            if (result.has("entries")) {
                plan.value = result; dao.state(AppState("engine-last-plan", result.getString("id")))
                if(reviewDraft.value?.optString("plan_id")!=result.getString("id"))reviewDraft.value=dao.state("engine-review:${result.getString("id")}")?.value?.let { runCatching { JSONObject(it) }.getOrNull() }
                if(action=="prepare" && dao.state("engine-destination:${result.getString("id")}")==null)dao.state(AppState("engine-destination:${result.getString("id")}",destination))
                if (dao.state("engine-consent:${result.getString("id")}")?.value == "1") importResult(result)
            }
            message.value = "Selesai"
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            message.value = if (cancelled) "Dibatalkan" else e.message?.take(240) ?: "Operasi gagal"
            // A process can die after SAF commit: reload the durable plan on the next review.
            runCatching { val recovered = client.begin("get_last").result.await(); if (recovered.optBoolean("ok")) { val result=recovered.getJSONObject("result");plan.value=result;if(dao.state("engine-consent:${result.getString("id")}")?.value=="1")importResult(result) } }
        } finally { client.close();refreshHistory();executing=false;busy.value = workActive; cancelled = false;pendingCancels.clear();activeEntry.value="";activePlan.value="";currentAction.value="" }
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
