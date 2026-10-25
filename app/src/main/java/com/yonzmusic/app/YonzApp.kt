package com.yonzmusic.app

import android.app.Application
import androidx.room.Room
import com.yonzmusic.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow

class YonzApp : Application() {
    val persistenceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1))
    val database by lazy { Room.databaseBuilder(this, MusicDatabase::class.java, "yonz-music.db").build() }
    val repository by lazy { Repository(this, database) }
    val preferences by lazy { Preferences(this) }
    val sleepEnd = MutableStateFlow(0L)
}
