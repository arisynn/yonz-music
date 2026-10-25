package com.yonzmusic.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.annotation.Keep
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Uses only a tree selected by ACTION_OPEN_DOCUMENT_TREE with a persisted read/write grant.
 * Never requests broad music/storage permissions and never walks the destination implicitly.
 */
@Keep
class SafDocumentStore(context: Context, private val destination: Uri) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val root = File(app.filesDir, "yono-engine").apply { mkdirs() }.canonicalFile
    private val receiptDir = File(root, "receipts").apply { mkdirs() }
    @Volatile private var cancelled: () -> Boolean = { false }

    init {
        require(destination.scheme == "content" && DocumentsContract.isTreeUri(destination)) { "Choose a SAF folder" }
    }

    internal fun setCancellation(value: () -> Boolean) { cancelled = value }
    private fun checkActive() { if (cancelled()) throw IOException("Cancelled") }

    @Keep fun exists(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        if (uri.scheme != "content") false else resolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    @Keep fun matches(value: String, expected: String): Boolean = runCatching {
        expected.isEmpty() || digest(Uri.parse(value)) == expected
    }.getOrDefault(false)

    private fun sha(): MessageDigest = MessageDigest.getInstance("SHA-256")
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun digest(uri: Uri): String {
        val hash = sha()
        resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                checkActive()
                val n = stream.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        } ?: throw IOException("Cannot open document")
        return hex(hash.digest())
    }

    private fun receiptFile(key: String): AtomicFile {
        require(key.matches(Regex("[a-zA-Z0-9-]{1,120}")))
        return AtomicFile(File(receiptDir, "$key.json"))
    }

    private fun record(key: String, value: JSONObject) {
        val file = receiptFile(key)
        val output = file.startWrite()
        try {
            output.write(value.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Keep fun lookup(key: String, expectedHash: String): String {
        val row = runCatching { JSONObject(receiptFile(key).openRead().use { it.readBytes().toString(Charsets.UTF_8) }) }.getOrNull() ?: return ""
        if (row.optString("sha256") != expectedHash) return ""
        val uri = row.optString("uri")
        if (!exists(uri)) return ""
        checkActive()
        if (matches(uri, expectedHash)) {
            if (row.optString("state") == "staging") record(key, row.put("state", "published"))
            return uri
        }
        checkActive()
        if (row.optString("state") == "staging") {
            // A user-requested retry may remove only this engine's own incomplete new document.
            if (DocumentsContract.deleteDocument(resolver, Uri.parse(uri))) receiptFile(key).delete()
        }
        return ""
    }

    private fun documentTreeRoot(): Uri = DocumentsContract.buildDocumentUriUsingTree(destination, DocumentsContract.getTreeDocumentId(destination))
    private data class Child(val uri: Uri, val name: String, val mime: String)

    private fun children(parent: Uri): List<Child> {
        val result = mutableListOf<Child>()
        val list = DocumentsContract.buildChildDocumentsUriUsingTree(destination, DocumentsContract.getDocumentId(parent))
        resolver.query(list, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                checkActive()
                result += Child(DocumentsContract.buildDocumentUriUsingTree(destination, cursor.getString(0)), cursor.getString(1), cursor.getString(2))
            }
        } ?: throw IOException("Folder cannot be read")
        return result
    }

    private fun component(value: String): String {
        require(value.isNotBlank() && value != "." && value != ".." && value.none { it == '/' || it == '\\' || it.code < 32 })
        require(value.toByteArray(Charsets.UTF_8).size <= 220)
        return value
    }

    private fun folder(name: String): Uri {
        val parent = documentTreeRoot()
        val found = children(parent).filter { it.name == name }
        if (found.isNotEmpty()) {
            require(found.size == 1 && found[0].mime == DocumentsContract.Document.MIME_TYPE_DIR) { "Ambiguous destination folder" }
            return found[0].uri
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Cannot create playlist folder")
    }

    private fun uniqueName(parent: Uri, requested: String): String {
        val names = children(parent).map { it.name.lowercase() }.toSet()
        if (requested.lowercase() !in names) return requested
        val stem = requested.substringBeforeLast('.', requested)
        val extension = requested.substringAfterLast('.', "")
        for (i in 2..10000) {
            val name = "$stem ($i)" + if (extension.isEmpty()) "" else ".$extension"
            if (name.lowercase() !in names) return name
        }
        throw IOException("Too many filename collisions")
    }

    private fun copyToDocument(source: File, uri: Uri) {
        resolver.openFileDescriptor(uri, "w")?.use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                FileInputStream(source).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
                // Cloud providers may not expose a seekable/fsync-capable descriptor.
                runCatching { output.fd.sync() }
            }
        } ?: throw IOException("Cannot create writable document")
    }

    @Keep @Synchronized
    fun commit(localPath: String, folderName: String, fileName: String, mime: String, key: String, expectedHash: String): String {
        lookup(key, expectedHash).takeIf { it.isNotEmpty() }?.let { return it }
        val source = File(localPath).canonicalFile
        require(source.toPath().startsWith(File(root, "work").toPath()) && source.isFile)
        require(expectedHash.matches(Regex("[a-f0-9]{64}")))
        val parent = folder(component(folderName))
        val finalName = uniqueName(parent, component(fileName))
        var created: Uri? = null
        var published = false
        try {
            checkActive()
            created = DocumentsContract.createDocument(resolver, parent, mime, finalName)
                ?: throw IOException("Provider refused document creation")
            // createDocument creates a new document; no existing URI is ever opened for writing.
            record(key, JSONObject().put("state", "staging").put("uri", created.toString()).put("sha256", expectedHash))
            copyToDocument(source, created)
            if (digest(created) != expectedHash) throw IOException("Copied audio checksum differs")
            record(key, JSONObject().put("state", "published").put("uri", created.toString()).put("sha256", expectedHash))
            published = true
            return created.toString()
        } finally {
            // Only delete a document created by this invocation, never an existing user's file.
            if (!published && created != null) runCatching { DocumentsContract.deleteDocument(resolver, created) }
        }
    }

    /** Call explicitly after a crashed import. Returns only known staging receipts for review.
     * Do not auto-delete them: a provider may have completed the copy before the crash.
     */
    fun unfinishedReceipts(): List<JSONObject> = receiptDir.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
        runCatching { JSONObject(AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            .put("receipt_key", file.nameWithoutExtension) }.getOrNull()?.takeIf { it.optString("state") == "staging" }
    }

    @Keep fun copyToPrivate(value: String, workspace: String): String {
        val directory = File(workspace).canonicalFile
        require(directory.toPath().startsWith(File(root, "work").toPath()) && directory.isDirectory)
        val uri = Uri.parse(value)
        require(uri.scheme == "content")
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else ""
        }.orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in setOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "webm"))
        val output = File(directory, "input.$extension")
        var bytes = 0L
        resolver.openInputStream(uri)?.use { input -> output.outputStream().use { target ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                require(bytes <= 256L * 1024 * 1024)
                target.write(buffer, 0, count)
            }
        } } ?: throw IOException("Cannot read selected audio")
        return output.canonicalPath
    }

    @Keep fun probe(path: String): String {
        val file = File(path).canonicalFile
        require(file.toPath().startsWith(File(root, "work").toPath()))
        val info = com.yonzmusic.app.download.AudioValidation.validate(file, 0)
        return JSONObject().put("duration", info.duration / 1000.0).put("audio", true).put("video", false).toString()
    }
}
