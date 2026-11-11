package com.yonzmusic.app

import android.app.Application
import androidx.room.Room
import com.yonzmusic.app.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class YonzApp : Application() {
    companion object {lateinit var instance:YonzApp;private set}
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.yonzmusic.app.i18n.BahasaLocale.wrap(base))
    }
    val persistenceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1))
    val database by lazy { Room.databaseBuilder(this, MusicDatabase::class.java, "yonz-music.db").addMigrations(MusicDatabase.MIGRATION_1_2, MusicDatabase.MIGRATION_2_3, MusicDatabase.MIGRATION_3_4, MusicDatabase.MIGRATION_4_5).build() }
    val repository by lazy { Repository(this, database) }
    val preferences by lazy { Preferences(this) }
    val streams by lazy { com.yonzmusic.app.playback.StreamRepository(this) }
    val streamCache by lazy { com.yonzmusic.app.playback.StreamAudioCache(this) }
    val onlineActivity by lazy { OnlineActivityRepository(this) }
    val discovery by lazy { com.yonzmusic.app.search.DiscoveryRepository(this) }
    val catalog by lazy { com.yonzmusic.app.search.MusicCatalog(this) }
    internal var streamTestDataSource: androidx.media3.datasource.DataSource.Factory? = null // Internal DI; no Intent/config hook.
    val downloadGallery by lazy { com.yonzmusic.engine.DownloadGalleryVisibility(this) }
    val acquisition by lazy { com.yonzmusic.engine.AcquisitionRepository(this) }
    val onlineCollections by lazy { com.yonzmusic.app.data.OnlineCollections(this) }
    val sleepEnd = MutableStateFlow(0L)
    val updates by lazy { com.yonzmusic.app.update.UpdateManager(this) }
    override fun onCreate() {
        super.onCreate()
        instance=this
        com.yonzmusic.app.i18n.BahasaLocale.apply(this)
        persistenceScope.launch { preferences.flow.collect { settings -> streams.quality=settings.streamQuality; streamCache.configure(settings.streamCacheMb) } }
        // Retire only the obsolete account credential; leave all music/collections intact.
        persistenceScope.launch {
            android.util.AtomicFile(java.io.File(noBackupFilesDir,"spotify-oauth.enc")).delete()
            runCatching {java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry("yono.spotify.oauth.v1")}}
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { updates.onForeground(); persistenceScope.launch { downloadGallery.reconcile() } }
            override fun onStop(owner: LifecycleOwner) { updates.onBackground() }
        })
    }
}
