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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.yonzmusic.app.ui.icons.QueueMusic
import com.yonzmusic.app.ui.icons.SkipNext

@Composable fun SearchScreen(vm: MusicViewModel,tracks: List<Track>,playlists: List<PlaylistSummary>,favorites: Set<String>,activeId: String?,
    onPlay: (List<Track>,String)->Unit,onMenu: (Track)->Unit,onPlaylist: (PlaylistSummary)->Unit,onDownload: (OnlineSong)->Unit,onDownloadSettings:(OnlineSong)->Unit) {
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
                    item {EmptyState("Temukan musikmu","Ketik judul atau artis. Ketuk hasil untuk mendengarkan.","search")}
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
                        Row(Modifier.fillMaxWidth().clickable(onClickLabel=if(current && playback.requested)"Jeda ${song.title}"else "Putar ${song.title}") {
                            focus.clearFocus();keyboard?.hide()
                            if(current)vm.playback.toggle()else vm.playOnline(song)
                        }.padding(vertical=Design.sm),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Design.md)) {
                                    SourceThumbnail(song.artwork)
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium,color=if(current)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        Text(song.credit.takeIf {it.isNotBlank()}?.let {if(song.artistVerified)it else "Kanal: $it"} ?: "Kredit belum tersedia",maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        val extra=if(current) {if(playback.buffering)"Menyiapkan…"else if(playback.playing)"Sedang diputar"else "Dijeda"} else song.durationMs.takeIf {it>0}?.let(::time).orEmpty()
                                        if(extra.isNotBlank())Text(extra,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                                    }
                                        ActionIcon(Icons.Rounded.MoreVert,"Opsi ${song.title}",{choose(song)})
                        }
                    }
                    if(state.next.isNotBlank())item {OutlinedButton(vm.onlineSearch::more,enabled=!state.loading,modifier=Modifier.fillMaxWidth()){Text("Muat lainnya")}}
                    if(state.cached)item {Text("Hasil pencarian tersimpan sementara di perangkat.",style=MaterialTheme.typography.labelSmall)}
                }
            }
        }
    }
    selected?.let {song->EngineResultSheet(song,vm,onDownload,onDownloadSettings){selected=null}}
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
@Composable private fun EngineResultSheet(song:OnlineSong,vm:MusicViewModel,onDownload:(OnlineSong)->Unit,onDownloadSettings:(OnlineSong)->Unit,close:()->Unit) {
    val context=LocalContext.current
    ModalBottomSheet(onDismissRequest=close,modifier=Modifier.statusBarsPadding(),sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        DialogSystemBars()
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(Design.page),verticalArrangement=Arrangement.spacedBy(Design.md)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Design.md)) {
                SourceThumbnail(song.artwork)
                Column(Modifier.weight(1f)) {
                    Text(song.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    val credit=song.credit.takeIf {it.isNotBlank()}?.let {if(song.artistVerified)it else "Kanal: $it"}
                    Text(listOfNotNull(credit,song.durationMs.takeIf {it>0}?.let(::time)).joinToString(" · "),style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
                OnlineResultAction("Berikut","Putar berikutnya",Icons.Rounded.SkipNext,Modifier.weight(1f)){vm.playOnline(song,true);close()}
                OnlineResultAction("Antrean","Tambah antrean",Icons.Rounded.QueueMusic,Modifier.weight(1f)){vm.playOnline(song,false);close()}
                OnlineResultAction("Unduh","Simpan offline",Icons.Rounded.Download,Modifier.weight(1f)){onDownload(song);close()}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
            TextButton({
                if(SearchContract.https(song.page,SearchContract.sourceHosts))runCatching {
                    vm.playback.pause();context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(song.page)))
                }.onFailure {vm.notify("Tidak ada aplikasi untuk membuka sumber.")}
            },Modifier.weight(1f)){Text("Sumber")}
            TextButton({onDownloadSettings(song);close()},Modifier.weight(1f)){Text("Atur")}
            }
        }
    }
}

@Composable private fun OnlineResultAction(label:String,description:String,icon:ImageVector,modifier:Modifier=Modifier,action:()->Unit) {
    FilledTonalButton(onClick=action,modifier=modifier.heightIn(min=80.dp),shape=Design.small,contentPadding=PaddingValues(horizontal=Design.xs,vertical=Design.md),colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f))) {
        Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(Design.sm)) {
            Icon(icon,description,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.primary)
            Text(label,style=MaterialTheme.typography.labelMedium,textAlign=TextAlign.Center,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
