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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.MusicViewModel
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.metadata.*
import com.yonzmusic.app.search.*
import kotlinx.coroutines.CancellationException

@Composable fun ChartDiscovery(vm:MusicViewModel,open:(CatalogItem)->Unit,onHistory:()->Unit,onDownload:(OnlineSong)->Unit,onConfigure:(OnlineSong)->Unit,scroll:LazyListState) {
    val app=LocalContext.current.applicationContext as YonzApp
    val uri=LocalUriHandler.current
    var country by rememberSaveable {mutableStateOf(vm.discovery.country)}
    var category by rememberSaveable {mutableStateOf(ChartCategory.TRENDING)}
    var filter by rememberSaveable {mutableStateOf(OfficialFilter.ALL)}
    var page by remember {mutableStateOf(ChartPage())}
    var loading by remember {mutableStateOf(false)};var refresh by remember {mutableIntStateOf(0)}
    var count by remember {mutableIntStateOf(20)};var regions by remember {mutableStateOf(false)}
    var selected by remember {mutableStateOf<OnlineSong?>(null)}
    var genre by remember {mutableStateOf<Pair<String,String>?>(null)}
    val history by vm.onlineHistory.collectAsStateWithLifecycle();val playback by vm.playback.state.collectAsStateWithLifecycle()
    val metadata by app.metadata.state.collectAsStateWithLifecycle()
    LaunchedEffect(category,country,refresh,genre) {
        page=ChartPage();loading=true;count=20
        try {
            page=genre?.let {ChartPage(items=app.charts.genre(it.second),source="YouTube Music Genre",available=true,sourceUrl="https://music.youtube.com/",message="Pilihan genre dari sumber; tidak berperingkat.")} ?: app.charts.load(category,country,refresh>0)
        }catch(e:CancellationException){throw e}catch(_:Exception){page=ChartPage(message="Sumber belum tersedia. Coba lagi atau buka situs chart.")}
        finally {loading=false}
    }
    val songs=page.entries.map {e->e.copy(song=metadata[e.song.id]?.let {MetadataPolicy.merge(e.song,it)} ?: e.song)}.filter {filter.accepts(it.song)}
    // Album/playlist navigation has no per-release authorization proof; never pass it as official music.
    val catalogItems=page.items.filter {filter==OfficialFilter.ALL || it.kind=="artist"}
    fun choose(next:ChartCategory,region:String=country,official:OfficialFilter=OfficialFilter.ALL){category=next;country=region;genre=null;filter=official}
    LazyColumn(state=scroll,contentPadding=PaddingValues(bottom=Design.page),verticalArrangement=Arrangement.spacedBy(Design.sm)) {
        item {SectionTitle("Jelajahi musik","Chart & rilisan dari sumbernya",action={TextButton({regions=true}){Text(MusicCharts.regionName(country)+" ▾")}})}
        item {Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
            ChartCategory.entries.forEach {value->FilterChip(category==value && genre==null,{choose(value)},label={Text(value.label)})}
        }}
        item {Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(Design.sm)) {
            OfficialFilter.entries.forEach {value->FilterChip(filter==value,{filter=value},label={Text(value.label)})}
        }}
        item {SectionTitle(genre?.first ?: if(category==ChartCategory.SEA)"Official ${MusicCharts.regionName(country)} Chart" else category.label,category.description,action={IconButton({refresh++},enabled=!loading){Icon(Icons.Rounded.Refresh,"Segarkan chart")}})}
        if(loading)item {LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=Design.page))}
        if(page.source.isNotBlank() || page.message.isNotBlank())item {
            Column(Modifier.padding(horizontal=Design.page),verticalArrangement=Arrangement.spacedBy(Design.xs)) {
                if(page.source.isNotBlank())Text(page.source,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                if(page.sourceUpdated.isNotBlank())Text("Tanggal chart: ${page.sourceUpdated}",style=MaterialTheme.typography.labelSmall)
                else Text("Tanggal pembaruan chart belum diberikan sumber",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(page.fetchedAt>0)Text("${if(page.stale)"Cache lama" else if(page.cached)"Cache" else "Diambil"}: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(page.fetchedAt))}",style=MaterialTheme.typography.labelSmall)
                if(page.message.isNotBlank())Text(page.message,style=MaterialTheme.typography.bodySmall)
                TextButton({runCatching {uri.openUri(page.sourceUrl)}}){Text("Buka sumber chart ↗")}
            }
        }
        if(songs.isNotEmpty())item {
            LazyRow(contentPadding=PaddingValues(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(Design.md)) {
                items(songs.take(count),key={it.song.id}) {entry->
                    val song=entry.song
                    Column(Modifier.width(160.dp).clickable {vm.playOnline(song)},verticalArrangement=Arrangement.spacedBy(Design.xs)) {
                        SourceThumbnail(song.artwork,Modifier.size(160.dp),song.id)
                        Row {
                            Text((entry.rank?.let {"#$it · "}.orEmpty())+song.title,Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                            ActionIcon(Icons.Rounded.MoreVert,"Opsi ${song.title}",{selected=song})
                        }
                        Text(song.credit,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                        Text(song.officialStatus.label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(song.contentKind!=ContentKind.UNKNOWN)Text(song.contentKind.label,style=MaterialTheme.typography.labelSmall)
                        if(entry.movement.isNotBlank())Text(when(entry.movement){"up"->"↑ Naik";"down"->"↓ Turun";"new"->"Baru";else->"Posisi tetap"},style=MaterialTheme.typography.labelSmall)
                        if(entry.label.isNotBlank())Text(entry.label,style=MaterialTheme.typography.labelSmall)
                        if(entry.peak!=null || entry.lastWeek!=null)Text("Puncak ${entry.peak ?: "—"} · Minggu lalu ${entry.lastWeek ?: "—"}",style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if(!loading && (page.entries.isNotEmpty() || page.items.isNotEmpty()) && songs.isEmpty() && catalogItems.isEmpty())item {Text("Belum ada hasil dengan bukti resmi yang sesuai filter ini. Unggahan yang belum terverifikasi tidak ditampilkan.",Modifier.padding(horizontal=Design.page),style=MaterialTheme.typography.bodyMedium)}
        items(catalogItems.take(count),key={it.id}) {item->
            val rank=runCatching {org.json.JSONObject(item.json).optInt("rank").takeIf {it>0}}.getOrNull()
            ListItem(headlineContent={Text(rank?.let {"#$it · ${item.title}"} ?: item.title,maxLines=2,overflow=TextOverflow.Ellipsis)},supportingContent={Text(item.credit.ifBlank {item.provider}+(if(category==ChartCategory.NEW_RELEASES)" · Status resmi belum terverifikasi" else ""))},leadingContent={SourceThumbnail(item.artwork,identity=item.id)},trailingContent={Icon(Icons.Rounded.ChevronRight,null)},modifier=Modifier.clickable {open(item)}.padding(horizontal=Design.sm))
        }
        items(page.genres.take(count),key={it.second}) {value->ListItem(headlineContent={Text(value.first)},trailingContent={Icon(Icons.Rounded.ChevronRight,null)},modifier=Modifier.clickable {genre=value}.padding(horizontal=Design.sm))}
        if(maxOf(songs.size,catalogItems.size,page.genres.size)>count)item {TextButton({count+=20},Modifier.padding(horizontal=Design.page)){Text("Lihat lebih banyak")}}
        item {SectionTitle("Temukan berikutnya","Pilih satu bagian untuk memuatnya")}
        item {Column(Modifier.padding(horizontal=Design.page)) {
            val sections=listOf(Triple("Musik Trending",ChartCategory.TRENDING,country),Triple("Official Music Videos",ChartCategory.DAILY_VIDEOS,country),Triple("Top Songs Indonesia",ChartCategory.WEEKLY_SONGS,"ID"),Triple("Top Songs Global",ChartCategory.WEEKLY_SONGS,"ZZ"),Triple("Official Indonesia Chart",ChartCategory.SEA,"ID"),Triple("Popular Artists",ChartCategory.WEEKLY_ARTISTS,country),Triple("New Official Releases",ChartCategory.NEW_RELEASES,country),Triple("Top Music Videos",ChartCategory.WEEKLY_VIDEOS,country),Triple("Genre Discovery",ChartCategory.GENRES,country))
            sections.forEach {(label,value,region)->TextButton({choose(value,region,if(label=="Official Music Videos")OfficialFilter.VIDEO else if(label=="New Official Releases")OfficialFilter.OFFICIAL else OfficialFilter.ALL)}){Text(label)}}
        }}
        if(history.isNotEmpty()){item {SectionTitle("Terakhir diputar",action={TextButton(onHistory){Text("Semua")}})};items(history.take(4),key={"recent:${it.id}"}) {song->OnlineSongRow(song,playback.id,{vm.playOnline(song)},{selected=song})}}
    }
    if(regions)AlertDialog(onDismissRequest={regions=false},title={Text("Wilayah chart")},text={LazyColumn {items((page.countries+MusicCharts.regions).distinct()){code->TextButton({country=code;genre=null;regions=false;vm.discovery.country=code}){Text(MusicCharts.regionName(code))}}}},confirmButton={TextButton({regions=false}){Text("Tutup")}})
    selected?.let {OnlineSongActions(vm,it,onDownload,onConfigure){selected=null}}
}
