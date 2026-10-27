package com.yonzmusic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import com.yonzmusic.app.ui.YonzRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var musicViewModel: MusicViewModel? = null
    private fun receiveOAuth(incoming: android.content.Intent?) {
        val uri=incoming?.data ?: return
        if(uri.scheme==packageName && uri.host=="spotify" && uri.path=="/callback") {
            incoming.data=null
            val app=application as YonzApp
            app.persistenceScope.launch { app.spotify.callback(uri) }
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {super.onNewIntent(intent);receiveOAuth(intent)}
    override fun onResume() { super.onResume(); (application as YonzApp).updates.apply { interaction(true); resume() } }
    override fun onPause() {musicViewModel?.onlinePreview?.stop();(application as YonzApp).updates.interaction(false);super.onPause()}
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        receiveOAuth(intent)
        val vm = ViewModelProvider(this)[MusicViewModel::class.java]
        musicViewModel = vm
        splash.setKeepOnScreenCondition { !vm.appearanceReady.value }
        enableEdgeToEdge()
        setContent { YonzRoot(vm) }
    }
}
