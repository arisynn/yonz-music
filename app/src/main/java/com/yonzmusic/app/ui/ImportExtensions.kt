package com.yonzmusic.app.ui

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
        SectionTitle("Chosen sources", "Opening Yono reads the cache. Refresh checks only these sources.", { ActionIcon(Icons.Rounded.Refresh, "Refresh selected sources", vm::scan, enabled = !scanning) })
        if (sources.isEmpty()) Text("Choose files or a folder above. No music-storage permission is needed.", Modifier.padding(horizontal = Design.page), style = MaterialTheme.typography.bodySmall)
        sources.take(visibleSources).forEach { source ->
            Surface(Modifier.fillMaxWidth().padding(horizontal = Design.page, vertical = Design.xs), shape = Design.small, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(Design.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                        Column(Modifier.weight(1f)) {
                            Text(source.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(source.status + if (source.persistent) "" else " · temporary grant", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(source.enabled, { vm.sourceEnabled(source.id, it) }, enabled = !scanning)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (source.kind == "tree") {
                            Checkbox(source.recursive, { vm.sourceRecursive(source.id, it) }, enabled = !scanning)
                            Text("Subfolders", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        } else Spacer(Modifier.weight(1f))
                        TextButton({ removing = source }, enabled = !scanning) { Text("Remove") }
                    }
                }
            }
        }
        if (sources.size > visibleSources) TextButton({ visibleSources += 20 }, Modifier.padding(horizontal = Design.page)) { Text("Show more sources · ${sources.size - visibleSources}") }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Design.page), horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
            TextButton({ backup.launch("Yono_Collections.json") }) { Text("Export collections") }
            TextButton({ restoreConfirmation = true }) { Text("Restore collections") }
        }
    }
    removing?.let { source -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove source?") }, text = { Text("Hide ${source.name} from the active library. Original audio, playlist references, favorites and history are preserved. Android access may still be used by overlapping selections.") }, confirmButton = { TextButton({ vm.removeSource(source.id); removing = null }) { Text("Remove") } }, dismissButton = { TextButton({ removing = null }) { Text("Cancel") } }) }
    if (restoreConfirmation) AlertDialog(onDismissRequest = { restoreConfirmation = false }, title = { Text("Restore collection metadata?") }, text = { Text("This adds playlists, favorites and history from your backup. It contains no audio and grants no storage access. Select music sources afterward. Repeating a restore can add another copy of a playlist.") }, confirmButton = { TextButton({ restoreConfirmation = false; restore.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text("Choose backup") } }, dismissButton = { TextButton({ restoreConfirmation = false }) { Text("Cancel") } })
}

@Composable fun LicensedDownloads(vm: MusicViewModel) { CompactDownloads(vm) }
