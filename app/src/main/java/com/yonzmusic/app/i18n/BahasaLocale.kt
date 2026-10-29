package com.yonzmusic.app.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** One product language, including Material/Media3 controls, dates and accessibility text.
 * This changes resources only, never persisted theme/sort/status or content metadata.
 */
object BahasaLocale {
    private val indonesia = Locale.forLanguageTag("id-ID")
    fun wrap(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(indonesia))
        configuration.setLayoutDirection(indonesia)
        return context.createConfigurationContext(configuration)
    }
    fun apply(context: Context) {
        Locale.setDefault(indonesia)
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        mapOf("default_channel_id" to "Pemutaran musik", "audio-downloads" to "Unduhan musik",
            "app-updates" to "Pembaruan aplikasi").forEach { (id, name) ->
            // OEM notification settings may be temporarily unavailable during boot.
            runCatching {
            notifications.getNotificationChannel(id)?.let { channel ->
                channel.name = name
                notifications.createNotificationChannel(channel)
            }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // The wrapped app/activity/service contexts already supply Indonesian resources.
            // Failure of optional OS language bookkeeping must not prevent startup.
            runCatching {
            val manager = context.getSystemService(android.app.LocaleManager::class.java)
            if (manager.applicationLocales.toLanguageTags() != "id-ID") {
                manager.applicationLocales = LocaleList.forLanguageTags("id-ID")
            }
            }
        }
    }
}
