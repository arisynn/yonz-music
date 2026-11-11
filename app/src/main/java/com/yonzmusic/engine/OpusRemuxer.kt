@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.yonzmusic.engine

import android.content.Context
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.inspector.MediaExtractorCompat
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.OggMuxer
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Copies encoded packets. No decoder, encoder, or quality-changing conversion. */
object OpusRemuxer {
    fun remux(context:Context,source:File,cancelled:()->Boolean = {false}):File {
        val output=File(source.parentFile,"audio-remux.opus")
        require(source.canonicalFile!=output.canonicalFile)
        val extractor=MediaExtractorCompat(context)
        val inputHash=MessageDigest.getInstance("SHA-256")
        try {
            extractor.setDataSource(source.absolutePath)
            require(extractor.trackCount==1){"Unduhan memerlukan satu aliran audio Opus."}
            val media=extractor.getTrackFormat(0)
            require(media.getString(MediaFormat.KEY_MIME)==MimeTypes.AUDIO_OPUS){"Sumber ini tidak menyediakan Opus. Lagu tetap dapat di-stream."}
            val init=mutableListOf<ByteArray>()
            for(i in 0..2)media.getByteBuffer("csd-$i")?.duplicate()?.let {b->ByteArray(b.remaining()).also {b.get(it);init.add(it)}}
            require(init.firstOrNull()?.take(8)?.toByteArray()?.toString(Charsets.US_ASCII)=="OpusHead"){"Header Opus tidak valid."}
            val format=Format.Builder().setSampleMimeType(MimeTypes.AUDIO_OPUS).setSampleRate(48000)
                .setChannelCount(media.getInteger(MediaFormat.KEY_CHANNEL_COUNT)).setInitializationData(init).build()
            extractor.selectTrack(0)
            output.outputStream().channel.use {channel->
                val muxer=OggMuxer.Builder(channel).setVendorString("Yono Music / Media3").build()
                try {
                    val track=muxer.addTrack(format)
                    val buffer=ByteBuffer.allocateDirect(1024*1024)
                    var packets=0
                    while(true) {
                        check(!cancelled() && !Thread.currentThread().isInterrupted){"Unduhan dibatalkan."}
                        buffer.clear();val size=extractor.readSampleData(buffer,0)
                        if(size<0)break
                        require(size in 1..buffer.capacity() && ++packets<=2_000_000)
                        buffer.position(0);buffer.limit(size);inputHash.update(buffer.duplicate())
                        muxer.writeSampleData(track,buffer,BufferInfo(extractor.sampleTime.coerceAtLeast(0),size,0))
                        if(!extractor.advance())break
                    }
                    require(packets>0)
                } finally {muxer.close()}
            }
            val check=MediaExtractorCompat(context)
            val outputHash=MessageDigest.getInstance("SHA-256")
            try {
                check.setDataSource(output.absolutePath);require(check.trackCount==1 && check.getTrackFormat(0).getString(MediaFormat.KEY_MIME)==MimeTypes.AUDIO_OPUS)
                check.selectTrack(0);val b=ByteBuffer.allocateDirect(1024*1024)
                while(true){check(!cancelled());b.clear();val n=check.readSampleData(b,0);if(n<0)break;b.position(0);b.limit(n);outputHash.update(b);if(!check.advance())break}
                require(inputHash.digest().contentEquals(outputHash.digest())){"Paket Opus berubah saat penyimpanan."}
            } finally {check.release()}
            require(output.inputStream().use {ByteArray(4).apply {require(it.read(this)==4)}.toString(Charsets.US_ASCII)}=="OggS")
            return output
        } catch(e:Exception){output.delete();throw e} finally {extractor.release()}
    }
}
