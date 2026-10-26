package com.yonzmusic.app.ui

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
    MenuAction(Icons.Rounded.Refresh, "Check for updates") { app.updates.check(true) }
    if (state.message.isNotBlank()) Text(state.message, Modifier.padding(horizontal = Design.page, vertical = Design.sm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.status in setOf("Ready", "Permission")) TextButton({ app.updates.install() }, Modifier.padding(horizontal = Design.page)) { Text("Install downloaded update") }
    TextButton(app.updates::discard, Modifier.padding(horizontal = Design.page)) { Text("Clear app update cache") }
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
                    Text("A compatible update is available · ${"%.1f".format(java.util.Locale.ROOT, artifact.size / 1048576.0)} MB")
                    Text(artifact.summary, style = MaterialTheme.typography.bodyMedium)
                    Text("Unduh downloads the APK. Android will ask you to confirm installation.", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(manager::download) { Text("Unduh") } }, dismissButton = { TextButton(manager::dismiss) { Text("Batal") } })
        }
        "Downloading", "Verifying" -> AlertDialog(onDismissRequest = {}, title = { Text(if (state.status == "Verifying") "Verifying update" else "Downloading update") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(Design.gap)) {
                if (fraction != null && state.status == "Downloading") LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (state.status == "Verifying") "Checking the package, version, checksum and APK signature." else "${state.received / 1024} / ${state.total / 1024} KB")
            }
        }, confirmButton = {}, dismissButton = { TextButton(manager::cancelDownload) { Text("Batal") } })
        "Permission" -> AlertDialog(onDismissRequest = { manager.ui.value = state.copy(status = "Ready") }, title = { Text("Allow Android installation") }, text = { Text(state.message) }, confirmButton = { TextButton({
            manager.ui.value = state.copy(status = "Ready", message = "Return here and tap Install when Android allows this source.")
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
        }) { Text("Open Android settings") } }, dismissButton = { TextButton({ manager.ui.value = state.copy(status = "Ready") }) { Text("Later") } })
    }
}
