package com.yonzmusic.app.ui

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
        item { Text("Musikmu, ruangmu.",Modifier.padding(horizontal=Design.page,vertical=Design.sm),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(tracks.isEmpty()) item { EmptyState("Mulai dari musikmu", "Pilih file atau folder. Koleksi dan playback tetap bekerja offline.",action={ Button(onImport) { Text("Pilih musik") } }) }
        if(recent.isNotEmpty()) {
            item { SectionTitle("Terakhir diputar",action={ TextButton(onHistory) { Text("Semua") } }) }
            items(recent,key={ "recent_${it.id}" }) { t -> TrackRow(t,t.id==activeId,t.id in favorites,{onPlay(recent to t.id)},{onMenu(t)}) }
        }
        if(mixes.isNotEmpty()) {
            item { SectionTitle("Smart Mix","Dari pemutaran lokal yang bermakna") }
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
        item { Text("Pemutaran bermakna: 30 detik atau setengah durasi (minimal 5 detik, maksimal durasi lagu). Seek dan jeda tidak menambah waktu dengar.",style=MaterialTheme.typography.bodySmall) }
        items(m.tracks,key={it.id}) { t -> ListItem(headlineContent={Text(t.title,maxLines=1,overflow=TextOverflow.Ellipsis)},supportingContent={Text(t.artist,maxLines=1)},modifier=Modifier.clickable { onPlay(m.tracks to t.id);mix=null }) }
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
        when (tab) { "Albums" -> tracks.filter { MetadataPolicy.known(it.album).isNotBlank() }.groupBy(::albumKey); "Artists" -> tracks.groupBy { it.artist }; "Folders" -> tracks.groupBy { it.folder }; else -> emptyMap() }.entries.sortedBy { it.value.firstOrNull()?.let { t -> if (tab == "Albums") t.album.lowercase() else it.key.lowercase() } }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = Design.page)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = Design.page, end = Design.md), verticalAlignment = Alignment.CenterVertically) {
                Text("${tracks.count { it.available }} songs · saved on your device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                ActionIcon(Icons.Rounded.Refresh, "Rescan library", onScan, enabled = !scanning)
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Design.page), horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                listOf("Songs", "Albums", "Artists", "Folders").forEach { label -> FilterChip(selected = tab == label, onClick = { tab = label }, label = { Text(label) }) }
            }
        }
        if (scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Design.page, vertical = Design.sm)) }
        if (tracks.isEmpty() && !scanning) item { EmptyState("Your collection lives here", "Choose your music files or folders in Import. Only your selections appear here.", action = { Button(onImport) { Text("Choose music") } }) }
        if (tracks.isNotEmpty() && tab == "Songs") {
            item { Row(Modifier.fillMaxWidth().padding(start = Design.page, end = Design.md), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ sorted.firstOrNull { it.available }?.let { onPlay(sorted, it.id) } }) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(Design.xs)); Text("Play all") }
                Spacer(Modifier.weight(1f))
                Box { TextButton({ sortOpen = true }) { Icon(Icons.Rounded.Sort, null); Spacer(Modifier.width(Design.xs)); Text(sort) }; DropdownMenu(sortOpen, { sortOpen = false }) { listOf("Title", "Artist", "Album", "Recently added", "Duration").forEach { label -> DropdownMenuItem(text = { Text(label) }, onClick = { onSort(label); sortOpen = false }) } } }
            } }
            items(sorted, key = { it.id }) { t -> TrackRow(t, t.id == activeId, t.id in favorites, { onPlay(sorted, t.id) }, { onMenu(t) }) }
        } else if (tab != "Songs") items(groups, key = { it.key }) { (key, songs) ->
            val t = songs.first()
            val label = when (tab) { "Albums" -> t.album; "Folders" -> key.substringAfterLast('/').ifBlank { key }; else -> key }
            Row(Modifier.fillMaxWidth().clickable { onGroup(when (tab) { "Albums" -> "album"; "Artists" -> "artist"; else -> "folder" }, key, label) }.padding(horizontal = Design.page, vertical = Design.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.gap)) {
                if (tab == "Albums") Artwork(t, Modifier.size(64.dp)) else Box(Modifier.size(56.dp).clip(if (tab == "Artists") CircleShape else Design.small).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(if (tab == "Artists") Icons.Rounded.Person else Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary) }
                Column(Modifier.weight(1f)) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium); Text(if (tab == "Folders") "$key · ${songs.size} songs" else "${songCount(songs.size)}${if (tab == "Albums") " · ${t.artist}" else ""}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable fun SearchScreen(tracks: List<Track>, playlists: List<PlaylistSummary>, favorites: Set<String>, activeId: String?, onPlay: (List<Track>, String) -> Unit, onMenu: (Track) -> Unit, onPlaylist: (PlaylistSummary) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by produceState<List<Track>>(emptyList(), tracks, query) {
        delay(120)
        value = withContext(Dispatchers.Default) { val key = MusicListParser.normalize(query); if (key.isBlank()) emptyList() else tracks.filter { key in MusicListParser.normalize("${it.title} ${it.artist} ${it.album}") } }
    }
    val foundPlaylists = remember(playlists, query) { if (query.isBlank()) emptyList() else playlists.filter { it.name.contains(query, true) } }
    LazyColumn(contentPadding = PaddingValues(bottom = Design.page)) {
        item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = Design.page, vertical = Design.sm), placeholder = { Text("Songs, artists, albums…") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) ActionIcon(Icons.Rounded.Close, "Clear search", { query = "" }) }, singleLine = true, shape = Design.card) }
        if (query.isBlank()) item { EmptyState("Find your next favorite", "Search titles, artists, albums, and playlists in your own collection.", "search") }
        else if (results.isEmpty() && foundPlaylists.isEmpty()) item { EmptyState("Nothing here just yet", "Try another title, artist, or album. Only songs in your local library appear here.", "search") }
        if (foundPlaylists.isNotEmpty()) {
            item { SectionTitle("Playlists", "${foundPlaylists.size} results") }
            items(foundPlaylists, key = { "playlist_${it.id}" }) { p -> ListItem(headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(songCount(p.count)) }, leadingContent = { Icon(Icons.Rounded.QueueMusic, null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { onPlaylist(p) }.padding(horizontal = Design.md)) }
        }
        if (results.isNotEmpty()) {
            item { SectionTitle("Songs", "${results.size} results") }
            items(results, key = { it.id }) { t -> TrackRow(t, t.id == activeId, t.id in favorites, { onPlay(results, t.id) }, { onMenu(t) }) }
        }
    }
}
@Composable fun PlaylistsScreen(playlists: List<PlaylistSummary>, tracks: List<Track>, entries: List<PlaylistEntry>, favoriteCount: Int, onCreate: () -> Unit, onFavorites: () -> Unit, onOpen: (PlaylistSummary) -> Unit, onImport: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = Design.page)) {
        item { Row(Modifier.padding(horizontal = Design.page, vertical = Design.sm), horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
            Button(onCreate, Modifier.weight(1f)) { if (LocalDensity.current.fontScale <= 1.3f) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(Design.xs)) }; Text("Create") }
            OutlinedButton(onImport, Modifier.weight(1f)) { if (LocalDensity.current.fontScale <= 1.3f) { Icon(Icons.Rounded.LibraryAdd, null); Spacer(Modifier.width(Design.xs)) }; Text("Import") }
        } }
        item {
            Surface(onClick = onFavorites, color = MaterialTheme.colorScheme.secondaryContainer, shape = Design.card, modifier = Modifier.fillMaxWidth().padding(Design.page)) {
                Row(Modifier.padding(Design.page), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.gap)) {
                    Icon(Icons.Rounded.Favorite, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Column(Modifier.weight(1f)) { Text("Favorites", style = MaterialTheme.typography.titleLarge); Text("${songCount(favoriteCount)} you keep close", style = MaterialTheme.typography.bodySmall) }
                    Icon(Icons.Rounded.ChevronRight, null)
                }
            }
        }
        if (playlists.isEmpty()) item { EmptyState("A place for every mood", "Bring songs together into a collection that's entirely yours.", "playlist", { FilledTonalButton(onCreate) { Text("Create a playlist") } }) }
        else {
            item { SectionTitle("Your collections", "${playlists.size} playlists") }
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
                    Text("${tracks.sumOf { it.duration } / 60000} min · on your device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({ tracks.firstOrNull { it.available }?.let { onPlay(tracks, it.id) } }, enabled = tracks.any { it.available }) { Icon(Icons.Rounded.PlayArrow, null); Text("Play") }
                }
            }
        }
        if (editable) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = Design.page), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onAdd) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(Design.xs)); Text("Add songs") }
                Spacer(Modifier.weight(1f))
                ActionIcon(Icons.Rounded.SwapVert, if (edit) "Finish reordering" else "Reorder songs", { edit = !edit }, tint = if (edit) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                Box { ActionIcon(Icons.Rounded.MoreHoriz, "Playlist options", { more = true }); DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { more = false; onRename() })
                    DropdownMenuItem(text = { Text("Export M3U8") }, onClick = { more = false; onExport() }, enabled = tracks.isNotEmpty())
                    DropdownMenuItem(text = { Text("Delete playlist", color = MaterialTheme.colorScheme.error) }, onClick = { more = false; onDelete() })
                } }
            }
        }
        if (edit && editable) item { Text("Use the arrows to change song order.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Design.page, vertical = Design.sm)) }
        if (tracks.isEmpty()) item { EmptyState("Room for your favorites", if (editable) "Add songs from your library to make this collection your own." else "Songs you collect will appear here.", "playlist", if (editable) { { FilledTonalButton(onAdd) { Text("Add songs") } } } else null) }
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, t ->
            Column {
                TrackRow(t, t.id == activeId, t.id in favorites, { onPlay(tracks, t.id) }, { onMenu(t) })
                if (edit && editable) Row(Modifier.fillMaxWidth().padding(start = Design.page, end = Design.gap), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1} of ${tracks.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    ActionIcon(Icons.Rounded.KeyboardArrowUp, "Move ${t.title} up", { onMove(index, index - 1) }, enabled = index > 0)
                    ActionIcon(Icons.Rounded.KeyboardArrowDown, "Move ${t.title} down", { onMove(index, index + 1) }, enabled = index < tracks.lastIndex)
                }
            }
        }
    }
}
