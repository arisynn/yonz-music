package com.yonzmusic.app

import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.*
import com.yonzmusic.app.download.*
import com.yonzmusic.app.playback.PlaybackService
import com.yonzmusic.app.playback.mediaItem
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in LIVE network check, distinct from fixture tests. The SAF grant is chosen through DocumentsUI first. */
@RunWith(AndroidJUnit4::class)
class LiveEngineAcquisitionTest {
    @Test fun permittedCommonsSourceThroughYtDlpToSelectedFolderAndMediaSession() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveEngine") == "true")
        val app = ApplicationProvider.getApplicationContext<YonzApp>()
        val dao = app.repository.dao
        val destination = dao.state("download_destination")?.value ?: error("Choose a writable test destination through the actual app UI first")
        val ca = File(app.filesDir, "test-network-ca.pem")
        require(ca.isFile)
        val transport = JSONObject().put("proxy", "http://10.0.2.2:9080").put("ca", ca.absolutePath)
        // Source page's CC BY-SA 2.0 license and performance credits were checked against the Commons API.
        val url = "https://upload.wikimedia.org/wikipedia/commons/2/24/Mozart_-_Eine_kleine_Nachtmusik_-_1._Allegro.ogg"
        val candidate = EmbeddedEngine.inspect(app, url, false, transport)
        val batch = "live-gate-${System.currentTimeMillis()}"
        val playlist = app.repository.create("Yono live permitted acquisition")
        val initial = DownloadJob(batch, batch, 0, playlist, "Yono live permitted acquisition", destination,
            "Eine kleine Nachtmusik - Allegro", "Advent Chamber Orchestra; Wolfgang Amadeus Mozart", "Commons licensed performance", candidate.id, url,
            "CC BY-SA 2.0", "https://creativecommons.org/licenses/by-sa/2.0/", 355413, 11470392)
        dao.jobs(listOf(initial))
        var updates = 0
        val completed = DownloadEngine(app, transport).run(initial) { updates++ }
        assertTrue(completed.error, completed.status in setOf("Complete", "Existing")); if (completed.status == "Complete") assertTrue(updates > 0)
        val track = dao.track(completed.trackId)!!
        assertTrue(track.active && track.available); assertEquals(1, dao.entries(playlist).size)
        assertEquals(initial.title, track.title); assertEquals(initial.artist, track.artist)
        assertTrue(kotlin.math.abs(track.duration - 355413) < 2500)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        instrumentation.runOnMainSync { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
        val player = future!!.get(45, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync { player.setMediaItems(listOf(track.mediaItem())); player.prepare(); player.play() }
            val deadline = SystemClock.elapsedRealtime() + 45000
            var playing = false
            while (!playing && SystemClock.elapsedRealtime() < deadline) { instrumentation.runOnMainSync { playing = player.isPlaying }; if (!playing) Thread.sleep(200) }
            assertTrue("Newly acquired shared audio must play through the real MediaSession", playing)
            File(app.filesDir, "live-engine-report.json").writeText(JSONObject().put("network", "LIVE HTTPS via test proxy with verified CA")
                .put("runtime", JSONObject(EmbeddedEngine.runtimeReport(app))).put("source", url).put("license", initial.license)
                .put("status", completed.status).put("size", completed.received).put("sha256", completed.sha256)
                .put("uri", completed.uri).put("durationMs", track.duration).put("progressEvents", updates).put("mediaSessionPlaying", true).toString(2))
        } finally { instrumentation.runOnMainSync { player.pause(); player.release() } }
    }
    @Test fun permittedCreatorMp3ThroughEmbeddedEngineAndRealMediaSession() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveEngine") == "true")
        val app = ApplicationProvider.getApplicationContext<YonzApp>(); val dao = app.repository.dao
        val destination = dao.state("download_destination")?.value ?: error("Select a writable test destination first")
        val ca = File(app.filesDir, "test-network-ca.pem"); require(ca.isFile)
        val transport = JSONObject().put("proxy", "http://10.0.2.2:9080").put("ca", ca.absolutePath)
        // Creator's official licensing page expressly offers CC BY 4.0 downloads with attribution.
        val url = "https://incompetech.com/music/royalty-free/mp3-royaltyfree/Monkeys%20Spinning%20Monkeys.mp3"
        val candidate = EmbeddedEngine.inspect(app, url, false, transport)
        val batch = "live-creator-${System.currentTimeMillis()}"; val playlist = app.repository.create("Yono permitted creator acquisition")
        val initial = DownloadJob(batch, batch, 0, playlist, "Yono permitted creator acquisition", destination,
            "Monkeys Spinning Monkeys", "Kevin MacLeod", "Incompetech", candidate.id, url,
            "CC BY 4.0 · Kevin MacLeod (incompetech.com)", "https://creativecommons.org/licenses/by/4.0/")
        dao.jobs(listOf(initial)); var updates = 0
        val completed = DownloadEngine(app, transport).run(initial) { updates++ }
        assertTrue(completed.error, completed.status in setOf("Complete", "Existing"))
        if (completed.status == "Complete") assertTrue(updates > 0)
        val track = dao.track(completed.trackId)!!; assertTrue(track.active && track.available); assertTrue(track.duration > 60000)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        instrumentation.runOnMainSync { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
        val player = future!!.get(45, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync { player.setMediaItems(listOf(track.mediaItem())); player.prepare(); player.play() }
            val deadline = SystemClock.elapsedRealtime() + 45000; var playing = false
            while (!playing && SystemClock.elapsedRealtime() < deadline) { instrumentation.runOnMainSync { playing = player.isPlaying }; if (!playing) Thread.sleep(200) }
            assertTrue("Creator MP3 must play through the actual MediaSession", playing)
            File(app.filesDir, "live-creator-report.json").writeText(JSONObject().put("network", "LIVE HTTPS via test proxy with verified CA")
                .put("source", url).put("licensePage", "https://incompetech.com/music/royalty-free/licenses/")
                .put("license", initial.license).put("status", completed.status).put("size", completed.received).put("sha256", completed.sha256)
                .put("uri", completed.uri).put("durationMs", track.duration).put("progressEvents", updates).put("mediaSessionPlaying", true).toString(2))
        } finally { instrumentation.runOnMainSync { player.pause(); player.release() } }
    }

}
