package com.yonzmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import com.yonzmusic.app.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.MusicViewModel
import com.yonzmusic.app.data.OnlineCollection
import com.yonzmusic.app.search.OnlineSong

@Composable fun CreateCollectionDialog(close:()->Unit,save:(String,Boolean)->Unit) {
    var name by rememberSaveable {mutableStateOf("")};var online by rememberSaveable {mutableStateOf(false)}
    AlertDialog(modifier=Modifier.imePadding(),properties=DialogProperties(decorFitsSystemWindows=false),onDismissRequest=close,title={Text("Create playlist")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(name,{name=it.take(100)},label={Text("Nama playlist")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth().clickable{online=!online}) {Checkbox(online,{online=it});Text("Playlist online")}
        Text(if(online)"Simpan referensi lagu dari Search online. Audio tidak diunduh otomatis."else "Tambahkan lagu dari koleksi lokal.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton({save(name.trim(),online)},enabled=name.isNotBlank()){Text("Buat")}},dismissButton={TextButton(close){Text("Batal")}})
}

@Composable fun OnlineSaveSongDialog(vm:MusicViewModel,song:OnlineSong,close:()->Unit) {
    val lists by vm.onlineCollections.collectAsStateWithLifecycle();var create by rememberSaveable{mutableStateOf(false)};var name by rememberSaveable{mutableStateOf("")}
    AlertDialog(modifier=Modifier.imePadding(),properties=DialogProperties(decorFitsSystemWindows=false),onDismissRequest=close,title={Text("Simpan ke playlist online")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        if(!create && lists.isNotEmpty())LazyColumn(Modifier.heightIn(max=220.dp)){items(lists,key={it.id}){p->
            ListItem(headlineContent={Text(p.name,maxLines=1,overflow=TextOverflow.Ellipsis)},supportingContent={Text("${p.songs.size} lagu · online")},modifier=Modifier.clickable{vm.appendOnline(p.id,song);close()})
        }}
        if(create || lists.isEmpty())OutlinedTextField(name,{name=it.take(100)},singleLine=true,label={Text("Nama playlist baru")},modifier=Modifier.fillMaxWidth())
        else TextButton({create=true}){Text("Playlist baru")}
    }},confirmButton={if(create || lists.isEmpty())TextButton({vm.createOnline(name,song);close()},enabled=name.isNotBlank()){Text("Simpan")}},dismissButton={TextButton(close){Text("Batal")}})
}

@Composable fun OnlinePlaylistImportDialog(vm:MusicViewModel,close:()->Unit) {
    val busy by vm.onlinePlaylistLoading.collectAsStateWithLifecycle();val preview by vm.onlinePlaylistPreview.collectAsStateWithLifecycle();val error by vm.onlinePlaylistError.collectAsStateWithLifecycle()
    var url by rememberSaveable{mutableStateOf("")};var name by rememberSaveable{mutableStateOf("")}
    LaunchedEffect(preview?.collection?.id){name=preview?.collection?.name.orEmpty()}
    fun dismiss(){vm.closeOnlineImport();close()}
    AlertDialog(modifier=Modifier.imePadding(),properties=DialogProperties(decorFitsSystemWindows=false),onDismissRequest={dismiss()},title={Text("Playlist online")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
        if(preview==null) {
            OutlinedTextField(url,{url=it.take(4096)},label={Text("Tautan YouTube Music / YouTube")},maxLines=3,enabled=!busy,modifier=Modifier.fillMaxWidth())
            Text("Baca daftar publik lalu periksa sebelum menyimpan. Tidak mengunduh audio.",style=MaterialTheme.typography.bodySmall)
        } else {
            OutlinedTextField(name,{name=it.take(100)},label={Text("Nama playlist")},singleLine=true,modifier=Modifier.fillMaxWidth())
            val p=preview!!
            Text("${p.collection.songs.size} siap · ${p.unavailable} tidak tersedia · ${p.duplicates} duplikat",style=MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.height(160.dp)) {
                itemsIndexed(p.collection.songs){i,s->Text("${i+1}. ${s.title}",maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(vertical=Design.xs))}
            }
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton({if(preview==null)vm.readOnlinePlaylist(url)else {vm.saveOnlinePreview(name);close()}},enabled=!busy && if(preview==null)url.isNotBlank()else name.isNotBlank()){Text(if(preview==null)"Baca daftar"else "Simpan")}},dismissButton={TextButton({dismiss()}){Text(if(busy)"Batalkan"else "Tutup")}})
}

@Composable fun OnlineCollectionDetail(vm:MusicViewModel,p:OnlineCollection?,activeId:String?,add:()->Unit,download:(OnlineSong)->Unit,back:()->Unit) {
    var rename by remember{mutableStateOf(false)};var delete by remember{mutableStateOf(false)};var menu by remember{mutableStateOf(false)}
    if(p==null){EmptyState("Playlist tidak ditemukan","Kembali ke Playlists untuk memilih koleksi.","playlist");return}
    LazyColumn(contentPadding=PaddingValues(bottom=Design.page)) {
        item {Row(Modifier.fillMaxWidth().padding(horizontal=Design.page,vertical=Design.sm),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
            Column(Modifier.weight(1f)){Text("${p.songs.size} lagu · online",maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium);Text("Perlu internet",maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            FilledIconButton({vm.playOnlineCollection(p)},enabled=p.songs.isNotEmpty()){Icon(Icons.Rounded.PlayArrow,"Putar playlist online")}
            Box {ActionIcon(Icons.Rounded.MoreVert,"Opsi playlist online",{menu=true});DropdownMenu(menu,{menu=false}) {
                DropdownMenuItem(text={Text("Tambah lagu")},onClick={menu=false;add()})
                DropdownMenuItem(text={Text("Ubah nama")},onClick={menu=false;rename=true})
                DropdownMenuItem(text={Text("Hapus playlist")},onClick={menu=false;delete=true})
            }}
        }}
        if(p.songs.isEmpty())item {Column(Modifier.fillMaxWidth().padding(Design.page),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
            Text("Playlist masih kosong",style=MaterialTheme.typography.titleLarge)
            Text("Cari lagu online, lalu pilih Playlist dari opsi lagu.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(add){Text("Cari lagu")}
        }}
        itemsIndexed(p.songs,key={_,s->s.id}){index,song->
            var options by remember{mutableStateOf(false)}
            Row(Modifier.fillMaxWidth().clickable{vm.playOnlineCollection(p,index)}.padding(horizontal=Design.page,vertical=Design.sm),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Design.md)) {
                SourceThumbnail(song.artwork)
                Column(Modifier.weight(1f)) {
                    Text(song.title,maxLines=2,overflow=TextOverflow.Ellipsis,color=if(song.id==activeId)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(if(song.artistVerified)song.credit else song.credit.takeIf{it.isNotBlank()}?.let{"Kanal: $it"} ?: "Kredit belum tersedia",maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                }
                Box {ActionIcon(Icons.Rounded.MoreVert,"Opsi ${song.title}",{options=true});DropdownMenu(options,{options=false}){
                    DropdownMenuItem(text={Text("Unduh")},onClick={options=false;download(song)})
                    DropdownMenuItem(text={Text("Pindah naik")},enabled=index>0,onClick={options=false;vm.shiftOnline(p.id,song.id,-1)})
                    DropdownMenuItem(text={Text("Pindah turun")},enabled=index<p.songs.lastIndex,onClick={options=false;vm.shiftOnline(p.id,song.id,1)})
                    DropdownMenuItem(text={Text("Hapus dari playlist")},onClick={options=false;vm.removeOnline(p.id,song.id)})
                }}
            }
        }
    }
    if(rename)NameDialog("Ubah nama playlist",p.name,{rename=false}){vm.renameOnline(p.id,it);rename=false}
    if(delete)AlertDialog(onDismissRequest={delete=false},title={Text("Hapus playlist online?")},text={Text("Hanya daftar referensi ini yang dihapus. Berkas audio lokal tetap aman.")},confirmButton={TextButton({vm.deleteOnline(p.id);delete=false;back()}){Text("Hapus")}},dismissButton={TextButton({delete=false}){Text("Batal")}})
}
