package com.yonzmusic.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.MusicViewModel
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.search.OnlineSong

/** One compact approval surface, reused from Search and Now Playing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun StreamDownloadSheet(song:OnlineSong,vm:MusicViewModel,close:()->Unit,queued:()->Unit) {
    val context=LocalContext.current;val app=context.applicationContext as YonzApp
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val destination by vm.outputDestination.collectAsStateWithLifecycle()
    var authorized by remember(song.id){mutableStateOf(false)}
    val savedPlaylist by vm.onlineDownloadPlaylist.collectAsStateWithLifecycle()
    var playlist by remember(song.id,savedPlaylist){mutableStateOf(savedPlaylist)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        result.data?.data?.let {uri->
            val flags=(result.data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching {require(flags==(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION));context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);vm.destination(uri)}.onFailure {vm.notify("Folder belum memberi akses menetap. Pilih folder lain.")}
        }
    }
    ModalBottomSheet(onDismissRequest=close,modifier=Modifier.statusBarsPadding(),sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        DialogSystemBars()
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(Design.page),verticalArrangement=Arrangement.spacedBy(Design.md)) {
            Text("Simpan untuk nanti",style=MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Design.md)) {
                SourceThumbnail(song.artwork)
                Column(Modifier.weight(1f)) {
                    Text(song.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(song.credit.isNotBlank())Text(if(song.artistVerified)song.credit else "Kanal: ${song.credit}",style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Design.md)) {
                OutlinedButton({picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))},Modifier.weight(1f)) {
                    Text(if(destination.isBlank())"Pilih folder tujuan"else runCatching {android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(destination)).substringAfter(':').ifBlank {"Folder tujuan"}+" · Ubah"}.getOrDefault("Folder tujuan · Ubah"),maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("Playlist",style=MaterialTheme.typography.labelMedium)
                    Switch(playlist,{playlist=it},modifier=Modifier.semantics {contentDescription="Buat playlist"})
                }
            }
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(authorized,{authorized=it});Text("Saya berhak mengunduh audio ini dan sumber mengizinkan.",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)}
            Text(if (preferences.hideDownloadsFromGallery) "Format asli · tersembunyi dari Galeri (atur di Pengaturan)" else "Format asli · metadata & sampul",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button({vm.onlinePlaylist(playlist);app.acquisition.enqueueSelected(song,destination,playlist,vm::notify);close();queued()},enabled=authorized && destination.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Tambah ke unduhan")}
        }
    }
}
