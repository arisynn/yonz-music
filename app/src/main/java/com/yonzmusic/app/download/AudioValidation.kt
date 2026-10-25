package com.yonzmusic.app.download

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.abs

object AudioValidation {
    data class Info(val duration: Long, val mime: String, val extension: String)
    fun validate(file: File, expectedDuration: Long): Info {
        require(file.length() in 1..com.yonzmusic.app.data.Repository.MAX_AUDIO_BYTES) { "Audio is empty or too large." }
        val header = file.inputStream().use { input -> val buffer = ByteArray(256); val size = input.read(buffer); buffer.copyOf(size.coerceAtLeast(0)) }
        if (header.size >= 10 && String(header, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            var length = 0
            for (i in 6..9) { require(header[i].toInt() and 128 == 0); length = (length shl 7) or header[i].toInt() }
            require(length <= 4 * 1024 * 1024) { "Audio metadata exceeds 4 MB." }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val tracks = (0 until extractor.trackCount).filter { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            require(tracks.size == 1) { "A single complete audio stream is required." }
            require((0 until extractor.trackCount).none { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }) { "This source contains video. Video-to-audio conversion is not implemented." }
            val format = extractor.getTrackFormat(tracks.single())
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            require(duration > 0) { "Audio duration is missing." }
            require(expectedDuration <= 0 || abs(expectedDuration - duration) <= maxOf(2500L, (expectedDuration * .015).toLong())) { "Audio duration differs from the source. The file was not published." }
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val prefix = String(header, Charsets.ISO_8859_1)
            val extension = when {
                mime == "audio/mpeg" -> "mp3"
                prefix.startsWith("RIFF") && prefix.substring(8, 12) == "WAVE" -> "wav"
                prefix.startsWith("fLaC") -> "flac"
                prefix.startsWith("OggS") -> "ogg"
                header.take(4) == listOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte()) && "webm" in prefix -> "webm"
                "ftyp" in prefix.take(64) -> "m4a"
                mime == "audio/mp4a-latm" && header.size >= 2 && header[0].toInt() and 255 == 255 && header[1].toInt() and 246 == 240 -> "aac"
                else -> error("This standalone audio container is not implemented for saving. No file was published.")
            }
            extractor.selectTrack(tracks.single())
            var last = -1L; var previous = -1L; var packets = 0
            while (extractor.sampleTime >= 0) {
                previous = last
                last = extractor.sampleTime / 1000
                require(++packets <= 2000000) { "Audio exceeds the validation limit." }
                if (!extractor.advance()) break
            }
            val lastPacket = (last - previous).coerceIn(0, 1750)
            require(last >= (duration - maxOf(250L, lastPacket + 250)).coerceAtLeast(0)) { "Audio ends before its declared duration." }
            return Info(duration, mime, extension)
        } finally { extractor.release() }
    }
    /** Native metadata readers provide the probe without invoking FFprobe. */
    fun embeddedMetadata(file: File): Triple<String, String, String> {
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(file.absolutePath)
            fun value(key: Int) = reader.extractMetadata(key).orEmpty().take(512).takeUnless { it == "<unknown>" }.orEmpty()
            Triple(value(MediaMetadataRetriever.METADATA_KEY_TITLE), value(MediaMetadataRetriever.METADATA_KEY_ARTIST), value(MediaMetadataRetriever.METADATA_KEY_ALBUM))
        } finally { reader.release() }
    }
}
