package com.yonzmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.MusicViewModel
import com.yonzmusic.app.data.*
import com.yonzmusic.app.search.OnlineSong
import com.yonzmusic.app.playback.StreamTimings

@Composable fun StreamingHome(vm: MusicViewModel, current: Track?, onPlayer: ()->Unit, onSearch: ()->Unit,
    onFavorites: ()->Unit, onHistory: ()->Unit, onLibrary: ()->Unit, onPlaylist: (OnlineCollection)->Unit,
    onDownload: (OnlineSong)->Unit, onConfigure: (OnlineSong)->Unit, scrollState: LazyListState) {
    val favorites by vm.onlineFavorites.collectAsStateWithLifecycle()
    val history by vm.onlineHistory.collectAsStateWithLifecycle()
    val playlists by vm.onlineCollections.collectAsStateWithLifecycle()
    val recommendations by vm.recommendations.collectAsStateWithLifecycle()
    val loading by vm.discoveryLoading.collectAsStateWithLifecycle()
    val error by vm.discoveryError.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val state by vm.playback.state.collectAsStateWithLifecycle()
    var selected by remember {mutableStateOf<OnlineSong?>(null)}
    val seed=(if(preferences.onlineHistory)history.firstOrNull() else null)?.videoId ?: favorites.firstOrNull()?.videoId.orEmpty()
    LaunchedEffect(seed) {vm.discover(seed)}
    DisposableEffect(Unit) {onDispose {vm.cancelDiscovery()}}
    LazyColumn(state=scrollState,contentPadding=PaddingValues(bottom=Design.page),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal=Design.page,vertical=Design.md),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
                Text("Dengarkan musikmu.",style=MaterialTheme.typography.headlineSmall)
                Text("Cari lagu, lalu langsung putar.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onSearch,modifier=Modifier.fillMaxWidth()) {Icon(Icons.Rounded.Search,null);Spacer(Modifier.width(Design.sm));Text("Cari lagu atau artis")}
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
                    AssistChip(onFavorites,label={Text("Favorit")},leadingIcon={Icon(Icons.Rounded.FavoriteBorder,null)})
                    AssistChip(onHistory,label={Text("Riwayat")})
                    AssistChip(onLibrary,label={Text("Musik perangkat")})
                }
            }
        }
        if(current!=null) item {
            SectionTitle("Lanjutkan mendengarkan")
            ListItem(headlineContent={Text(current.title,maxLines=1,overflow=TextOverflow.Ellipsis)},supportingContent={Text(if(state.buffering)"Menyiapkan audio…" else if(state.playing)"Sedang diputar" else "Buka pemutar",style=MaterialTheme.typography.bodySmall)},leadingContent={Artwork(current,Modifier.size(48.dp))},trailingContent={Icon(Icons.Rounded.PlayArrow,null)},modifier=Modifier.padding(horizontal=Design.sm).clip(Design.card).clickable(onClick=onPlayer))
        }
        if(history.isNotEmpty()) {
            item {SectionTitle("Terakhir diputar",action={TextButton(onHistory){Text("Semua")}})}
            items(history.take(3),key={"recent:"+it.id}) {song->OnlineSongRow(song,state.id,{vm.playOnline(song)},{selected=song})}
        }
        if(favorites.isNotEmpty()) {
            item {SectionTitle("Favoritmu",action={TextButton(onFavorites){Text("Semua")}})}
            item {OnlineShelf(favorites.take(8),{vm.playOnline(it)},{selected=it})}
        }
        if(seed.isNotBlank()) {
            item {SectionTitle("Untukmu","Dari musik yang kamu pilih",action={TextButton({vm.discover(seed,true)},enabled=!loading){Text("Segarkan")}})}
            if(loading && recommendations.isEmpty())item {LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=Design.page))}
            if(recommendations.isNotEmpty())item {OnlineShelf(recommendations.take(12),{vm.playOnline(it)},{selected=it})}
            if(error.isNotBlank())item {Text(error,Modifier.padding(horizontal=Design.page),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        if(playlists.isNotEmpty()) {
            item {SectionTitle("Daftar putarmu")}
            items(playlists.take(4),key={"playlist:"+it.id}) {p->ListItem(headlineContent={Text(p.name,maxLines=1,overflow=TextOverflow.Ellipsis)},supportingContent={Text("${p.songs.size} lagu · daring")},leadingContent={SourceThumbnail(p.songs.firstOrNull()?.artwork.orEmpty())},modifier=Modifier.clickable {onPlaylist(p)}.padding(horizontal=Design.sm))}
        }
        if(seed.isBlank())item {Text("Mulai dengan mencari lagu. Favorit dan musik yang kamu dengarkan akan membuat Beranda ini terasa milikmu.",Modifier.padding(Design.page),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    selected?.let {song->OnlineSongActions(vm,song,onDownload,onConfigure){selected=null}}
}

@Composable internal fun OnlineShelf(songs: List<OnlineSong>, play: (OnlineSong)->Unit, menu: (OnlineSong)->Unit) {
    LazyRow(contentPadding=PaddingValues(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(Design.md)) {
        items(songs,key={it.id}) {song->
            Column(Modifier.width(144.dp).clip(Design.small).clickable {play(song)},verticalArrangement=Arrangement.spacedBy(Design.xs)) {
                SourceThumbnail(song.artwork,Modifier.size(144.dp))
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(song.title,Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                    ActionIcon(Icons.Rounded.MoreVert,"Opsi ${song.title}",{menu(song)})
                }
                Text(song.credit,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable fun OnlineSongRow(song: OnlineSong, active: String?, play: ()->Unit, menu: ()->Unit) {
    ListItem(headlineContent={Text(song.title,maxLines=2,overflow=TextOverflow.Ellipsis,color=if(active==song.id)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)},supportingContent={Text(song.credit.ifBlank {"Kredit belum tersedia"},maxLines=1,overflow=TextOverflow.Ellipsis)},leadingContent={SourceThumbnail(song.artwork)},trailingContent={ActionIcon(Icons.Rounded.MoreVert,"Opsi ${song.title}",menu)},modifier=Modifier.clickable(onClickLabel="Putar ${song.title}",onClick=play).padding(horizontal=Design.sm))
}

@Composable fun OnlineSongActions(vm: MusicViewModel, song: OnlineSong, download: (OnlineSong)->Unit, configure: (OnlineSong)->Unit, close: ()->Unit) {
    var save by remember(song.id) {mutableStateOf(false)}
    if(save)OnlineSaveSongDialog(vm,song,close)
    else EngineResultSheet(song,vm,download,configure,{save=true},close)
}

@Composable fun ActivityScreen(vm: MusicViewModel, history: Boolean, local: List<Track>, favorites: Set<String>, active: String?,
    playLocal: (List<Track>,String)->Unit, menuLocal: (Track)->Unit, download: (OnlineSong)->Unit, configure: (OnlineSong)->Unit) {
    val onlineFavorites by vm.onlineFavorites.collectAsStateWithLifecycle()
    val onlineHistory by vm.onlineHistory.collectAsStateWithLifecycle()
    val songs=if(history)onlineHistory else onlineFavorites
    var selected by remember {mutableStateOf<OnlineSong?>(null)}
    LazyColumn(contentPadding=PaddingValues(bottom=Design.page)) {
        if(songs.isNotEmpty()) {
            item {SectionTitle("Daring","${songs.size} lagu")}
            items(songs,key={"online:"+it.id}) {s->OnlineSongRow(s,active,{vm.playOnline(s)},{selected=s})}
        }
        if(local.isNotEmpty()) {
            item {SectionTitle("Di perangkat","${local.size} lagu")}
            items(local,key={"local:"+it.id}) {t->TrackRow(t,t.id==active,t.id in favorites,{playLocal(local,t.id)},{menuLocal(t)})}
        }
        if(songs.isEmpty() && local.isEmpty())item {EmptyState(if(history)"Belum ada riwayat" else "Belum ada favorit",if(history)"Musik yang benar-benar kamu dengarkan akan tampil di sini." else "Ketuk hati pada lagu untuk menyimpannya di sini.")}
    }
    selected?.let {OnlineSongActions(vm,it,download,configure){selected=null}}
}

@Composable fun StreamingSettings(vm: MusicViewModel) {
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val bytes by vm.cacheBytes.collectAsStateWithLifecycle()
    var confirmClear by remember {mutableStateOf(false)}
    var diagnostics by remember {mutableStateOf(false)}
    LaunchedEffect(Unit) {vm.refreshCacheSize()}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
        SectionTitle("Streaming","Ringan, sesuai koneksimu")
        Column(Modifier.padding(horizontal=Design.page),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
            Text("Kualitas audio",style=MaterialTheme.typography.titleMedium)
            QualityChoices(preferences.streamQuality,vm::streamQuality)
            Text("Seimbang mengutamakan sekitar 128 kbps. Mengikuti kualitas yang tersedia; perubahan berlaku pada pemutaran berikutnya.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Cache audio sementara",style=MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
                listOf(0,32,64,100).forEach {size->FilterChip(preferences.streamCacheMb==size,{vm.streamCacheMb(size)},label={Text(if(size==0)"Nonaktif" else if(size==100)"100 MB · uji" else "$size MiB")})}
            }
            Text("${bytes/(1024*1024)} MiB terpakai. Cache lama dibuang otomatis. Gunakan Unduh untuk menyimpan permanen.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(vm::clearStreamCache) {Text("Bersihkan cache audio")}
        }
        ToggleSetting("Putar lagu terkait otomatis","Lanjutkan antrean daring saat habis. Antrean musik lokal tetap berhenti.",preferences.autoplay,vm::autoplay)
        ToggleSetting("Riwayat dengar daring","Disimpan di perangkat untuk Beranda personal. Mematikan ini menghentikan pencatatan baru.",preferences.onlineHistory,vm::onlineHistory)
        TextButton({confirmClear=true},Modifier.padding(horizontal=Design.page)) {Text("Hapus riwayat dengar daring")}
        TextButton({diagnostics=true},Modifier.padding(horizontal=Design.page)) {Text("Diagnostik pemutaran")}
    }
    if(confirmClear)AlertDialog(onDismissRequest={confirmClear=false},title={Text("Hapus riwayat daring?")},text={Text("Favorit, daftar putar, dan audio tetap disimpan.")},confirmButton={TextButton({vm.clearOnlineHistory();confirmClear=false}){Text("Hapus")}},dismissButton={TextButton({confirmClear=false}){Text("Batal")}})
    if(diagnostics) {
        val samples=remember {StreamTimings.snapshot()}
        AlertDialog(onDismissRequest={diagnostics=false},title={Text("Diagnostik sesi ini")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Waktu di perangkat, tanpa judul, URL, atau pengiriman data.",style=MaterialTheme.typography.bodySmall)
            if(samples.isEmpty())Text("Putar musik dahulu untuk melihat pengukuran.")
            samples.forEach {(key,values)->val sorted=values.sorted();Text("${when(key){"resolve"->"Persiapan sumber";"catalog"->"Katalog";"first_audio"->"Mulai putar";"buffering"->"Waktu buffer";else->key}}: median ${sorted[sorted.size/2]} ms · ${sorted.size} sampel",style=MaterialTheme.typography.bodySmall)}
        }},confirmButton={TextButton({diagnostics=false}){Text("Tutup")}})
    }
}

@Composable fun QualityChoices(selected: String, choose: (String)->Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
        listOf("economy" to "Hemat","balanced" to "Seimbang","best" to "Terbaik").forEach {(key,label)->FilterChip(selected==key,{choose(key)},label={Text(label)})}
    }
}
