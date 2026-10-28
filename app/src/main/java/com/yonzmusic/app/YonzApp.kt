package com.yonzmusic.app

import android.app.Application
import androidx.room.Room
import com.yonzmusic.app.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class YonzApp : Application() {
    val persistenceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1))
    val database by lazy { Room.databaseBuilder(this, MusicDatabase::class.java, "yonz-music.db").addMigrations(MusicDatabase.MIGRATION_1_2, MusicDatabase.MIGRATION_2_3).build() }
    val repository by lazy { Repository(this, database) }
    val preferences by lazy { Preferences(this) }
    val streams by lazy { com.yonzmusic.app.playback.StreamRepository(this) }
    internal var streamTestDataSource: androidx.media3.datasource.DataSource.Factory? = null // Internal DI; no Intent/config hook.
    val acquisition by lazy { com.yonzmusic.engine.AcquisitionRepository(this) }
    val onlineCollections by lazy { com.yonzmusic.app.data.OnlineCollections(this) }
    val sleepEnd = MutableStateFlow(0L)
    val updates by lazy { com.yonzmusic.app.update.UpdateManager(this) }
    override fun onCreate() {
        super.onCreate()
        // Retire only the obsolete account credential; leave all music/collections intact.
        persistenceScope.launch {
            android.util.AtomicFile(java.io.File(noBackupFilesDir,"spotify-oauth.enc")).delete()
            runCatching {java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry("yono.spotify.oauth.v1")}}
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { updates.onForeground() }
            override fun onStop(owner: LifecycleOwner) { updates.onBackground() }
        })
    }
}
