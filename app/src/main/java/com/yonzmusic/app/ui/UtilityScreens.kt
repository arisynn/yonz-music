package com.yonzmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import com.yonzmusic.app.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.yonzmusic.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.MusicViewModel
import com.yonzmusic.app.data.*
import com.yonzmusic.app.importer.*
import java.text.DateFormat
import java.util.Date

@Composable fun SettingsScreen(preferences: UserPreferences, tracks: List<Track>, scanning: Boolean, sleepEnd: Long,
    theme: (String) -> Unit, motion: (Boolean) -> Unit, glass: (Boolean) -> Unit, scan: () -> Unit, timer: (Int) -> Unit,
    equalizer: () -> Unit, onImport: () -> Unit, appSettings: () -> Unit, smartMix: (Boolean) -> Unit = {}
) {
    val c = MaterialTheme.colorScheme
    var licenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val licenseText by produceState("") { value = withContext(Dispatchers.IO) { context.resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() } } }
    val duplicates = remember(tracks) { tracks.filter { it.available && it.artist != "Unknown artist" }.groupBy { Triple(MusicListParser.titleKey(it.title), MusicListParser.artistKey(it.artist), it.duration / 1000) }.values.count { it.size > 1 } }
    LazyColumn(contentPadding = PaddingValues(bottom = Design.section)) {
        item { SectionTitle("Make it feel like you", "Appearance") }
        item {
            Surface(Modifier.padding(horizontal = Design.page), color = c.surface, shape = Design.card) {
                Column(Modifier.padding(vertical = Design.sm)) {
                    listOf("System", "Dark", "Light").forEach { name ->
                        Row(Modifier.fillMaxWidth().clickable { theme(name) }.padding(horizontal = Design.gap, vertical = Design.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                            RadioButton(preferences.theme == name, onClick = null)
                            Text(if (name == "System") "Follow system" else "Always ${name.lowercase()}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Icon(when (name) { "Dark" -> Icons.Rounded.DarkMode; "Light" -> Icons.Rounded.LightMode; else -> Icons.Rounded.Contrast }, null, tint = c.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { ToggleSetting("Smart Mix", "Koleksi otomatis dari waktu dengar lokal. Tidak mengubah album atau playlist buatanmu.", preferences.smartMix, smartMix) }
        item { ToggleSetting("Glass surfaces", "Softly blurred artwork on Android 12+. Translucent surfaces on earlier versions.", preferences.glass, glass) }
        item { ToggleSetting("Reduce motion", "Keep interactions calm. Android's system animation setting is also respected.", preferences.reduceMotion, motion) }
        item { SectionTitle("Listen your way", "Playback") }
        item { MenuAction(Icons.Rounded.Equalizer, "Open system equalizer", equalizer) }
        item {
            Column(Modifier.padding(horizontal = Design.page, vertical = Design.gap), verticalArrangement = Arrangement.spacedBy(Design.sm)) {
                Text("Sleep timer", style = MaterialTheme.typography.titleMedium)
                if (sleepEnd > 0) SleepRemaining(sleepEnd) else Text("Pause playback after a little while.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                    listOf(15, 30, 45, 60).forEach { minutes -> OutlinedButton({ timer(minutes) }) { Text("$minutes min") } }
                    if (sleepEnd > 0) TextButton({ timer(0) }) { Text("Cancel") }
                }
            }
        }
        item { SectionTitle("Your files stay yours", "Music library") }
        item { ListItem(headlineContent = { Text("Rescan library") }, supportingContent = { Text(if (scanning) "Reading local music…" else "${tracks.count { it.available }} available · ${tracks.count { !it.available }} unavailable") }, leadingContent = { Icon(Icons.Rounded.Refresh, null) }, modifier = Modifier.clickable(enabled = !scanning, onClick = scan)) }
        if (scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Design.page)) }
        item { MenuAction(Icons.Rounded.LibraryAdd, "Import music or playlists", onImport) }
        item { ListItem(headlineContent = { Text("Possible duplicate recordings") }, supportingContent = { Text("$duplicates metadata groups. Files are never deleted or merged automatically.") }, leadingContent = { Icon(Icons.Rounded.CopyAll, null) }) }
        item { MenuAction(Icons.Rounded.Security, "Manage Android permissions", appSettings) }
        item { MenuAction(Icons.Rounded.Info, "Open-source licenses", { licenses = true }) }
        item { UpdateSettings() }
        item { SectionTitle("A space of your own", "Yono Music · ${com.yonzmusic.app.BuildConfig.VERSION_NAME}") }
        item { Text("Dibuat oleh Karisyono · Open source · GPL-3.0-or-later", Modifier.padding(horizontal = Design.page), style = MaterialTheme.typography.titleSmall) }
        item { MenuAction(Icons.Rounded.Info, "Source code · arisynn/yonz-music") { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/arisynn/yonz-music"))) } } }
        item { Text("Built for listening, without ads, accounts, or tracking. Your music, playlists, and preferences stay on this device. Only optional downloads and GitHub update checks use the internet. Playback stays offline.\n\nOn Xiaomi HyperOS, if screen-off playback is interrupted, open this app's battery settings and allow background activity. No battery exemption is requested automatically.\n\nSleep timers apply to the current playback-service session. Playback context is restored paused after reopening.\n\n© 2026 Karisyono · Yono Music · Your Music. Your Space.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = Design.page, vertical = Design.sm)) }
    }
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Open-source licenses") }, text = { Text(licenseText, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }, confirmButton = { TextButton({ licenses = false }) { Text("Done") } })
}
@Composable private fun ToggleSetting(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleableSetting(checked, change).padding(horizontal = Design.page, vertical = Design.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.gap)) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onCheckedChange = null)
    }
}
private fun Modifier.toggleableSetting(value: Boolean, change: (Boolean) -> Unit): Modifier = this.toggleable(value = value, role = Role.Switch, onValueChange = change)

@Composable fun ImportScreen(vm: MusicViewModel, history: List<ImportLog>, files: () -> Unit, folder: () -> Unit, list: () -> Unit, done: () -> Unit, onHistory: (ImportLog) -> Unit) {
    val preview by vm.importPreview.collectAsStateWithLifecycle()
    val busy by vm.importBusy.collectAsStateWithLifecycle()
    val selectedTracks by vm.tracks.collectAsStateWithLifecycle()
    val pasteArtists = remember(selectedTracks) { selectedTracks.filter { it.available }.flatMap { it.artist.split(';') }.distinct() }
    var text by remember { mutableStateOf(vm.importedText) }
    var name by rememberSaveable { mutableStateOf("Imported collection") }
    val c = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    var downloadMode by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(preview) { if (preview != null) { listState.scrollToItem(0); if (vm.importedFileName != "pasted.txt") name = vm.importedFileName.substringBeforeLast('.').take(100).ifBlank { "Imported collection" } } }
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = Design.section), verticalArrangement = Arrangement.spacedBy(Design.sm)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Design.page), horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                FilterChip(!downloadMode, { downloadMode = false }, label = { Text("Files & playlists") })
                FilterChip(downloadMode, { downloadMode = true }, label = { Text("Downloads") })
            }
        }
        if (downloadMode) { item { LicensedDownloads(vm) } } else {
        item { Column(Modifier.padding(horizontal = Design.page, vertical = Design.md), verticalArrangement = Arrangement.spacedBy(Design.sm)) {
            Text(if (preview == null) "Bring your collection\ninto your space." else "Here's what we found.", style = MaterialTheme.typography.headlineMedium)
            Text(if (preview == null) "Choose audio to play, or match a song list with files already in your library. Your originals stay exactly where they are." else "Only matched local audio becomes playable. Missing titles stay in this report.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        } }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(Design.page)) }
        if (preview == null) {
            item {
                Surface(Modifier.padding(horizontal = Design.page), color = c.surface, shape = Design.card) {
                    Column {
                        ImportChoice(Icons.Rounded.AudioFile, "Local audio files", "MP3, FLAC, M4A, WAV, OGG and more", !busy, files)
                        HorizontalDivider(Modifier.padding(horizontal = Design.gap), color = c.outlineVariant)
                        ImportChoice(Icons.Rounded.FolderOpen, "A music folder", "Read audio in the folder and its subfolders", !busy, folder)
                        HorizontalDivider(Modifier.padding(horizontal = Design.gap), color = c.outlineVariant)
                        ImportChoice(Icons.Rounded.PlaylistAdd, "A playlist or song list", "TXT, CSV, JSON, M3U and M3U8", !busy, list)
                    }
                }
            }
            item { SelectedSources(vm) }
            item { SectionTitle("Or paste a list", "One Artist — Title per line. Numbered AI lists work too.") }
            item { SmartSongListField(text, { text = it; vm.setImportText(it) }, pasteArtists,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Design.page), enabled = !busy, label = "Song list",
                onTooLarge = { vm.notify("Masukan melebihi 2 MiB") }) }
            item { Button({ vm.analyze() }, Modifier.fillMaxWidth().padding(horizontal = Design.page), enabled = text.isNotBlank() && !busy) { Icon(Icons.Rounded.ManageSearch, null); Spacer(Modifier.width(Design.sm)); Text("Match with my library") } }
            item { Text("Lists contain metadata. Reuse selected local audio, or review permitted sources in Downloads. Spotify music metadata does not itself provide playable audio.", modifier = Modifier.padding(horizontal = Design.page), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
            if (history.isNotEmpty()) {
                item { SectionTitle("Import history", "Saved locally") }
                items(history.take(8), key = { it.id }) { log -> ListItem(headlineContent = { Text(log.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(log.date))) }, leadingContent = { Icon(Icons.Rounded.History, null) }, trailingContent = { Icon(Icons.Rounded.ChevronRight, null) }, modifier = Modifier.clickable { onHistory(log) }) }
            }
        } else {
            val result = preview!!
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Design.page), horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                    listOf("Matched", "Unmatched", "Duplicate", "Invalid").forEach { status -> Surface(color = c.surfaceVariant, shape = Design.small) { Text("${result.count(status)} $status", Modifier.padding(horizontal = Design.md, vertical = Design.sm), style = MaterialTheme.typography.labelMedium) } }
                }
            }
            item { OutlinedTextField(name, { name = it.take(100) }, label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = Design.page)) }
            item { Button({ vm.commitImport(name, done) }, Modifier.fillMaxWidth().padding(horizontal = Design.page), enabled = result.matched.isNotEmpty() && name.isNotBlank() && !busy) { Text("Create playlist · ${result.matched.size} songs") } }
            item { TextButton({ vm.clearImportPreview(); text = vm.importedText }, Modifier.padding(horizontal = Design.page)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null); Spacer(Modifier.width(Design.sm)); Text("Edit or choose another list") } }
            itemsIndexed(result.rows) { _, row -> ImportResultRow(row) }
        }
        }
    }
}
@Composable private fun ImportChoice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, enabled: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action).padding(Design.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.gap)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Rounded.ChevronRight, null)
    }
}
@Composable fun ImportResultRow(row: ImportRow) {
    ListItem(headlineContent = { Text(row.input, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("${row.status} · ${row.detail}", style = MaterialTheme.typography.bodySmall) }, leadingContent = { Icon(when (row.status) { "Matched" -> Icons.Rounded.CheckCircle; "Duplicate" -> Icons.Rounded.ContentCopy; "Invalid" -> Icons.Rounded.ErrorOutline; else -> Icons.Rounded.HelpOutline }, row.status, tint = when (row.status) { "Matched" -> MaterialTheme.colorScheme.primary; "Invalid" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant }) }, modifier = Modifier.padding(horizontal = Design.sm))
}
