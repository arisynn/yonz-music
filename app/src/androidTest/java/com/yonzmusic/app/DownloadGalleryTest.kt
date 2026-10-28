package com.yonzmusic.app

import android.media.MediaExtractor
import android.net.Uri
import android.provider.DocumentsContract as D
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.UserPreferences
import com.yonzmusic.engine.DownloadGalleryVisibility
import com.yonzmusic.engine.SafDocumentStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real persisted SAF grant and provider, own generated audio. No live network/Gallery claim. */
@RunWith(AndroidJUnit4::class)
class DownloadGalleryTest {
    private val app get() = ApplicationProvider.getApplicationContext<YonzApp>()
    private val resolver get() = app.contentResolver
    private fun children(tree: Uri, parent: Uri): Map<String, Uri> = resolver.query(
        D.buildChildDocumentsUriUsingTree(tree, D.getDocumentId(parent)),
        arrayOf(D.Document.COLUMN_DISPLAY_NAME, D.Document.COLUMN_DOCUMENT_ID), null, null, null
    )!!.use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), D.buildDocumentUriUsingTree(tree, c.getString(1))) } }
    private fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(8192)
        while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun text(uri: Uri) = resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
    private fun replace(uri: Uri, value: String) { resolver.openOutputStream(uri, "wt")!!.use { it.write(value.toByteArray()) } }

    @Test fun defaultPublicationKeepsOriginalWebmAndIdempotentRecovery(): Unit = runBlocking {
        assertTrue(UserPreferences().hideDownloadsFromGallery)
        assertTrue("Missing pre-update preference must default on", app.preferences.flow.first().hideDownloadsFromGallery)
        val tree = Uri.parse(app.repository.dao.state("download_destination")!!.value)
        val token = UUID.randomUUID().toString()
        val name = "QA gallery original $token"
        val root = D.buildDocumentUriUsingTree(tree, D.getTreeDocumentId(tree))
        val workspace = File(app.filesDir, "yono-engine/work/gallery-$token").apply { mkdirs() }
        val source = File(workspace, "source.webm")
        InstrumentationRegistry.getInstrumentation().context.assets.open("original-audio/owned.webm").use { input -> source.outputStream().use { input.copyTo(it) } }
        val expected = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
        val store = SafDocumentStore(app, tree)
        try {
            val audio = Uri.parse(store.commit(source.path, name, "Owned.webm", "audio/webm", token, expected))
            val parent = children(tree, root).getValue(name)
            val files = children(tree, parent)
            assertTrue(files.containsKey(".nomedia")); assertEquals(audio, files["Owned.webm"])
            // Providers can derive MIME from .webm and report video/webm for audio-only bytes.
            // The real invariant is the original byte hash and native audio-only track inspection.
            val providerMime = resolver.getType(audio)
            assertEquals(expected, hash(audio))
            assertTrue(text(files.getValue(".nomedia")).startsWith("Yono Music gallery marker\n"))
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(app, audio, null)
                assertTrue(extractor.trackCount > 0)
                for (i in 0 until extractor.trackCount) assertTrue(extractor.getTrackFormat(i).getString("mime")!!.startsWith("audio/"))
            } finally { extractor.release() }
            source.delete() // Publication receipt must still recover with no private source remaining.
            assertEquals(audio.toString(), store.commit(source.path, name, "Owned.webm", "audio/webm", token, expected))
            assertEquals(expected, hash(audio))
            File(app.filesDir, "gallery-original-proof.json").writeText(org.json.JSONObject()
                .put("requestedMime", "audio/webm").put("providerMime", providerMime)
                .put("sha256Unchanged", true).put("nativeAudioOnly", true).put("fixture", true).toString(2) + "\n")
        } finally {
            // Keep the small generated published fixture and its marker for reopening/rescan QA.
            workspace.deleteRecursively()
        }
    }

    @Test fun toggleRecoveryOwnMarkersOnlyAndUnavailableFolderNotice(): Unit = runBlocking {
        val tree = Uri.parse(app.repository.dao.state("download_destination")!!.value)
        val root = D.buildDocumentUriUsingTree(tree, D.getTreeDocumentId(tree))
        val parent = D.createDocument(resolver, root, D.Document.MIME_TYPE_DIR, "QA gallery policy ${UUID.randomUUID()}")!!
        val journal = AtomicFile(File(app.cacheDir, "gallery-test-${UUID.randomUUID()}.json"))
        var hide = true
        fun policy() = DownloadGalleryVisibility(app, journal, { hide }, { "" })
        val audio = D.createDocument(resolver, parent, "audio/wav", "Own.wav")!!
        InstrumentationRegistry.getInstrumentation().context.assets.open("formats/owned-tone.wav").use { input -> resolver.openOutputStream(audio)!!.use { input.copyTo(it) } }
        val original = hash(audio)
        try {
            var policy = policy()
            policy.beforePublish(tree, parent)
            val marker = children(tree, parent).getValue(".nomedia")
            val firstPayload = text(marker)
            policy.beforePublish(tree, parent); assertEquals(firstPayload, text(marker))
            hide = false; policy = policy() // Reconstructed manager uses the durable ownership journal.
            policy.reconcile(); assertEquals("", policy.notice.value)
            assertFalse(children(tree, parent).containsKey(".nomedia")); assertEquals(original, hash(audio))
            hide = true; policy.reconcile()
            val recreated = children(tree, parent).getValue(".nomedia")
            replace(recreated, "User marker, keep this")
            hide = false; policy.reconcile()
            assertEquals("User marker, keep this", text(recreated)); assertTrue(policy.notice.value.contains("milik pengguna"))
            D.deleteDocument(resolver, recreated) // Test-owned modified marker only.
            val user = D.createDocument(resolver, parent, "application/octet-stream", ".nomedia")!!
            replace(user, "")
            hide = true; policy.beforePublish(tree, parent); assertEquals("", text(user))
            hide = false; policy.reconcile(); assertEquals("", text(user)); assertEquals(original, hash(audio))
            D.deleteDocument(resolver, user)
            val collision = D.createDocument(resolver, parent, D.Document.MIME_TYPE_DIR, ".nomedia")!!
            hide = true
            assertTrue("A failed marker must stop publication with an error", runCatching { policy.beforePublish(tree, parent) }.isFailure)
            assertTrue(policy.notice.value.isNotBlank()); assertEquals(original, hash(audio))
            D.deleteDocument(resolver, collision)
            policy.reconcile(); assertEquals("", policy.notice.value)
            D.deleteDocument(resolver, parent)
            policy.reconcile(); assertTrue(policy.notice.value.contains("belum dapat diperbarui"))
        } finally { runCatching { D.deleteDocument(resolver, parent) }; journal.delete() }
    }
}
