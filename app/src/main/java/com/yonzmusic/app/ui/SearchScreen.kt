package com.yonzmusic.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.yonzmusic.app.ui.icons.Download
import com.yonzmusic.app.ui.icons.Pause

@Composable fun SearchScreen(vm: MusicViewModel,tracks: List<Track>,playlists: List<PlaylistSummary>,favorites: Set<String>,activeId: String?,
    onPlay: (List<Track>,String)->Unit,onMenu: (Track)->Unit,onPlaylist: (PlaylistSummary)->Unit,onDownload: (OnlineSong)->Unit) {
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val onlineSelection by vm.searchOnline.collectAsStateWithLifecycle()
    val online=onlineSelection && BuildConfig.ONLINE_SEARCH_PREVIEW
    val provider by vm.searchProvider.collectAsStateWithLifecycle()
    val response by vm.onlineSearch.state.collectAsStateWithLifecycle()
    val state=response.takeIf {it.provider==provider && it.query==SearchContract.query(query)} ?: OnlineSearchState(query,provider,loading=query.trim().length>=3)
    val recent by vm.recentSearch.collectAsStateWithLifecycle()
    val playback by vm.playback.state.collectAsStateWithLifecycle()
    val localSource=remember(playback.id,tracks){tracks.firstOrNull {it.id==playback.id}?.sourceIdentity}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var selected by remember {mutableStateOf<OnlineSong?>(null)}
    var configure by remember {mutableStateOf(false)}
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
            if(event==Lifecycle.Event.ON_STOP){vm.onlineSearch.cancel()}
            else if(event==Lifecycle.Event.ON_START && latestOnline)vm.onlineSearch.query(latestQuery,latestProvider)
        }
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);vm.onlineSearch.cancel()}
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

                    ActionIcon(Icons.Rounded.Tune,"Pengaturan pencarian",{focus.clearFocus();keyboard?.hide();configure=true})
                }
            }
        }
        if(!online)Box(Modifier.weight(1f)) {LocalSearchResults(query,tracks,playlists,favorites,activeId,onPlay,onMenu,onPlaylist)}
        else {
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=Design.page,vertical=Design.sm),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
                item {
                    Text("Hasil dari sumber online · pilih rekaman yang kamu maksud.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
                    item {EmptyState("Temukan musik di luar koleksimu","Ketik setidaknya 3 karakter. Hasil muncul otomatis. Pilih Putar untuk mendengarkan atau Unduh untuk menyimpan offline.","search")}
                }
                else {
                    if(state.loading)item {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Mencari…",style=MaterialTheme.typography.labelSmall)}
                    if(state.error.isNotBlank())item {
                        Text(state.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
                            TextButton({vm.onlineSearch.query(query,provider,true)}){Text("Coba lagi")}

                        }
                    }

                    if(!state.loading && state.error.isBlank() && state.songs.isEmpty())item {EmptyState("Belum ada hasil","Coba judul atau nama artis yang lebih lengkap. Hasil bergantung pada katalog dan wilayah sumber.","search")}
                    items(state.songs,key={it.id}) { song->
                        val current=playback.id==song.id || localSource=="youtube:${song.videoId}"
                        Surface(shape=Design.small,color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.35f)) {
                            Column(Modifier.fillMaxWidth().padding(Design.md),verticalArrangement=Arrangement.spacedBy(Design.xs)) {
                                Row(Modifier.fillMaxWidth().clickable {focus.clearFocus();keyboard?.hide();vm.playOnline(song)},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Design.md)) {
                                    SourceThumbnail(song.artwork)
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                                        Text(song.credit.takeIf {it.isNotBlank()}?.let {if(song.artistVerified)it else "Kanal: $it"} ?: "Kredit belum tersedia",maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        val extra=listOfNotNull(song.album.takeIf {it.isNotBlank()},song.durationMs.takeIf {it>0}?.let(::time)).joinToString(" · ")
                                        if(extra.isNotBlank())Text(extra,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                    Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                                        FilledTonalIconButton({if(current)vm.playback.toggle()else vm.playOnline(song)}) {Icon(if(current && playback.requested)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,if(current && playback.requested)"Jeda ${song.title}"else "Putar ${song.title}")}
                                        if(current)Text(if(playback.buffering)"Menyiapkan…"else if(playback.playing)"Sedang diputar"else "Dijeda",modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                    }
                                    Row {
                                        ActionIcon(Icons.Rounded.Download,"Unduh ${song.title}",{focus.clearFocus();keyboard?.hide();onDownload(song)})
                                        ActionIcon(Icons.Rounded.MoreVert,"Opsi ${song.title}",{choose(song)})
                                    }
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
    selected?.let {song->EngineResultSheet(song,vm,onDownload){selected=null}}
    if(configure)SearchHistorySettings(vm){configure=false}
}

@Composable private fun SearchHistorySettings(vm: MusicViewModel,close: ()->Unit) {
    var historyEnabled by remember {mutableStateOf(vm.searchSettings.remembersHistory())}
    AlertDialog(onDismissRequest=close,title={Text("Pengaturan pencarian")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
            Text("Pencarian menggunakan mesin yt-dlp tertanam. Tidak memerlukan API key Google. Ketersediaan hasil mengikuti akses sumber.")
            Row(verticalAlignment=Alignment.CenterVertically) {Text("Simpan riwayat di perangkat",Modifier.weight(1f));Switch(historyEnabled,{historyEnabled=it})}
            TextButton({vm.clearSearchHistory(historyEnabled)}){Text("Hapus riwayat")}
        }
    },confirmButton={TextButton({if(historyEnabled!=vm.searchSettings.remembersHistory())vm.clearSearchHistory(historyEnabled);close()}){Text("Simpan")}},dismissButton={TextButton(close){Text("Batal")}})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EngineResultSheet(song:OnlineSong,vm:MusicViewModel,onDownload:(OnlineSong)->Unit,close:()->Unit) {
    val context=LocalContext.current
    ModalBottomSheet(onDismissRequest=close,modifier=Modifier.statusBarsPadding(),sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        DialogSystemBars()
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(Design.page),verticalArrangement=Arrangement.spacedBy(Design.md)) {
            Text(song.title,style=MaterialTheme.typography.titleLarge)
            if(song.credit.isNotBlank())Text(if(song.artistVerified)song.credit else "Kanal: ${song.credit}")
            if(song.durationMs>0)Text(time(song.durationMs),style=MaterialTheme.typography.bodySmall)
            Text("Metadata dari sumber asli melalui yt-dlp. Nama kanal tidak selalu nama artis. Hasil pencarian tidak membuktikan izin mengunduh.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton({
                if(SearchContract.https(song.page,SearchContract.sourceHosts))runCatching {
                    vm.playback.pause();context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(song.page)))
                }.onFailure {vm.notify("Tidak ada aplikasi untuk membuka sumber.")}
            },Modifier.fillMaxWidth()){Text("Buka sumber untuk mendengarkan")}
            Button({vm.playOnline(song);close()},Modifier.fillMaxWidth()){Text("Putar di Yono")}
            Row(horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
                TextButton({vm.playOnline(song,true);close()}){Text("Putar berikutnya")}
                TextButton({vm.playOnline(song,false);close()}){Text("Tambah antrean")}
            }
            FilledTonalButton({onDownload(song);close()},Modifier.fillMaxWidth()){Text("Simpan offline") }
            Text("Unduhan tetap meminta tujuan dan otorisasi pada alur yang sudah ada. Format audio asli dipertahankan.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
