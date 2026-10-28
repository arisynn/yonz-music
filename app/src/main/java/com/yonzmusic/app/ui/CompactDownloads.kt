package com.yonzmusic.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.MusicViewModel
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.importer.PasteListFormatter
import org.json.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.LazyColumn

@Composable fun CompactDownloads(vm: MusicViewModel) {
    val context = LocalContext.current
    val repo = (context.applicationContext as YonzApp).acquisition
    val busy by repo.busy.collectAsStateWithLifecycle()
    val event by repo.event.collectAsStateWithLifecycle()
    val plan by repo.plan.collectAsStateWithLifecycle()
    val message by repo.message.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val destination by vm.outputDestination.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val artists = remember(tracks) { tracks.filter { it.available }.flatMap { it.artist.split(';') }.distinct() }
    var text by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("auto") }
    val savedDraft by repo.reviewDraft.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var createPlaylist by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val planId=plan?.optString("id").orEmpty()
    LaunchedEffect(planId,savedDraft?.optString("plan_id")) {
        plan?.let { p ->
            val draft=savedDraft?.takeIf { it.optString("plan_id")==planId } ?: p
            name=draft.optString("name",p.optString("name"))
            createPlaylist=draft.optBoolean("create_playlist",false)
            val ids=draft.optJSONArray("entry_ids") ?: p.optJSONArray("selected_ids")
            val rows=p.optJSONArray("entries") ?: JSONArray()
            selected=if(ids!=null)(0 until ids.length()).map { ids.getString(it) }else (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
        }
    }
    fun draft(): JSONObject = JSONObject().put("plan_id",planId).put("name",name).put("create_playlist",createPlaylist).put("entry_ids",JSONArray(selected))
    fun persistReview() { if(planId.isNotBlank())repo.saveReview(draft()) }
    var authorized by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(true) }
    val pendingInput by vm.pendingDownloadInput.collectAsStateWithLifecycle()
    LaunchedEffect(pendingInput) {
        if(pendingInput.isNotBlank()) {text=pendingInput;kind="auto";editing=true;authorized=false;vm.pendingDownloadInput.value=""}
    }
    LaunchedEffect(plan?.optString("id")) { if (plan != null) editing = false }
    var downloadsOpen by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data?.data != null) runCatching {
            val required = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            require(data.flags and required == required)
            context.contentResolver.takePersistableUriPermission(data.data!!, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.destination(data.data!!)
        }.onFailure { vm.notify("Pilih folder dengan akses baca/tulis permanen.") }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytesBounded(2 * 1024 * 1024).toString(Charsets.UTF_8).removePrefix("\uFEFF") } ?: error("Berkas tidak dapat dibaca")
                } }.onSuccess { value -> text = value; kind = PasteListFormatter.detectKind(value) }.onFailure { vm.notify(it.message ?: "Gagal membaca berkas") }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Design.page), verticalArrangement = Arrangement.spacedBy(Design.sm)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("Unduh musik",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
            TextButton({downloadsOpen=true}){Text("Daftar")}
        }
        if (editing || plan == null) {
        SmartSongListField(text, { text = it; kind = PasteListFormatter.detectKind(it) }, artists,
            modifier = Modifier.fillMaxWidth(), enabled = !busy, onTooLarge = { vm.notify("Masukan melebihi 2 MiB") })
        Row(horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
            TextButton({ file.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }, enabled = !busy) { Text("Pilih berkas") }
            TextButton({ folder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) }, enabled = !busy) { Text(if (destination.isBlank()) "Pilih tujuan" else "Ubah tujuan") }
        }
        if (destination.isNotBlank()) Text("Simpan ke: ${Uri.decode(destination.substringAfterLast('/')).substringAfter(':')}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
        if (preferences.hideDownloadsFromGallery) Text("Folder unduhan tersembunyi dari Galeri · atur di Pengaturan", style = MaterialTheme.typography.bodySmall)
        } else TextButton({ editing = true }) { Text("Ubah masukan / tujuan") }
        Row { Checkbox(authorized, { authorized = it }, enabled = !busy); Text("Saya berhak mengunduh audio ini dan sumber/layanannya mengizinkan.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
        if (!busy && (editing || plan == null)) Button({ repo.submit("prepare", JSONObject().put("kind", kind).put("content", text).put("resume_existing",true)) }, enabled = authorized && text.isNotBlank() && destination.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Periksa daftar") }
        else if (busy) {
            val stage = event.optString("stage")
            Text(when(stage) { "cancelling" -> "Membatalkan…"; "matching", "verifying" -> "Memeriksa rekaman…"; "downloading" -> "Mengunduh audio…"; "saving" -> "Menyimpan…"; "snapshot" -> "Memeriksa koleksi pilihan…"; else -> event.optString("message", "Membaca daftar…") }, style = MaterialTheme.typography.bodyMedium)
            val total = event.optInt("total"); if (total > 0) Text("${event.optInt("completed")} / $total", style = MaterialTheme.typography.bodySmall)
            val bytes = event.optLong("downloaded_bytes"); val totalBytes = event.optLong("total_bytes")
            val fraction = ProgressFraction.of(bytes,totalBytes)
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(repo::cancel, enabled = stage != "cancelling") { Text("Batal") }
        }
        if (plan == null && !busy && text.isBlank()) TextButton({ repo.submit("get_last") }, enabled = destination.isNotBlank()) { Text("Pulihkan pemeriksaan terakhir") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (!editing) plan?.let { p ->
            val counts = p.optJSONObject("counts") ?: JSONObject()
            if(p.optString("reuse_reason").isNotBlank())Text(p.optString("reuse_reason"),style=MaterialTheme.typography.bodySmall)
            Text("${p.optInt("total")} lagu · ${counts.optInt("ready")} siap · ${counts.optInt("exists") + counts.optInt("duplicate")} sudah ada · ${counts.optInt("downloaded")} selesai", style = MaterialTheme.typography.titleSmall)
            Text("${counts.optInt("ambiguous")} perlu dipilih · ${counts.optInt("unmatched") + counts.optInt("invalid") + counts.optInt("failed") + counts.optInt("pending")} belum cocok", style = MaterialTheme.typography.bodySmall)
            val rows=p.getJSONArray("entries")
            val chosen=(0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("id") in selected && it.optString("status") in setOf("ready","exists") }
            val ready=chosen.count { it.optString("status")=="ready" }
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(createPlaylist,{createPlaylist=it;persistReview()},enabled=!busy && !p.optBoolean("layout_locked"))
                Text("Buat playlist di aplikasi",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            }
            if(createPlaylist) OutlinedTextField(name,{name=it.take(100);persistReview()},label={Text("Nama playlist · otomatis jika kosong")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy && !p.optBoolean("layout_locked"))
            Text(if(chosen.size<=1 || !createPlaylist) "Audio langsung di folder tujuan · sampul di .cover" else "Audio di subfolder ${name.ifBlank { p.optString("name") }} · sampul di .cover",style=MaterialTheme.typography.bodySmall)
            Button({
                persistReview()
                repo.submit("acquire",draft().put("entry_ids",JSONArray(chosen.map { it.getString("id") })).put("format","original"))
            },enabled=!busy && authorized && chosen.isNotEmpty() && (ready>0 || createPlaylist),modifier=Modifier.fillMaxWidth()) {
                Text(if(ready>0) "Unduh $ready · format asli" else if(createPlaylist) "Buat playlist · ${chosen.size} lagu lokal" else "Lagu pilihan sudah tersedia")
            }

            Row {
                TextButton({ details = true }) { Text("Tinjau & pilih · ${chosen.size}") }
                TextButton({ repo.submit("retry", JSONObject().put("plan_id", p.getString("id"))) }, enabled = !busy) { Text("Coba gagal lagi") }
            }
        }
    }
    if(downloadsOpen) DownloadSheet(vm){downloadsOpen=false}
    if(details) plan?.let { p -> PlaylistReviewSheet(p,selected,!busy,{ selected=it;persistReview() },{ row,choice ->
        persistReview();repo.submit("choose",JSONObject().put("plan_id",p.getString("id")).put("entry_id",row.getString("id")).put("youtube_id",choice));details=false
    },{details=false}) }

}
private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; require(out.size() + n <= limit) { "Berkas melebihi 2 MiB" }; out.write(buffer, 0, n) }
    return out.toByteArray()
}
