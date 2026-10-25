package com.yonzmusic.engine

import android.net.Uri
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
    private var task: YonoEngineClient.Task? = null
    @Volatile private var cancelled = false
    private val dao get() = app.repository.dao
    fun cancel() { cancelled = true; event.value = JSONObject().put("stage", "cancelling"); task?.cancel() }
    fun submit(action: String, payload: JSONObject = JSONObject()) {
        if (busy.value) return
        busy.value = true; cancelled = false
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                check(dao.nextTransfer() == null) { "Tunggu antrean unduhan sebelumnya selesai." }
                val id = UUID.randomUUID().toString()
                dao.state(AppState("engine-request:$id", JSONObject().put("action", action).put("payload", payload).put("destination", dao.state("download_destination")?.value.orEmpty()).toString()))
                androidx.work.WorkManager.getInstance(app).enqueueUniqueWork("yono-engine", androidx.work.ExistingWorkPolicy.KEEP,
                    androidx.work.OneTimeWorkRequestBuilder<EngineWorker>().setInputData(androidx.work.workDataOf("request" to id)).build())
            } catch (e: Exception) { busy.value = false; message.value = e.message.orEmpty() }
        }
    }
    suspend fun execute(requestId: String) = withContext(Dispatchers.IO) {
        busy.value = true
        val operationContext = currentCoroutineContext()
        val request = JSONObject(dao.state("engine-request:$requestId")!!.value)
        val destination = request.optString("destination").ifBlank { dao.state("download_destination")?.value.orEmpty() }
        require(destination.isNotBlank()) { "Pilih tujuan unduhan dahulu." }
        val client = YonoEngineClient(app, SafDocumentStore(app, Uri.parse(destination)), YonoEngineClient.RuntimePaths(
            quickJs = File(app.applicationInfo.nativeLibraryDir, "libqjs.so")))
        suspend fun call(action: String, payload: JSONObject): JSONObject {
            if (!operationContext.isActive || cancelled) error("Dibatalkan")
            val current = client.begin(action, payload, if (action == "acquire") 7200 else 900); task = current
            val observer = launch { current.events.collect { event.value = it } }
            val result = try { current.result.await() } catch (e: CancellationException) { current.cancel(); withContext(NonCancellable) { current.result.await() }; throw e } finally { observer.cancel(); task = null }
            if (!result.optBoolean("ok")) error(result.optJSONObject("error")?.optString("message") ?: "Operasi gagal")
            return result.getJSONObject("result")
        }
        try {
            val action = request.getString("action"); val payload = request.getJSONObject("payload")
            if (action == "prepare") {
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
                        .put("sha256", hash.digest().joinToString("") { "%02x".format(it) }))
                    event.value = JSONObject().put("stage", "snapshot").put("completed", rows.length())
                }
                call("snapshot", JSONObject().put("scope", "app-selected-sources").put("rows", rows))
            }
            if (action == "acquire") dao.state(AppState("engine-consent:${payload.getString("plan_id")}", "1"))
            val result = call(action, payload)
            if (result.has("entries")) {
                plan.value = result; dao.state(AppState("engine-last-plan", result.getString("id")))
                if (dao.state("engine-consent:${result.getString("id")}")?.value == "1") importResult(result)
            }
            message.value = "Selesai"
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            message.value = if (cancelled) "Dibatalkan" else e.message?.take(240) ?: "Operasi gagal"
            // A process can die after SAF commit: reload the durable plan on the next review.
            runCatching { val recovered = client.begin("get_last").result.await(); if (recovered.optBoolean("ok")) plan.value = recovered.getJSONObject("result") }
        } finally { client.close(); busy.value = false; cancelled = false }
    }
    private suspend fun importResult(result: JSONObject) {
        val id = result.getString("id")
        val playlist = dao.state("engine-playlist:$id")?.value?.toLongOrNull() ?: app.repository.create(result.getString("name")).also { dao.state(AppState("engine-playlist:$id", it.toString())) }
        val knownByUri = dao.allTracks().associateBy { it.uri }.toMutableMap()
        val rows = result.getJSONArray("entries"); val ids = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val uri = row.optString("uri"); if (uri.isBlank()) continue
            val known = knownByUri[uri]
            val song = row.getJSONObject("song"); val artists = song.getJSONArray("artists")
            val artist = (0 until artists.length()).joinToString("; ") { artists.getString(it) }
            val track = known ?: app.repository.indexDownloaded(Uri.parse(uri), DownloadJob(row.getString("id"), id, i, playlist, result.getString("name"), "",
                song.getString("title"), artist, song.optString("album"), song.optString("youtube_id"), song.optString("source_url"), "User-authorized source", ""))
            val art = row.optString("artwork_uri")
            val indexed = if (art.isNotBlank() && art != track.artUri) track.copy(artUri = art).also { dao.upsert(listOf(it)) } else track
            knownByUri[uri] = indexed
            ids += indexed.id
        }
        app.repository.append(playlist, ids)
    }
}
