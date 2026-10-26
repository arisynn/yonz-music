package com.yonzmusic.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.MusicViewModel
import org.json.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun downloadStatus(value:String):String=when(value) {
    "complete" -> "Selesai memproses";"complete_with_errors" -> "Selesai · sebagian gagal/dibatalkan";"review" -> "Siap diperiksa";"paused_network" -> "Jaringan bermasalah · coba ulang";"preparing" -> "Membaca metadata";
    "ready","pending","Queued" -> "Menunggu";"matching","verifying","reading_source","reading_playlist" -> "Membaca metadata"
    "downloading","Downloading","track_started" -> "Mengunduh";"saving","artwork","validating_audio","Validating","Publishing","Saved" -> "Memproses metadata/sampul"
    "downloaded","Complete" -> "Selesai";"exists","Existing" -> "Sudah ada";"duplicate","Duplicate" -> "Duplikat"
    "failed","Failed","unmatched","invalid","ambiguous" -> "Gagal / perlu diperiksa";"cancelled","Cancelled" -> "Dibatalkan";else -> value
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DownloadSheet(vm:MusicViewModel,dismiss:()->Unit) {
    val context=LocalContext.current;val repo=(context.applicationContext as YonzApp).acquisition
    val plans by repo.history.collectAsStateWithLifecycle();val busy by repo.busy.collectAsStateWithLifecycle();val active by repo.activeEntry.collectAsStateWithLifecycle()
    val activePlan by repo.activePlan.collectAsStateWithLifecycle();val action by repo.currentAction.collectAsStateWithLifecycle()
    val tracks by vm.collectionTracks.collectAsStateWithLifecycle()
    val byHash=remember(tracks){tracks.filter{it.available && it.active && it.sha256.isNotBlank()}.associateBy{it.sha256}}
    val scope=rememberCoroutineScope()
    var authorized by rememberSaveable { mutableStateOf(false) }
    val message by repo.message.collectAsStateWithLifecycle();val legacy by vm.downloads.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf("") };var chooser by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { repo.refreshHistory() }
    val plan=plans.firstOrNull { it.optString("id")==selected } ?: plans.firstOrNull { it.optString("id")==activePlan } ?: plans.firstOrNull()
    val rows=plan?.optJSONArray("entries") ?: JSONArray()
    val done=(0 until rows.length()).count { val row=rows.getJSONObject(it);row.optString("status") in setOf("downloaded","exists","duplicate") && row.optString("uri").isNotBlank() }
    ModalBottomSheet(onDismissRequest=dismiss) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding(),contentPadding=PaddingValues(bottom=Design.page),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
            item { SectionTitle("Daftar unduhan",if(busy)"Ada aktivitas · progres nyata" else "Riwayat disimpan di perangkat",action={if(busy)TextButton(repo::cancel){Text("Batalkan antrean")}}) }
            if(plans.isNotEmpty()) item { TextButton({chooser=true},Modifier.padding(horizontal=Design.page)){Text("${plan?.optString("name")} · pilih riwayat",maxLines=2)};Text("$done / ${rows.length()} selesai",Modifier.padding(horizontal=Design.page),style=MaterialTheme.typography.bodySmall) }
            if(plan!=null && !busy && (plan.optJSONObject("counts")?.optInt("ready") ?: 0)>0) item {
                Column(Modifier.padding(horizontal=Design.page)) {
                    Row { Checkbox(authorized,{authorized=it});Text("Saya berhak mengunduh audio dan sumber mengizinkan.",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall) }
                    Button({ val selectedIds=JSONArray();for(i in 0 until rows.length()){val row=rows.getJSONObject(i);if(row.optString("status") in setOf("ready","exists"))selectedIds.put(row.getString("id"))};repo.submit("acquire",JSONObject().put("plan_id",plan.getString("id")).put("entry_ids",selectedIds).put("format","original")) },enabled=authorized){Text("Unduh yang siap · format asli")}
                }
            }
            if(message.isNotBlank())item { Text(message,Modifier.padding(horizontal=Design.page),style=MaterialTheme.typography.bodySmall) }
            if(plans.isEmpty() && legacy.isEmpty()) item { Text("Belum ada unduhan. Periksa daftar melalui Import → Downloads.",Modifier.padding(Design.page)) }
            items(rows.length(),key={rows.getJSONObject(it).optString("id",it.toString())}) { index ->
                val row=rows.getJSONObject(index);val song=row.optJSONObject("song") ?: row.optJSONObject("request")?.optJSONObject("song") ?: JSONObject()
                val artists=song.optJSONArray("artists") ?: JSONArray();val artist=(0 until artists.length()).joinToString("; "){artists.optString(it)}
                val status=row.optString("status");val id=row.optString("id");val planId=plan?.optString("id").orEmpty();val uri=row.optString("uri")
                val isActive=busy && active==id && activePlan==planId
                val bound=byHash[row.optJSONObject("audio")?.optString("sha256")]
                val image=bound?.artUri ?: row.optString("artwork_uri")
                Column(Modifier.fillMaxWidth().padding(horizontal=Design.page,vertical=Design.xs),verticalArrangement=Arrangement.spacedBy(Design.xs)) {
                    Row(horizontalArrangement=Arrangement.spacedBy(Design.md)) {
                        if(image.isNotBlank())Artwork(com.yonzmusic.app.data.Track(id,uri,song.optString("title"),artUri=image,artRevision=plan?.optLong("updated_at") ?: 0),Modifier.size(48.dp))
                        Column(Modifier.weight(1f)) { Text(song.optString("title").ifBlank { row.optJSONObject("request")?.optString("input") ?: "Entri ${index+1}" },maxLines=2,style=MaterialTheme.typography.titleSmall);if(artist.isNotBlank())Text(artist,maxLines=2,style=MaterialTheme.typography.bodySmall) }
                    }
                    if(isActive) DownloadProgress() else DownloadStatusLabel(downloadStatus(status))
                    row.optJSONObject("error")?.let { Text(it.optString("message"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
                    Row {
                        if(status !in setOf("downloaded","exists","duplicate","cancelled")) TextButton({repo.cancelItem(planId,id)},enabled=!busy || (action=="acquire" && activePlan==planId)){Text("Batalkan")}
                        if(status in setOf("failed","cancelled","unmatched","invalid","pending"))TextButton({repo.retryItem(planId,row)},enabled=!busy){Text("Coba ulang")}
                        if(uri.isNotBlank() && status in setOf("downloaded","exists","duplicate"))TextButton({
                            scope.launch {
                                val target=Uri.parse(bound?.uri ?: uri)
                                val readable=withContext(Dispatchers.IO){runCatching{context.contentResolver.openFileDescriptor(target,"r")?.use{true} ?: false}.getOrDefault(false)}
                                if(!readable)vm.notify("Lokasi hasil berubah. Pilih lokasi baru atau hubungkan folder melalui Library.")
                                else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(target,row.optJSONObject("audio")?.optString("mime","audio/*") ?: "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure {vm.notify("Tidak ada aplikasi untuk membuka hasil. Audio tetap di folder tujuan.")}
                            }
                        }){Text("Buka hasil")}
                    }
                    HorizontalDivider()
                }
            }
            if(legacy.isNotEmpty())item { SectionTitle("Riwayat versi sebelumnya") }
            items(legacy,key={"legacy_${it.id}"}) { job -> Column(Modifier.padding(horizontal=Design.page)) {
                Text(job.title);Text("${job.artist} · ${downloadStatus(job.status)}",style=MaterialTheme.typography.bodySmall)
                if(job.error.isNotBlank())Text(job.error,style=MaterialTheme.typography.bodySmall)
                Row { if(job.status in setOf("Queued","Downloading","Validating","Publishing"))TextButton({vm.cancelDownload(job.batchId)}){Text("Batalkan antrean lama")}
                    if(job.status in setOf("Failed","Cancelled"))TextButton({vm.retryDownload(job.batchId)},enabled=!busy){Text("Coba ulang")}
                }
            } }
        }
    }
    if(chooser)AlertDialog(onDismissRequest={chooser=false},title={Text("Riwayat unduhan")},text={ LazyColumn(Modifier.heightIn(max=360.dp)) {
        items(plans,key={it.optString("id")}) { p -> ListItem(headlineContent={Text(p.optString("name"),maxLines=2)},supportingContent={Text("${p.optInt("total")} entri · ${downloadStatus(p.optString("state"))}")},modifier=Modifier.clickable{selected=p.optString("id");chooser=false}) }
    } },confirmButton={TextButton({chooser=false}){Text("Tutup")}})
}
@Composable private fun DownloadProgress() {
    val context=LocalContext.current;val event by (context.applicationContext as YonzApp).acquisition.event.collectAsStateWithLifecycle()
    val bytes=event.optLong("downloaded_bytes");val total=event.optLong("total_bytes")
    DownloadStatusLabel(downloadStatus(event.optString("stage")))
    val count=event.optInt("total");if(count>0 && event.optString("stage") in setOf("matching","verifying"))Text("${event.optInt("completed")} / $count diperiksa",style=MaterialTheme.typography.bodySmall)
    val fraction=ProgressFraction.of(bytes,total)
    if(fraction!=null) { LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth());Text("${bytes/1024} / ${total/1024} KiB",style=MaterialTheme.typography.bodySmall) }
    else LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable private fun DownloadStatusLabel(status:String) {
    androidx.compose.animation.Crossfade(status,animationSpec=androidx.compose.animation.core.tween(if(LocalReducedMotion.current)0 else 140),label="download status") { value -> Text(value,style=MaterialTheme.typography.bodySmall) }
}
