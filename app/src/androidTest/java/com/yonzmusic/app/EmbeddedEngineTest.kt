package com.yonzmusic.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.DownloadJob
import com.yonzmusic.app.download.*
import com.yonzmusic.app.network.SafeHttp
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Original tone served by a local fixture, then real bundled Python/yt-dlp/Mutagen on Android. */
@RunWith(AndroidJUnit4::class)
class EmbeddedEngineTest {
    private val app get() = ApplicationProvider.getApplicationContext<YonzApp>()
    private val testConfig = JSONObject().put("localFixture", true)
    private fun fixture(): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets.open("owned-tone.mp3").use { it.readBytes() }
    private fun <T> server(block: (String) -> T): T {
        val data = fixture()
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                val socket = server.accept()
                thread(isDaemon = true) {
                    runCatching { socket.use { client ->
                        val input = client.getInputStream().bufferedReader(); var line = input.readLine()
                        while (!line.isNullOrEmpty()) line = input.readLine()
                        val output = client.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        for (offset in data.indices step 8192) { output.write(data, offset, minOf(8192, data.size - offset)); output.flush(); Thread.sleep(10) }
                    } }
                }
            }
        }
        try { return block("http://127.0.0.1:${server.localPort}/owned-tone.mp3") } finally { server.close(); worker.join(1000) }
    }
    private fun job(candidate: AudioCandidate) = DownloadJob("fixture", "fixture", 0, 0, "Fixture", "", candidate.title, candidate.artist, candidate.album,
        candidate.id, candidate.url, "Original generated test signal", "", candidate.duration, candidate.size)
    @Test fun runtimeAndOriginalAudioTransferTagsArtworkAndNativeProbe() = server { url ->
        val runtime = JSONObject(EmbeddedEngine.runtimeReport(app))
        assertTrue(runtime.getString("python").startsWith("3.12"))
        assertEquals("2026.10.21", runtime.getString("yt_dlp"))
        assertEquals("0.8.0", runtime.getString("ejs"))
        assertEquals(0, runtime.getInt("quickjsExit"))
        assertTrue(runtime.getString("quickjs").contains("42"))
        assertTrue(runtime.getJSONObject("ytDlpJsRuntime").getBoolean("supported"))
        assertEquals(0, runtime.getInt("ejsExit"))
        assertTrue(JSONObject(runtime.getString("ejsBoot")).getBoolean("ejsLoaded"))
        File(app.filesDir, "engine-runtime.json").writeText(runtime.toString(2))
        val candidate = EmbeddedEngine.inspect(app, url, false, testConfig)
        val directory = File(app.cacheDir, "engine-fixture-${System.nanoTime()}").apply { mkdirs() }
        var updates = 0; var received = 0L
        try {
            val file = EmbeddedEngine.transfer(app, job(candidate), directory, false, object : EmbeddedEngine.Callback {
                override fun cancelled() = false
                override fun progress(bytes: Long, total: Long) { updates++; received = bytes }
            }, testConfig)
            assertEquals(fixture().size.toLong(), file.length()); assertEquals(file.length(), received); assertTrue(updates > 0)
            val before = AudioValidation.validate(file, 12000)
            val originalPayload = payloadHash(file)
            val art = File(app.cacheDir, "engine-fixture-art.png")
            try {
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(54, 89, 115)) }
                art.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                val taggedJob = job(candidate).copy(title = "Yono Engine Fixture", artist = "Karisyono Test Signal", album = "Original fixture")
                assertTrue(EmbeddedEngine.tags(app, file, taggedJob, art))
                val after = AudioValidation.validate(file, before.duration)
                assertEquals(before.duration, after.duration); assertEquals(originalPayload, payloadHash(file))
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(file.absolutePath)
                    assertEquals("Yono Engine Fixture", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                    assertEquals("Karisyono Test Signal", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                    assertNotNull(reader.embeddedPicture)
                } finally { reader.release() }
            } finally { art.delete() }
        } finally { directory.deleteRecursively() }
    }
    @Test fun cancellationStopsActualTransferAndNeverPublishesAudio() = server { url ->
        val candidate = EmbeddedEngine.inspect(app, url, false, testConfig)
        val directory = File(app.cacheDir, "engine-cancel-${System.nanoTime()}").apply { mkdirs() }
        var received = 0L; var rejected = false
        try {
            try {
                EmbeddedEngine.transfer(app, job(candidate), directory, false, object : EmbeddedEngine.Callback {
                    override fun cancelled() = received > 0
                    override fun progress(bytes: Long, total: Long) { received = bytes }
                }, testConfig)
            } catch (_: Exception) { rejected = true }
            assertTrue("Cancellation must abort the engine", rejected)
            assertTrue("Test must cancel after real bytes arrived", received > 0)
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".mp3") })
        } finally { directory.deleteRecursively() }
    }
    private fun payloadHash(file: File): String {
        val header = file.inputStream().use { val bytes = ByteArray(10); it.read(bytes); bytes }
        var offset = 0
        if (String(header, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            for (i in 6..9) offset = (offset shl 7) or (header[i].toInt() and 127)
            offset += 10 + if (header[3].toInt() == 4 && header[5].toInt() and 16 != 0) 10 else 0
        }
        val payload = file.readBytes().copyOfRange(offset, file.length().toInt())
        return java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    }
}
