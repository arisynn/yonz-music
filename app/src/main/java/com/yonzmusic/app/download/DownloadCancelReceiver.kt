package com.yonzmusic.app.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yonzmusic.app.YonzApp
import kotlinx.coroutines.launch

/** Explicit immutable notification PendingIntent; no exported command surface. */
class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val batch = intent.getStringExtra("batch")?.takeIf { it.length in 1..100 } ?: return
        val pending = goAsync()
        val app = context.applicationContext as YonzApp
        app.persistenceScope.launch { try { DownloadWorker.cancel(app, batch) } finally { pending.finish() } }
    }
}
