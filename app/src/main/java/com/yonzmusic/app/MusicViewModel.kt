package com.yonzmusic.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.yonzmusic.app.data.*
import com.yonzmusic.app.importer.*
import com.yonzmusic.app.playback.PlaybackConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as YonzApp
    val repository = app.repository
    private val dao = repository.dao
    val preferences = app.preferences.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())
    val tracks = dao.tracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
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

    init { if (repository.hasPermission()) scan() }
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { messages.emit(e.message?.take(240) ?: "Something went wrong. Try again.") }
    }
    fun notify(message: String) { messages.tryEmit(message) }
    fun scan() { action { messages.emit(repository.scan()) } }
    fun files(uris: List<Uri>) { action { messages.emit(repository.importAudio(uris)) } }
    fun folder(uri: Uri) { action { messages.emit(repository.importFolder(uri)) } }
    fun favorite(id: String) { action { if (id in favorites.value) dao.unfavorite(id) else dao.favorite(Favorite(id)) } }
    fun create(name: String, ids: List<String> = emptyList()) { action { repository.create(name, ids); messages.emit("Playlist created") } }
    fun rename(id: Long, name: String) { action { dao.rename(id, repository.validName(name)) } }
    fun delete(id: Long) { action { dao.deletePlaylist(id); messages.emit("Playlist deleted. Your audio files are safe.") } }
    fun add(id: Long, tracks: List<String>) { action { repository.append(id, tracks); messages.emit("Added to playlist") } }
    fun remove(id: Long, trackId: String) { action { dao.removeEntry(id, trackId) } }
    fun move(id: Long, from: Int, to: Int) { action { repository.move(id, from, to) } }
    fun theme(value: String) { action { app.preferences.theme(value) } }
    fun sort(value: String) { action { app.preferences.sort(value) } }
    fun motion(value: Boolean) { action { app.preferences.motion(value) } }
    fun glass(value: Boolean) { action { app.preferences.glass(value) } }
    fun play(tracks: List<Track>, id: String) { if (tracks.none { it.id == id && it.available }) notify("This file is unavailable. Rescan or choose it again in Import.") else if (!playback.state.value.ready) notify("Connecting to playback. Try again in a moment.") else playback.play(tracks, id) }
    fun resetImport() { importJob?.cancel(); importPreview.value = null; importedText = ""; importedFileName = "pasted.txt"; importBusy.value = false }
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
        val all = dao.allTracks()
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
                    val current = dao.allTracks().filter { it.available }.map { it.id }.toSet()
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
