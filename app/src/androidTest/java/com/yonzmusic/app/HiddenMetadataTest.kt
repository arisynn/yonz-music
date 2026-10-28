package com.yonzmusic.app

import android.net.Uri
import android.provider.DocumentsContract as D
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.*
import com.yonzmusic.engine.CoverDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HiddenMetadataTest {
    @Test fun prettyHiddenManifestFreshNonrecursiveImportAndRenameKeepArtworkAndCollections(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<YonzApp>()
        val resolver = app.contentResolver
        val tree = Uri.parse(app.repository.dao.state("download_destination")!!.value)
        val root = D.buildDocumentUriUsingTree(tree, D.getTreeDocumentId(tree))
        val folder = D.createDocument(resolver, root, D.Document.MIME_TYPE_DIR, "QA hidden JSON ${UUID.randomUUID()}")!!
        fun create(parent: Uri, name: String, mime: String, asset: String): Uri {
            val uri = D.createDocument(resolver, parent, mime, name)!!
            InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input -> resolver.openOutputStream(uri)!!.use { input.copyTo(it) } }
            return uri
        }
        fun hash(uri: Uri) = resolver.openInputStream(uri)!!.use { input -> MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) } }
        val db = Room.inMemoryDatabaseBuilder(app, MusicDatabase::class.java).build()
        val fresh = Room.inMemoryDatabaseBuilder(app, MusicDatabase::class.java).build()
        try {
            val repo = Repository(app, db)
            val audio = create(folder, "Misleading filename.webm", "audio/webm", "original-audio/owned-relocation.webm")
            val coverFolder = CoverDirectory.obtain(resolver, tree, folder)
            val image = create(coverFolder, "recording.png", "image/png", "original-audio/cover.png")
            val track = repo.indexDownloaded(audio, DownloadJob("hidden-json", "hidden-json", 0, 0, "", "", "Judul terverifikasi", "Karisyono; Artis kedua", "Album diketahui", "own:json-fixture", "https://example.test/owned.webm", "Own fixture", "", sha256 = hash(audio))).copy(artUri = image.toString())
            repo.dao.upsert(listOf(track))
            val portable = PortableMetadata(app)
            val manifest = portable.writeNextTo(track, portable.encode(track, "recording.png", hash(image)))!!
            assertTrue(D.getDocumentId(manifest).substringBeforeLast('/').endsWith("/.yono"))
            val text = resolver.openInputStream(manifest)!!.bufferedReader().use { it.readText() }
            assertTrue(text.endsWith("\n")); assertTrue(text.contains("\n  \"audio\": {")); assertTrue(text.contains("Judul terverifikasi"))
            assertEquals(".cover/recording.png", portable.read(manifest)!!.artName)
            // Fresh independent Room DB, explicit source with recursive browsing OFF from its first scan.
            val imported = Repository(app, fresh)
            imported.dao.source(MusicSource("hidden-json", folder.toString(), "JSON QA", "tree", recursive = false, persistent = true))
            imported.scan()
            val result = imported.dao.allTracks().single { it.sha256 == track.sha256 }
            assertEquals(track.title, result.title); assertEquals(track.artist, result.artist); assertEquals(track.album, result.album)
            assertEquals(image.toString(), result.artUri)
            val playlist = imported.create("Hidden JSON QA", listOf(result.id))
            imported.dao.favorite(Favorite(result.id)); imported.dao.remember(History(result.id, 1234, 2222))
            val moved = D.renameDocument(resolver, audio, "Another name.webm")!!
            imported.scan()
            val recovered = imported.dao.track(result.id)!!
            assertEquals(moved.toString(), recovered.uri); assertEquals(result.title, recovered.title)
            assertEquals(result.artUri, recovered.artUri); assertEquals(track.sha256, hash(moved))
            assertNotNull(imported.dao.favoriteRow(result.id)); assertEquals(result.id, imported.dao.entries(playlist).single().trackId)
            assertEquals(2222L, imported.dao.historyRow(result.id)!!.position)
        } finally { db.close(); fresh.close(); D.deleteDocument(resolver, folder) }
    }
}
