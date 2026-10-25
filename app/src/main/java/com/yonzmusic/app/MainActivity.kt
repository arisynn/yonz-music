package com.yonzmusic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yonzmusic.app.ui.YonzRoot

class MainActivity : ComponentActivity() {
    override fun onResume() { super.onResume(); (application as YonzApp).updates.apply { interaction(true); resume() } }
    override fun onPause() { (application as YonzApp).updates.interaction(false); super.onPause() }
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { val vm: MusicViewModel = viewModel(); YonzRoot(vm) }
    }
}
