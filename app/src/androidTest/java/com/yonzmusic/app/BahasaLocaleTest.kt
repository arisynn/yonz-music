package com.yonzmusic.app

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.i18n.Bahasa
import com.yonzmusic.app.i18n.BahasaLocale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BahasaLocaleTest {
    private val app get() = ApplicationProvider.getApplicationContext<YonzApp>()
    @Test fun applicationAndBundledControlsUseIndonesianEvenOnEnglishSystem() {
        assertTrue(app.resources.configuration.locales[0].language in setOf("id", "in"))
        val id = app.resources.getIdentifier("media3_controls_play_description", "string", app.packageName)
        assertTrue("Bundled Media3 control must exist", id != 0)
        assertEquals("Putar", app.getString(id))
        assertEquals("Pemutaran musik", app.getString(R.string.playback_channel))
    }
    @Test fun existingNotificationChannelIsRenamedWithoutResettingItsPreferences() {
        val manager = app.getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel("app-updates")
        val channel = existing ?: NotificationChannel("app-updates", "App updates", NotificationManager.IMPORTANCE_LOW)
        val previousName = channel.name
        channel.name = "App updates"
        manager.createNotificationChannel(channel)
        val before = manager.getNotificationChannel(channel.id)
        try {
            BahasaLocale.apply(app)
            val after = manager.getNotificationChannel(channel.id)
            assertEquals("Pembaruan aplikasi", after.name.toString())
            assertEquals(before.importance, after.importance)
            assertEquals(before.sound, after.sound)
            assertEquals(before.shouldVibrate(), after.shouldVibrate())
            assertEquals(before.canShowBadge(), after.canShowBadge())
        } finally {
            if (existing == null) manager.deleteNotificationChannel(channel.id)
            else { existing.name = previousName; manager.createNotificationChannel(existing) }
        }
    }
    @Test fun oldPreferenceAndParserKeysSurviveRealDataStoreRoundTrip(): Unit = runBlocking {
        val before = app.preferences.flow.first()
        try {
            app.preferences.theme("Dark"); app.preferences.sort("Artist")
            val loaded = app.preferences.flow.first()
            assertEquals("Dark", loaded.theme); assertEquals("Artist", loaded.sort)
            assertEquals("Selalu gelap", Bahasa.pilihan(loaded.theme))
            assertEquals("Artis", Bahasa.pilihan(loaded.sort))
        } finally { app.preferences.theme(before.theme); app.preferences.sort(before.sort) }
    }
}
