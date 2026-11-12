package com.yonzmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.MusicViewModel
import com.yonzmusic.app.home.*
import com.yonzmusic.app.ui.icons.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

/** Home alone has a red accent. Other destinations retain the app's existing theme. */
@Composable internal fun HomeStyle(content:@Composable ()->Unit) {
    val c=MaterialTheme.colorScheme;val light=c.background.luminance()>.5f
    val accent=if(light)Color(0xFFC52542)else Color(0xFFFF617A)
    MaterialTheme(colorScheme=c.copy(primary=accent,onPrimary=if(light)Color.White else Color(0xFF3D0010),
        primaryContainer=if(light)Color(0xFFFFE0E6)else Color(0xFF47202A),onPrimaryContainer=accent),content=content)
}
@Composable internal fun PremiumHomeScreen(vm:MusicViewModel,onSearch:()->Unit,onImport:()->Unit,scrollState:LazyListState) {
    val home by vm.home.state.collectAsStateWithLifecycle()
    val radioLoading by vm.homeRadioLoading.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    var selected by remember {mutableStateOf<HomeMix?>(null)}
    var options by remember {mutableStateOf<HomeTrack?>(null)}
    var profile by remember {mutableStateOf(false)}
    LaunchedEffect(vm) {vm.home.open()}
    HomeStyle {
        val c=MaterialTheme.colorScheme
        LazyColumn(state=scrollState,contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
            item("listen-now") {
                Column(Modifier.padding(horizontal=Design.page).padding(top=Design.sm),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                            Text(when(hour){in 5..10->"Selamat pagi";in 11..14->"Selamat siang";in 15..18->"Selamat sore";else->"Selamat malam"},style=MaterialTheme.typography.labelLarge,color=c.primary)
                            Text(SimpleDateFormat("EEEE, d MMMM",Locale("id","ID")).format(Date()),style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)
                        }
                        ActionIcon(Icons.Rounded.Search,"Cari musik",onSearch)
                        Surface(onClick={profile=true},shape=CircleShape,color=c.primaryContainer,modifier=Modifier.size(44.dp)) {
                            Box(contentAlignment=Alignment.Center){Text("Y",style=MaterialTheme.typography.titleLarge,color=c.primary)}
                        }
                    }
                    Text("Dengarkan sekarang",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
                }
            }
            if(!home.ready)item("loading") {LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=Design.page))}
            if(home.refreshing || radioLoading)item("fetching") {
                Column(Modifier.padding(horizontal=Design.page),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if(radioLoading)"Menyiapkan radio…"else "Memperbarui pilihan musik…",style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)
                }
            }
            if(home.message.isNotBlank())item("notice") {Text(home.message,Modifier.padding(horizontal=Design.page),style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)}
            if(home.ready && home.quick.isEmpty())item("first-listen") {
                Column(Modifier.padding(horizontal=Design.page),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Mulai dengan musik yang kamu suka.",style=MaterialTheme.typography.titleLarge)
                    Text("Cari lagu atau buka koleksimu. Pilihan personal tumbuh dari favorit dan musik yang benar-benar kamu dengarkan.",style=MaterialTheme.typography.bodyMedium,color=c.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Button(onSearch){Text("Cari musik")};OutlinedButton(onImport){Text("Impor musik")}}
                }
            }
            if(home.quick.isNotEmpty())item("quick-picks") {
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    HomeHeading("Pilihan cepat","Dekat dengan yang kamu suka")
                    Column(Modifier.padding(horizontal=Design.page),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        home.quick.chunked(2).forEach {row->Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            row.forEach {t->
                                Surface(onClick={vm.playHome(listOf(t),radioKind="direct")},shape=Design.small,color=c.surfaceVariant.copy(alpha=.45f),modifier=Modifier.weight(1f)) {
                                    Row(Modifier.heightIn(min=60.dp).padding(6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        HomeArtwork(t,Modifier.size(42.dp))
                                        Column(Modifier.weight(1f)) {Text(t.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall);Text(t.artist,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=c.onSurfaceVariant)}
                                    }
                                }
                            }
                            if(row.size==1)Spacer(Modifier.weight(1f))
                        }}
                    }
                }
            }
            if(home.made.isNotEmpty())item("made-for-you") {
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    HomeHeading("Dibuat untukmu",if(home.updatedAt>0)"Pilihan personal · diperbarui ${SimpleDateFormat("d MMM, HH:mm",Locale("id")).format(Date(home.updatedAt))}"else "Dari koleksi dan aktivitasmu",onRefresh={vm.home.open(true)},refreshEnabled=!home.refreshing)
                    HomeTracks(home.made,{vm.playHome(listOf(it),radioKind="direct")},{options=it})
                }
            }
            if(preferences.smartMix && home.daily.isNotEmpty())item("daily-mix") {HomeMixes("Daily Mix","Campuran harian · komposisi mengikuti ketersediaan",home.daily){selected=it}}
            if(home.discover.isNotEmpty())item("discover") {HomeMixes("Temukan musik baru","Belum pernah kamu dengarkan dalam catatan lokal",listOf(HomeMix("discover","Discover Mix","Dari artis, metadata dan rekomendasi sumber yang relevan",home.discover))){selected=it}}
            if(home.artists.isNotEmpty())item("artist-radio") {HomeMixes("Radio artis","Musik artis pilihan dan lagu terkait",home.artists){selected=it}}
            if(home.quick.isNotEmpty())item("song-radio") {
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    HomeHeading("Mulai radio lagu","Satu lagu menjadi titik awal")
                    HomeTracks(home.quick,{vm.homeRadio(it,false)},{options=it})
                }
            }
            if(home.moods.isNotEmpty())item("moods") {HomeMixes("Suasana","Berdasarkan tag musik yang tersedia",home.moods){selected=it}}
            if(home.genres.isNotEmpty())item("genres") {HomeMixes("Jelajahi genre","Berdasarkan metadata sumber",home.genres){selected=it}}
            if(home.repeat.isNotEmpty())item("on-repeat") {HomeTrackSection("Diputar berulang","Pemutaran bermakna · 30 hari terakhir",home.repeat,{selected=HomeMix("repeat","On Repeat","Sering diputar dengan mempertimbangkan penyelesaian dan skip",home.repeat)},vm){options=it}}
            if(home.recent.isNotEmpty())item("recent") {HomeTrackSection("Baru diputar",null,home.recent,{selected=HomeMix("recent","Baru diputar","Urutan waktu terakhir didengarkan",home.recent)},vm){options=it}}
            if(home.favorites.isNotEmpty())item("favorites") {HomeTrackSection("Favoritmu","Terhubung dengan favorit koleksimu",home.favorites,{selected=HomeMix("favorites","Favoritmu","Lagu yang kamu tandai favorit",home.favorites)},vm){options=it}}
            if(home.top.isNotEmpty())item("local-top") {HomeTrackSection("Teratas di perangkatmu","Statistik lokal · 30 hari terakhir",home.top,{selected=HomeMix("top","Teratas di perangkatmu","Berdasarkan pemutaran bermakna di perangkat ini",home.top)},vm){options=it}}
            if(home.albums.isNotEmpty())item("albums") {HomeMixes("Dari album favorit","Lagu album yang tersedia dalam koleksimu",home.albums){selected=it}}
            if(home.releases.isNotEmpty())item("new-releases") {HomeTrackSection("Rilisan baru","Tanggal rilis sumber · 90 hari terakhir",home.releases,{selected=HomeMix("releases","Rilisan baru","Tanggal rilis yang tersedia dari metadata sumber",home.releases)},vm){options=it}}
        }
        selected?.let {mix->HomePlaylistSheet(if(mix.id=="favorites")mix.copy(tracks=home.favorites)else mix,vm,{options=it}){selected=null}}
        options?.let {track->HomeSongOptions(track,vm){options=null}}
        if(profile)AlertDialog(onDismissRequest={profile=false},title={Text("Profil musikmu")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("${home.stats.values.sumOf {it.plays}} pemutaran bermakna dalam 30 hari terakhir")
            Text("${home.favorites.size} favorit · ${home.artists.size} pilihan radio artis")
            Text("Preferensi tersimpan di perangkat ini. Tidak memerlukan akun.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton({profile=false}){Text("Selesai")}},dismissButton={TextButton({vm.resetRecommendations();profile=false}){Text("Reset preferensi")}})
    }
}
@Composable private fun HomeHeading(title:String,subtitle:String?=null,more:(()->Unit)?=null,onRefresh:(()->Unit)?=null,refreshEnabled:Boolean=true) {
    Row(Modifier.fillMaxWidth().padding(horizontal=Design.page),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);subtitle?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        if(more!=null)ActionIcon(Icons.Rounded.ChevronRight,"Lihat semua $title",more)
        if(onRefresh!=null)ActionIcon(Icons.Rounded.Refresh,"Perbarui pilihan",onRefresh,enabled=refreshEnabled)
    }
}
@Composable internal fun HomeArtwork(track:HomeTrack,modifier:Modifier) {
    if(track.local!=null)Artwork(track.local,modifier)else SongThumbnail(track.online!!,modifier)
}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun HomeTracks(rows:List<HomeTrack>,play:(HomeTrack)->Unit,options:(HomeTrack)->Unit) {
    LazyRow(contentPadding=PaddingValues(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        items(rows.take(12),key={it.key}) {t->Column(Modifier.width(144.dp).clip(Design.small).combinedClickable(onClick={play(t)},onLongClickLabel="Opsi lagu",onLongClick={options(t)})) {
            HomeArtwork(t,Modifier.size(144.dp));Spacer(Modifier.height(6.dp))
            Text(t.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
            Text(t.artist,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
    }
}
@Composable private fun HomeTrackSection(title:String,subtitle:String?,rows:List<HomeTrack>,more:()->Unit,vm:MusicViewModel,options:(HomeTrack)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {HomeHeading(title,subtitle,more);HomeTracks(rows,{vm.playHome(listOf(it),radioKind="direct")},options)}
}
@Composable private fun HomeMixes(title:String,subtitle:String,mixes:List<HomeMix>,open:(HomeMix)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        HomeHeading(title,subtitle)
        LazyRow(contentPadding=PaddingValues(horizontal=Design.page),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            items(mixes,key={it.id}) {mix->Column(Modifier.width(176.dp).clip(Design.small).clickable {open(mix)}) {
                Box(Modifier.size(176.dp).clip(Design.card)) {
                    HomeArtwork(mix.tracks.first(),Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.75f)))))
                    if(mix.kind!="album")Text(if(mix.kind=="artist")"Radio"else mix.title,Modifier.align(Alignment.BottomStart).padding(12.dp),color=Color.White,style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp));Text(mix.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                Text(mix.subtitle,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun HomePlaylistSheet(mix:HomeMix,vm:MusicViewModel,options:(HomeTrack)->Unit={},close:()->Unit) {
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val playback by vm.playback.state.collectAsStateWithLifecycle()
    var autoplay by remember(mix.id) {mutableStateOf(preferences.onlineRadio)}
    val hasOnline=mix.tracks.any {it.online!=null}
    ModalBottomSheet(onDismissRequest=close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=Design.page)) {
            Text(mix.title,style=MaterialTheme.typography.headlineMedium)
            Text("${mix.tracks.size} lagu · ${mix.subtitle}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button({if(mix.kind=="artist")vm.homeRadio(mix.tracks.first(),true)else vm.playHome(mix.tracks,radioKind=if(autoplay && hasOnline)"song"else "");close()},Modifier.weight(1f)) {Icon(Icons.Rounded.PlayArrow,null);Text(if(mix.kind=="artist")"Radio"else "Putar")}
                FilledTonalButton({if(mix.kind=="artist")vm.homeRadio(mix.tracks.first(),true,shuffle=true)else vm.playHome(mix.tracks,shuffle=true,radioKind=if(autoplay && hasOnline)"song"else "");close()},Modifier.weight(1f)) {Icon(Icons.Rounded.Shuffle,null);Text("Acak")}
                ActionIcon(Icons.Rounded.QueueMusic,"Tambah semua ke antrean",{vm.playHome(mix.tracks,enqueue=true);close()})
            }
            if(hasOnline && mix.kind!="artist")Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text("Autoplay",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Switch(autoplay,{autoplay=it})}
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=420.dp)) {
                itemsIndexed(mix.tracks,key={_,t->t.key}) {index,t->
                    Row(Modifier.fillMaxWidth().heightIn(min=60.dp).clickable {vm.playHome(mix.tracks,index,radioKind=if(autoplay && hasOnline)"song"else "");close()},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        HomeArtwork(t,Modifier.size(44.dp))
                        Column(Modifier.weight(1f)) {Text(t.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium,color=if(playback.id==t.id)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface);Text(t.artist+(if(t.local!=null)" · Offline"else ""),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        ActionIcon(Icons.Rounded.MoreVert,"Opsi ${t.title}",{options(t)})
                    }
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun HomeSongOptions(track:HomeTrack,vm:MusicViewModel,close:()->Unit) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest=close) {
        Column(Modifier.navigationBarsPadding().padding(bottom=16.dp)) {
            Text(track.title,Modifier.padding(horizontal=Design.page),style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
            MenuAction(Icons.Rounded.PlayArrow,"Putar") {vm.playHome(listOf(track),radioKind="direct");close()}
            MenuAction(Icons.Rounded.Favorite,if(track.id in favorites || track.online?.id in favorites)"Hapus favorit"else "Tambah favorit") {vm.favoriteHome(track);close()}
            MenuAction(Icons.Rounded.QueueMusic,"Tambah ke antrean") {vm.playHome(listOf(track),enqueue=true);close()}
            MenuAction(Icons.Rounded.PlaylistPlay,"Radio lagu") {vm.homeRadio(track,false);close()}
            MenuAction(Icons.Rounded.GraphicEq,"Radio artis") {vm.homeRadio(track,true);close()}
            track.online?.let {song->MenuAction(Icons.Rounded.Close,"Kurang cocok") {vm.lessSuitable(song);close()}}
        }
    }
}

/** The existing Favorites table is also authoritative for streamed favorites; no shadow list. */
@Composable internal fun HomeFavoritesScreen(vm:MusicViewModel,options:(HomeTrack)->Unit) {
    val home by vm.home.state.collectAsStateWithLifecycle()
    val rows=home.favorites
    val stored by vm.collectionTracks.collectAsStateWithLifecycle()
    val ids by vm.favorites.collectAsStateWithLifecycle()
    val unavailable=remember(stored,ids,rows) {
        val visible=rows.map {it.id}.toSet()
        stored.filter {it.id in ids && it.id !in visible && (!it.active || !it.available)}
    }
    LazyColumn(contentPadding=PaddingValues(horizontal=Design.page,vertical=Design.sm)) {
        if(rows.isEmpty() && unavailable.isEmpty() && home.ready)item {
            if(ids.isEmpty())EmptyState("Belum ada favorit","Tandai lagu melalui menu lagu di Beranda atau koleksimu.")
            else EmptyState("Favorit tetap tersimpan","Metadata atau lokasi musik belum tersedia. Hubungkan kembali sumber dari Koleksi.")
        }
        if(rows.isNotEmpty())item {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button({vm.playHome(rows)}){Icon(Icons.Rounded.PlayArrow,null);Text("Putar")}
                FilledTonalButton({vm.playHome(rows,shuffle=true)}){Icon(Icons.Rounded.Shuffle,null);Text("Acak")}
                ActionIcon(Icons.Rounded.QueueMusic,"Tambah favorit ke antrean",{vm.playHome(rows,enqueue=true)})
            }
        }
        itemsIndexed(rows,key={_,t->t.key}) {i,t->Row(Modifier.fillMaxWidth().heightIn(min=60.dp).clickable {vm.playHome(rows,i)},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            HomeArtwork(t,Modifier.size(44.dp))
            Column(Modifier.weight(1f)){Text(t.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium);Text(t.artist,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)}
            ActionIcon(Icons.Rounded.MoreVert,"Opsi ${t.title}",{options(t)})
        }}
        if(unavailable.isNotEmpty())item {Text("Lokasi belum tersedia",Modifier.padding(vertical=Design.sm),style=MaterialTheme.typography.titleMedium)}
        items(unavailable,key={"unavailable:${it.id}"}) {t->
            TrackRow(t.copy(available=false),false,true,{vm.notify("Favorit ini tetap tersimpan. Hubungkan kembali lokasinya melalui menu lagu.")},{options(HomeTrack(local=t))})
        }
    }
}
