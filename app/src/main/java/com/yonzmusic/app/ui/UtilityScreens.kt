package com.yonzmusic.app.ui

import com.yonzmusic.app.i18n.Bahasa

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
    equalizer: () -> Unit, onImport: () -> Unit, appSettings: () -> Unit, smartMix: (Boolean) -> Unit = {}, hideDownloads: (Boolean) -> Unit = {}, galleryNotice: String = ""
) {
    val c = MaterialTheme.colorScheme
    var licenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val licenseText by produceState("") { value = withContext(Dispatchers.IO) { context.resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() } } }
    val duplicates = remember(tracks) { tracks.filter { it.available && it.artist != "Unknown artist" }.groupBy { Triple(MusicListParser.titleKey(it.title), MusicListParser.artistKey(it.artist), it.duration / 1000) }.values.count { it.size > 1 } }
    LazyColumn(contentPadding = PaddingValues(bottom = Design.section)) {
        item { SectionTitle("Sesuaikan dengan seleramu", "Tampilan") }
        item {
            Surface(Modifier.padding(horizontal = Design.page), color = c.surface, shape = Design.card) {
                Column(Modifier.padding(vertical = Design.sm)) {
                    listOf("System", "Dark", "Light").forEach { name ->
                        Row(Modifier.fillMaxWidth().clickable { theme(name) }.padding(horizontal = Design.gap, vertical = Design.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                            RadioButton(preferences.theme == name, onClick = null)
                            Text(if (name == "System") "Ikuti sistem" else Bahasa.pilihan(name), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Icon(when (name) { "Dark" -> Icons.Rounded.DarkMode; "Light" -> Icons.Rounded.LightMode; else -> Icons.Rounded.Contrast }, null, tint = c.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { ToggleSetting("Campuran pintar", "Koleksi otomatis dari waktu dengar lokal. Tidak mengubah album atau daftar putar buatanmu.", preferences.smartMix, smartMix) }
        item { ToggleSetting("Efek kaca", "Sampul diburamkan secara halus pada Android 12 ke atas. Versi sebelumnya memakai permukaan transparan.", preferences.glass, glass) }
        item { ToggleSetting("Kurangi animasi", "Kurangi gerakan antarmuka. Pengaturan animasi sistem Android juga tetap diikuti.", preferences.reduceMotion, motion) }
        item { SectionTitle("Dengarkan dengan caramu", "Pemutaran") }
        item { MenuAction(Icons.Rounded.Equalizer, "Buka ekualiser sistem", equalizer) }
        item {
            Column(Modifier.padding(horizontal = Design.page, vertical = Design.gap), verticalArrangement = Arrangement.spacedBy(Design.sm)) {
                Text("Pengatur waktu tidur", style = MaterialTheme.typography.titleMedium)
                if (sleepEnd > 0) SleepRemaining(sleepEnd) else Text("Jeda pemutaran setelah waktu yang ditentukan.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                    listOf(15, 30, 45, 60).forEach { minutes -> OutlinedButton({ timer(minutes) }) { Text("$minutes menit") } }
                    if (sleepEnd > 0) TextButton({ timer(0) }) { Text("Batal") }
                }
            }
        }
        item { SectionTitle("Berkasmu tetap milikmu", "Pustaka musik") }
        item { ListItem(headlineContent = { Text("Sinkronkan pustaka") }, supportingContent = { Text(if (scanning) "Membaca musik lokal…" else "${tracks.count { it.available }} tersedia · ${tracks.count { !it.available }} tidak tersedia") }, leadingContent = { Icon(Icons.Rounded.Refresh, null) }, modifier = Modifier.clickable(enabled = !scanning, onClick = scan)) }
        if (scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Design.page)) }
        item { ToggleSetting("Sembunyikan unduhan dari Galeri", "Aktif secara default. Berlaku untuk seluruh folder tujuan unduhan dan subfoldernya. Yono tetap bisa memutarnya; pemutar lain yang memakai indeks media Android bisa tidak menampilkannya. Galeri mungkin perlu memperbarui indeks.", preferences.hideDownloadsFromGallery, hideDownloads) }
        if (galleryNotice.isNotBlank()) item { Text(galleryNotice, Modifier.padding(horizontal = Design.page, vertical = Design.sm), style = MaterialTheme.typography.bodySmall, color = c.error) }
        item { MenuAction(Icons.Rounded.LibraryAdd, "Impor musik atau daftar putar", onImport) }
        item { ListItem(headlineContent = { Text("Kemungkinan rekaman duplikat") }, supportingContent = { Text("$duplicates kelompok metadata. Berkas tidak pernah dihapus atau digabung otomatis.") }, leadingContent = { Icon(Icons.Rounded.CopyAll, null) }) }
        item { MenuAction(Icons.Rounded.Security, "Kelola izin Android", appSettings) }
        item { MenuAction(Icons.Rounded.Info, "Lisensi sumber terbuka", { licenses = true }) }
        item { UpdateSettings() }
        item { SectionTitle("Ruang musikmu", "Yono Music · ${com.yonzmusic.app.BuildConfig.VERSION_NAME}") }
        item { Text("Dibuat oleh Karisyono · Sumber terbuka · GPL-3.0-or-later", Modifier.padding(horizontal = Design.page), style = MaterialTheme.typography.titleSmall) }
        item { MenuAction(Icons.Rounded.Info, "Kode sumber · arisynn/yonz-music") { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/arisynn/yonz-music"))) } } }
        item { Text("Dengarkan musik tanpa iklan, akun, atau pelacakan. Musik, daftar putar, dan preferensimu tersimpan di perangkat. Pencarian daring, pemutaran daring, unduhan, dan pemeriksaan pembaruan GitHub menggunakan internet. Penyedia sumber menerima permintaan pencarian atau media; riwayat pencarian bisa dihapus atau dimatikan. Pemutaran musik lokal tetap berfungsi tanpa internet.\n\nJika pemutaran saat layar mati terhenti di Xiaomi HyperOS, buka pengaturan baterai aplikasi dan izinkan aktivitas latar belakang. Aplikasi tidak meminta pengecualian baterai secara otomatis.\n\nPengatur waktu tidur berlaku untuk sesi pemutaran yang aktif. Saat dibuka kembali, antrean dan posisi pemutaran dipulihkan dalam keadaan jeda.\n\n© 2026 Karisyono · Yono Music · Musikmu. Ruangmu.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = Design.page, vertical = Design.sm)) }
    }
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Lisensi sumber terbuka") }, text = { Text(licenseText, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }, confirmButton = { TextButton({ licenses = false }) { Text("Selesai") } })
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
    var name by rememberSaveable { mutableStateOf("Koleksi impor") }
    val c = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    var downloadMode by rememberSaveable { mutableStateOf(vm.pendingDownloadInput.value.isNotBlank()) }
    LaunchedEffect(preview) { if (preview != null) { listState.scrollToItem(0); if (vm.importedFileName != "pasted.txt") name = vm.importedFileName.substringBeforeLast('.').take(100).ifBlank { "Koleksi impor" } } }
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = Design.section), verticalArrangement = Arrangement.spacedBy(Design.sm)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Design.page), horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                FilterChip(!downloadMode, { downloadMode = false }, label = { Text("Berkas dan daftar putar") })
                FilterChip(downloadMode, { downloadMode = true }, label = { Text("Unduhan") })
            }
        }
        if (downloadMode) { item { LicensedDownloads(vm) } } else {
        item { Column(Modifier.padding(horizontal = Design.page, vertical = Design.md), verticalArrangement = Arrangement.spacedBy(Design.sm)) {
            Text(if (preview == null) "Bawa koleksimu\nke ruangmu." else "Berikut hasil pemeriksaannya.", style = MaterialTheme.typography.headlineMedium)
            Text(if (preview == null) "Pilih audio untuk diputar, atau cocokkan daftar lagu dengan berkas yang sudah ada di pustaka. Berkas asli tetap di tempatnya." else "Hanya audio lokal yang cocok yang bisa diputar. Judul yang belum ditemukan tetap dicatat dalam laporan ini.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        } }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(Design.page)) }
        if (preview == null) {
            item {
                Surface(Modifier.padding(horizontal = Design.page), color = c.surface, shape = Design.card) {
                    Column {
                        ImportChoice(Icons.Rounded.AudioFile, "Berkas audio lokal", "MP3, FLAC, M4A, WAV, OGG, dan lainnya", !busy, files)
                        HorizontalDivider(Modifier.padding(horizontal = Design.gap), color = c.outlineVariant)
                        ImportChoice(Icons.Rounded.FolderOpen, "Folder musik", "Baca audio dalam folder dan subfoldernya", !busy, folder)
                        HorizontalDivider(Modifier.padding(horizontal = Design.gap), color = c.outlineVariant)
                        ImportChoice(Icons.Rounded.PlaylistAdd, "Daftar putar atau daftar lagu", "TXT, CSV, JSON, M3U, dan M3U8", !busy, list)
                    }
                }
            }
            item { SelectedSources(vm) }
            item { SectionTitle("Atau tempel daftar lagu", "Satu Artis — Judul per baris. Daftar bernomor dari AI juga bisa digunakan.") }
            item { SmartSongListField(text, { text = it; vm.setImportText(it) }, pasteArtists,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Design.page), enabled = !busy, label = "Daftar lagu",
                onTooLarge = { vm.notify("Masukan melebihi 2 MiB") }) }
            item { Button({ vm.analyze() }, Modifier.fillMaxWidth().padding(horizontal = Design.page), enabled = text.isNotBlank() && !busy) { Icon(Icons.Rounded.ManageSearch, null); Spacer(Modifier.width(Design.sm)); Text("Cocokkan dengan pustaka") } }
            item { Text("Daftar berisi metadata. Gunakan audio lokal yang dipilih atau tinjau sumber yang diizinkan melalui Unduhan. Metadata Spotify sendiri tidak menyediakan audio yang bisa diputar.", modifier = Modifier.padding(horizontal = Design.page), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
            if (history.isNotEmpty()) {
                item { SectionTitle("Riwayat impor", "Tersimpan di perangkat") }
                items(history.take(8), key = { it.id }) { log -> ListItem(headlineContent = { Text(log.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(log.date))) }, leadingContent = { Icon(Icons.Rounded.History, null) }, trailingContent = { Icon(Icons.Rounded.ChevronRight, null) }, modifier = Modifier.clickable { onHistory(log) }) }
            }
        } else {
            val result = preview!!
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Design.page), horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                    listOf("Matched", "Unmatched", "Duplicate", "Invalid").forEach { status -> Surface(color = c.surfaceVariant, shape = Design.small) { Text("${result.count(status)} ${Bahasa.pilihan(status)}", Modifier.padding(horizontal = Design.md, vertical = Design.sm), style = MaterialTheme.typography.labelMedium) } }
                }
            }
            item { OutlinedTextField(name, { name = it.take(100) }, label = { Text("Nama daftar putar") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = Design.page)) }
            item { Button({ vm.commitImport(name, done) }, Modifier.fillMaxWidth().padding(horizontal = Design.page), enabled = result.matched.isNotEmpty() && name.isNotBlank() && !busy) { Text("Buat daftar putar · ${result.matched.size} lagu") } }
            item { TextButton({ vm.clearImportPreview(); text = vm.importedText }, Modifier.padding(horizontal = Design.page)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null); Spacer(Modifier.width(Design.sm)); Text("Ubah atau pilih daftar lain") } }
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
    ListItem(headlineContent = { Text(row.input, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("${Bahasa.pilihan(row.status)} · ${Bahasa.pesan(row.detail)}", style = MaterialTheme.typography.bodySmall) }, leadingContent = { Icon(when (row.status) { "Matched" -> Icons.Rounded.CheckCircle; "Duplicate" -> Icons.Rounded.ContentCopy; "Invalid" -> Icons.Rounded.ErrorOutline; else -> Icons.Rounded.HelpOutline }, Bahasa.pilihan(row.status), tint = when (row.status) { "Matched" -> MaterialTheme.colorScheme.primary; "Invalid" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant }) }, modifier = Modifier.padding(horizontal = Design.sm))
}
