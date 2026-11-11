package com.yonzmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import com.yonzmusic.app.ui.icons.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.MusicViewModel
import com.yonzmusic.app.data.*
import com.yonzmusic.app.search.*
import kotlinx.coroutines.*

@Composable private fun CatalogRow(item:CatalogItem,open:()->Unit) {
    ListItem(headlineContent={Text(item.title,maxLines=2,overflow=TextOverflow.Ellipsis)},supportingContent={Text(item.credit.ifBlank {when(item.kind){"artist"->"Artis";"album"->"Album";"radio"->"Radio dinamis";else->"Playlist"}},maxLines=1,overflow=TextOverflow.Ellipsis)},leadingContent={SourceThumbnail(item.artwork)},trailingContent={Icon(Icons.Rounded.ChevronRight,null)},modifier=Modifier.clickable(onClick=open).padding(horizontal=Design.sm))
}
@Composable private fun DiscoveryNotice(error:String,loading:Boolean,retry:()->Unit) {
    if(loading)LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=Design.page))
    if(error.isNotBlank())Column(Modifier.padding(Design.page)) {Text(error,style=MaterialTheme.typography.bodyMedium);TextButton(retry,enabled=!loading){Text("Coba lagi")}}
}

@Composable fun DiscoveryHome(vm:MusicViewModel,open:(CatalogItem)->Unit,onHistory:()->Unit,onDownload:(OnlineSong)->Unit,onConfigure:(OnlineSong)->Unit,scroll:LazyListState) {
    var country by remember {mutableStateOf(vm.discovery.country)}
    var page by remember {mutableStateOf(DiscoveryPage())};var loading by remember {mutableStateOf(false)};var error by remember {mutableStateOf("")};var refresh by remember {mutableIntStateOf(0)}
    var selected by remember {mutableStateOf<OnlineSong?>(null)}
    val history by vm.onlineHistory.collectAsStateWithLifecycle();val playback by vm.playback.state.collectAsStateWithLifecycle()
    LaunchedEffect(country,refresh) {loading=true;error="";try {page=vm.discovery.charts(country,refresh>0);if(page.songs.isEmpty())error="Chart musik belum tersedia untuk wilayah ini."
            launch {for(song in page.songs.take(10)) {try {val enriched=vm.discovery.enrich(song);page=page.copy(songs=page.songs.map {if(it.id==song.id)enriched else it})}catch(e:CancellationException){throw e}catch(_:Exception){break}}}}catch(e:CancellationException){throw e}catch(_:Exception){error="Chart belum tersedia. Periksa koneksi lalu coba lagi."}finally {loading=false}}
    LazyColumn(state=scroll,contentPadding=PaddingValues(bottom=Design.page),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
        item {Row(Modifier.fillMaxWidth().padding(horizontal=Design.page),verticalAlignment=Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton({country=if(country=="ID")"ZZ" else "ID";vm.discovery.country=country;page=DiscoveryPage()}) {Text(if(country=="ID")"Indonesia ▾" else "Global ▾")}
        }}
        item {SectionTitle("Musik trending","Chart YouTube Music",action={IconButton({refresh++},enabled=!loading){Icon(Icons.Rounded.Refresh,"Segarkan chart")}})}
        item {DiscoveryNotice(error,loading){refresh++}}
        if(page.cached)item {Text("Chart tersimpan · diperbarui saat tersedia",Modifier.padding(horizontal=Design.page),style=MaterialTheme.typography.labelSmall)}
        if(page.songs.isNotEmpty())item {OnlineShelf(page.songs,{vm.playOnline(it)},{selected=it})}
        if(page.items.isNotEmpty()) {
            item {SectionTitle("Artis populer","Dari chart wilayah ini")}
            item {LazyRow(contentPadding=PaddingValues(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(Design.md)) {
                items(page.items,key={it.id}){item->Column(Modifier.width(128.dp).clickable{open(item)},verticalArrangement=Arrangement.spacedBy(Design.sm)) {
                    SourceThumbnail(item.artwork,Modifier.size(128.dp));Text(item.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                }}
            }}
        }
        if(history.isNotEmpty()) {item{SectionTitle("Terakhir diputar",action={TextButton(onHistory){Text("Semua")}})};items(history.take(4),key={"recent:${it.id}"}){s->OnlineSongRow(s,playback.id,{vm.playOnline(s)},{selected=s})}}
    }
    selected?.let {OnlineSongActions(vm,it,onDownload,onConfigure){selected=null}}
}

@Composable fun DiscoverySearch(vm:MusicViewModel,open:(CatalogItem)->Unit,download:(OnlineSong)->Unit,configure:(OnlineSong)->Unit) {
    var query by rememberSaveable {mutableStateOf("")};var kind by rememberSaveable {mutableStateOf("song")}
    var page by remember {mutableStateOf(DiscoveryPage())};var error by remember {mutableStateOf("")};var loading by remember {mutableStateOf(false)};var retry by remember {mutableIntStateOf(0)}
    var selected by remember {mutableStateOf<OnlineSong?>(null)}
    val playback by vm.playback.state.collectAsStateWithLifecycle();val recent by vm.recentSearch.collectAsStateWithLifecycle()
    LaunchedEffect(query,kind,retry) {
        page=DiscoveryPage();error="";loading=false
        if(query.trim().length<2)return@LaunchedEffect
        delay(600);loading=true
        try {
            page=if(query.contains("://"))DiscoveryPage(songs=vmAppCatalog(vm,query)) else vm.discovery.search(query,kind)
            if(kind=="song" && page.songs.isEmpty() && page.items.isEmpty())page=DiscoveryPage(songs=vmAppCatalog(vm,query))
            vm.searchSettings.rememberQuery(query);vm.recentSearch.value=vm.searchSettings.history()
        }catch(e:CancellationException){throw e}catch(_:Exception){
            if(kind=="song")try {page=DiscoveryPage(songs=vmAppCatalog(vm,query));error="Metadata katalog belum tersedia. Menampilkan informasi dari sumber audio."}catch(e:CancellationException){throw e}catch(_:Exception){error="Pencarian belum tersedia. Periksa koneksi lalu coba lagi."}
            else error="Pencarian belum tersedia. Periksa koneksi lalu coba lagi."
        }finally {loading=false}
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query,{query=it.take(200)},placeholder={Text("Lagu, album, artis…",maxLines=1,overflow=TextOverflow.Ellipsis)},leadingIcon={Icon(Icons.Rounded.Search,null)},trailingIcon={if(query.isNotBlank())IconButton({query=""}){Icon(Icons.Rounded.Close,"Hapus pencarian")}},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=Design.page),shape=Design.card)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
            listOf("song" to "Lagu","album" to "Album","artist" to "Artis","playlist" to "Playlist").forEach {(key,label)->FilterChip(kind==key,{kind=key},label={Text(label)})}
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=Design.page)) {
            item {DiscoveryNotice(error,loading){retry++}}
            if(query.trim().length<2) {
                item {SectionTitle("Pencarian terakhir",action={TextButton({vm.clearSearchHistory()}){Text("Hapus")}})}
                items(recent){value->ListItem(headlineContent={Text(value)},leadingContent={Icon(Icons.Rounded.History,null)},modifier=Modifier.clickable {query=value})}
                item {Text("Temukan album dan katalog artis favoritmu. Metadata dari MusicBrainz; audio dicocokkan saat diputar.",Modifier.padding(Design.page),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            } else {
                items(page.songs,key={it.id}){s->OnlineSongRow(s,playback.id,{vm.playOnline(s)},{selected=s})}
                items(page.items.filter {it.kind!="song"},key={it.id}){item->CatalogRow(item){open(item)}}
                if(!loading && error.isBlank() && page.items.isEmpty() && page.songs.isEmpty())item {EmptyState("Belum ada hasil","Coba judul atau nama artis yang lebih lengkap.")}
            }
        }
    }
    selected?.let {OnlineSongActions(vm,it,download,configure){selected=null}}
}
private suspend fun vmAppCatalog(vm:MusicViewModel,query:String):List<OnlineSong> = vm.catalogSearch(query)

@Composable fun CollectionHub(vm:MusicViewModel,offline:()->Unit,favorites:()->Unit,history:()->Unit,localPlaylist:(PlaylistSummary)->Unit,onlinePlaylist:(OnlineCollection)->Unit,open:(CatalogItem)->Unit,create:()->Unit,import:()->Unit,link:()->Unit) {
    val saved by vm.savedCatalog.collectAsStateWithLifecycle();val playlists by vm.playlists.collectAsStateWithLifecycle();val online by vm.onlineCollections.collectAsStateWithLifecycle()
    val localFav by vm.favorites.collectAsStateWithLifecycle();val onlineFav by vm.onlineFavorites.collectAsStateWithLifecycle();val tracks by vm.collectionTracks.collectAsStateWithLifecycle()
    var filter by rememberSaveable {mutableStateOf("Semua")};var add by remember {mutableStateOf(false)}
    LazyColumn(contentPadding=PaddingValues(bottom=Design.page),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
        item {Row(Modifier.fillMaxWidth().padding(horizontal=Design.page),verticalAlignment=Alignment.CenterVertically) {Spacer(Modifier.weight(1f));FilledTonalButton({add=true}){Icon(Icons.Rounded.Add,null);Spacer(Modifier.width(4.dp));Text("Tambah")}}}
        item {Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(Design.sm)) {listOf("Semua","Playlist","Album","Artis","Radio").forEach {name->FilterChip(filter==name,{filter=name},label={Text(name)})}}}
        if(filter=="Semua") {
            item {Card(onClick=favorites,modifier=Modifier.fillMaxWidth().padding(horizontal=Design.page),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {ListItem(headlineContent={Text("Favorit",style=MaterialTheme.typography.titleLarge)},supportingContent={Text("${localFav.size+onlineFav.size} lagu pilihanmu")},leadingContent={Icon(Icons.Rounded.Favorite,null)},colors=ListItemDefaults.colors(containerColor=MaterialTheme.colorScheme.secondaryContainer))}}
            item {ListItem(headlineContent={Text("Offline")},supportingContent={Text("${tracks.count {it.available}} lagu tersedia · unduhan & musik perangkat")},leadingContent={Icon(Icons.Rounded.Download,null)},modifier=Modifier.clickable(onClick=offline).padding(horizontal=Design.sm))}
            item {ListItem(headlineContent={Text("Riwayat dengar")},leadingContent={Icon(Icons.Rounded.History,null)},modifier=Modifier.clickable(onClick=history).padding(horizontal=Design.sm))}
        }
        if(filter in listOf("Semua","Playlist")) {
            if(online.isNotEmpty() || playlists.isNotEmpty())item {SectionTitle("Playlist")}
            items(online,key={"online:${it.id}"}){p->ListItem(headlineContent={Text(p.name)},supportingContent={Text("${p.songs.size} lagu · streaming")},leadingContent={Icon(Icons.Rounded.QueueMusic,null)},modifier=Modifier.clickable {onlinePlaylist(p)}.padding(horizontal=Design.sm))}
            items(playlists,key={"local:${it.id}"}){p->ListItem(headlineContent={Text(p.name)},supportingContent={Text("${p.count} lagu · perangkat")},leadingContent={Icon(Icons.Rounded.QueueMusic,null)},modifier=Modifier.clickable {localPlaylist(p)}.padding(horizontal=Design.sm))}
        }
        val selected=saved.filter {filter=="Semua" || it.kind==mapOf("Album" to "album","Artis" to "artist","Radio" to "radio","Playlist" to "playlist")[filter]}
        if(selected.isNotEmpty())item {SectionTitle(if(filter=="Semua")"Disimpan & diikuti" else filter)}
        items(selected,key={it.id}){item->CatalogRow(item){open(item)}}
        if(selected.isEmpty() && filter !in listOf("Semua","Playlist"))item {EmptyState("Belum ada ${filter.lowercase()}","Simpan dari hasil pencarian atau halaman artis.")}
    }
    if(add)AlertDialog(onDismissRequest={add=false},title={Text("Tambahkan ke Koleksi")},text={Column {TextButton({add=false;create()}){Text("Buat playlist")};TextButton({add=false;link()}){Text("Impor tautan playlist")};TextButton({add=false;import()}){Text("Tambahkan musik perangkat")}}},confirmButton={TextButton({add=false}){Text("Selesai")}})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CatalogDetail(vm:MusicViewModel,item:CatalogItem,open:(CatalogItem)->Unit,back:()->Unit,download:(OnlineSong)->Unit,configure:(OnlineSong)->Unit) {
    var page by remember(item.id){mutableStateOf(DiscoveryPage())};var loading by remember(item.id){mutableStateOf(false)};var error by remember(item.id){mutableStateOf("")};var refresh by remember(item.id){mutableIntStateOf(0)}
    var selected by remember(item.id){mutableStateOf<OnlineSong?>(null)};val saved by vm.savedCatalog.collectAsStateWithLifecycle();val state by vm.playback.state.collectAsStateWithLifecycle()
    val isSaved=saved.any {it.id==item.id}
    LaunchedEffect(item.id,refresh) {loading=true;error="";try{page=vm.discovery.detail(item,refresh>0)}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?.take(180) ?: "Katalog belum tersedia."}finally{loading=false}}
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title={Text(item.title,maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(back){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Kembali")}})
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=Design.page)) {
            item {Column(Modifier.padding(Design.page),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
                if(item.artwork.isNotBlank())SourceThumbnail(item.artwork,Modifier.size(160.dp).align(Alignment.CenterHorizontally))
                Text(item.title,style=MaterialTheme.typography.headlineSmall);if(item.credit.isNotBlank())Text(item.credit,style=MaterialTheme.typography.bodyMedium)
                Text(if(item.kind=="radio")"Radio dinamis · unduhan menyimpan daftar saat ini" else "${item.provider} · ${page.songs.size} lagu",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
                    Button({vm.playSongs(page.songs)},enabled=page.songs.isNotEmpty()){Icon(Icons.Rounded.PlayArrow,null);Text("Putar")}
                    FilledTonalButton({if(isSaved)vm.removeCatalog(item)else vm.saveCatalog(item)}){Text(if(isSaved)"Tersimpan ✓" else if(item.kind=="artist")"Ikuti" else "Simpan")}
                    OutlinedButton({vm.downloadSongs(page.songs){page.songs.firstOrNull()?.let(configure)}},enabled=page.songs.isNotEmpty()){Text("Unduh Opus")}
                }
                if(item.kind=="artist")TextButton({page.songs.firstOrNull()?.let {vm.saveRadioFor(it)}},enabled=page.songs.isNotEmpty()){Text("Simpan radio artis")}
                if(item.kind=="radio")TextButton({refresh++},enabled=!loading){Text("Segarkan radio")}
            }}
            item {DiscoveryNotice(error,loading){refresh++}}
            if(page.items.isNotEmpty())item {SectionTitle("Album")}
            items(page.items,key={it.id}){album->CatalogRow(album){open(album)}}
            if(page.songs.isNotEmpty())item {SectionTitle(if(item.kind=="album")"Daftar lagu" else "Lagu")}
            itemsIndexed(page.songs,key={index,s->"$index:${s.id}"}){index,s->OnlineSongRow(s,state.id,{vm.playSongs(page.songs,index)},{selected=s})}
        }
    }
    selected?.let {OnlineSongActions(vm,it,download,configure){selected=null}}
}
