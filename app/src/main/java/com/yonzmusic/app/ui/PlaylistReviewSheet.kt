package com.yonzmusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.*

/** One bounded review surface; choices persist through the acquisition repository, never Composable-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlaylistReviewSheet(plan:JSONObject,selected:List<String>,enabled:Boolean,select:(List<String>)->Unit,choose:(JSONObject,String)->Unit,close:()->Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var attention by rememberSaveable { mutableStateOf(false) }
    val array=plan.optJSONArray("entries") ?: JSONArray()
    val rows=remember(plan.toString()) {(0 until array.length()).map {array.getJSONObject(it)}}
    val visible=remember(rows,query,attention) {rows.filter { row ->
        (!attention || row.optString("status") !in setOf("ready","exists","downloaded","duplicate")) &&
            (query.isBlank() || (row.optJSONObject("song")?.toString() ?: row.optJSONObject("request")?.toString().orEmpty()).contains(query,true))
    }}
    ModalBottomSheet(onDismissRequest=close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).navigationBarsPadding().imePadding()) {
            SectionTitle(plan.optString("name"),"${selected.size} pilihan · urutan sumber dipertahankan")
            OutlinedTextField(query,{query=it},placeholder={Text("Cari judul atau artis")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=Design.page))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=Design.md),verticalAlignment=Alignment.CenterVertically) {
                FilterChip(attention,{attention=!attention},label={Text("Perlu perhatian")})
                Spacer(Modifier.width(Design.sm))
                TextButton({select(rows.filter { it.optString("status") in setOf("ready","exists") }.map {it.getString("id")})},enabled=enabled){Text("Semua cocok")}
                TextButton({select(emptyList())},enabled=enabled){Text("Kosongkan")}
            }
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=Design.page)) {
                items(visible,key={it.getString("id")}) { row ->
                    val song=row.optJSONObject("song") ?: row.optJSONObject("request")?.optJSONObject("song")
                    val title=song?.optString("title").orEmpty().ifBlank { row.optJSONObject("request")?.optString("input").orEmpty().ifBlank { "Entri ${row.optInt("position")}" } }
                    val artists=song?.optJSONArray("artists") ?: JSONArray()
                    val artist=(0 until artists.length()).joinToString("; "){artists.optString(it)}
                    val status=row.optString("status")
                    val selectable=status in setOf("ready","exists")
                    Column(Modifier.fillMaxWidth().padding(horizontal=Design.page,vertical=Design.sm)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(row.getString("id") in selected,{check -> select(if(check)(selected+row.getString("id")).distinct() else selected-row.getString("id"))},enabled=enabled && selectable)
                            SourceThumbnail(song?.optString("artwork_url").orEmpty())
                            Spacer(Modifier.width(Design.sm))
                            Column(Modifier.weight(1f)) {
                                Text("${row.optInt("position")}. $title",style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                if(artist.isNotBlank())Text(artist,style=MaterialTheme.typography.bodySmall,maxLines=2)
                            }
                        }
                        Text(when(status){"ready"->"Kandidat terverifikasi · siap diunduh";"exists"->"Sudah tersedia · ${row.optString("reason")}";"duplicate"->"Rekaman sama sudah ada di daftar";"downloaded"->"Sudah diunduh";"ambiguous"->"Perlu memilih rekaman";"pending"->"Belum diperiksa";else->row.optJSONObject("error")?.optString("message") ?: status},style=MaterialTheme.typography.bodySmall,color=if(selectable)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                        if(status=="ready")Text(if(row.optJSONObject("candidate")?.optBoolean("direct")==true)"Audio dari tautan pilihan; tetap diperiksa sebelum disimpan."else "Judul, kredit artis, penanda versi, dan durasi diperiksa; kandidat bukan persentase tebakan.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        val choices=row.optJSONObject("error")?.optJSONArray("choices")
                        if(choices!=null)for(i in 0 until choices.length()) {
                            val c=choices.getJSONObject(i)
                            TextButton({choose(row,c.getString("youtube_id"))},enabled=enabled) {Text("Pilih ${c.optString("title")} · ${c.optJSONArray("artists")} · ${c.optDouble("duration").toInt()} dtk",maxLines=3,overflow=TextOverflow.Ellipsis)}
                        }
                        HorizontalDivider(Modifier.padding(top=Design.sm))
                    }
                }
                if(visible.isEmpty())item {Text("Tidak ada entri untuk filter ini.",Modifier.padding(Design.page))}
            }
            FilledTonalButton(close,Modifier.fillMaxWidth().padding(horizontal=Design.page,vertical=Design.sm)){Text("Selesai meninjau")}
        }
    }
}
