package com.yonzmusic.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class Repository(val context: Context, val db: MusicDatabase) {
    val dao = db.music()
    val scanning = MutableStateFlow(false)
    private val scanMutex = Mutex()
    val permission: String get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun hasPermission() = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    private fun stable(uri: String) = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun scan(): String = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            scanning.value = true
            try {
                var count = 0
                var failed = 0
                if (hasPermission()) {
                    val found = mediaTracks()
                    db.withTransaction { dao.unavailableSource("media"); dao.upsert(found) }
                    count += found.size
                }
                val roots = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
                for (root in roots) {
                    try {
                        val found = folderTracks(root.uri)
                        db.withTransaction { dao.unavailableSource(root.uri.toString()); dao.upsert(found) }
                        count += found.size
                    } catch (_: Exception) { failed++; dao.unavailableSource(root.uri.toString()) }
                }
                // Revalidate explicit SAF documents; opening descriptors reads no audio bytes.
                val docs = dao.allTracks().filter { it.source == "document" }
                for (track in docs) {
                    val available = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(track.uri), "r")?.use { true } ?: false }.getOrDefault(false)
                    if (available != track.available) dao.upsert(listOf(track.copy(available = available)))
                }
                if (!hasPermission() && roots.isEmpty() && docs.isEmpty()) "Allow music access or choose local files to begin."
                else "Library updated · $count scanned${if (failed > 0) " · $failed folders unavailable" else ""}"
            } finally { scanning.value = false }
        }
    }
    private fun mediaTracks(): List<Track> {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = mutableListOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED)
        if (Build.VERSION.SDK_INT >= 29) columns += MediaStore.Audio.Media.RELATIVE_PATH else columns += MediaStore.Audio.Media.DATA
        val result = ArrayList<Track>()
        context.contentResolver.query(base, columns.toTypedArray(), "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0", null, null)?.use { c ->
            fun str(column: String) = c.getString(c.getColumnIndexOrThrow(column)).orEmpty()
            fun num(column: String) = c.getLong(c.getColumnIndexOrThrow(column))
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(base, num(MediaStore.Audio.Media._ID)).toString()
                val file = str(MediaStore.Audio.Media.DISPLAY_NAME)
                val path = str(if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA)
                val folder = if (Build.VERSION.SDK_INT >= 29) path.trimEnd('/') else path.substringBeforeLast('/', "Music")
                val albumId = num(MediaStore.Audio.Media.ALBUM_ID)
                result += Track(stable(uri), uri, clean(str(MediaStore.Audio.Media.TITLE), file.substringBeforeLast('.')),
                    clean(str(MediaStore.Audio.Media.ARTIST), "Unknown artist"), clean(str(MediaStore.Audio.Media.ALBUM), "Unknown album"), folder.ifBlank { "Music" }, file,
                    num(MediaStore.Audio.Media.DURATION), num(MediaStore.Audio.Media.DATE_ADDED) * 1000,
                    if (albumId > 0) "content://media/external/audio/albumart/$albumId" else "")
            }
        } ?: error("Android could not read the music library. Try again.")
        return result
    }
    private fun clean(value: String?, fallback: String) = (value?.takeUnless { it.isBlank() || it == "<unknown>" } ?: fallback).take(512)
    suspend fun importAudio(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            scanning.value = true
            try {
                val existing = dao.allTracks().associateBy { it.uri }
                var added = 0; var duplicates = 0; var invalid = 0
                for (uri in uris.distinct().take(10000)) {
                    if (uri.scheme != "content") { invalid++; continue }
                    if (existing[uri.toString()] != null) { duplicates++; continue }
                    val track = documentTrack(uri, "Chosen files", "document")
                    if (track == null) invalid++ else { dao.upsert(listOf(track)); added++ }
                }
                "$added added · $duplicates already indexed · $invalid unreadable"
            } finally { scanning.value = false }
        }
    }
    suspend fun importFolder(root: Uri): String = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            scanning.value = true
            try {
                val found = folderTracks(root)
                db.withTransaction { dao.unavailableSource(root.toString()); dao.upsert(found) }
                "${found.size} audio files indexed from folder"
            } finally { scanning.value = false }
        }
    }
    private fun folderTracks(root: Uri): List<Track> {
        require(root.scheme == "content" && DocumentsContract.isTreeUri(root)) { "Choose a local document folder." }
        val result = ArrayList<Track>()
        val visited = HashSet<String>()
        val pending = ArrayDeque<Pair<String, String>>()
        val rootId = DocumentsContract.getTreeDocumentId(root)
        pending.add(rootId to rootId.substringAfter(':').ifBlank { "Chosen folder" })
        while (pending.isNotEmpty()) {
            check(visited.size < 20000) { "Folder too large. Choose a smaller music folder." }
            val (id, path) = pending.removeFirst()
            if (!visited.add(id)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, id)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val childId = c.getString(0); val name = c.getString(1).orEmpty(); val mime = c.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) pending.add(childId to "$path/$name")
                    else if (mime.startsWith("audio/") || name.substringAfterLast('.').lowercase() in setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus")) {
                        check(result.size < 10000) { "Folder contains over 10,000 tracks. Choose a smaller folder." }
                        val uri = DocumentsContract.buildDocumentUriUsingTree(root, childId)
                        documentTrack(uri, path, root.toString(), name)?.let(result::add)
                    }
                }
            } ?: error("Folder is not available. Select it again.")
        }
        return result
    }
    private fun documentTrack(uri: Uri, folder: String, source: String, knownName: String = ""): Track? {
        return runCatching {
            var name = knownName
            if (name.isBlank()) context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0).orEmpty() }
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                require(duration > 0) { "Not readable audio" }
                Track(stable(uri.toString()), uri.toString(), clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), name.substringBeforeLast('.').ifBlank { "Untitled audio" }),
                    clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), "Unknown artist"), clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM), "Unknown album"),
                    folder, name, duration, System.currentTimeMillis(), source = source)
            } finally { r.release() }
        }.getOrNull()
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
