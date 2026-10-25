package com.yonzmusic.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.yonzmusic.app.data.*
import com.yonzmusic.app.importer.*
import com.yonzmusic.app.download.*
import com.yonzmusic.app.playback.PlaybackConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as YonzApp
    val repository = app.repository
    private val dao = repository.dao
    val appearanceReady = MutableStateFlow(false)
    val preferences = app.preferences.flow.onEach { appearanceReady.value = true }.stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())
    val tracks = dao.tracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val collectionTracks = dao.collectionTracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sources = dao.sources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val migrationNotice = dao.migrationNotice().map { it?.value == "1" }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val downloads = dao.downloads().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val outputDestination = dao.stateFlow("download_destination").map { it?.value.orEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val audioCandidates = MutableStateFlow<List<AudioCandidate>>(emptyList())
    val engineCandidates = MutableStateFlow<List<AudioCandidate>>(emptyList())
    val engineReport = MutableStateFlow("")
    val engineInput = MutableStateFlow("")
    private var importedSourceRequests = emptyList<SongRequest>()
    private val sourceMutex = kotlinx.coroutines.sync.Mutex()
    val acquisition = MutableStateFlow<List<AcquisitionRow>>(emptyList())
    val sourceBusy = MutableStateFlow(false)
    private var sourceJob: Job? = null
    val favorites = dao.favorites().map { it.map(Favorite::trackId).toSet() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val history = dao.history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = dao.playlists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val entries = dao.entries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val imports = dao.imports().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val scanning = repository.scanning
    val sleepEnd = app.sleepEnd
    val playback = PlaybackConnection(application)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val importPreview = MutableStateFlow<ImportPreview?>(null)
    val importBusy = MutableStateFlow(false)
    private var importJob: Job? = null
    private var committing = false
    var importedFileName: String = "pasted.txt"
        private set
    var importedText: String = ""
        private set

    // Opening the app reads Room only; no storage scan or broad permission onboarding.
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { messages.emit(e.message?.take(240) ?: "Something went wrong. Try again.") }
    }
    fun notify(message: String) { messages.tryEmit(message) }
    fun scan() { action { messages.emit(repository.scan()) } }
    fun files(uris: List<Uri>) { action { messages.emit(repository.importAudio(uris)) } }
    fun folder(uri: Uri) { action { messages.emit(repository.importFolder(uri)) } }
    fun sourceEnabled(id: String, enabled: Boolean) { action { repository.changeSource(id, enabled = enabled) } }
    fun sourceRecursive(id: String, recursive: Boolean) { action { repository.changeSource(id, recursive = recursive) } }
    fun removeSource(id: String) { action { repository.removeSource(id); messages.emit("Source removed. Audio and collections are preserved.") } }
    fun dismissMigration() { action { dao.state(AppState("source_migration_notice", "0")) } }
    fun backup(uri: Uri) { action { CollectionBackup(app).export(uri); messages.emit("Collection backup saved. It contains metadata, not audio.") } }
    fun restoreBackup(uri: Uri) { action { messages.emit(CollectionBackup(app).restore(uri)) } }
    fun destination(uri: Uri) { action {
        require(app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }) { "Choose a provider that offers persistent read and write access for downloads." }
        dao.state(AppState("download_destination", uri.toString())); messages.emit("Download destination saved. It is not automatically scanned.")
    } }
    fun searchAudio(query: String) {
        sourceJob?.cancel()
        sourceJob = action {
            sourceBusy.value = true; audioCandidates.value = emptyList()
            try { audioCandidates.value = withContext(Dispatchers.IO) { sourceMutex.withLock { CommonsSource.search(query) } }; if (audioCandidates.value.isEmpty()) messages.emit("No recognized freely licensed audio found in Commons.") }
            finally { if (sourceJob == currentCoroutineContext()[Job]) sourceBusy.value = false }
        }
    }
    fun cancelSourceSearch() { sourceJob?.cancel(); sourceBusy.value = false }
    fun sourcesFromImportedList() { action {
        val requests = withContext(Dispatchers.Default) { MusicListParser.parse(importedText, importedFileName) }
        val sources = requests.filter { it.error.isBlank() && it.reference.startsWith("https://") }
        require(sources.size in 1..50) { "This list needs 1–50 explicit HTTPS source links. Titles alone can be matched locally; they are not downloadable audio." }
        importedSourceRequests = sources
        engineInput.value = sources.map { it.reference }.distinct().joinToString("\n")
        messages.emit("Source links loaded. Review permissions, then read their metadata.")
    } }
    fun inspectEngineSources(text: String, contentPermission: Boolean, servicePermission: Boolean) {
        sourceJob?.cancel()
        sourceJob = action {
            require(contentPermission) { "Confirm content rights and the source's download permission before requesting it." }
            val urls = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().take(51).toList()
            require(urls.size in 1..50 && urls.all { it.length <= 4096 }) { "Use 1–50 explicit source links, one per line." }
            sourceBusy.value = true; engineCandidates.value = emptyList(); engineReport.value = ""
            try {
                val candidates = mutableListOf<AudioCandidate>(); val failures = mutableListOf<String>()
                for ((index, url) in urls.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val candidate = withContext(Dispatchers.IO) { runCatching {
                        sourceMutex.lock()
                        try {
                            val known = dao.completedDownloads().firstNotNullOfOrNull { old ->
                                if (old.url == url) dao.track(old.trackId)?.takeIf { it.active && it.available }?.let { track ->
                                    AudioCandidate(old.sourceId, track.title, track.artist, track.album, track.duration, old.expectedSize, url, old.license, old.licenseUrl, url)
                                } else null
                            }
                            val extracted = known ?: EmbeddedEngine.inspect(app, url, servicePermission)
                            val requested = importedSourceRequests.singleOrNull { it.reference == url && it.title.isNotBlank() && it.artist.isNotBlank() }
                            if (requested != null) {
                                val pendingTags = extracted.artist in setOf("Unspecified credits", "Unknown artist", "")
                                if (!pendingTags) require(RecordingMatch.rejection(requested, extracted) == null) { "Requested title, artist credits, recording/version or duration differs from this source." }
                                extracted.copy(title = requested.title, artist = requested.artist, request = requested,
                                    description = if (pendingTags) "Requested recording: file tags will be checked before publication. A mismatch fails safely." else extracted.description)
                            } else extracted
                        } finally { sourceMutex.unlock() }
                    } }
                    candidate.onSuccess { candidates += it }.onFailure { failures += "Entry ${index + 1}: ${SourceFailure.describe(it)}" }
                    engineCandidates.value = candidates.toList()
                    engineReport.value = "${candidates.size} sources ready for review · ${failures.size} failed\n" + failures.joinToString("\n")
                }
            } finally { if (sourceJob == currentCoroutineContext()[Job]) sourceBusy.value = false }
        }
    }
    fun engineDownload(name: String, candidates: List<AudioCandidate>, servicePermission: Boolean) = enqueueAcquisition(name,
        candidates.map { AcquisitionRow(it.title, "Ready", it.license, candidate = it) }, servicePermission)
    fun resolveMissing() {
        sourceJob?.cancel()
        sourceJob = action {
            sourceBusy.value = true; acquisition.value = emptyList()
            try {
                val requests = withContext(Dispatchers.Default) { MusicListParser.parse(importedText, importedFileName) }
                require(requests.size <= 50) { "Online resolution is limited to 50 entries per batch. Local matching supports 10,000." }
                val preview = MusicListParser.match(requests, dao.allTracks().filter { it.active })
                val rows = mutableListOf<AcquisitionRow>(); val seen = HashSet<String>()
                for ((index, request) in requests.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val local = preview.rows[index]
                    val row = when {
                        local.status == "Matched" -> AcquisitionRow(local.input, "Existing", local.detail, local.trackId.orEmpty())
                        local.status in setOf("Invalid", "Duplicate") -> AcquisitionRow(local.input, local.status, local.detail)
                        request.artist.isBlank() -> AcquisitionRow(local.input, "Unmatched", "Specify artist credits, or review an exact Commons source below")
                        else -> {
                            val candidates = withContext(Dispatchers.IO) { sourceMutex.withLock { CommonsSource.search("${request.title} ${request.artist}".take(250)) } }
                                .filter { RecordingMatch.rejection(request, it) == null }.distinctBy { it.id }
                            when {
                                candidates.size > 1 -> AcquisitionRow(local.input, "Ambiguous", "Several compatible recordings; no automatic substitution")
                                candidates.isEmpty() -> AcquisitionRow(local.input, "Unmatched", "No permitted source with matching title/version and complete artist credits")
                                !seen.add(candidates.single().id) -> AcquisitionRow(local.input, "Duplicate", "Source already included")
                                else -> AcquisitionRow(local.input, "Ready", "${candidates.single().license} · ${candidates.single().artist}", candidate = candidates.single())
                            }
                        }
                    }
                    rows += row; acquisition.value = rows.toList()
                }
            } finally { if (sourceJob == currentCoroutineContext()[Job]) sourceBusy.value = false }
        }
    }
    fun downloadAudio(name: String, candidates: List<AudioCandidate>) = enqueueAcquisition(name, candidates.map { AcquisitionRow(it.title, "Ready", it.license, candidate = it) })
    fun downloadResolved(name: String) = enqueueAcquisition(name, acquisition.value)
    private fun enqueueAcquisition(name: String, rows: List<AcquisitionRow>, servicePermission: Boolean = false) { action {
        val destination = dao.state("download_destination")?.value ?: error("Choose a writable music destination first.")
        require(app.contentResolver.persistedUriPermissions.any { it.uri.toString() == destination && it.isWritePermission && it.isReadPermission }) { "Output access has expired. Select the destination again." }
        val accepted = rows.filter { it.status in setOf("Ready", "Existing") }.distinctBy { it.candidate?.id ?: it.trackId }
        require(accepted.isNotEmpty() && accepted.size <= 50) { "Select between 1 and 50 supported recordings." }
        val batchId = java.util.UUID.randomUUID().toString()
        val validName = repository.validName(name)
        val jobs = app.database.withTransaction {
            dao.state(AppState("download_authorization:$batchId", if (servicePermission) "content-and-service" else "content"))
            val playlistId = repository.create(validName)
            val localTracks = dao.allTracks().filter { it.active && it.available }
            val localMap = localTracks.associateBy { it.id }
            val completedBySource = dao.completedDownloads().groupBy { it.sourceId }
            val included = HashSet<String>()
            accepted.mapIndexed { index, row ->
                val candidate = row.candidate
                val historyMatch = localMap[row.trackId] ?: candidate?.let { c -> completedBySource[c.id].orEmpty().firstNotNullOfOrNull { localMap[it.trackId] } }
                val compatibleLocal = if (historyMatch == null && candidate != null && candidate.duration > 0 && candidate.artist !in setOf("Unspecified credits", "Unknown artist", "")) {
                    val request = SongRequest(candidate.title, candidate.title, candidate.artist, duration = candidate.duration)
                    localTracks.filter { it.artist != "Unknown artist" && RecordingMatch.rejection(request,
                        AudioCandidate(it.id, it.title, it.artist, it.album, it.duration, 0, "", "", "", "")) == null }
                } else emptyList()
                require(compatibleLocal.size <= 1) { "Several local recordings match. Use an exact local reference to resolve the ambiguity first." }
                val known = (historyMatch ?: compatibleLocal.singleOrNull())?.takeIf { track ->
                    runCatching { app.contentResolver.openFileDescriptor(Uri.parse(track.uri), "r")?.use { true } ?: false }.getOrDefault(false)
                }
                if (known != null) {
                    require(known.active && known.available) { "An existing song became unavailable. Analyze again." }
                    DownloadJob(java.util.UUID.randomUUID().toString(), batchId, index, playlistId, validName, destination, known.title, known.artist, known.album,
                        candidate?.id.orEmpty(), candidate?.url.orEmpty(), candidate?.license.orEmpty(), candidate?.licenseUrl.orEmpty(), status = if (included.add(known.id)) "Existing" else "Duplicate", trackId = known.id)
                } else {
                    require(candidate != null) { "An existing file became unavailable. Refresh its selected source and analyze again." }
                    DownloadJob(java.util.UUID.randomUUID().toString(), batchId, index, playlistId, validName, destination, candidate.title, candidate.artist, candidate.album,
                        candidate.id, candidate.url, candidate.license, candidate.licenseUrl, candidate.duration, candidate.size, candidate.artUrl)
                }
            }.also { list ->
                list.zip(accepted).forEach { (job, row) -> row.candidate?.request?.let { request ->
                    dao.state(AppState("requested:${job.id}", org.json.JSONObject().put("title", request.title).put("artist", request.artist).put("duration", request.duration).toString()))
                } }
                dao.jobs(list)
                dao.addEntries(list.filter { it.status == "Existing" }.mapIndexed { n, it -> PlaylistEntry(playlistId, it.trackId, n) })
                val log = dao.log(ImportLog(name = validName, report = "{\"type\":\"acquisition\",\"batch\":\"$batchId\"}"))
                dao.logDetails(rows.mapIndexed { n, row -> ImportDetail(log, n, row.input.take(2048), row.status, row.detail.take(2048)) })
            }
        }
        if (jobs.any { it.status == "Queued" }) DownloadWorker.enqueue(app, batchId)
        messages.emit("Collection created. ${jobs.count { it.status == "Queued" }} downloads queued; existing audio is reused.")
    } }
    fun cancelDownload(batch: String) { action {
        DownloadWorker.cancel(app, batch)
    } }
    fun retryDownload(batch: String) { action {
        val jobs = dao.batch(batch).filter { it.status in setOf("Failed", "Cancelled") && it.attempts < 4 }
        require(jobs.isNotEmpty()) { "No retryable transfers. Start a new import after reviewing the source." }
        require(dao.playlistExists(jobs.first().playlistId)) { "This playlist was deleted. Start a new import to reuse any completed audio." }
        dao.jobs(jobs.map { it.copy(status = "Queued", error = "") }); DownloadWorker.enqueue(app, batch)
    } }
    fun favorite(id: String) { action { if (id in favorites.value) dao.unfavorite(id) else dao.favorite(Favorite(id)) } }
    fun create(name: String, ids: List<String> = emptyList()) { action { repository.create(name, ids); messages.emit("Playlist created") } }
    fun rename(id: Long, name: String) { action { dao.rename(id, repository.validName(name)) } }
    fun delete(id: Long) { action {
        val batches = dao.playlistBatches(id)
        app.database.withTransaction { batches.forEach { dao.cancelBatch(it) }; dao.deletePlaylist(id) }
        messages.emit("Playlist deleted. Completed audio is safe; unfinished transfers were cancelled.")
    } }
    fun add(id: Long, tracks: List<String>) { action { repository.append(id, tracks); messages.emit("Added to playlist") } }
    fun remove(id: Long, trackId: String) { action { dao.removeEntry(id, trackId) } }
    fun move(id: Long, from: Int, to: Int) { action { repository.move(id, from, to) } }
    fun theme(value: String) { action { app.preferences.theme(value) } }
    fun sort(value: String) { action { app.preferences.sort(value) } }
    fun motion(value: Boolean) { action { app.preferences.motion(value) } }
    fun glass(value: Boolean) { action { app.preferences.glass(value) } }
    fun play(tracks: List<Track>, id: String) { if (tracks.none { it.id == id && it.available }) notify("This file is unavailable. Rescan or choose it again in Import.") else if (!playback.state.value.ready) notify("Connecting to playback. Try again in a moment.") else playback.play(tracks, id) }
    fun resetImport() {
        importJob?.cancel(); sourceJob?.cancel(); sourceBusy.value = false
        engineInput.value = ""; engineCandidates.value = emptyList(); engineReport.value = ""; importedSourceRequests = emptyList()
        audioCandidates.value = emptyList(); acquisition.value = emptyList()
        importPreview.value = null; importedText = ""; importedFileName = "pasted.txt"; importBusy.value = false
    }
    fun setImportText(text: String) { importedText = text; importPreview.value = null }
    fun clearImportPreview() { importPreview.value = null }
    fun readList(uri: Uri) {
        importJob?.cancel()
        importJob = action {
            importBusy.value = true
            try {
                val pair = withContext(Dispatchers.IO) {
                    var name = "playlist.txt"
                    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0).orEmpty() }
                    val text = app.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= MusicListParser.MAX_BYTES) { "Lists are limited to 2 MB." }; out.write(buffer, 0, n) }
                        out.toString("UTF-8")
                    } ?: error("This document could not be read.")
                    name to text
                }
                importedFileName = pair.first; importedText = pair.second
                analyzeNow()
            } finally { importBusy.value = false }
        }
    }
    fun analyze() {
        importJob?.cancel()
        importJob = action { importBusy.value = true; try { analyzeNow() } finally { importBusy.value = false } }
    }
    private suspend fun analyzeNow() {
        importPreview.value = null
        val all = dao.allTracks().filter { it.active }
        importPreview.value = withContext(Dispatchers.Default) {
            val requests = MusicListParser.parse(importedText, importedFileName)
            require(requests.isNotEmpty()) { "Add at least one song or choose a playlist document." }
            MusicListParser.match(requests, all)
        }
    }
    fun commitImport(name: String, done: () -> Unit) {
        if (committing) return
        val preview = importPreview.value ?: return
        if (preview.matched.isEmpty()) { notify("No matched local songs to add."); return }
        committing = true
        action {
            try {
                val valid = repository.validName(name)
                app.database.withTransaction {
                    val current = dao.allTracks().filter { it.available && it.active }.map { it.id }.toSet()
                    val ids = preview.matched.mapNotNull { it.trackId }.filter { it in current }
                    require(ids.isNotEmpty()) { "Matched files are no longer available. Analyze the list again." }
                    repository.create(valid, ids)
                    val summary = org.json.JSONObject().put("matched", preview.matched.size).put("unmatched", preview.count("Unmatched")).put("duplicates", preview.count("Duplicate")).put("invalid", preview.count("Invalid")).toString()
                    val logId = dao.log(ImportLog(name = valid, report = summary))
                    dao.logDetails(preview.rows.mapIndexed { n, row -> ImportDetail(logId, n, row.input, row.status, row.detail) })
                }
                messages.emit("${preview.matched.size} tracks added to $valid")
                resetImport(); done()
            } finally { committing = false }
        }
    }
    fun export(uri: Uri, tracks: List<Track>) { action {
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(MusicListParser.export(tracks)) } ?: error("The export could not be saved.")
        }
        messages.emit("M3U8 exported. Content URI references work with the same local library.")
    } }
    override fun onCleared() { playback.close(); super.onCleared() }
}
