package com.yonzmusic.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.yonzmusic.app.importer.MusicListParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Explicit local portraits. No name-based web guesses, no album art labelled as an artist photo. */
@Composable fun ArtistPortrait(artist: String, modifier: Modifier = Modifier) {
    val context=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    val file=remember(artist) {
        val key=MessageDigest.getInstance("SHA-256").digest(MusicListParser.normalize(artist).toByteArray()).joinToString(""){"%02x".format(it)}
        File(context.filesDir,"artist-portraits/$key.jpg")
    }
    var revision by remember(artist) { mutableLongStateOf(0) }
    val bitmap by produceState<Bitmap?>(null,artist,revision) { value=withContext(Dispatchers.IO) { if(file.isFile)BitmapFactory.decodeFile(file.path)else null } }
    var options by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if(uri!=null)scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val bytes=context.contentResolver.openInputStream(uri)?.use { input ->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=8*1024*1024);out.write(buffer,0,n)};out.toByteArray()
                } ?: error("Gambar tidak terbaca")
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=40_000_000)
                var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1024)sample*=2
                val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("Format gambar belum terbaca")
                file.parentFile!!.mkdirs()
                val atomic=android.util.AtomicFile(file);val output=atomic.startWrite()
                try { check(decoded.compress(Bitmap.CompressFormat.JPEG,90,output));atomic.finishWrite(output) }catch(e:Exception){atomic.failWrite(output);throw e}finally{decoded.recycle()}
            } }.onSuccess { revision++ }.onFailure { error="Foto belum dapat disimpan. Pilih gambar JPG/PNG yang lebih kecil (maksimal 8 MiB)." }
        }
    }
    val initials=remember(artist) { artist.split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() } }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer).clickable { options=true }
        .semantics { contentDescription="Foto artis $artist. Ketuk untuk memilih foto." },contentAlignment=Alignment.Center) {
        if(bitmap!=null)Image(bitmap!!.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Text(initials,style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.onSecondaryContainer)
    }
    if(options)AlertDialog(onDismissRequest={options=false},title={Text(artist)},text={Text("Gunakan foto pilihanmu. Foto disimpan di perangkat dan tetap tampil tanpa internet; sampul album tidak dianggap sebagai foto artis.")},confirmButton={TextButton({options=false;picker.launch(arrayOf("image/jpeg","image/png","image/webp"))}){Text("Pilih foto")}},dismissButton={TextButton({options=false;scope.launch { withContext(Dispatchers.IO){file.delete()};revision++ }}){Text("Gunakan inisial")}})
    if(error.isNotBlank())AlertDialog(onDismissRequest={error=""},title={Text("Foto artis")},text={Text(error)},confirmButton={TextButton({error=""}){Text("Tutup")}})
}
