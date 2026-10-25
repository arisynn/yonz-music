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
    val destination by vm.outputDestination.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("auto") }
    var name by rememberSaveable { mutableStateOf("Playlist saya") }
    var authorized by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(plan?.optString("id")) { if (plan != null) editing = false }
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
                } }.onSuccess { value -> text = value; kind = when { value.trimStart().startsWith("{") || value.trimStart().startsWith("[") -> "json"; value.startsWith("#EXTM3U") -> "m3u"; value.lineSequence().firstOrNull().orEmpty().contains(",") -> "csv"; else -> "text" } }.onFailure { vm.notify(it.message ?: "Gagal membaca berkas") }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Design.page), verticalArrangement = Arrangement.spacedBy(Design.sm)) {
        Text("Unduh musik", style = MaterialTheme.typography.headlineSmall)
        if (editing || plan == null) {
        OutlinedTextField(text, { if (it.toByteArray().size <= 2 * 1024 * 1024) { text = it; kind = "auto" } else vm.notify("Masukan melebihi 2 MiB") }, label = { Text("Tempel tautan atau daftar lagu") }, placeholder = { Text("Artis - Judul, satu per baris") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Row(horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
            TextButton({ file.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }, enabled = !busy) { Text("Pilih berkas") }
            TextButton({ folder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) }, enabled = !busy) { Text(if (destination.isBlank()) "Pilih tujuan" else "Ubah tujuan") }
        }
        if (destination.isNotBlank()) Text("Simpan ke: ${Uri.decode(destination.substringAfterLast('/')).substringAfter(':')}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
        } else TextButton({ editing = true }) { Text("Ubah masukan / tujuan") }
        Row { Checkbox(authorized, { authorized = it }, enabled = !busy); Text("Saya berhak mengunduh audio ini dan sumber/layanannya mengizinkan.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
        if (!busy && (editing || plan == null)) Button({ repo.submit("prepare", JSONObject().put("kind", kind).put("content", text).put("name", name)) }, enabled = authorized && text.isNotBlank() && destination.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Periksa daftar") }
        else if (busy) {
            val stage = event.optString("stage")
            Text(when(stage) { "cancelling" -> "Membatalkan…"; "matching", "verifying" -> "Memeriksa rekaman…"; "downloading" -> "Mengunduh audio…"; "saving" -> "Menyimpan…"; "snapshot" -> "Memeriksa koleksi pilihan…"; else -> event.optString("message", "Membaca daftar…") }, style = MaterialTheme.typography.bodyMedium)
            val total = event.optInt("total"); if (total > 0) Text("${event.optInt("completed")} / $total", style = MaterialTheme.typography.bodySmall)
            val bytes = event.optLong("downloaded_bytes"); val totalBytes = event.optLong("total_bytes")
            if (totalBytes > 0) LinearProgressIndicator(progress = { (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(repo::cancel, enabled = stage != "cancelling") { Text("Batal") }
        }
        if (plan == null && !busy) TextButton({ repo.submit("get_last") }, enabled = destination.isNotBlank()) { Text("Pulihkan pemeriksaan terakhir") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (!editing) plan?.let { p ->
            val counts = p.optJSONObject("counts") ?: JSONObject()
            Text("${p.optInt("total")} lagu · ${counts.optInt("ready")} siap · ${counts.optInt("exists") + counts.optInt("duplicate")} sudah ada", style = MaterialTheme.typography.titleSmall)
            Text("${counts.optInt("ambiguous")} perlu dipilih · ${counts.optInt("unmatched") + counts.optInt("invalid") + counts.optInt("failed") + counts.optInt("pending")} belum cocok", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Nama playlist") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            if (counts.optInt("ready") + counts.optInt("exists") > 0) Button({
                val rows = p.getJSONArray("entries"); val ids = JSONArray()
                for (i in 0 until rows.length()) { val row = rows.getJSONObject(i); if (row.optString("status") in setOf("ready", "exists")) ids.put(row.getString("id")) }
                repo.submit("acquire", JSONObject().put("plan_id", p.getString("id")).put("entry_ids", ids).put("format", "original").put("name", name))
            }, enabled = !busy && authorized, modifier = Modifier.fillMaxWidth()) { Text(if (counts.optInt("ready") > 0) "Unduh ${counts.optInt("ready")} · format asli" else "Buat playlist dari lagu lokal") }
            Row {
                TextButton({ details = true }) { Text("Lihat rincian") }
                TextButton({ repo.submit("retry", JSONObject().put("plan_id", p.getString("id"))) }, enabled = !busy) { Text("Coba gagal lagi") }
            }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Hasil pemeriksaan") }, text = {
        val rows = plan?.optJSONArray("entries") ?: JSONArray()
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(rows.length()) { i -> val row = rows.getJSONObject(i)
                Text("${i+1}. ${row.optJSONObject("song")?.optString("title") ?: row.optJSONObject("request")?.optString("input")} · ${row.optString("status")}", style = MaterialTheme.typography.bodyMedium)
                row.optJSONObject("error")?.let { error -> Text(error.optString("message"), style = MaterialTheme.typography.bodySmall)
                    error.optJSONArray("choices")?.let { choices -> for (j in 0 until choices.length()) { val c = choices.getJSONObject(j); TextButton({ repo.submit("choose", JSONObject().put("plan_id", plan!!.getString("id")).put("entry_id", row.getString("id")).put("youtube_id", c.getString("youtube_id"))); details = false }, enabled = !busy) { Text("Pilih: ${c.optString("title")} · ${c.optJSONArray("artists")}") } } }
                }
                HorizontalDivider()
            }
        }
    }, confirmButton = { TextButton({ details = false }) { Text("Tutup") } })
}
private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; require(out.size() + n <= limit) { "Berkas melebihi 2 MiB" }; out.write(buffer, 0, n) }
    return out.toByteArray()
}
