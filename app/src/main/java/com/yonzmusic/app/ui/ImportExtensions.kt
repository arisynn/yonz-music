package com.yonzmusic.app.ui

import com.yonzmusic.app.i18n.Bahasa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.MusicViewModel
import com.yonzmusic.app.data.MusicSource
import com.yonzmusic.app.download.AudioCandidate
import com.yonzmusic.app.ui.icons.*

@Composable fun SelectedSources(vm: MusicViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<MusicSource?>(null) }
    var visibleSources by rememberSaveable { mutableIntStateOf(20) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::backup) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::restoreBackup) }
    var restoreConfirmation by remember { mutableStateOf(false) }
    Column {
        SectionTitle("Sumber pilihan", "Saat dibuka, Yono membaca data tersimpan. Sinkronisasi hanya memeriksa sumber berikut.", { ActionIcon(Icons.Rounded.Refresh, "Sinkronkan sumber pilihan", vm::scan, enabled = !scanning) })
        if (sources.isEmpty()) Text("Pilih berkas atau folder di atas. Akses penyimpanan musik secara menyeluruh tidak diperlukan.", Modifier.padding(horizontal = Design.page), style = MaterialTheme.typography.bodySmall)
        sources.take(visibleSources).forEach { source ->
            Surface(Modifier.fillMaxWidth().padding(horizontal = Design.page, vertical = Design.xs), shape = Design.small, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(Design.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                        Column(Modifier.weight(1f)) {
                            Text(source.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(Bahasa.pesan(source.status) + if (source.persistent) "" else " · akses sementara", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(source.enabled, { vm.sourceEnabled(source.id, it) }, enabled = !scanning)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (source.kind == "tree") {
                            Checkbox(source.recursive, { vm.sourceRecursive(source.id, it) }, enabled = !scanning)
                            Text("Subfolder", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        } else Spacer(Modifier.weight(1f))
                        TextButton({ removing = source }, enabled = !scanning) { Text("Hapus") }
                    }
                }
            }
        }
        if (sources.size > visibleSources) TextButton({ visibleSources += 20 }, Modifier.padding(horizontal = Design.page)) { Text("Tampilkan sumber lainnya · ${sources.size - visibleSources}") }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Design.page), horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
            TextButton({ backup.launch("Yono_Collections.json") }) { Text("Ekspor koleksi") }
            TextButton({ restoreConfirmation = true }) { Text("Pulihkan koleksi") }
        }
    }
    removing?.let { source -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Hapus sumber?") }, text = { Text("Sembunyikan ${source.name} dari pustaka aktif. Audio asli, referensi daftar putar, favorit, dan riwayat tetap disimpan. Izin Android mungkin masih digunakan oleh pilihan sumber yang saling mencakup.") }, confirmButton = { TextButton({ vm.removeSource(source.id); removing = null }) { Text("Hapus") } }, dismissButton = { TextButton({ removing = null }) { Text("Batal") } }) }
    if (restoreConfirmation) AlertDialog(onDismissRequest = { restoreConfirmation = false }, title = { Text("Pulihkan metadata koleksi?") }, text = { Text("Daftar putar, favorit, dan riwayat dari cadangan akan ditambahkan. Cadangan tidak berisi audio atau izin penyimpanan. Pilih sumber musik setelah pemulihan. Memulihkan cadangan yang sama lagi bisa membuat salinan daftar putar.") }, confirmButton = { TextButton({ restoreConfirmation = false; restore.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text("Pilih cadangan") } }, dismissButton = { TextButton({ restoreConfirmation = false }) { Text("Batal") } })
}

@Composable fun LicensedDownloads(vm: MusicViewModel) { CompactDownloads(vm) }
