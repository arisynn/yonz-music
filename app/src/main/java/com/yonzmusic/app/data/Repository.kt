package com.yonzmusic.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** The registry, never persistedUriPermissions or MediaStore enumeration, defines the library. */
class Repository(val context: Context, val db: MusicDatabase) {
    val dao = db.music()
    val scanning = MutableStateFlow(false)
    private val scanMutex = Mutex()
    private val resolver get() = context.contentResolver
    fun stable(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun identity(uri: Uri): String = runCatching {
        if (DocumentsContract.isDocumentUri(context, uri)) "${uri.authority}:${DocumentsContract.getDocumentId(uri)}" else uri.toString()
    }.getOrDefault(uri.toString())

    suspend fun scan(): String = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            scanning.value = true
            try {
                val sources = dao.allSources().filter { it.enabled }
                var failed = 0
                for (source in sources) {
                    coroutineContext.ensureActive()
                    if (!scanSource(source)) failed++
                }
                refreshVisibility()
                if (sources.isEmpty()) "Choose music files or a folder in Import. Only your selections are indexed."
                else "Selected sources refreshed${if (failed > 0) " · $failed need attention in Import" else ""}"
            } finally { scanning.value = false }
        }
    }
    suspend fun importAudio(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            scanning.value = true
            try {
                var valid = 0
                for (uri in uris.distinct().take(1000)) {
                    if (uri.scheme != "content") continue
                    val source = source(uri, "document")
                    dao.source(source)
                    if (scanSource(source)) valid++
                }
                refreshVisibility()
                "$valid selected files indexed · originals stay in place"
            } finally { scanning.value = false }
        }
    }
    suspend fun importFolder(uri: Uri): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "Choose a document folder." }
        require(uri.authority != "com.android.externalstorage.documents" || DocumentsContract.getTreeDocumentId(uri).substringAfter(':').trim('/').isNotBlank()) { "Choose a music folder instead of a storage root." }
        scanMutex.withLock {
            scanning.value = true
            try {
                val source = source(uri, "tree")
                dao.source(source)
                val ok = scanSource(source)
                refreshVisibility()
                if (ok) "Selected music folder indexed" else "Folder access needs attention. Select it again in Import."
            } finally { scanning.value = false }
        }
    }
    private suspend fun source(uri: Uri, kind: String): MusicSource {
        val id = stable("$kind:$uri")
        val existing = dao.source(id)
        val document = if (kind == "tree") DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
        var name = if (kind == "tree") "Music folder" else "Audio file"
        runCatching { resolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0).orEmpty().take(200).ifBlank { name } } }
        val persistent = resolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }
        return (existing ?: MusicSource(id, uri.toString(), name, kind)).copy(enabled = true, persistent = persistent)
    }
    suspend fun changeSource(id: String, enabled: Boolean? = null, recursive: Boolean? = null) = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val old = dao.source(id) ?: return@withLock
            val next = old.copy(enabled = enabled ?: old.enabled, recursive = recursive ?: old.recursive)
            dao.source(next)
            if (recursive != null && next.enabled) { scanning.value = true; try { scanSource(next) } finally { scanning.value = false } }
            refreshVisibility()
        }
    }
    suspend fun removeSource(id: String) = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            db.withTransaction { dao.clearLocations(id); dao.deleteSource(id); refreshVisibility() }
            // Grants may also serve an overlapping source/output destination. Never revoke shared access here.
        }
    }
    private suspend fun scanSource(source: MusicSource): Boolean {
        return try {
            val root = Uri.parse(source.uri)
            val oldLocations = dao.locations().filter { it.sourceId == source.id }.associateBy { it.identity }
            val allLocations = dao.locations().groupBy { it.identity }
            val oldTracks = dao.allTracks()
            val downloadSources = dao.allSources().filter { it.kind == "download" }.map { it.id }.toSet()
            val byId = oldTracks.associateBy { it.id }
            val priorByIdentity = oldTracks.groupBy { identity(Uri.parse(it.uri)) }
            val completed = dao.completedDownloads()
            val foundTracks = LinkedHashMap<String, Track>()
            val found = mutableListOf<TrackLocation>()
            suspend fun read(uri: Uri, folder: String, knownName: String = "", size: Long = 0, modified: Long = 0) {
                coroutineContext.ensureActive()
                val key = identity(uri)
                val cached = oldLocations[key]
                val cacheTrack = cached?.let { byId[it.trackId] }
                var track = if (cached != null && cacheTrack != null && size > 0 && modified > 0 && cached.size == size && cached.modified == modified) cacheTrack.copy(uri = uri.toString(), folder = folder, fileName = knownName.ifBlank { cacheTrack.fileName }, active = true, available = true)
                    else documentTrack(uri, folder, source.id, knownName)
                if (track == null) { if (cached != null) found += cached.copy(available = false); return }
                val equivalent = (allLocations[key].orEmpty().map { it.trackId } + priorByIdentity[key].orEmpty().map { it.id } + listOfNotNull(legacyMediaId(uri, oldTracks), hashBinding(uri, size, completed)))
                    .map { dao.state("track_alias:$it")?.value ?: it }.distinct().filter { dao.track(it) != null }
                val existing = equivalent.sortedWith(compareBy<String> { if (byId[it]?.source == "media") 0 else 1 }.thenBy { it }).firstOrNull()
                if (existing != null && equivalent.size > 1) mergeIdentities(existing, equivalent.filter { it != existing })
                val id = existing ?: stable(key)
                val previous = byId[id]
                val downloaded = previous != null && (previous.source in downloadSources || allLocations[key].orEmpty().any { it.sourceId in downloadSources })
                track = track.copy(id = id, added = previous?.added ?: track.added, artUri = previous?.artUri.orEmpty().ifBlank { track.artUri },
                    title = if (downloaded) previous!!.title else track.title,
                    artist = if (downloaded) previous!!.artist else track.artist,
                    album = if (downloaded) previous!!.album else track.album)
                foundTracks[id] = track
                found += TrackLocation(source.id, key, id, uri.toString(), size, modified)
            }
            if (source.kind == "tree") {
                val visited = HashSet<String>()
                val pending = ArrayDeque<Pair<String, String>>()
                val rootId = DocumentsContract.getTreeDocumentId(root)
                pending.add(rootId to source.name)
                while (pending.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    check(visited.size < 20000) { "Choose a smaller folder (20,000 folders maximum)." }
                    val (id, path) = pending.removeFirst()
                    if (!visited.add(id)) continue
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, id)
                    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    resolver.query(children, projection, null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val child = cursor.getString(0); val name = cursor.getString(1).orEmpty(); val mime = cursor.getString(2).orEmpty()
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (source.recursive) pending.add(child to "$path/$name") }
                            else if (!name.startsWith(".yono-part-") && !name.endsWith(".part") && (mime.startsWith("audio/") || name.substringAfterLast('.').lowercase() in AUDIO_EXT)) {
                                check(found.size < 10000) { "Choose a smaller folder (10,000 songs maximum)." }
                                read(DocumentsContract.buildDocumentUriUsingTree(root, child), path, name, cursor.getLong(3), cursor.getLong(4))
                            }
                        }
                    } ?: error("Folder is unavailable. Select it again.")
                }
            } else {
                var size = 0L; var modified = 0L
                runCatching { resolver.query(root, arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { if (it.moveToFirst()) { size = it.getLong(0); modified = it.getLong(1) } } }
                read(root, "Chosen files", size = size, modified = modified)
                require(found.isNotEmpty()) { "Audio is missing, unreadable or unsupported. Select it again." }
            }
            db.withTransaction {
                val rootId = if (source.kind == "tree") DocumentsContract.getTreeDocumentId(root) else ""
                val present = found.map { it.identity }.toSet()
                val missing = oldLocations.values.filter { it.identity !in present &&
                    (source.kind != "tree" || source.recursive || runCatching { DocumentsContract.getDocumentId(Uri.parse(it.uri)).substringBeforeLast('/') == rootId }.getOrDefault(false)) }
                found += missing.map { it.copy(available = false) }
                val remapped = foundTracks.values.map { it.copy(id = dao.state("track_alias:${it.id}")?.value ?: it.id) }.associateBy { it.id }.values.toList()
                dao.upsert(remapped); dao.clearLocations(source.id)
                dao.locations(found.map { it.copy(trackId = dao.state("track_alias:${it.trackId}")?.value ?: it.trackId) })
                dao.source(source.copy(status = "${found.count { it.available }} available · ${found.count { !it.available }} unavailable"))
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {
            db.withTransaction {
                dao.locations(dao.locations().filter { it.sourceId == source.id }.map { it.copy(available = false) })
                dao.source(source.copy(status = "Access unavailable. Select this source again."))
            }
            false
        }
    }
    /** Android's document→MediaStore mapping is identity evidence. Filenames/artist guesses are not. */
    private fun legacyMediaId(uri: Uri, old: List<Track>): String? {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrDefault("")
        val mediaDocumentId = if (uri.authority == "com.android.providers.media.documents" && documentId.startsWith("audio:")) documentId.substringAfter(':') else null
        val mapped = if (Build.VERSION.SDK_INT >= 29) runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull() else null
        val selectedId = mediaDocumentId ?: mapped?.takeIf { it.authority == "media" && (it.pathSegments.contains("audio") || it.pathSegments.contains("file")) }?.lastPathSegment ?: return null
        val matches = old.filter { track ->
            val previous = Uri.parse(track.uri)
            previous.authority == "media" && previous.pathSegments.takeLast(3).take(2) == listOf("audio", "media") &&
                previous.lastPathSegment == selectedId
        }
        return matches.singleOrNull()?.id
    }
    /** Transactional identity reconciliation only after exact document/media/hash evidence. */
    suspend fun mergeIdentities(target: String, aliases: List<String>) = db.withTransaction {
        require(dao.track(target) != null)
        for (old in aliases.distinct().filter { it != target }) {
            if (dao.track(old) == null) continue
            if (dao.favoriteRow(old) != null) { dao.favorite(Favorite(target)); dao.unfavorite(old) }
            val history = dao.historyRow(old)
            if (history != null) {
                if ((dao.historyRow(target)?.playedAt ?: Long.MIN_VALUE) < history.playedAt) dao.remember(history.copy(trackId = target))
                dao.deleteHistory(old)
            }
            for (playlist in dao.memberships(old).map { it.playlistId }.distinct()) {
                val entries = dao.entries(playlist).map { if (it.trackId == old) target else it.trackId }.distinct()
                dao.clearEntries(playlist); dao.addEntries(entries.mapIndexed { n, id -> PlaylistEntry(playlist, id, n) })
            }
            dao.rebindLocations(old, target); dao.rebindDownloads(old, target)
            dao.compressAliases(old, target); dao.state(AppState("track_alias:$old", target)); dao.deleteIndexRow(old)
        }
    }
    private fun hashBinding(uri: Uri, size: Long, completed: List<DownloadJob>): String? {
        val candidates = completed.filter { it.received == size && size in 1..MAX_AUDIO_BYTES && it.sha256.length == 64 }
        if (candidates.isEmpty()) return null
        val hash = runCatching { resolver.openInputStream(uri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var total = 0L
            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= MAX_AUDIO_BYTES); digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        } }.getOrNull()
        return candidates.filter { it.sha256 == hash }.map { it.trackId }.filter { it.isNotBlank() }.distinct().singleOrNull()
    }
    suspend fun refreshVisibility() {
        val enabled = dao.allSources().filter { it.enabled }.map { it.id }.toSet()
        val locations = dao.locations().filter { it.sourceId in enabled }.groupBy { it.trackId }
        val changes = dao.allTracks().mapNotNull { track ->
            val membership = locations[track.id].orEmpty()
            val usable = membership.firstOrNull { it.available }
            val next = track.copy(active = membership.isNotEmpty(), available = usable != null, uri = usable?.uri ?: track.uri)
            next.takeIf { it != track }
        }
        if (changes.isNotEmpty()) dao.upsert(changes)
    }
    suspend fun indexDownloaded(uri: Uri, job: DownloadJob): Track = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val source = source(uri, "download")
            val parsed = documentTrack(uri, job.playlistName, source.id) ?: error("Saved audio could not be read.")
            val existing = dao.track(job.trackId)
            val track = parsed.copy(id = existing?.id ?: stable(identity(uri)), title = job.title, artist = job.artist,
                album = job.album.ifBlank { "Downloaded audio" }, added = existing?.added ?: parsed.added)
            db.withTransaction {
                dao.source(source.copy(persistent = true, status = "Validated download"))
                dao.upsert(listOf(track)); dao.locations(listOf(TrackLocation(source.id, identity(uri), track.id, uri.toString(), job.received)))
            }
            track
        }
    }
    private fun clean(value: String?, fallback: String) = (value?.takeUnless { it.isBlank() || it == "<unknown>" } ?: fallback).take(512)
    private fun documentTrack(uri: Uri, folder: String, source: String, knownName: String = ""): Track? = runCatching {
        var name = knownName
        if (name.isBlank()) resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0).orEmpty() }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(duration > 0)
            Track(stable(identity(uri)), uri.toString(), clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), name.substringBeforeLast('.').ifBlank { "Untitled audio" }),
                clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), "Unknown artist"), clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM), "Unknown album"),
                folder, name, duration, System.currentTimeMillis(), source = source)
        } finally { retriever.release() }
    }.getOrNull()
    companion object {
        val AUDIO_EXT = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "webm", "weba")
        const val MAX_AUDIO_BYTES = 256L * 1024 * 1024
    }
    suspend fun create(name: String, ids: List<String> = emptyList()): Long = db.withTransaction {
        val id = dao.create(Playlist(name = validName(name)))
        dao.addEntries(ids.distinct().mapIndexed { index, track -> PlaylistEntry(id, track, index) }); id
    }
    suspend fun append(id: Long, ids: List<String>) = db.withTransaction {
        val ordered = CollectionOrder.append(dao.entries(id).map { it.trackId }, ids)
        dao.clearEntries(id)
        dao.addEntries(ordered.mapIndexed { n, track -> PlaylistEntry(id, track, n) })
    }
    suspend fun move(id: Long, from: Int, to: Int) = db.withTransaction {
        val ordered = CollectionOrder.move(dao.entries(id).map { it.trackId }, from, to)
        dao.clearEntries(id); dao.addEntries(ordered.mapIndexed { n, track -> PlaylistEntry(id, track, n) })
    }
    fun validName(value: String) = value.trim().take(100).also { require(it.isNotEmpty()) { "Give your playlist a name." } }
}
