package com.yonzmusic.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.BuildConfig
import com.yonzmusic.app.MusicViewModel
import com.yonzmusic.app.data.*
import com.yonzmusic.app.search.*
import com.yonzmusic.app.ui.icons.Tune

@Composable fun SearchScreen(vm: MusicViewModel,tracks: List<Track>,playlists: List<PlaylistSummary>,favorites: Set<String>,activeId: String?,
    onPlay: (List<Track>,String)->Unit,onMenu: (Track)->Unit,onPlaylist: (PlaylistSummary)->Unit,onDownload: (OnlineSong)->Unit) {
    val context=LocalContext.current
    val largeType=LocalDensity.current.fontScale>1.2f
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val onlineSelection by vm.searchOnline.collectAsStateWithLifecycle()
    val online=onlineSelection && BuildConfig.ONLINE_SEARCH_PREVIEW
    val provider by vm.searchProvider.collectAsStateWithLifecycle()
    val response by vm.onlineSearch.state.collectAsStateWithLifecycle()
    val state=response.takeIf {it.provider==provider && it.query==SearchContract.query(query)} ?: OnlineSearchState(query,provider,loading=query.trim().length>=3)
    val recent by vm.recentSearch.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var selected by remember {mutableStateOf<OnlineSong?>(null)}
    var configure by remember {mutableStateOf(false)}
    var providerMenu by remember {mutableStateOf(false)}
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    fun choose(song: OnlineSong) {focus.clearFocus();keyboard?.hide();selected=song}
    val latestQuery by rememberUpdatedState(query)
    val latestOnline by rememberUpdatedState(online)
    val latestProvider by rememberUpdatedState(provider)
    LaunchedEffect(query,online,provider) {
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if(online)vm.onlineSearch.query(query,provider)else vm.onlineSearch.cancel()
        }
    }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_STOP){vm.onlineSearch.cancel();vm.onlinePreview.stop()}
            else if(event==Lifecycle.Event.ON_START && latestOnline)vm.onlineSearch.query(latestQuery,latestProvider)
        }
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);vm.onlineSearch.cancel();vm.onlinePreview.stop()}
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query,{vm.searchQuery.value=it.take(200)},singleLine=true,
            modifier=Modifier.fillMaxWidth().padding(horizontal=Design.page,vertical=Design.sm),shape=Design.card,
            placeholder={Text(if(online)"Judul, artis, atau tautan…"else "Lagu, artis, album…",maxLines=1,overflow=TextOverflow.Ellipsis)},
            leadingIcon={Icon(Icons.Rounded.Search,null)},trailingIcon={if(query.isNotBlank())ActionIcon(Icons.Rounded.Close,"Hapus pencarian",{vm.searchQuery.value=""})})
        if(BuildConfig.ONLINE_SEARCH_PREVIEW) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=Design.gap),horizontalArrangement=Arrangement.spacedBy(Design.sm),verticalAlignment=Alignment.CenterVertically) {
                FilterChip(!online,{vm.searchOnline.value=false},label={Text("Koleksi")})
                FilterChip(online,{vm.searchOnline.value=true},label={Text("Online")})
                if(online) {
                    Box {
                        TextButton({providerMenu=true}){Text(provider.label+" ▾",maxLines=1)}
                        DropdownMenu(providerMenu,{providerMenu=false}) {
                            SearchProvider.entries.forEach {p->DropdownMenuItem(text={Text(p.label)},onClick={providerMenu=false;selected=null;vm.searchProvider.value=p})}
                        }
                    }
                    ActionIcon(Icons.Rounded.Tune,"Pengaturan pencarian",{configure=true})
                }
            }
        }
        if(!online)Box(Modifier.weight(1f)) {LocalSearchResults(query,tracks,playlists,favorites,activeId,onPlay,onMenu,onPlaylist)}
        else {
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=Design.page,vertical=Design.sm),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
                item {
                    Text(when(provider) {
                        SearchProvider.APPLE->"Temukan lagu di iTunes · cuplikan promosi dan tautan toko."
                        SearchProvider.YOUTUBE->"YouTube · pemutar resmi; nama kanal bukan selalu nama artis."
                        SearchProvider.COMMONS->"Audio berlisensi terbuka · periksa atribusi sumber."
                    },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(query.trim().length<3) {
                    if(query.isBlank() && recent.isNotEmpty())item {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            Text("Pencarian terakhir",style=MaterialTheme.typography.titleSmall)
                            TextButton({vm.clearSearchHistory()}){Text("Hapus")}
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
                            recent.forEach {value->SuggestionChip(onClick={vm.searchQuery.value=value},label={Text(value,maxLines=1)})}
                        }
                    }
                    item {EmptyState("Temukan musik di luar koleksimu","Ketik setidaknya 3 karakter. Hasil muncul otomatis; audio hanya diputar saat kamu memilihnya.","search")}
                }
                else {
                    if(state.loading)item {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Mencari…",style=MaterialTheme.typography.labelSmall)}
                    if(state.error.isNotBlank())item {
                        Text(state.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
                            TextButton({vm.onlineSearch.query(query,provider,true)}){Text("Coba lagi")}
                            if(state.needsKey)TextButton({configure=true}){Text("Atur API key")}
                        }
                    }
                    if(provider==SearchProvider.YOUTUBE && SearchContract.youtubeId(query)==null && (state.error.isNotBlank() || vm.searchSettings.key().isBlank()))item {
                        OutlinedButton({
                            vm.onlinePreview.stop();vm.playback.pause()
                            val url="https://www.youtube.com/results?search_query="+SearchContract.encoded(query)
                            runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}.onFailure {vm.notify("Tidak ada aplikasi untuk membuka YouTube.")}
                        },Modifier.fillMaxWidth()) {Text("Buka pencarian di YouTube")}
                        Text("Tautan video YouTube dapat dibuka di pemutar resmi tanpa API key. Pencarian di dalam aplikasi memerlukan key milikmu.",style=MaterialTheme.typography.bodySmall)
                    }
                    if(!state.loading && state.error.isBlank() && state.songs.isEmpty())item {EmptyState("Belum ada hasil","Coba judul atau nama artis yang lebih lengkap. Hasil bergantung pada katalog dan wilayah sumber.","search")}
                    items(state.songs,key={it.id}) { song->
                        Surface(shape=Design.small,color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.35f)) {
                            Column(Modifier.fillMaxWidth().padding(Design.md),verticalArrangement=Arrangement.spacedBy(Design.xs)) {
                                Row(Modifier.fillMaxWidth().clickable {choose(song)},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Design.md)) {
                                    SourceThumbnail(song.artwork)
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                                        Text(song.credit.ifBlank {"Kredit belum tersedia"},maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        val extra=listOfNotNull(song.album.takeIf {it.isNotBlank()},song.durationMs.takeIf {it>0}?.let(::time)).joinToString(" · ")
                                        if(extra.isNotBlank())Text(extra,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if(largeType)Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(Design.xs)) {
                                    TextButton({choose(song)}){Text(if(song.provider==SearchProvider.YOUTUBE)"Lihat video"else "Dengarkan")}
                                    if(song.provider==SearchProvider.APPLE)AppleStoreBadge(song.page){vm.onlinePreview.stop();vm.playback.pause()}
                                    else Text(song.license.ifBlank {song.provider.label},style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                                } else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                    TextButton({choose(song)},Modifier.weight(1f)){Text(if(song.provider==SearchProvider.YOUTUBE)"Lihat video"else "Dengarkan",maxLines=2)}
                                    if(song.provider==SearchProvider.APPLE)AppleStoreBadge(song.page){vm.onlinePreview.stop();vm.playback.pause()}
                                    else Text(song.license.ifBlank {song.provider.label},style=MaterialTheme.typography.labelSmall,modifier=Modifier.widthIn(max=120.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if(state.next.isNotBlank())item {OutlinedButton(vm.onlineSearch::more,enabled=!state.loading,modifier=Modifier.fillMaxWidth()){Text("Muat lainnya")}}
                    if(state.cached)item {Text("Hasil pencarian tersimpan sementara di perangkat.",style=MaterialTheme.typography.labelSmall)}
                }
            }
        }
    }
    selected?.let {song->OnlinePreviewSheet(song,vm,onDownload){selected=null}}
    if(configure)YoutubeSearchSettings(vm){configure=false}
}

@Composable private fun YoutubeSearchSettings(vm: MusicViewModel,close: ()->Unit) {
    var value by remember {mutableStateOf(vm.searchSettings.key())}
    var error by remember {mutableStateOf("")}
    var historyEnabled by remember {mutableStateOf(vm.searchSettings.remembersHistory())}
    AlertDialog(onDismissRequest=close,title={Text("Pengaturan pencarian")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
            Text("Aktifkan YouTube Data API v3 pada proyek Google Cloud milikmu. Gunakan API key yang dibatasi untuk API tersebut. Kuota mengikuti proyek Google.")
            OutlinedTextField(value,{value=it.trim().take(150)},label={Text("YouTube API key")},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),visualTransformation=PasswordVisualTransformation())
            Text("Key disimpan di ruang privat tanpa backup. Tidak dibundel, tidak ditulis ke URL/log, dan hanya dikirim ke API Google. Kosongkan untuk menghapusnya.",style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=Alignment.CenterVertically) {Text("Simpan riwayat di perangkat",Modifier.weight(1f));Switch(historyEnabled,{historyEnabled=it})}
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton({runCatching {vm.searchSettings.setKey(value);if(historyEnabled!=vm.searchSettings.remembersHistory())vm.clearSearchHistory(historyEnabled)}.onSuccess {vm.onlineSearch.query(vm.searchQuery.value,vm.searchProvider.value,true);close()}.onFailure {error="Key tidak valid atau belum tersimpan."}}){Text("Simpan")}},dismissButton={TextButton(close){Text("Batal")}})
}
