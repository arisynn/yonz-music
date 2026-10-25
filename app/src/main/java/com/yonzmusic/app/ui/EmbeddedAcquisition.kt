package com.yonzmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.MusicViewModel
import com.yonzmusic.app.download.AudioCandidate

/** One extension of the existing Import flow; no terminal, duplicate destination or fake catalog. */
@Composable fun EmbeddedAcquisition(vm: MusicViewModel, hasDestination: Boolean, beforeDownload: () -> Unit) {
    val candidates by vm.engineCandidates.collectAsStateWithLifecycle()
    val report by vm.engineReport.collectAsStateWithLifecycle()
    val busy by vm.sourceBusy.collectAsStateWithLifecycle()
    val sourceInput by vm.engineInput.collectAsStateWithLifecycle()
    var urls by rememberSaveable { mutableStateOf("") }
    var contentPermission by rememberSaveable { mutableStateOf(false) }
    var servicePermission by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var name by rememberSaveable { mutableStateOf("Imported playlist") }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(sourceInput) { if (sourceInput.isNotBlank()) urls = sourceInput }
    Column(verticalArrangement = Arrangement.spacedBy(Design.sm)) {
        SectionTitle("Spoti engine · inside Yono", "Bundled yt-dlp · native Android playback and storage")
        Text("Use explicit links to your audio or sources you are permitted to download. Original audio formats are preserved; no DRM, login/cookie import or access bypass. Website support varies and extraction can fail. A metadata link is not an audio license.", Modifier.padding(horizontal = Design.page), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(urls, { urls = it.take(200000) }, label = { Text("Authorized source links · one per line") }, modifier = Modifier.fillMaxWidth().padding(horizontal = Design.page), minLines = 2, maxLines = 5, enabled = !busy)
        if (vm.importedText.isNotBlank()) TextButton(vm::sourcesFromImportedList, Modifier.padding(horizontal = Design.page), enabled = !busy) { Text("Use source links from imported TXT/CSV/JSON") }
        Row(Modifier.padding(horizontal = Design.md), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(contentPermission, { contentPermission = it; selected = emptySet() })
            Text("I own the audio and supplied artwork or have licenses, and the source permits downloading.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.padding(horizontal = Design.md), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(servicePermission, { servicePermission = it; selected = emptySet() })
            Text("For restricted streaming services, I also have the service's download authorization. Content-owner permission alone does not override service terms.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.padding(horizontal = Design.page), verticalArrangement = Arrangement.spacedBy(Design.xs)) {
            Button({ selected = emptySet(); vm.inspectEngineSources(urls, contentPermission, servicePermission) }, enabled = urls.isNotBlank() && contentPermission && !busy) { Text("Read source metadata") }
            if (busy) TextButton(vm::cancelSourceSearch) { Text("Cancel") }
        }
        if (report.isNotBlank()) Text(report, Modifier.padding(horizontal = Design.page), style = MaterialTheme.typography.bodySmall)
        candidates.forEach { candidate ->
            Surface(Modifier.fillMaxWidth().padding(horizontal = Design.page), color = MaterialTheme.colorScheme.surface, shape = Design.small) {
                Column(Modifier.padding(Design.md), verticalArrangement = Arrangement.spacedBy(Design.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(candidate.id in selected, { checked -> selected = if (checked) selected + candidate.id else selected - candidate.id })
                        Text(candidate.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(candidate.artist, style = MaterialTheme.typography.bodyMedium)
                    Text("${if (candidate.duration > 0) time(candidate.duration) else "Duration checked after transfer"} · ${candidate.license}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (candidate.request != null) Text("Requested recording: title, full artist credits, version and duration will be checked before saving.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (candidates.isNotEmpty()) {
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Playlist name") }, modifier = Modifier.fillMaxWidth().padding(horizontal = Design.page), singleLine = true)
            Button({ confirming = true }, Modifier.padding(horizontal = Design.page), enabled = selected.isNotEmpty() && hasDestination && contentPermission && !busy && name.isNotBlank()) { Text("Download reviewed · ${selected.size}") }
            if (!hasDestination) Text("Choose the music destination below before downloading.", Modifier.padding(horizontal = Design.page), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("Download reviewed audio?") }, text = { Text("Yono will use the bundled engine, verify complete audio, preserve the original encoding, add metadata/artwork when available, and save it to your chosen playlist folder. Existing recordings are reused when their identity is verified.") }, confirmButton = { TextButton({ beforeDownload(); vm.engineDownload(name, candidates.filter { it.id in selected }, servicePermission); confirming = false; selected = emptySet() }) { Text("Unduh") } }, dismissButton = { TextButton({ confirming = false }) { Text("Batal") } })
}
