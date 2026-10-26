package com.yonzmusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.YonzApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun SpotifyConnectionDialog(close:()->Unit) {
    val context=LocalContext.current
    val connection=(context.applicationContext as YonzApp).spotify
    val message by connection.message.collectAsStateWithLifecycle()
    var clientId by rememberSaveable {mutableStateOf("")}
    var error by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest=close,title={Text("Metadata Spotify")},text={
        Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
            Text("Koneksi opsional untuk API resmi. Memerlukan Client ID aplikasi Spotify Developer milikmu atau konfigurasi yang diizinkan pemiliknya. Jangan masukkan client secret atau password.")
            Text("Daftarkan redirect URI persis:",style=MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.text.selection.SelectionContainer {Text(connection.redirect,style=MaterialTheme.typography.bodySmall)}
            OutlinedTextField(clientId,{clientId=it.trim().take(32)},label={Text("Client ID")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
            Text("Development Mode saat ini membatasi akun dan umumnya isi playlist milik/kolaborasi pengguna. Playlist publik lain, editorial, atau Radio belum tentu dapat dibaca. Metadata bukan audio Spotify; TXT/CSV/JSON tetap tersedia.",style=MaterialTheme.typography.bodySmall)
            if(message.isNotBlank())Text(message,style=MaterialTheme.typography.bodySmall)
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            TextButton({scope.launch{withContext(Dispatchers.IO){connection.disconnect()}}},enabled=!busy){Text("Putuskan koneksi lokal")}
        }
    },confirmButton={TextButton({scope.launch{
        busy=true
        runCatching {withContext(Dispatchers.IO){connection.begin(clientId)}}.onSuccess {intent->runCatching{context.startActivity(intent)}.onFailure{error="Browser belum dapat dibuka."}}
            .onFailure {error=it.message.orEmpty()}
        busy=false
    }},enabled=!busy && clientId.length==32){Text("Hubungkan di browser")}},dismissButton={TextButton(close){Text("Tutup")}})
}
