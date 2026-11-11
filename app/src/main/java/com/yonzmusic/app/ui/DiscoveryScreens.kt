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
import com.yonzmusic.app.ui.icons.ChevronRight
import com.yonzmusic.app.MusicViewModel
import com.yonzmusic.app.search.*

@Composable internal fun DiscoveryControls(vm:MusicViewModel) {
    val region by vm.discovery.region.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(horizontal=Design.page),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
        Text("Jelajahi",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
        FilterChip(region=="ID",{vm.discovery.selectRegion("ID")},label={Text("Indonesia")})
        FilterChip(region=="ZZ",{vm.discovery.selectRegion("ZZ")},label={Text("Global")})
    }
}
@Composable private fun ShelfHeading(title:String,state:DiscoveryShelf,refresh:()->Unit) {
    SectionTitle(title,action={ActionIcon(Icons.Rounded.Refresh,"Muat ulang $title",refresh,enabled=!state.loading)})
    Column(Modifier.padding(horizontal=Design.page),verticalArrangement=Arrangement.spacedBy(Design.xs)) {
        if(state.source.isNotBlank())Text(state.source,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(state.fetchedAt>0)Text("Diambil ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(state.fetchedAt))}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(state.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(state.error.isNotBlank())Text(state.error,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        else if(!state.loading && state.songs.isEmpty() && state.artists.isEmpty())Text("Belum tersedia untuk wilayah ini.",style=MaterialTheme.typography.bodySmall)
    }
}
@Composable internal fun PopularSongs(vm:MusicViewModel) {
    val region by vm.discovery.region.collectAsStateWithLifecycle()
    val shelf by vm.discovery.songs.collectAsStateWithLifecycle()
    val playback by vm.playback.state.collectAsStateWithLifecycle()
    LaunchedEffect(region) {vm.discovery.load("songs")}
    Column(verticalArrangement=Arrangement.spacedBy(Design.sm)) {
        ShelfHeading("Lagu populer",shelf) {vm.discovery.load("songs",true)}
        LazyRow(contentPadding=PaddingValues(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(Design.md)) {
            items(shelf.songs,key={it.id}) {song ->
                val current=playback.id==song.id || vm.isCurrentOnline(song)
                Column(Modifier.width(144.dp).clip(Design.small).clickable {vm.selectOnline(song)}.padding(bottom=Design.sm)) {
                    SourceThumbnail(song.artwork,Modifier.size(144.dp))
                    Spacer(Modifier.height(Design.sm))
                    Text(song.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium,color=if(current)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(song.credit,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
@Composable internal fun PopularArtists(vm:MusicViewModel,onArtist:(OnlineArtist)->Unit) {
    val region by vm.discovery.region.collectAsStateWithLifecycle()
    val shelf by vm.discovery.artists.collectAsStateWithLifecycle()
    LaunchedEffect(region) {vm.discovery.load("artists")}
    Column(verticalArrangement=Arrangement.spacedBy(Design.sm)) {
        ShelfHeading("Artis populer",shelf) {vm.discovery.load("artists",true)}
        LazyRow(contentPadding=PaddingValues(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(Design.md)) {
            items(shelf.artists,key={it.id}) {artist ->
                Column(Modifier.width(120.dp).clip(Design.small).clickable {onArtist(artist)}.padding(bottom=Design.sm)) {
                    SourceThumbnail(artist.artwork,Modifier.size(120.dp))
                    Spacer(Modifier.height(Design.sm))
                    Text(artist.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
@Composable internal fun OnlineArtistRow(artist:OnlineArtist,open:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=open).padding(vertical=Design.sm),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Design.md)) {
        SourceThumbnail(artist.artwork)
        Column(Modifier.weight(1f)) {Text(artist.name,style=MaterialTheme.typography.titleMedium);Text("Artis · lihat lagu",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Icon(Icons.Rounded.ChevronRight,null)
    }
}
@Composable internal fun OnlineArtistScreen(vm:MusicViewModel,onDownload:(OnlineSong)->Unit,onConfigure:(OnlineSong)->Unit,artistId:String,artistName:String) {
    val page by vm.artistPage.collectAsStateWithLifecycle()
    val playback by vm.playback.state.collectAsStateWithLifecycle()
    var selected by remember {mutableStateOf<OnlineSong?>(null)}
    var save by remember {mutableStateOf<OnlineSong?>(null)}
    LaunchedEffect(artistId) {vm.ensureArtist(OnlineArtist(artistId,artistName))}
    DisposableEffect(Unit) {onDispose {vm.closeArtist()}}
    LazyColumn(contentPadding=PaddingValues(horizontal=Design.page,vertical=Design.md),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
        item {
            page.artist?.let {artist ->OnlineArtistRow(artist) {}}
            Text("Lagu pilihan artis · YouTube Music",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(page.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(page.error.isNotBlank()) {
                Text(page.error,color=MaterialTheme.colorScheme.error)
                TextButton({page.artist?.let(vm::openArtist)}) {Text("Coba lagi")}
            }
            if(!page.loading && page.error.isBlank() && page.songs.isEmpty())Text("Daftar lagu artis belum tersedia.")
        }
        items(page.songs,key={it.id}) {song ->
            Row(Modifier.fillMaxWidth().clickable {vm.selectOnline(song)}.padding(vertical=Design.sm),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Design.md)) {
                SourceThumbnail(song.artwork)
                Column(Modifier.weight(1f)) {
                    Text(song.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium,color=if(playback.id==song.id || vm.isCurrentOnline(song))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(song.credit,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ActionIcon(Icons.Rounded.MoreVert,"Opsi ${song.title}",{selected=song})
            }
        }
    }
    selected?.let {song ->EngineResultSheet(song,vm,onDownload,onConfigure,{save=it;selected=null}) {selected=null}}
    save?.let {song ->OnlineSaveSongDialog(vm,song) {save=null}}
}
