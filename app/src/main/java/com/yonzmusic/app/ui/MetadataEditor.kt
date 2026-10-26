package com.yonzmusic.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yonzmusic.app.data.Track
import com.yonzmusic.app.data.Availability

@Composable fun MetadataEditor(track: Track, dismiss: ()->Unit, save:(String,String,String,Uri?)->Unit, export:()->Unit, import:()->Unit) {
    var title by rememberSaveable(track.id) { mutableStateOf(track.title) }
    var artists by rememberSaveable(track.id) { mutableStateOf(com.yonzmusic.app.data.MetadataPolicy.known(track.artist)) }
    var album by rememberSaveable(track.id) { mutableStateOf(com.yonzmusic.app.data.MetadataPolicy.known(track.album)) }
    var artwork by rememberSaveable(track.id) { mutableStateOf<String?>(null) }
    val context=LocalContext.current
    val image=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
        runCatching { context.contentResolver.takePersistableUriPermission(it,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        artwork=it.toString()
    } }
    androidx.compose.ui.window.Dialog(onDismissRequest=dismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val view=androidx.compose.ui.platform.LocalView.current
        SideEffect { (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(Design.gap),contentAlignment=androidx.compose.ui.Alignment.Center) {
            Surface(Modifier.widthIn(max=560.dp).fillMaxWidth().heightIn(max=maxHeight),shape=Design.card,color=MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(Design.page),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
                    Text("Metadata dan sampul",style=MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
                        Text("Metadata disimpan tanpa mengubah audio. Manifest dibuat jika folder dapat ditulis.",style=MaterialTheme.typography.bodySmall)
                        OutlinedTextField(title,{title=it.take(512)},label={Text("Judul")},maxLines=2,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(artists,{artists=it.take(512)},label={Text("Artis · pisahkan dengan ;")},maxLines=3,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(album,{album=it.take(512)},label={Text("Album (jika diketahui)")},maxLines=2,modifier=Modifier.fillMaxWidth())
                        TextButton({image.launch(arrayOf("image/*"))}){Text(if(artwork==null)"Pilih sampul lokal" else "Sampul dipilih")}
                        TextButton(import){Text("Impor manifest untuk audio ini")}
                        TextButton(export){Text("Ekspor manifest tersimpan")}
                    }
                    Row(Modifier.align(androidx.compose.ui.Alignment.End)) {
                        TextButton(dismiss){Text("Batal")}
                        TextButton({save(title,artists,album,artwork?.let(Uri::parse))},enabled=title.isNotBlank()){Text("Simpan")}
                    }
                }
            }
        }
    }
}
@Composable fun RelocationDialog(track:Track, candidates:List<String>, dismiss:()->Unit, choose:()->Unit, reconnect:()->Unit, use:(Uri)->Unit) {
    AlertDialog(onDismissRequest=dismiss,title={Text("Hubungkan lokasi audio")},text={ Column(Modifier.heightIn(max=300.dp).verticalScroll(rememberScrollState())) {
        Text(Availability.label(track.availability))
        Text("ID track, favorit, playlist dan riwayat dipertahankan. Hanya folder pilihan yang diperiksa. Jika hash lama belum tersedia, pemilihan file adalah konfirmasi manual rekaman asli; durasi tetap diperiksa.",style=MaterialTheme.typography.bodySmall)
        candidates.forEach { value -> TextButton({use(Uri.parse(value))}){Text(Uri.decode(value.substringAfterLast('/')),maxLines=3)} }
        TextButton(reconnect){Text("Hubungkan ulang folder")}
    } },confirmButton={TextButton(choose){Text("Pilih lokasi baru")}},dismissButton={TextButton(dismiss){Text("Tutup")}})
}
