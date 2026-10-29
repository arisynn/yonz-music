package com.yonzmusic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import com.yonzmusic.app.ui.YonzRoot

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.yonzmusic.app.i18n.BahasaLocale.wrap(base))
    }
    override fun onResume() { super.onResume(); (application as YonzApp).updates.apply { interaction(true); resume() } }
    override fun onPause() {(application as YonzApp).updates.interaction(false);super.onPause()}
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(this)[MusicViewModel::class.java]
        splash.setKeepOnScreenCondition { !vm.appearanceReady.value }
        enableEdgeToEdge()
        setContent { YonzRoot(vm) }
    }
}
