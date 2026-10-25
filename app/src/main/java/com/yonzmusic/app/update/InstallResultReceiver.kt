package com.yonzmusic.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yonzmusic.app.YonzApp
import kotlinx.coroutines.launch

/** Non-exported; only our explicit nonce-bearing PackageInstaller PendingIntent can call this. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { (context.applicationContext as YonzApp).updates.installerResult(intent) }
}

/** Android sends this action only for this installed package's successful replacement. */
class ReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val app = context.applicationContext as YonzApp
        app.persistenceScope.launch { try { app.updates.reconcileInstalled() } finally { pending.finish() } }
    }
}
