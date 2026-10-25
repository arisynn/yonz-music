package com.yonzmusic.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.security.MessageDigest
import java.util.ArrayDeque
import org.json.JSONArray
import org.json.JSONObject

/** Invoke on Dispatchers.IO only after an explicit import/rescan of a selected SAF tree.
 * Returns a complete snapshot, or throws; never installs an incomplete snapshot.
 */
class SelectedFolderScanner(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    data class Result(val rows: JSONArray, val unreadable: List<String>, val examined: Int)

    fun scan(tree: Uri, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): Result {
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree))
        val pending = ArrayDeque<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        val rows = JSONArray()
        val errors = mutableListOf<String>()
        val extensions = setOf("mp3", "m4a", "flac", "ogg", "oga", "opus", "wav", "aac", "webm", "weba")
        var examined = 0
        fun active() { if (cancelled()) throw IOException("Cancelled") }
        pending.add(DocumentsContract.getTreeDocumentId(tree) to "")
        while (pending.isNotEmpty()) {
            active()
            val (id, prefix) = pending.removeFirst()
            if (!seen.add(id)) continue
            require(seen.size <= 100000) { "Selected tree is too large" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    active()
                    val documentId = cursor.getString(0)
                    val name = cursor.getString(1)
                    val mime = cursor.getString(2)
                    val relative = prefix + name
                    if (name.startsWith('.')) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(documentId to "$relative/")
                        continue
                    }
                    if (name.substringAfterLast('.', "").lowercase() !in extensions) continue
                    require(rows.length() < 100000) { "Selected library has too many tracks" }
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                    val reader = MediaMetadataRetriever()
                    try {
                        reader.setDataSource(app, uri)
                        val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000) ?: 0.0
                        if (duration <= 0) throw IOException("Missing duration")
                        val title = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                            ?: name.substringBeforeLast('.', name)
                        val artist = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                        val artists = JSONArray(artist.split(';').map { it.trim() }.filter { it.isNotEmpty() })
                        val hash = MessageDigest.getInstance("SHA-256")
                        resolver.openInputStream(uri)?.use { stream ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                active()
                                val count = stream.read(buffer)
                                if (count < 0) break
                                hash.update(buffer, 0, count)
                            }
                        } ?: throw IOException("Cannot read selected audio")
                        val sha256 = hash.digest().joinToString("") { "%02x".format(it) }
                        rows.put(JSONObject().put("uri", uri.toString()).put("relative_path", relative)
                            .put("title", title).put("artists", artists).put("duration", duration)
                            .put("album", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()).put("sha256", sha256))
                    } catch (error: Exception) {
                        active()
                        errors += relative
                    } finally { reader.release() }
                    examined++
                    progress(examined)
                }
            } ?: throw IOException("Selected directory cannot be read")
        }
        return Result(rows, errors, examined)
    }
}
