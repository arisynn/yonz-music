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

@Composable internal fun MusicForYou(vm:MusicViewModel,onSearch:()->Unit) {
    val state by vm.forYou.state.collectAsStateWithLifecycle()
    val playback by vm.playback.state.collectAsStateWithLifecycle()
    Column(verticalArrangement=Arrangement.spacedBy(Design.md)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=Design.page),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Musik untukmu",style=MaterialTheme.typography.headlineMedium)
                Text(state.seed?.let {"Karena kamu memutar ${it.title}"} ?: "Mulai dari lagu yang kamu suka.",
                    maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(state.seed!=null)ActionIcon(Icons.Rounded.Refresh,"Perbarui rekomendasi",{vm.playback.refreshRecommendations()},enabled=playback.playing && state.seed?.let(vm::isCurrentOnline)==true && !state.loading)
        }
        if(state.seed==null)Column(Modifier.padding(horizontal=Design.page),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
            Text("Cari dan putar sebuah lagu. Pilihan berikutnya akan disiapkan dari musik yang kamu dengarkan.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onSearch) {Text("Cari lagu")}
        }
        if(state.loading)LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=Design.page))
        if(state.error.isNotBlank())Text(state.error,Modifier.padding(horizontal=Design.page),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(state.seed!=null && !state.loading && state.songs.isEmpty() && state.error.isBlank())
            Text("Pilihan musik akan disiapkan saat lagu mulai diputar.",Modifier.padding(horizontal=Design.page),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(state.songs.isNotEmpty())LazyRow(contentPadding=PaddingValues(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(Design.md)) {
            items(state.songs,key={it.id}) {song->ForYouCard(song,vm)}
        }
        if(state.recent.isNotEmpty()) {
            SectionTitle("Baru kamu putar")
            LazyRow(contentPadding=PaddingValues(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(Design.md)) {
                items(state.recent,key={it.id}) {song->ForYouCard(song,vm,120)}
            }
        }
    }
}
@Composable private fun ForYouCard(song:OnlineSong,vm:MusicViewModel,size:Int=152) {
    val playback by vm.playback.state.collectAsStateWithLifecycle()
    val current=playback.id==song.id || vm.isCurrentOnline(song)
    Column(Modifier.width(size.dp).clip(Design.small).clickable {vm.selectOnline(song)}.padding(bottom=Design.sm)) {
        SongThumbnail(song,Modifier.size(size.dp))
        Spacer(Modifier.height(Design.sm))
        Text(song.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium,
            color=if(current)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(song.credit,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
                SongThumbnail(song)
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
