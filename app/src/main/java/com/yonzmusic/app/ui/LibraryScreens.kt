package com.yonzmusic.app.ui

import com.yonzmusic.app.i18n.Bahasa

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import com.yonzmusic.app.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yonzmusic.app.data.*
import com.yonzmusic.app.importer.MusicListParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable fun HomeScreen(
    tracks: List<Track>, favorites: Set<String>, history: List<History>, current: Track?, activeId: String?,
    onPlay: (Pair<List<Track>, String>) -> Unit, onMenu: (Track) -> Unit, onFavorites: () -> Unit, onHistory: () -> Unit,
    onAlbum: (String, String) -> Unit, onImport: () -> Unit, onLibrary: () -> Unit, onContinue: () -> Unit = {},
    scrollState: LazyListState = rememberLazyListState(), mixes: List<SmartMix.Mix> = emptyList()
) {
    val recent=remember(tracks,history) { val map=tracks.filter { it.available }.associateBy { it.id };history.mapNotNull { map[it.trackId] }.take(2) }
    val added=remember(tracks) { tracks.filter { it.available }.sortedByDescending { it.added }.take(6) }
    val albums=remember(tracks) { tracks.filter { it.available && MetadataPolicy.known(it.album).isNotBlank() }.distinctBy(::albumKey).take(5) }
    var mix by remember { mutableStateOf<SmartMix.Mix?>(null) }
    LazyColumn(state=scrollState,contentPadding=PaddingValues(bottom=Design.page)) {
        item { HomeIdentity(tracks) }
        if(tracks.isEmpty()) item { EmptyState("Mulai dari musikmu", "Pilih berkas atau folder. Koleksi dan pemutaran tetap berfungsi tanpa internet.",action={ Button(onImport) { Text("Pilih musik") } }) }
        if(recent.isNotEmpty()) {
            item { SectionTitle("Terakhir diputar",action={ TextButton(onHistory) { Text("Semua") } }) }
            items(recent,key={ "recent_${it.id}" }) { t -> TrackRow(t,t.id==activeId,t.id in favorites,{onPlay(recent to t.id)},{onMenu(t)}) }
        }
        if(mixes.isNotEmpty()) {
            item { SectionTitle("Campuran pintar","Dari pemutaran lokal yang bermakna") }
            items(mixes,key={ "mix_${it.key}" }) { m ->
                ListItem(headlineContent={ Text(m.title) },supportingContent={ Text("${m.tracks.size} lagu · ketuk untuk aturan dan daftar",maxLines=2) },
                    leadingContent={ Artwork(m.tracks.firstOrNull(),Modifier.size(48.dp)) },modifier=Modifier.clickable { mix=m }.padding(horizontal=Design.md))
            }
        }
        if(added.isNotEmpty()) {
            item { SectionTitle("Baru ditambahkan") }
            item { LazyRow(contentPadding=PaddingValues(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(Design.md)) {
                items(added,key={it.id}) { t -> ArtworkCard(t,t.title,t.artist) { onPlay(added to t.id) } }
            } }
        }
        if(albums.isNotEmpty()) {
            item { SectionTitle("Album pilihan") }
            item { LazyRow(contentPadding=PaddingValues(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(Design.md)) {
                items(albums,key=::albumKey) { t -> ArtworkCard(t,t.album,t.artist) { onAlbum(albumKey(t),t.album) } }
            } }
        }
    }
    mix?.let { m -> AlertDialog(onDismissRequest={mix=null},title={Text(m.title)},text={ LazyColumn(Modifier.heightIn(max=400.dp)) {
        item { Text(m.reason,style=MaterialTheme.typography.bodySmall) }
        item { Text("Pemutaran bermakna: 30 detik atau setengah durasi (minimal 5 detik, maksimal durasi lagu). Menggeser posisi dan menjeda tidak menambah waktu dengar.",style=MaterialTheme.typography.bodySmall) }
        items(m.tracks,key={it.id}) { t -> ListItem(headlineContent={Text(t.title,maxLines=1,overflow=TextOverflow.Ellipsis)},supportingContent={Text(Bahasa.artis(t.artist),maxLines=1)},modifier=Modifier.clickable { onPlay(m.tracks to t.id);mix=null }) }
    } },confirmButton={TextButton({onPlay(m.tracks to m.tracks.first().id);mix=null}){Text("Putar")}},dismissButton={TextButton({mix=null}){Text("Tutup")}}) }
}
@Composable private fun ArtworkCard(track: Track, title: String, subtitle: String, action: () -> Unit) {
    Column(Modifier.width(144.dp).clip(Design.small).clickable(onClick = action).padding(bottom = Design.sm)) {
        Artwork(track, Modifier.size(144.dp))
        Spacer(Modifier.height(Design.sm))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun LibraryScreen(
    tracks: List<Track>, favorites: Set<String>, activeId: String?, sort: String, scanning: Boolean,
    onImport: () -> Unit, onScan: () -> Unit, onSort: (String) -> Unit,
    onPlay: (List<Track>, String) -> Unit, onMenu: (Track) -> Unit, onGroup: (String, String, String) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf("Songs") }
    var sortOpen by remember { mutableStateOf(false) }
    val sorted = remember(tracks, sort) { sortedTracks(tracks, sort) }
    val groups = remember(tracks, tab) {
        when (tab) { "Albums" -> tracks.filter { MetadataPolicy.known(it.album).isNotBlank() }.groupBy(::albumKey); "Artists" -> tracks.groupBy { it.artist }; "Folders" -> tracks.filter { it.available }.groupBy { it.folder }; else -> emptyMap() }.entries.sortedBy { it.value.firstOrNull()?.let { t -> if (tab == "Albums") t.album.lowercase() else it.key.lowercase() } }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = Design.page)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = Design.page, end = Design.md), verticalAlignment = Alignment.CenterVertically) {
                Text("${tracks.count { it.available }} lagu · tersimpan di perangkat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                ActionIcon(Icons.Rounded.Refresh, "Sinkronkan pustaka", onScan, enabled = !scanning)
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Design.page), horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                listOf("Songs", "Albums", "Artists", "Folders").forEach { label -> FilterChip(selected = tab == label, onClick = { tab = label }, label = { Text(Bahasa.pilihan(label)) }) }
            }
        }
        if (scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Design.page, vertical = Design.sm)) }
        if (tracks.isEmpty() && !scanning) item { EmptyState("Koleksimu ada di sini", "Pilih berkas atau folder musik melalui Impor. Hanya pilihanmu yang tampil di sini.", action = { Button(onImport) { Text("Pilih musik") } }) }
        if(tab=="Folders" && tracks.any { !it.available }) item {
            TextButton(onImport,Modifier.padding(horizontal=Design.page)) { Text("Sumber belum tersedia · kelola atau hubungkan ulang") }
        }
        if(tab=="Folders" && tracks.isNotEmpty() && groups.isEmpty() && !scanning) item {
            Text("Belum ada folder dengan audio yang tersedia. Periksa sumber atau sinkronkan kembali.",Modifier.padding(Design.page),style=MaterialTheme.typography.bodyMedium)
        }
        if (tracks.isNotEmpty() && tab == "Songs") {
            item { Row(Modifier.fillMaxWidth().padding(start = Design.page, end = Design.md), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ sorted.firstOrNull { it.available }?.let { onPlay(sorted, it.id) } }) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(Design.xs)); Text("Putar semua") }
                Spacer(Modifier.weight(1f))
                Box { TextButton({ sortOpen = true }) { Icon(Icons.Rounded.Sort, null); Spacer(Modifier.width(Design.xs)); Text(Bahasa.pilihan(sort)) }; DropdownMenu(sortOpen, { sortOpen = false }) { listOf("Title", "Artist", "Album", "Recently added", "Duration").forEach { label -> DropdownMenuItem(text = { Text(Bahasa.pilihan(label)) }, onClick = { onSort(label); sortOpen = false }) } } }
            } }
            items(sorted, key = { it.id }) { t -> TrackRow(t, t.id == activeId, t.id in favorites, { onPlay(sorted, t.id) }, { onMenu(t) }) }
        } else if (tab != "Songs") items(groups, key = { it.key }) { (key, songs) ->
            val t = songs.first()
            val rawLabel = when (tab) { "Albums" -> t.album; "Folders" -> key.substringAfterLast('/').ifBlank { key }; else -> key }
            val label = if (tab == "Artists") Bahasa.artis(rawLabel) else rawLabel
            Row(Modifier.fillMaxWidth().clickable { onGroup(when (tab) { "Albums" -> "album"; "Artists" -> "artist"; else -> "folder" }, key, label) }.padding(horizontal = Design.page, vertical = Design.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.gap)) {
                if (tab == "Albums") Artwork(t, Modifier.size(64.dp)) else if(tab == "Artists") ArtistPortrait(rawLabel, Modifier.size(56.dp)) else Box(Modifier.size(56.dp).clip(Design.small).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary) }
                Column(Modifier.weight(1f)) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium); Text(if (tab == "Folders") "$key · ${songs.size} lagu" else "${songCount(songs.size)}${if (tab == "Albums") " · ${t.artist}" else ""}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable fun LocalSearchResults(query: String, tracks: List<Track>, playlists: List<PlaylistSummary>, favorites: Set<String>, activeId: String?, onPlay: (List<Track>, String) -> Unit, onMenu: (Track) -> Unit, onPlaylist: (PlaylistSummary) -> Unit) {
    val results by produceState<List<Track>>(emptyList(), tracks, query) {
        delay(120)
        value = withContext(Dispatchers.Default) { if (query.isBlank()) emptyList() else tracks.mapNotNull {track->com.yonzmusic.app.search.LocalSearch.score(query,track.title,track.artist,track.album)?.let {it to track}}.sortedBy {it.first}.map {it.second} }
    }
    val foundPlaylists = remember(playlists, query) { if (query.isBlank()) emptyList() else playlists.filter {com.yonzmusic.app.search.LocalSearch.score(query,it.name)!=null} }
    LazyColumn(contentPadding = PaddingValues(bottom = Design.page)) {
        if (query.isBlank()) item { EmptyState("Temukan lagu favoritmu", "Cari judul, artis, album, dan daftar putar dalam koleksimu.", "search") }
        else if (results.isEmpty() && foundPlaylists.isEmpty()) item { EmptyState("Belum ada hasil", "Coba judul, artis, atau album lain. Hanya lagu dalam pustaka lokal yang tampil di sini.", "search") }
        if (foundPlaylists.isNotEmpty()) {
            item { SectionTitle("Daftar putar", "${foundPlaylists.size} hasil") }
            items(foundPlaylists, key = { "playlist_${it.id}" }) { p -> ListItem(headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(songCount(p.count)) }, leadingContent = { Icon(Icons.Rounded.QueueMusic, null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { onPlaylist(p) }.padding(horizontal = Design.md)) }
        }
        if (results.isNotEmpty()) {
            item { SectionTitle("Lagu", "${results.size} hasil") }
            items(results, key = { it.id }) { t -> TrackRow(t, t.id == activeId, t.id in favorites, { onPlay(results, t.id) }, { onMenu(t) }) }
        }
    }
}
@Composable fun PlaylistsScreen(playlists: List<PlaylistSummary>, tracks: List<Track>, entries: List<PlaylistEntry>, favoriteCount: Int, onCreate: () -> Unit, onFavorites: () -> Unit, onOpen: (PlaylistSummary) -> Unit, onImport: () -> Unit, online:List<OnlineCollection> = emptyList(),onOnline:(OnlineCollection)->Unit = {},onLink:()->Unit = {}) {
    LazyColumn(contentPadding = PaddingValues(bottom = Design.page)) {
        item { Row(Modifier.padding(horizontal = Design.page, vertical = Design.sm), horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
            Button(onCreate, Modifier.weight(1f),contentPadding=PaddingValues(horizontal=Design.sm,vertical=Design.sm)) { Text("Buat",maxLines=1) }
            OutlinedButton(onImport, Modifier.weight(1f),contentPadding=PaddingValues(horizontal=Design.sm,vertical=Design.sm)) { Text("Impor",maxLines=1) }
            OutlinedButton(onLink,Modifier.weight(1f),contentPadding=PaddingValues(horizontal=Design.sm,vertical=Design.sm)){Text("Tautan",maxLines=1)}
        } }
        item {
            Surface(onClick = onFavorites, color = MaterialTheme.colorScheme.secondaryContainer, shape = Design.card, modifier = Modifier.fillMaxWidth().padding(Design.page)) {
                Row(Modifier.padding(Design.page), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.gap)) {
                    Icon(Icons.Rounded.Favorite, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Column(Modifier.weight(1f)) { Text("Favorit", style = MaterialTheme.typography.titleLarge); Text("${songCount(favoriteCount)} pilihanmu", style = MaterialTheme.typography.bodySmall) }
                    Icon(Icons.Rounded.ChevronRight, null)
                }
            }
        }
        if(online.isNotEmpty()) {
            item {SectionTitle("Daring","${online.size} daftar putar")}
            items(online,key={"online:"+it.id}){p->
                Row(Modifier.fillMaxWidth().clickable{onOnline(p)}.padding(horizontal=Design.page,vertical=Design.md),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Design.gap)) {
                    SourceThumbnail(p.songs.firstOrNull()?.artwork.orEmpty())
                    Column(Modifier.weight(1f)){Text(p.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium);Text("${p.songs.size} lagu · daring",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Icon(Icons.Rounded.ChevronRight,null)
                }
            }
        }
        if (playlists.isEmpty() && online.isEmpty()) item { EmptyState("Kumpulan lagu pilihanmu", "Satukan lagu pilihan dalam koleksi milikmu sendiri.", "playlist", { FilledTonalButton(onCreate) { Text("Buat daftar putar") } }) }
        else if(playlists.isNotEmpty()) {
            item { SectionTitle("Koleksimu", "${playlists.size} daftar putar") }
            items(playlists, key = { it.id }) { p ->
                val first = remember(entries, tracks, p.id) { val id = entries.firstOrNull { it.playlistId == p.id }?.trackId; tracks.find { it.id == id } }
                Row(Modifier.fillMaxWidth().clickable { onOpen(p) }.padding(horizontal = Design.page, vertical = Design.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.gap)) {
                    Artwork(first, Modifier.size(72.dp))
                    Column(Modifier.weight(1f)) { Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(songCount(p.count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
@Composable fun DetailScreen(tracks: List<Track>, favorites: Set<String>, activeId: String?, editable: Boolean, onPlay: (List<Track>, String) -> Unit, onMenu: (Track) -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit, onMove: (Int, Int) -> Unit, onAdd: () -> Unit) {
    var edit by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(bottom = Design.page)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = Design.page, vertical = Design.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.gap)) {
                Artwork(tracks.firstOrNull(), Modifier.size(104.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Design.sm)) {
                    Text(songCount(tracks.size), style = MaterialTheme.typography.titleLarge)
                    Text("${tracks.sumOf { it.duration } / 60000} menit · di perangkat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({ tracks.firstOrNull { it.available }?.let { onPlay(tracks, it.id) } }, enabled = tracks.any { it.available }) { Icon(Icons.Rounded.PlayArrow, null); Text("Putar") }
                }
            }
        }
        if (editable) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = Design.page), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onAdd) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(Design.xs)); Text("Tambah lagu") }
                Spacer(Modifier.weight(1f))
                ActionIcon(Icons.Rounded.SwapVert, if (edit) "Selesai mengurutkan" else "Urutkan lagu", { edit = !edit }, tint = if (edit) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                Box { ActionIcon(Icons.Rounded.MoreHoriz, "Opsi daftar putar", { more = true }); DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text("Ubah nama") }, onClick = { more = false; onRename() })
                    DropdownMenuItem(text = { Text("Ekspor M3U8") }, onClick = { more = false; onExport() }, enabled = tracks.isNotEmpty())
                    DropdownMenuItem(text = { Text("Hapus daftar putar", color = MaterialTheme.colorScheme.error) }, onClick = { more = false; onDelete() })
                } }
            }
        }
        if (edit && editable) item { Text("Gunakan tombol panah untuk mengubah urutan lagu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Design.page, vertical = Design.sm)) }
        if (tracks.isEmpty()) item { EmptyState("Tempat untuk lagu pilihanmu", if (editable) "Tambahkan lagu dari pustaka untuk mengisi koleksi ini." else "Lagu yang kamu kumpulkan akan tampil di sini.", "playlist", if (editable) { { FilledTonalButton(onAdd) { Text("Tambah lagu") } } } else null) }
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, t ->
            Column {
                TrackRow(t, t.id == activeId, t.id in favorites, { onPlay(tracks, t.id) }, { onMenu(t) })
                if (edit && editable) Row(Modifier.fillMaxWidth().padding(start = Design.page, end = Design.gap), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1} dari ${tracks.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    ActionIcon(Icons.Rounded.KeyboardArrowUp, "Pindahkan ${t.title} ke atas", { onMove(index, index - 1) }, enabled = index > 0)
                    ActionIcon(Icons.Rounded.KeyboardArrowDown, "Pindahkan ${t.title} ke bawah", { onMove(index, index + 1) }, enabled = index < tracks.lastIndex)
                }
            }
        }
    }
}
