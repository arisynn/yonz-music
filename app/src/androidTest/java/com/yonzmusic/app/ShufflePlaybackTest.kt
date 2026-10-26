package com.yonzmusic.app

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.os.SystemClock
import android.provider.MediaStore
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.Track
import com.yonzmusic.app.playback.PlaybackConnection
import com.yonzmusic.app.playback.PlaybackService
import com.yonzmusic.app.playback.mediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Original bundled WAV, real service and controllers; no external downloads or user files. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ShufflePlaybackTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = inst.runOnMainSync(action)
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ok = false
            main { ok = predicate() }
            if (ok) return
            Thread.sleep(100)
        }
        fail(message)
    }
    @Test fun shuffleVisitsWholeQueueFromMiddleAndNextFollowsServiceOrder() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<YonzApp>()
        val uri = app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "Yono_Shuffle_QA_${System.nanoTime()}.wav")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/YonoShuffleQA")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            })!!
        inst.context.assets.open("formats/owned-tone.wav").use { input ->
            app.contentResolver.openOutputStream(uri)!!.use { input.copyTo(it) }
        }
        app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        val nonce = java.util.UUID.randomUUID().toString()
        val tracks = (0..7).map { Track("shuffle-$nonce-$it", uri.toString(), "Shuffle fixture $it", "Karisyono QA", source = "instrumentation") }
        app.repository.dao.upsert(tracks)
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var connection: PlaybackConnection
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        main {
            connection = PlaybackConnection(app)
            future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        val player = future.get(45, TimeUnit.SECONDS)
        await("App controller connected") { connection.state.value.ready }
        val oldItems = mutableListOf<androidx.media3.common.MediaItem>()
        var oldIndex = 0; var oldPosition = 0L; var oldShuffle = false; var oldRepeat = 0
        main {
            oldItems.addAll((0 until player.mediaItemCount).map(player::getMediaItemAt))
            oldIndex = player.currentMediaItemIndex.coerceAtLeast(0); oldPosition = player.currentPosition
            oldShuffle = player.shuffleModeEnabled; oldRepeat = player.repeatMode
            player.pause(); player.repeatMode = Player.REPEAT_MODE_OFF
        }
        try {
            // Starting at a middle/last item must not put unseen songs behind the shuffle cursor.
            for (start in listOf(4, 7, 2)) {
                main { player.shuffleModeEnabled = false; player.setMediaItems(tracks.map(Track::mediaItem), start, 0); player.prepare() }
                await("Fixture queue ready") { player.mediaItemCount == tracks.size && player.currentMediaItemIndex == start && !connection.state.value.shuffle }
                main { connection.shuffle() }
                await("Shuffle enabled in service") { player.shuffleModeEnabled && connection.state.value.shuffle }
                Thread.sleep(400) // Wait for the authoritative timeline beyond optimistic masking.
                val order = mutableListOf<Int>()
                main {
                    var index = player.currentMediaItemIndex
                    while (index != androidx.media3.common.C.INDEX_UNSET && index !in order) {
                        order += index
                        index = player.currentTimeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
                    }
                }
                assertEquals("Shuffle must keep the current song and make every other song reachable: $order", tracks.size, order.size)
                assertEquals(start, order.first())
                await("Visible queue must mirror the authoritative shuffled timeline") { connection.state.value.playbackOrder == order }
                for (index in order.drop(1)) {
                    main { connection.next() }
                    await("Next must use actual shuffle order $order; expected $index") { player.currentMediaItemIndex == index && connection.state.value.index == index }
                }
                main { connection.shuffle() }
                await("Shuffle disabled") { !player.shuffleModeEnabled && !connection.state.value.shuffle }
                main {
                    assertEquals("Disabling shuffle preserves original queue order", tracks.map { it.id }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
                }
            }
            // A replacement queue while shuffle remains enabled also starts at the requested song.
            main { player.shuffleModeEnabled = true; player.setMediaItems(tracks.take(3).map(Track::mediaItem), 2, 600); player.prepare() }
            await("Replacement queue shuffle is anchored without restarting the song") {
                connection.state.value.playbackOrder.firstOrNull() == 2 && player.currentPosition >= 500 && player.playbackState == Player.STATE_READY
            }
            val newOrder = connection.state.value.playbackOrder
            assertEquals(setOf(0, 1, 2), newOrder.toSet())
            main { player.play() }
            await("Original local WAV actually plays") { player.isPlaying }
            for (expected in newOrder.drop(1)) {
                main { player.seekTo(2850) }
                await("End of decoded audio must advance in shuffle order to $expected") { player.currentMediaItemIndex == expected && player.isPlaying }
            }
            main { player.repeatMode = Player.REPEAT_MODE_ALL; player.seekTo(2850) }
            await("Repeat all wraps to the first shuffled song") { player.currentMediaItemIndex == newOrder.first() && player.isPlaying }
            main { player.repeatMode = Player.REPEAT_MODE_ONE; player.seekTo(2850) }
            await("Repeat one restarts the same audio") { player.currentPosition < 1200 && player.isPlaying }
            main { assertEquals(newOrder.first(), player.currentMediaItemIndex) }
            // System/media controllers and the UI must share the same order, even in repeat-one.
            main { player.seekToNextMediaItem() }
            await("External controller Next shares shuffle order") { player.currentMediaItemIndex == newOrder[1] && connection.state.value.index == newOrder[1] }
        } finally {
            main {
                player.pause(); player.setMediaItems(oldItems, if (oldItems.isEmpty()) 0 else oldIndex.coerceIn(oldItems.indices), oldPosition.coerceAtLeast(0))
                player.shuffleModeEnabled = oldShuffle; player.repeatMode = oldRepeat
                connection.close(); player.release()
            }
            app.contentResolver.delete(uri, null, null)
            for (track in tracks) {
                app.database.openHelper.writableDatabase.execSQL("DELETE FROM history WHERE trackId = ?", arrayOf(track.id))
                app.database.openHelper.writableDatabase.execSQL("DELETE FROM tracks WHERE id = ?", arrayOf(track.id))
            }
        }
    }
}
