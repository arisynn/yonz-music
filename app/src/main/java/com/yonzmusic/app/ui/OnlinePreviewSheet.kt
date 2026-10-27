package com.yonzmusic.app.ui

import android.content.Intent
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.yonzmusic.app.BuildConfig
import com.yonzmusic.app.MusicViewModel
import com.yonzmusic.app.R
import com.yonzmusic.app.search.*
import kotlinx.coroutines.delay

@Composable internal fun AppleStoreBadge(page: String,beforeOpen: ()->Unit = {}) {
    val context=LocalContext.current
    // Apple's unmodified Small Badge is intended for titles in a list. Keep the
    // graphic at its original 79.25:16 ratio and the hit target at least 48dp.
    Box(Modifier.width(88.dp).height(48.dp).clickable {
        if(SearchContract.https(page,SearchContract.applePages))runCatching {beforeOpen();context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(page)))}
    },contentAlignment=Alignment.Center) {
        Image(painterResource(R.drawable.itunes_store_badge),"Buka lagu di iTunes Store",Modifier.size(79.25.dp,16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun OnlinePreviewSheet(song: OnlineSong,vm: MusicViewModel,onDownload: (OnlineSong)->Unit,close: () -> Unit) {
    val context=LocalContext.current
    val state by vm.onlinePreview.state.collectAsStateWithLifecycle()
    val local by vm.playback.state.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(song.id) {onDispose {vm.onlinePreview.stop()}}
    var youtubeStarted by remember(song.id) {mutableStateOf(false)}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_PAUSE){youtubeStarted=false;vm.onlinePreview.stop()}}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    var downloading by remember(song.id) {mutableStateOf(false)}
    var position by remember(song.id) {mutableLongStateOf(0)}
    var duration by remember(song.id) {mutableLongStateOf(0)}
    LaunchedEffect(song.id,state.playing,state.loading) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while(true) {position=vm.onlinePreview.position();duration=vm.onlinePreview.duration();delay(500)}
        }
    }
    fun openSource() {
        vm.onlinePreview.stop()
        val allowed=when(song.provider){SearchProvider.APPLE->SearchContract.applePages;SearchProvider.YOUTUBE->SearchContract.youtubePages;SearchProvider.COMMONS->setOf("commons.wikimedia.org")}
        if(SearchContract.https(song.page,allowed))runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(song.page)))}.onFailure {vm.notify("Tidak ada aplikasi untuk membuka sumber ini.")}
    }
    ModalBottomSheet(onDismissRequest=close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=620.dp).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal=Design.page),verticalArrangement=Arrangement.spacedBy(Design.md)) {
            Text(if(song.provider==SearchProvider.APPLE)"Cuplikan dari iTunes"else "Dengarkan dulu",style=MaterialTheme.typography.titleLarge)
            Text(song.title,style=MaterialTheme.typography.titleMedium)
            Text(song.credit + song.album.takeIf {it.isNotBlank()}?.let {" · $it"}.orEmpty(),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(song.provider==SearchProvider.YOUTUBE) {
                Text("Video diputar melalui pemutar resmi YouTube. Konten dan iklan mengikuti sumber.",style=MaterialTheme.typography.bodySmall)
                if(!youtubeStarted)Button({vm.onlinePreview.stop();vm.playback.pause();youtubeStarted=true}) {Text("Tampilkan pemutar YouTube")}
                else YouTubePreview(song.videoId,lifecycle,local.playing,{vm.playback.pause()})
            } else {
                if(song.provider==SearchProvider.APPLE) {
                    Text("Cuplikan promosi · provided courtesy of iTunes",style=MaterialTheme.typography.bodySmall)
                    AppleStoreBadge(song.page){vm.onlinePreview.stop();vm.playback.pause()}
                } else Text("${song.license} · cuplikan hingga 30 detik",style=MaterialTheme.typography.bodySmall)
                if(song.preview.isBlank())Text("Cuplikan tidak tersedia untuk rekaman ini.")
                else {
                    Button({if(state.id==song.id && state.playing)vm.onlinePreview.pause()else vm.onlinePreview.play(song)},enabled=!state.loading,modifier=Modifier.fillMaxWidth()) {
                        Text(when {state.id==song.id && state.loading->"Membuka cuplikan…";state.id==song.id && state.playing->"Jeda cuplikan";state.id==song.id->"Putar ulang cuplikan";else->"Putar cuplikan"})
                    }
                    if(state.id==song.id && state.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(state.id==song.id && duration>0) {
                        val fraction=ProgressFraction.of(position,duration) ?: 0f
                        LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth())
                        Text("${time(position)} / ${time(duration)}",style=MaterialTheme.typography.labelSmall)
                    }
                    if(state.id==song.id && state.error.isNotBlank())Text(state.error,color=MaterialTheme.colorScheme.error)
                }
            }
            if(song.provider==SearchProvider.COMMONS) {
                Text("Lisensi: ${song.license}. Periksa atribusi di halaman sumber sebelum menyimpan.",style=MaterialTheme.typography.bodySmall)
                FilledTonalButton({downloading=true;vm.onlinePreview.stop();onDownload(song);close()},enabled=!downloading,modifier=Modifier.fillMaxWidth()) {Text("Tinjau unduhan")}
            }
            if(song.provider!=SearchProvider.APPLE)OutlinedButton(::openSource,Modifier.fillMaxWidth()) {Text(if(song.provider==SearchProvider.YOUTUBE)"Buka di YouTube"else "Buka sumber & lisensi")}
            Text("Preview online tidak ditambahkan ke koleksi. Musik lokal tetap tersimpan di antreannya.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Design.md))
        }
    }
}

/** Official visible iframe. No extraction, JS/native bridge, sign-in, hidden video or background audio. */
@Composable private fun YouTubePreview(id: String,lifecycle: Lifecycle,localPlaying: Boolean,pauseLocal: () -> Unit) {
    val context=LocalContext.current
    var error by remember(id) {mutableStateOf("")}
    var ready by remember(id) {mutableStateOf(false)}
    LaunchedEffect(id) {delay(18000);if(!ready)error="Pemutar belum siap. Kamu dapat membuka video langsung di YouTube."}
    val view=remember(id) {
        require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
        runCatching {WebView(context).apply {
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true
            settings.allowFileAccess=false;settings.allowContentAccess=false
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture=true;settings.cacheMode=WebSettings.LOAD_NO_CACHE
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
            webViewClient=object: WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                    if(!request.isForMainFrame)return false
                    if(request.url.host=="www.youtube-nocookie.com")return false
                    if(request.hasGesture() && request.url.scheme=="https" && request.url.userInfo==null)runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,request.url))}
                    return true
                }
                override fun onReceivedError(view: WebView,request: WebResourceRequest,e: WebResourceError) {if(request.isForMainFrame)error="Pemutar belum tersedia. Gunakan Buka di YouTube."}
            }
            webChromeClient=object:WebChromeClient() {
                override fun onReceivedTitle(view:WebView,title:String?) {
                    if(title=="YonoPlayer:ready" || title=="YonoPlayer:playing"){ready=true;error=""}
                    else if(title?.startsWith("YonoPlayer:error:")==true) {
                        val code=title.substringAfterLast(':').toIntOrNull()
                        error=when(code){100->"Video tidak tersedia atau privat.";101,150->"Pemilik video membatasi pemutaran di aplikasi lain.";153->"Identitas pemutar belum diterima YouTube pada perangkat ini.";else->"Video belum dapat diputar melalui pemutar tertanam."}+" Gunakan Buka di YouTube."
                    }
                }
            }
            val origin="https://${BuildConfig.APPLICATION_ID}"
            loadDataWithBaseURL(origin,"""<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>html,body{margin:0;height:100%;background:#000}iframe,#player{width:100%;height:100%;border:0}</style></head><body><div id="player"></div><script src="https://www.youtube.com/iframe_api"></script><script>function onYouTubeIframeAPIReady(){new YT.Player('player',{host:'https://www.youtube-nocookie.com',videoId:'$id',playerVars:{playsinline:1,controls:1,fs:0,origin:'$origin'},events:{onReady:function(){document.title='YonoPlayer:ready'},onStateChange:function(e){if(e.data===1)document.title='YonoPlayer:playing'},onError:function(e){document.title='YonoPlayer:error:'+e.data}}})}</script></body></html>""","text/html","UTF-8",null)
            setOnTouchListener {_,_->pauseLocal();false}
        }}.getOrNull()
    }
    if(view==null) {Text("Pemutar tertanam membutuhkan Android System WebView yang aktif. Gunakan Buka di YouTube.");return}
    fun pause() {view.evaluateJavascript("document.querySelector('iframe').contentWindow.postMessage(JSON.stringify({event:'command',func:'pauseVideo',args:[]}), 'https://www.youtube-nocookie.com');",null)}
    LaunchedEffect(localPlaying) {if(localPlaying)pause()}
    DisposableEffect(view,lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_PAUSE){pause();view.onPause()}else if(event==Lifecycle.Event.ON_RESUME)view.onResume()}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);view.stopLoading();view.loadUrl("about:blank");view.onPause();view.removeAllViews();view.destroy()}
    }
    LaunchedEffect(error) {if(error.isNotBlank()){pause();view.stopLoading()}}
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    else AndroidView(factory={view},modifier=Modifier.fillMaxWidth().height(220.dp).clipToBounds())
    Text("Jika video dibatasi, memerlukan login, atau gagal dimuat, buka langsung di YouTube.",style=MaterialTheme.typography.bodySmall)
}
