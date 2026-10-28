package com.yonzmusic.engine

import android.net.Uri
import android.provider.DocumentsContract as D
import android.util.AtomicFile
import androidx.annotation.WorkerThread
import com.yonzmusic.app.YonzApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Only touches .nomedia in explicitly selected download directories. No media database deletion,
 * audio traversal, rename, conversion or music-file writes. Serialize policy changes/publication.
 * The private journal is written BEFORE creating a marker; its random contents prove ownership
 * even if Android kills the process between createDocument and saving its returned URI.
 */
class DownloadGalleryVisibility internal constructor(
    private val app: YonzApp,
    private val journal: AtomicFile = AtomicFile(File(app.noBackupFilesDir, "download-gallery.json")),
    private val enabled: () -> Boolean = { runBlocking { app.preferences.flow.first().hideDownloadsFromGallery } },
    private val currentDestination: () -> String = { runBlocking { app.repository.dao.state("download_destination")?.value.orEmpty() } }
) {
    private val resolver get() = app.contentResolver
    private val mutableNotice = MutableStateFlow("")
    val notice = mutableNotice.asStateFlow()
    private data class Target(val tree: Uri, val parent: Uri, val token: String)
    private data class Child(val uri: Uri, val name: String, val mime: String)

    private fun read(): MutableList<Target> {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return mutableListOf()
        // A damaged journal must never become permission to delete someone else's marker.
        val rows = JSONArray(journal.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        require(rows.length() <= 512)
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            Target(Uri.parse(it.getString("tree")), Uri.parse(it.getString("parent")), it.getString("token"))
        } }.toMutableList()
    }

    private fun write(rows: List<Target>) {
        val value = JSONArray().apply { rows.forEach { put(JSONObject().put("tree", it.tree.toString())
            .put("parent", it.parent.toString()).put("token", it.token)) } }
        val output = journal.startWrite()
        try { output.write(value.toString().toByteArray()); journal.finishWrite(output) }
        catch (error: Exception) { journal.failWrite(output); throw error }
    }

    private fun children(target: Target): List<Child> {
        val uri = D.buildChildDocumentsUriUsingTree(target.tree, D.getDocumentId(target.parent))
        return resolver.query(uri, arrayOf(D.Document.COLUMN_DOCUMENT_ID, D.Document.COLUMN_DISPLAY_NAME,
            D.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c -> buildList {
            while (c.moveToNext()) {
                check(size < 100000) { "Folder tujuan terlalu besar." }
                add(Child(D.buildDocumentUriUsingTree(target.tree, c.getString(0)), c.getString(1), c.getString(2)))
            }
        } } ?: error("Folder tujuan belum tersedia. Hubungkan ulang folder.")
    }

    private fun payload(target: Target) = "Yono Music gallery marker\n${target.token}\n"
    private fun owns(target: Target, child: Child): Boolean {
        if (child.mime == D.Document.MIME_TYPE_DIR) return false
        return resolver.openInputStream(child.uri)?.use { input ->
            // A marker is tiny. Never load an unrelated, potentially large user document.
            val buffer = ByteArray(257)
            var count = 0
            while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
            String(buffer, 0, count, Charsets.UTF_8) == payload(target)
        } ?: false
    }

    /** Returns true if another app/user's marker still controls the folder. */
    private fun apply(target: Target, hide: Boolean): Boolean {
        val markers = children(target).filter { it.name == ".nomedia" }
        require(markers.none { it.mime == D.Document.MIME_TYPE_DIR }) { "Nama .nomedia dipakai folder lain." }
        if (hide) {
            if (markers.isNotEmpty()) return false // No need to open an existing user's document to hide.
            val marker = D.createDocument(resolver, target.parent, "application/octet-stream", ".nomedia")
                ?: error("Penanda Galeri belum dapat dibuat. Periksa akses tulis folder.")
            try {
            resolver.openOutputStream(marker, "w")?.use { it.write(payload(target).toByteArray()); it.flush() }
                ?: error("Penanda Galeri belum dapat ditulis.")
            // Some providers silently append extensions. Do not promise concealment in that case.
            check(children(target).any { it.uri == marker && it.name == ".nomedia" && owns(target, it) }) {
                "Penyedia folder tidak mendukung .nomedia. Pilih folder lokal atau matikan sembunyikan dari Galeri."
            }
            } catch (error: Exception) {
                // This URI was created by this invocation, never an existing user's document.
                runCatching { D.deleteDocument(resolver, marker) }
                throw error
            }
            return false
        }
        var external = false
        for (marker in markers) {
            if (owns(target, marker)) check(D.deleteDocument(resolver, marker.uri)) { "Penanda Galeri belum dapat dilepas." }
            else external = true // Never delete a marker the user/another app created or modified.
        }
        return external
    }

    @WorkerThread
    fun beforePublish(tree: Uri, parent: Uri) = synchronized(lock) {
        require(tree.scheme == "content" && D.isTreeUri(tree) && parent.authority == tree.authority)
        require(D.getTreeDocumentId(tree) == D.getTreeDocumentId(parent))
        val rows = read()
        val target = rows.find { it.tree == tree && it.parent == parent } ?: Target(tree, parent, UUID.randomUUID().toString()).also {
            check(rows.size < 512) { "Terlalu banyak folder unduhan tersimpan." }
            rows.add(it); write(rows)
        }
        try { apply(target, enabled()) }
        catch (error: Exception) {
            mutableNotice.value = "Pengaturan Galeri belum diterapkan. Hubungkan ulang folder tujuan atau periksa akses tulis."
            throw java.io.IOException("Pengaturan Galeri gagal: ${error.message?.take(160)}", error)
        }
        Unit
    }

    suspend fun setEnabled(value: Boolean) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runBlocking { app.preferences.hideDownloadsFromGallery(value) }
            reconcileLocked(value)
        }
    }

    suspend fun reconcile() = withContext(Dispatchers.IO) { synchronized(lock) { reconcileLocked(enabled()) } }

    private fun reconcileLocked(hide: Boolean) {
        try {
            val rows = read()
            val current = currentDestination()
            if (current.isNotBlank()) {
                val tree = Uri.parse(current)
                val root = D.buildDocumentUriUsingTree(tree, D.getTreeDocumentId(tree))
                if (rows.none { it.tree == tree && it.parent == root }) {
                    check(rows.size < 512); rows.add(Target(tree, root, UUID.randomUUID().toString())); write(rows)
                }
            }
            var failures = 0
            var external = false
            rows.forEach { target ->
                try { if (apply(target, hide)) external = true } catch (_: Exception) { failures++ }
            }
            mutableNotice.value = when {
                failures > 0 -> "$failures folder belum dapat diperbarui. Hubungkan ulang folder tujuan; pengaturan dicoba lagi saat aplikasi dibuka."
                !hide && external -> "Penanda .nomedia milik pengguna/aplikasi lain tetap dipertahankan. Folder tersebut masih bisa tersembunyi."
                else -> ""
            }
        } catch (_: Exception) {
            mutableNotice.value = "Pengaturan Galeri belum diterapkan. Hubungkan ulang folder tujuan atau periksa akses tulis."
        }
    }

    companion object { private val lock = Any() }
}
