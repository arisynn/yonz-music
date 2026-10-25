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

@Composable fun LicensedDownloads(vm: MusicViewModel) {
    val context = LocalContext.current
    val destination by vm.outputDestination.collectAsStateWithLifecycle()
    val results by vm.audioCandidates.collectAsStateWithLifecycle()
    val busy by vm.sourceBusy.collectAsStateWithLifecycle()
    val jobs by vm.downloads.collectAsStateWithLifecycle()
    val resolved by vm.acquisition.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("Licensed collection") }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var confirmation by remember { mutableStateOf<List<AudioCandidate>?>(null) }
    var resolvedConfirmation by remember { mutableStateOf(false) }
    var expandedBatch by rememberSaveable { mutableStateOf<String?>(null) }
    var visibleBatches by rememberSaveable { mutableIntStateOf(8) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data?.data != null) {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching {
                require(flags == (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                context.contentResolver.takePersistableUriPermission(data.data!!, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
                .onSuccess { vm.destination(data.data!!) }.onFailure { vm.notify("Choose a folder provider with persistent write access.") }
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun prepareNotifications() { if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    Column(verticalArrangement = Arrangement.spacedBy(Design.sm)) {
        SectionTitle("Save your downloads", "Your chosen destination · original files stay safe")
        OutlinedButton({ picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION).putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary:Music"))) }, Modifier.padding(horizontal = Design.page)) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(Design.sm)); Text(if (destination.isBlank()) "Choose download destination" else "Change download destination") }
        if (destination.isNotBlank()) Text("A playlist subfolder will be created here. The destination itself is never scanned.", Modifier.padding(horizontal = Design.page), style = MaterialTheme.typography.bodySmall)
        EmbeddedAcquisition(vm, destination.isNotBlank(), ::prepareNotifications)
        SectionTitle("Additional licensed catalog", "Wikimedia Commons · public domain, CC0, CC BY and CC BY-SA")
        Text("Review the recording, published credits and license; composer credits may differ from performers.", Modifier.padding(horizontal = Design.page), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(query, { query = it.take(250) }, label = { Text("Search Commons or paste a Commons File link") }, modifier = Modifier.fillMaxWidth().padding(horizontal = Design.page), minLines = 1, maxLines = 3)
        Column(Modifier.padding(horizontal = Design.page), verticalArrangement = Arrangement.spacedBy(Design.xs)) {
            Button({ chosen = emptySet(); vm.searchAudio(query) }, enabled = query.isNotBlank() && !busy) { Text("Find licensed audio") }
            if (busy) TextButton(vm::cancelSourceSearch) { Text("Cancel") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Design.page))
        results.forEach { candidate ->
            Surface(Modifier.fillMaxWidth().padding(horizontal = Design.page), shape = Design.small, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(Design.md), verticalArrangement = Arrangement.spacedBy(Design.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(candidate.id in chosen, { checked -> chosen = if (checked) chosen + candidate.id else chosen - candidate.id })
                        Text(candidate.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(candidate.artist, style = MaterialTheme.typography.bodyMedium)
                    Text("${time(candidate.duration)} · ${candidate.size / 1024} KB · ${candidate.license}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (candidate.description.isNotBlank()) Text(candidate.description.take(700), style = MaterialTheme.typography.bodySmall)
                    TextButton({ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(candidate.page))) }.onFailure { vm.notify("No browser is available.") } }) { Text("Source and attribution") }
                }
            }
        }
        if (results.isNotEmpty() || resolved.isNotEmpty()) {
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Download playlist name") }, modifier = Modifier.fillMaxWidth().padding(horizontal = Design.page), singleLine = true)
        }
        if (chosen.isNotEmpty()) Button({ confirmation = results.filter { it.id in chosen } }, Modifier.padding(horizontal = Design.page), enabled = destination.isNotBlank() && name.isNotBlank() && !busy) { Text("Download selected · ${chosen.size}") }
        if (vm.importedText.isNotBlank()) {
            OutlinedButton(vm::resolveMissing, Modifier.padding(horizontal = Design.page), enabled = !busy) { Text("Resolve this song list in Commons") }
            resolved.forEach { row -> ImportResultRow(com.yonzmusic.app.importer.ImportRow(row.input, row.status, row.detail)) }
            if (resolved.any { it.status == "Ready" }) Button({ resolvedConfirmation = true }, Modifier.padding(horizontal = Design.page), enabled = !busy && name.isNotBlank() && destination.isNotBlank()) { Text("Download ready and reuse existing") }
        }
        jobs.groupBy { it.batchId }.entries.sortedBy { (_, tracks) -> if (tracks.any { it.status in setOf("Downloading", "Validating", "Publishing", "Saved") }) 0 else 1 }.take(visibleBatches).forEach { (batch, tracks) ->
            Column(Modifier.padding(horizontal = Design.page, vertical = Design.sm)) {
                Text(tracks.first().playlistName, style = MaterialTheme.typography.titleMedium)
                Text("${tracks.count { it.status in setOf("Complete", "Existing") }} saved/reused · ${tracks.count { it.status == "Failed" }} failed · ${tracks.count { it.status == "Duplicate" }} duplicates", style = MaterialTheme.typography.bodySmall)
                tracks.firstOrNull { it.status == "Downloading" }?.let { current -> Text("${current.title} · ${if (current.total > 0) "${current.received * 100 / current.total}%" else "Downloading"}", style = MaterialTheme.typography.bodySmall) }
                TextButton({ expandedBatch = if (expandedBatch == batch) null else batch }) { Text(if (expandedBatch == batch) "Hide details" else "Show details") }
                if (expandedBatch == batch) tracks.sortedBy { it.ordinal }.forEach { job ->
                    Text("${job.title} · ${job.status}${if (job.total > 0 && job.status == "Downloading") " ${job.received * 100 / job.total}%" else ""}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = Design.xs))
                    if (job.error.isNotBlank()) Text(job.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (job.status == "Complete") Text("${job.artist} · ${job.license} · ${job.licenseUrl}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                    if (tracks.any { it.status in setOf("Queued", "Downloading", "Validating", "Publishing", "Saved") }) TextButton({ vm.cancelDownload(batch) }) { Text("Cancel batch") }
                    if (tracks.any { it.status in setOf("Failed", "Cancelled") && it.attempts < 4 }) TextButton({ vm.retryDownload(batch) }) { Text("Retry failed") }
                }
            }
        }
        if (jobs.map { it.batchId }.distinct().size > visibleBatches) TextButton({ visibleBatches += 8 }, Modifier.padding(horizontal = Design.page)) { Text("More transfer history") }
    }
    if (confirmation != null || resolvedConfirmation) AlertDialog(onDismissRequest = { confirmation = null; resolvedConfirmation = false }, title = { Text("Download selected recordings?") }, text = { Text("Save the reviewed freely licensed audio into your chosen destination and create the playlist. Existing music is never overwritten. Retain the source attribution and follow each license when sharing.") }, confirmButton = { TextButton({
        prepareNotifications()
        if (resolvedConfirmation) vm.downloadResolved(name) else vm.downloadAudio(name, confirmation.orEmpty())
        confirmation = null; resolvedConfirmation = false; chosen = emptySet()
    }) { Text("Unduh") } }, dismissButton = { TextButton({ confirmation = null; resolvedConfirmation = false }) { Text("Batal") } })
}
