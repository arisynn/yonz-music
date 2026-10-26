package com.yonzmusic.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.*
import com.yonzmusic.app.download.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Real embedded engine + Room + chosen SAF output. No internet and no third-party audio. */
@RunWith(AndroidJUnit4::class)
class DownloadQueueTest {
    @Test fun failedFirstRecordingRetriesInOrderAndCancelledBatchDoesNotBlockFollowingBatch() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<YonzApp>(); val dao = app.repository.dao
        val destination = dao.state("download_destination")?.value
        assumeTrue("Select a writable test folder in DocumentsUI before running acquisition integration checks", !destination.isNullOrBlank())
        require(dao.nextTransfer() == null) { "Finish pending transfers before this isolated queue check" }
        val data = InstrumentationRegistry.getInstrumentation().context.assets.open("owned-tone.mp3").use { it.readBytes() }
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                val socket = server.accept()
                thread(isDaemon = true) { runCatching { socket.use { client ->
                    val input = client.getInputStream().bufferedReader(); var line = input.readLine()
                    while (!line.isNullOrEmpty()) line = input.readLine()
                    client.getOutputStream().use { out ->
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(data)
                    }
                } } }
            }
        }
        val config = JSONObject().put("localFixture", true)
        val stamp = System.nanoTime().toString(); val a = "queue-a-$stamp"; val b = "queue-b-$stamp"; val c = "queue-c-$stamp"
        val playlist = app.repository.create("Yono original queue fixture $stamp")
        val tree = android.net.Uri.parse(destination)
        val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        val destinationName = app.contentResolver.query(root, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { it.moveToFirst(); it.getString(0) }
        fun source(n: Int) = "http://127.0.0.1:${server.localPort}/owned-tone.mp3?record=$stamp-$n"
        fun job(n: Int, batch: String, ordinal: Int): DownloadJob {
            val candidate = EmbeddedEngine.inspect(app, source(n), false, config)
            return DownloadJob("$stamp-$n", batch, ordinal, playlist, destinationName, destination!!,
                "Original fixture $n", "Karisyono Test Signal", "Original fixture", candidate.id, source(n), "Original generated signal", "", 12000, data.size.toLong())
        }
        try {
            val first = job(0, a, 0).copy(expectedSize = data.size + 1L)
            val second = job(1, a, 1); val cancelled = job(2, b, 0); val following = job(3, c, 0)
            dao.jobs(listOf(first, second, cancelled, following)); DownloadWorker.cancel(app, b)
            val engine = DownloadEngine(app, config)
            repeat(3) { engine.run(dao.nextTransfer()!!) {} }
            assertNull(dao.nextTransfer()); assertEquals("Failed", dao.download(first.id)!!.status)
            assertEquals("Complete", dao.download(second.id)!!.status); assertEquals("Cancelled", dao.download(cancelled.id)!!.status)
            assertEquals("Legacy publication must not repeat the chosen folder name", android.provider.DocumentsContract.getTreeDocumentId(tree), android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(dao.download(second.id)!!.uri)).substringBeforeLast('/'))
            assertEquals("Complete", dao.download(following.id)!!.status); assertNull(dao.state("publish:${cancelled.id}"))
            val secondId = dao.download(second.id)!!.trackId; val followingId = dao.download(following.id)!!.trackId
            // User edits made while a failed first item waits for retry must survive.
            val manualId = "manual-$stamp"; dao.upsert(listOf(Track(manualId, "content://fixture/manual/$stamp", "User-added recording")))
            app.repository.append(playlist, listOf(manualId))
            dao.jobs(listOf(dao.download(first.id)!!.copy(status = "Queued", expectedSize = data.size.toLong(), error = "")))
            val retried = engine.run(dao.nextTransfer()!!) {}
            assertEquals(retried.error, "Complete", retried.status); assertEquals(2, retried.attempts)
            assertEquals(listOf(retried.trackId, secondId, followingId, manualId), dao.entries(playlist).map { it.trackId })
            val reuse = first.copy(id = "reuse-$stamp", batchId = "reuse-$stamp", expectedSize = data.size.toLong())
            dao.jobs(listOf(reuse)); var progress = 0
            val reused = engine.run(reuse) { progress++ }
            assertEquals("Existing", reused.status); assertEquals(retried.trackId, reused.trackId); assertEquals(0, progress)
            assertEquals(4, dao.entries(playlist).size)
            dao.removeEntry(playlist, manualId); dao.deleteIndexRow(manualId)
        } finally { server.close() }
    }
}
