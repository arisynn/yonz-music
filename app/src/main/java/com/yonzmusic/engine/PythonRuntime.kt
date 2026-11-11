package com.yonzmusic.engine

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform

/** All bridges share startup synchronization; local playback never starts Python. */
object PythonRuntime {
    fun module(context: Context,name: String): PyObject {
        synchronized(this) {if(!Python.isStarted()) {
            val started=android.os.SystemClock.elapsedRealtime()
            Python.start(AndroidPlatform(context.applicationContext))
            com.yonzmusic.app.playback.StreamTimings.record("engine_start",android.os.SystemClock.elapsedRealtime()-started)
        }}
        return Python.getInstance().getModule(name)
    }
}
