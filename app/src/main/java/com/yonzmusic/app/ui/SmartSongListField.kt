package com.yonzmusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.yonzmusic.app.importer.PasteListFormatter

/** One reusable editor for local imports and downloads; formatting never starts acquisition. */
@Composable internal fun SmartSongListField(
    text: String, onTextChange: (String) -> Unit, knownArtists: Collection<String>,
    modifier: Modifier = Modifier, enabled: Boolean = true, label: String = "Tempel tautan atau daftar lagu",
    onTooLarge: () -> Unit = {},
) {
    var field by remember { mutableStateOf(TextFieldValue(text)) }
    var original by remember { mutableStateOf<String?>(null) }
    var formattedText by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    // External file loads and restored drafts update the selection once, not on each progress tick.
    LaunchedEffect(text) { if (text != field.text) { field = TextFieldValue(text, TextRange(text.length)); original = null; formattedText = null; review = false } }
    fun accept(value: TextFieldValue) {
        // Cursor, focus and IME composition callbacks must not discard paste undo.
        if (value.text == field.text) { field = value; return }
        if (value.text.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024) { onTooLarge(); return }
        val bulk = value.composition == null && PasteListFormatter.isBulkEdit(field.text, value.text)
        val result = if (bulk) PasteListFormatter.format(value.text, knownArtists) else null
        original = if (result?.changed == true) value.text else null
        formattedText = if (result?.changed == true) result.text else null
        review = result?.needsReview ?: false
        field = if (result?.changed == true) TextFieldValue(result.text, TextRange(result.text.length)) else value
        onTextChange(field.text)
    }
    Column(modifier) {
        OutlinedTextField(field, ::accept, enabled = enabled, label = { Text(label) },
            placeholder = { Text("Artis - Judul, satu per baris") }, minLines = 2, maxLines = 3,
            modifier = Modifier.fillMaxWidth(), trailingIcon = {
                IconButton({ expanded = true }, enabled = enabled) { Icon(Icons.Rounded.Edit, "Ubah daftar lengkap") }
            })
        if (original != null || review) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (review) "Batas lagu belum jelas: gunakan baris baru atau ;" else "${text.lineSequence().count { it.isNotBlank() }} baris · dirapikan", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (original != null && text == formattedText) TextButton({
                val raw = original!!; field = TextFieldValue(raw, TextRange(raw.length)); original = null; formattedText = null; review = false; onTextChange(raw)
            }, enabled = enabled) { Text("Urungkan") }
        }
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("Ubah daftar lagu") }, text = {
        OutlinedTextField(field, ::accept, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), minLines = 3, maxLines = 10,
            label = { Text("Satu lagu per baris") }, enabled = enabled)
    }, confirmButton = { TextButton({ expanded = false }) { Text("Selesai") } })
}
