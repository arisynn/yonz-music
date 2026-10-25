package com.yonzmusic.app

import android.content.*
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.session.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.Track
import com.yonzmusic.app.playback.PlaybackService
import com.yonzmusic.app.playback.mediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Uses only an original, generated PCM tone. Never touches pre-existing audio. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class PlaybackIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 45000
        while (SystemClock.elapsedRealtime() < end) {
            var result = false
            main { result = condition() }
            if (result) return
            Thread.sleep(200)
        }
        fail(message)
    }
    @Test fun localPlaybackSeekMediaCommandsFocusAndSleepTimer() {
        val app = ApplicationProvider.getApplicationContext<YonzApp>()
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "Yonz_Instrumentation_Original.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/YonzInstrumentation")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        val id = java.security.MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        var controller: MediaController? = null
        var focus: AudioFocusRequest? = null
        val manager = app.getSystemService(AudioManager::class.java)
        try {
            val samples = 44100 * 20
            val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
            for (i in 0 until samples) buffer.putShort((kotlin.math.sin(i * 2.0 * Math.PI * 220 / 44100) * 1200).toInt().toShort())
            app.contentResolver.openOutputStream(uri)!!.use { it.write(buffer.array()) }
            app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            val track = Track(id, uri.toString(), "Instrumentation tone", "Original test signal", duration = 20000, source = "instrumentation")
            runBlocking { app.repository.dao.upsert(listOf(track)) }
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var pending: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            main { pending = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            controller = pending!!.get(45, TimeUnit.SECONDS)
            val player = controller
            main { player.setMediaItems(listOf(track.mediaItem())); player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE; player.prepare(); player.play() }
            await("Local WAV should play") { player.isPlaying }
            await("System effects need the real audio session") { player.sessionExtras.getInt(PlaybackService.AUDIO_SESSION, -1) > 0 }
            main { player.seekTo(5000) }
            await("Seek should reach requested position") { player.currentPosition >= 4800 }
            main { player.pause() }
            await("Pause should stop audio") { !player.isPlaying }
            main { player.play() }
            await("Resume should play") { player.isPlaying }
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper())).build()
            var granted = 0
            main { granted = manager.requestAudioFocus(focus!!) }
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, granted)
            await("Audio focus interruption should suppress playback") { !player.isPlaying }
            main { manager.abandonAudioFocusRequest(focus!!); focus = null }
            await("Playback should recover after transient focus") { player.isPlaying }
            var timer: com.google.common.util.concurrent.ListenableFuture<SessionResult>? = null
            main { timer = player.sendCustomCommand(SessionCommand(PlaybackService.TIMER, Bundle.EMPTY), Bundle().apply { putInt("minutes", 1) }) }
            assertEquals(SessionResult.RESULT_SUCCESS, timer!!.get(20, TimeUnit.SECONDS).resultCode)
            assertTrue(app.sleepEnd.value > 0)
            main { timer = player.sendCustomCommand(SessionCommand(PlaybackService.TIMER, Bundle.EMPTY), Bundle().apply { putInt("minutes", 0) }) }
            assertEquals(SessionResult.RESULT_SUCCESS, timer!!.get(20, TimeUnit.SECONDS).resultCode)
            assertEquals(0L, app.sleepEnd.value)
            main { player.pause() }
            await("Pause before editing the queue") { !player.isPlaying }
            main { player.addMediaItem(track.mediaItem()) }
            await("Queue append should be visible") { player.mediaItemCount == 2 }
            var savedAppend = false
            val appendDeadline = SystemClock.elapsedRealtime() + 10000
            while (!savedAppend && SystemClock.elapsedRealtime() < appendDeadline) {
                savedAppend = runBlocking { app.preferences.snapshot()?.ids?.size == 2 }
                if (!savedAppend) Thread.sleep(100)
            }
            assertTrue("Editing a paused queue must persist immediately", savedAppend)
            main { player.clearMediaItems() }
            var savedEmpty = false
            val saveDeadline = SystemClock.elapsedRealtime() + 10000
            while (!savedEmpty && SystemClock.elapsedRealtime() < saveDeadline) {
                savedEmpty = runBlocking { app.preferences.snapshot()?.ids?.isEmpty() == true }
                if (!savedEmpty) Thread.sleep(100)
            }
            assertTrue("Clearing queue must clear restored context", savedEmpty)
        } finally {
            main { focus?.let { manager.abandonAudioFocusRequest(it) }; controller?.release() }
            app.contentResolver.delete(uri, null, null) // Only the URI created by this test.
            runBlocking {
                app.database.openHelper.writableDatabase.execSQL("DELETE FROM history WHERE trackId = ?", arrayOf(id))
                app.database.openHelper.writableDatabase.execSQL("DELETE FROM tracks WHERE id = ?", arrayOf(id))
            }
        }
    }
}
