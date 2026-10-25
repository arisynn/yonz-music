package com.yonzmusic.app

import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.DownloadJob
import com.yonzmusic.app.download.AudioValidation
import com.yonzmusic.app.download.EmbeddedEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Original generated signals only. Native packet hashes prove that tags do not re-encode audio. */
@RunWith(AndroidJUnit4::class)
class AudioContainerTest {
    private val app get() = ApplicationProvider.getApplicationContext<YonzApp>()
    private fun copy(name: String): File = File(app.cacheDir, "container-${System.nanoTime()}-${name.substringAfterLast('/')}").also { file ->
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
    }
    private fun packets(file: File): String {
        val extractor = MediaExtractor(); val digest = MessageDigest.getInstance("SHA-256")
        try {
            extractor.setDataSource(file.absolutePath); extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(1024 * 1024)
            while (true) {
                buffer.clear(); val n = extractor.readSampleData(buffer, 0); if (n < 0) break
                buffer.limit(n); buffer.position(0); digest.update(buffer)
                if (!extractor.advance()) break
            }
        } finally { extractor.release() }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    @Test fun flacM4aOggAndWavRemainValidAndKeepIdenticalAudioPacketsAfterTagging() {
        for (extension in listOf("flac", "m4a", "ogg", "wav")) {
            val file = copy("formats/owned-tone.$extension")
            try {
                val before = AudioValidation.validate(file, 3000); assertEquals(extension, before.extension)
                val originalPackets = packets(file)
                val job = DownloadJob("formats", "formats", 0, 0, "Formats", "", "Native Tagged Fixture", "Karisyono Test Signal", "Format test",
                    "fixture:$extension", "https://example.org/owned.$extension", "Original generated signal", "")
                val embedded = EmbeddedEngine.tags(app, file, job, null)
                assertEquals(extension != "wav", embedded)
                val after = AudioValidation.validate(file, before.duration)
                assertEquals(before.duration, after.duration); assertEquals("Packet payload changed for $extension", originalPackets, packets(file))
                if (embedded) {
                    val reader = MediaMetadataRetriever()
                    try { reader.setDataSource(file.absolutePath); assertEquals("Native Tagged Fixture", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)) }
                    finally { reader.release() }
                }
            } finally { file.delete() }
        }
    }
    @Test fun invalidTruncatedAndVideoFilesAreRejectedBeforePublication() {
        val mp3 = copy("owned-tone.mp3"); val video = copy("formats/owned-video.mp4")
        val html = File(app.cacheDir, "not-audio-${System.nanoTime()}").apply { writeText("<html>Not an audio recording</html>") }
        try {
            val length = mp3.length(); java.io.RandomAccessFile(mp3, "rw").use { it.setLength(length / 2) }
            for ((file, duration) in listOf(mp3 to 12000L, html to 0L, video to 3000L)) {
                var rejected = false
                try { AudioValidation.validate(file, duration) } catch (_: Exception) { rejected = true }
                assertTrue("Rejected ${file.name}", rejected)
            }
        } finally { mp3.delete(); video.delete(); html.delete() }
    }
}
