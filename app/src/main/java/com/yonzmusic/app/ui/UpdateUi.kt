package com.yonzmusic.app.ui

import com.yonzmusic.app.i18n.Bahasa

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.YonzApp

@Composable fun UpdateSettings() {
    val app = LocalContext.current.applicationContext as YonzApp
    val state by app.updates.ui.collectAsStateWithLifecycle()
    MenuAction(Icons.Rounded.Refresh, "Periksa pembaruan") { app.updates.check(true) }
    if (state.message.isNotBlank()) Text(Bahasa.pesan(state.message), Modifier.padding(horizontal = Design.page, vertical = Design.sm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.status in setOf("Ready", "Permission")) TextButton({ app.updates.install() }, Modifier.padding(horizontal = Design.page)) { Text("Pasang pembaruan yang diunduh") }
    TextButton(app.updates::discard, Modifier.padding(horizontal = Design.page)) { Text("Bersihkan berkas pembaruan") }
}

@Composable fun UpdateDialogs() {
    val context = LocalContext.current
    val manager = (context.applicationContext as YonzApp).updates
    val state = manager.ui.collectAsStateWithLifecycle().value
    val fraction = ProgressFraction.of(state.received, state.total)
    when (state.status) {
        "Available" -> state.artifact?.let { artifact ->
            AlertDialog(onDismissRequest = manager::dismiss, title = { Text("Yono Music ${artifact.versionName}") }, text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Design.sm)) {
                    Text("Pembaruan yang sesuai tersedia · ${"%.1f".format(java.util.Locale.forLanguageTag("id-ID"), artifact.size / 1048576.0)} MB")
                    Text(artifact.summary, style = MaterialTheme.typography.bodyMedium)
                    Text("Tombol Unduh akan mengunduh APK. Android akan meminta konfirmasi sebelum memasangnya.", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(manager::download) { Text("Unduh") } }, dismissButton = { TextButton(manager::dismiss) { Text("Batal") } })
        }
        "Downloading", "Verifying" -> AlertDialog(onDismissRequest = {}, title = { Text(if (state.status == "Verifying") "Memverifikasi pembaruan" else "Mengunduh pembaruan") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(Design.gap)) {
                if (fraction != null && state.status == "Downloading") LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (state.status == "Verifying") "Memeriksa paket, versi, checksum, dan tanda tangan APK." else "${state.received / 1024} / ${state.total / 1024} KB")
            }
        }, confirmButton = {}, dismissButton = { TextButton(manager::cancelDownload) { Text("Batal") } })
        "Permission" -> AlertDialog(onDismissRequest = { manager.ui.value = state.copy(status = "Ready") }, title = { Text("Izinkan pemasangan melalui Android") }, text = { Text(Bahasa.pesan(state.message)) }, confirmButton = { TextButton({
            manager.ui.value = state.copy(status = "Ready", message = "Kembali ke sini lalu ketuk Pasang setelah Android mengizinkan sumber ini.")
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
        }) { Text("Buka pengaturan Android") } }, dismissButton = { TextButton({ manager.ui.value = state.copy(status = "Ready") }) { Text("Nanti") } })
    }
}
