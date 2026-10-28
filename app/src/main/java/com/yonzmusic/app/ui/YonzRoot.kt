package com.yonzmusic.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import android.app.Activity
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import com.yonzmusic.app.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonzmusic.app.MusicViewModel
import com.yonzmusic.app.data.*
import com.yonzmusic.app.importer.ImportRow
import org.json.JSONArray

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun YonzRoot(vm: MusicViewModel) {
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    YonzTheme(preferences.theme,preferences.reduceMotion) {
        CompositionLocalProvider(LocalPlayingState provides vm.playback.state) {
        UpdateDialogs()
        val context = LocalContext.current
        val colors = MaterialTheme.colorScheme
        val light = colors.background.luminance() > .5f
        SideEffect {
            val activity=generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<androidx.activity.ComponentActivity>().firstOrNull()
            activity?.let {
                val style=if(light)SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT)else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                it.enableEdgeToEdge(statusBarStyle=style,navigationBarStyle=style)
                WindowCompat.getInsetsController(it.window,it.window.decorView).apply {isAppearanceLightStatusBars=light;isAppearanceLightNavigationBars=light}
            }
        }
        val galleryNotice by (context.applicationContext as com.yonzmusic.app.YonzApp).downloadGallery.notice.collectAsStateWithLifecycle()
        val tracks by vm.tracks.collectAsStateWithLifecycle()
        val collectionTracks by vm.collectionTracks.collectAsStateWithLifecycle()
        val migrationNotice by vm.migrationNotice.collectAsStateWithLifecycle()
        val favorites by vm.favorites.collectAsStateWithLifecycle()
        val playlists by vm.playlists.collectAsStateWithLifecycle()
        val onlineCollections by vm.onlineCollections.collectAsStateWithLifecycle()
        var onlineLink by rememberSaveable {mutableStateOf(false)}
        val entries by vm.entries.collectAsStateWithLifecycle()
        val listenStats by vm.listenStats.collectAsStateWithLifecycle()
        val homeScroll=rememberLazyListState()
        val mixes=remember(tracks,favorites,listenStats,preferences.smartMix) { if(preferences.smartMix)SmartMix.build(tracks,favorites,listenStats,System.currentTimeMillis()) else emptyList() }
        val acquisition=(context.applicationContext as com.yonzmusic.app.YonzApp).acquisition
        val engineDownloading by acquisition.busy.collectAsStateWithLifecycle()
        val queuedDownloads by acquisition.queuedDownloads.collectAsStateWithLifecycle()
        val previousDownloads by vm.downloads.collectAsStateWithLifecycle()
        val downloading=engineDownloading || queuedDownloads.any {it.optString("status") in setOf("queued","running","cancelling")} || previousDownloads.any { it.status in setOf("Queued","Downloading","Validating","Publishing","Saved") }
        var downloadsOpen by rememberSaveable { mutableStateOf(false) }
        var metadataId by rememberSaveable { mutableStateOf<String?>(null) }
        var relocationId by rememberSaveable { mutableStateOf<String?>(null) }
        var relocationTarget by rememberSaveable { mutableStateOf<String?>(null) }
        var manifestId by rememberSaveable { mutableStateOf<String?>(null) }
        val history by vm.history.collectAsStateWithLifecycle()
        val imports by vm.imports.collectAsStateWithLifecycle()
        val scanning by vm.scanning.collectAsStateWithLifecycle()
        val playback by vm.playback.state.collectAsStateWithLifecycle()
        val sleepEnd by vm.sleepEnd.collectAsStateWithLifecycle()
        val playerTracks=remember(collectionTracks,playback.remoteTracks){collectionTracks+playback.remoteTracks}
        val current = remember(playerTracks, playback.id) { playerTracks.find { it.id == playback.id } }
        var streamDownload by remember {mutableStateOf<com.yonzmusic.app.search.OnlineSong?>(null)}
        fun downloadRemote(track:com.yonzmusic.app.data.Track) {
            playback.remoteTracks.firstOrNull {it.id==track.id}?.let {t->
                val id=t.id.removePrefix("engine:")
                val song=com.yonzmusic.app.search.OnlineSong(t.id,com.yonzmusic.app.search.SearchProvider.ENGINE,t.title,t.artist.removePrefix("Kanal: "),t.album,t.duration,t.artUri,"https://www.youtube.com/watch?v=$id",id,!t.artist.startsWith("Kanal: "))
                vm.downloadOnline(song){streamDownload=song}
            }
        }
        val snackbar = remember { SnackbarHostState() }
        var destination by rememberSaveable { mutableStateOf("home") }
        var parent by rememberSaveable { mutableStateOf("home") }
        var detailKind by rememberSaveable { mutableStateOf("") }
        var detailKey by rememberSaveable { mutableStateOf("") }
        var detailTitle by rememberSaveable { mutableStateOf("") }
        var selectedPlaylist by rememberSaveable { mutableLongStateOf(0) }
        var playerOpen by rememberSaveable { mutableStateOf(false) }
        var queueOpen by rememberSaveable { mutableStateOf(false) }
        var menuId by rememberSaveable { mutableStateOf<String?>(null) }
        var addIds by remember { mutableStateOf<List<String>?>(null) }
        var nameAction by rememberSaveable { mutableStateOf("") }
        var deleteId by rememberSaveable { mutableLongStateOf(0) }
        var removeId by rememberSaveable { mutableStateOf<String?>(null) }
        var infoId by remember { mutableStateOf<String?>(null) }
        var historyReport by remember { mutableStateOf<ImportLog?>(null) }
        var exportTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
        val primaryDestinations = listOf("home", "library", "search", "playlists")
        fun navigate(route: String) { if (destination in primaryDestinations) parent = destination; destination = route }
        fun detail(kind: String, key: String, title: String, id: Long = 0) {
            if (destination in primaryDestinations) parent = destination
            detailKind = kind; detailKey = key; detailTitle = title; selectedPlaylist = id; destination = "detail"
        }
        fun back() { destination = parent }
        fun keepGrant(uri: Uri, flags: Int) {
            val offered = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 && offered != 0) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, offered) }
                    .onFailure { vm.notify("Temporary access only. Select this source again after a restart if needed.") }
            } else vm.notify("This provider offered temporary access. Select it again if access expires.")
        }
        val files = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val uris = data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data)
                uris.forEach { keepGrant(it, data.flags) }; vm.files(uris)
            }
        }
        val folder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.let { data -> if (result.resultCode == Activity.RESULT_OK) data.data?.let { keepGrant(it, data.flags); vm.folder(it) } }
        }
        val relocateFile=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.let { data -> if(result.resultCode==Activity.RESULT_OK)data.data?.let { uri -> keepGrant(uri,data.flags);relocationTarget?.let { vm.relink(it,uri) };relocationTarget=null } }
        }
        val manifestImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null)manifestId?.let { vm.importMetadata(it,uri) };manifestId=null }
        val manifestExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null)manifestId?.let { vm.exportMetadata(it,uri) };manifestId=null }
        val list = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.readList(uri) }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri -> if (uri != null) vm.export(uri, exportTracks) }
        LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
        LaunchedEffect(playback.error) { playback.error?.let { snackbar.showSnackbar(it) } }
        BackHandler(enabled = destination !in primaryDestinations && !playerOpen && !queueOpen && menuId == null && addIds == null) { back() }
        val detailTracks = remember(tracks, collectionTracks, favorites, entries, history, detailKind, detailKey, selectedPlaylist) {
            when (detailKind) {
                "favorites" -> collectionTracks.filter { it.id in favorites }
                "history" -> history.mapNotNull { h -> tracks.find { it.id == h.trackId } }
                "playlist" -> { val map = collectionTracks.associateBy { it.id }; entries.filter { it.playlistId == selectedPlaylist }.sortedBy { it.position }.mapNotNull { map[it.trackId] } }
                "album" -> tracks.filter { albumKey(it) == detailKey }
                "artist" -> tracks.filter { it.artist == detailKey }
                "folder" -> tracks.filter { it.folder == detailKey }
                else -> tracks
            }
        }
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            containerColor = colors.background,
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            topBar = {
                TopAppBar(
                    title = {
                        if (destination == "home") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.sm)) {
                            BrandMark(Modifier.size(40.dp))
                            Text("Yono Music", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else Text(when (destination) { "library" -> "Your library"; "search" -> "Search"; "playlists" -> "Playlists"; "settings" -> "Settings"; "import" -> "Import music"; else -> if (detailKind == "playlist") playlists.find { it.id == selectedPlaylist }?.name ?: detailTitle else if(detailKind=="online")onlineCollections.find{it.id==detailKey}?.name ?: detailTitle else detailTitle }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = { if (destination !in primaryDestinations) ActionIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { back() }) },
                    actions = {
                        if (destination in primaryDestinations) {
                            if(downloading) ActionIcon(Icons.Rounded.PlaylistPlay,"Daftar unduhan aktif",{downloadsOpen=true})
                            ActionIcon(Icons.Rounded.LibraryAdd, "Import music", { vm.resetImport(); navigate("import") })
                            ActionIcon(Icons.Rounded.Tune, "Settings", { navigate("settings") })
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
                )
            },
            bottomBar = {
                if(!(destination=="search" && WindowInsets.ime.getBottom(LocalDensity.current)>0)) Column(Modifier.background(colors.background).navigationBarsPadding()) {
                    if (current != null && playback.queue.isNotEmpty()) MiniPlayer(current, playback.playing || playback.requested, preferences.glass, vm.playback, { playerOpen = true })
                    if (destination in primaryDestinations || destination == "detail") NavigationBar(containerColor = colors.background, tonalElevation = 0.dp, windowInsets = WindowInsets(0)) {
                        val labels = listOf("Home", "Library", "Search", "Playlists")
                        val icons = listOf(Icons.Rounded.Home, Icons.Rounded.LibraryMusic, Icons.Rounded.Search, Icons.Rounded.QueueMusic)
                        primaryDestinations.forEachIndexed { i, route ->
                            NavigationBarItem(selected = destination == route || (destination == "detail" && parent == route), onClick = { destination = route; parent = route }, icon = { Icon(icons[i], null) }, label = { Text(labels[i], maxLines = 1, overflow = TextOverflow.Ellipsis) }, colors = NavigationBarItemDefaults.colors(indicatorColor = colors.primaryContainer))
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            val modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()
            Box(modifier) {
                Crossfade(destination,modifier=Modifier.fillMaxSize(),animationSpec=tween(if(LocalReducedMotion.current)0 else 160),label="page") { route -> when (route) {
                    "home" -> HomeScreen(tracks, favorites, history, current, playback.id, { vm.play(it.first, it.second) }, { menuId = it.id }, { detail("favorites", "", "Favorites") }, { detail("history", "", "Recently played") }, { k, t -> detail("album", k, t) }, { vm.resetImport(); navigate("import") }, { destination = "library" }, { playerOpen = true },scrollState=homeScroll,mixes=mixes)
                    "library" -> LibraryScreen(tracks, favorites, playback.id, preferences.sort, scanning, { vm.resetImport(); navigate("import") }, vm::scan, vm::sort, { t, id -> vm.play(t, id) }, { menuId = it.id }, { kind, key, title -> detail(kind, key, title) })
                    "search" -> SearchScreen(vm,tracks, playlists, favorites, playback.id, { t, id -> vm.play(t, id) }, { menuId = it.id }, { p -> detail("playlist", "", p.name, p.id) },{song->
                        vm.downloadOnline(song){streamDownload=song}
                    },{song->streamDownload=song})
                    "playlists" -> PlaylistsScreen(playlists, tracks, entries, favorites.size, { nameAction = "create" }, { detail("favorites", "", "Favorites") }, { p -> detail("playlist", "", p.name, p.id) }, { vm.resetImport(); navigate("import") }, onlineCollections, {p->detail("online",p.id,p.name)},{onlineLink=true})
                    "detail" -> if(detailKind=="online")OnlineCollectionDetail(vm,onlineCollections.find{it.id==detailKey},playback.id,{vm.searchOnline.value=true;destination="search"},{song->vm.downloadOnline(song){streamDownload=song}},{destination="playlists"}) else DetailScreen(detailTracks, favorites, playback.id, detailKind == "playlist", { t, id -> vm.play(t, id) }, { menuId = it.id }, { nameAction = "rename" }, { deleteId = selectedPlaylist }, { exportTracks = detailTracks; export.launch((playlists.find { it.id == selectedPlaylist }?.name ?: detailTitle).take(60) + ".m3u8") }, { from, to -> vm.move(selectedPlaylist, from, to) }, { addIds = emptyList() })
                    "settings" -> SettingsScreen(preferences, tracks, scanning, sleepEnd, vm::theme, vm::motion, vm::glass, vm::scan, { minutes -> vm.playback.timer(minutes) }, {
                        val sessionId = vm.playback.audioSessionId()
                        if (sessionId <= 0) vm.notify("Play a song before opening system audio effects.") else {
                            val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId).putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName).putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                            runCatching { context.startActivity(intent) }.onFailure { vm.notify("Your device does not provide a system equalizer panel.") }
                        }
                    }, { vm.resetImport(); navigate("import") }, {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    },smartMix=vm::smartMix,hideDownloads=vm::hideDownloadsFromGallery,galleryNotice=galleryNotice)
                    "import" -> ImportScreen(vm, imports, { files.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) }, { folder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) }, { list.launch(arrayOf("text/*", "application/json", "audio/x-mpegurl", "application/vnd.apple.mpegurl", "application/octet-stream")) }, { destination = "playlists"; parent = "playlists" }, { historyReport = it })
                } }
                if (scanning && destination != "library" && destination != "settings") LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
        if (migrationNotice) AlertDialog(onDismissRequest = { vm.dismissMigration() }, title = { Text("Choose your music sources") }, text = { Text("Yono Music now shows only the files and folders you choose. Your playlists, favorites, history and original audio are preserved. Choose sources in Import to reconnect your collection; uncertain recordings stay unavailable.") }, confirmButton = { TextButton({ vm.dismissMigration(); vm.resetImport(); navigate("import") }) { Text("Choose sources") } }, dismissButton = { TextButton({ vm.dismissMigration() }) { Text("Later") } })
        if (playerOpen && current != null) Dialog(onDismissRequest = { playerOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            PlayerReveal { PlayerScreen(current, playback, vm.playback, current.id in favorites, preferences, sleepEnd, { playerOpen = false }, { if(current.source=="stream")downloadRemote(current)else vm.favorite(current.id) }, { queueOpen = true }, { if(current.source=="stream")downloadRemote(current)else menuId = current.id }) }
        }
        if (queueOpen) QueueSheet(playerTracks, playback, vm.playback, { queueOpen = false })
        val menuTrack = collectionTracks.find { it.id == menuId }
        if (menuTrack != null) ModalBottomSheet(onDismissRequest = { menuId = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Design.page)) {
                TrackRow(menuTrack, playback.id == menuTrack.id, menuTrack.id in favorites, { vm.play(listOf(menuTrack), menuTrack.id); menuId = null }, {}, trailing = {})
                MenuAction(if (menuTrack.id in favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (menuTrack.id in favorites) "Remove from favorites" else "Add to favorites") { vm.favorite(menuTrack.id); menuId = null }
                MenuAction(Icons.Rounded.PlaylistPlay, "Play next") { if (menuTrack.available) vm.playback.enqueue(menuTrack, true) else vm.notify("This file is unavailable. Rescan to retry."); menuId = null }
                MenuAction(Icons.Rounded.QueueMusic, "Add to queue") { if (menuTrack.available) vm.playback.enqueue(menuTrack, false) else vm.notify("This file is unavailable. Rescan to retry."); menuId = null }
                MenuAction(Icons.Rounded.PlaylistAdd, "Add to playlist") { addIds = listOf(menuTrack.id); menuId = null }
                if (destination == "detail" && detailKind == "playlist") MenuAction(Icons.Rounded.RemoveCircleOutline, "Remove from this playlist") { removeId = menuTrack.id; menuId = null }
                MenuAction(Icons.Rounded.Edit,"Edit metadata dan sampul") { metadataId=menuTrack.id;menuId=null }
                MenuAction(Icons.Rounded.Refresh,"Ambil sampul dari sumber lama") { acquisition.repairTrack(menuTrack.id);downloadsOpen=true;menuId=null }
                MenuAction(Icons.Rounded.FolderOpen,"Pilih lokasi baru / hubungkan folder") { relocationId=menuTrack.id;menuId=null }
                MenuAction(Icons.Rounded.Info, "Track details") { infoId = menuTrack.id; menuId = null }
            }
        }
        streamDownload?.let {song->StreamDownloadSheet(song,vm,{streamDownload=null},{downloadsOpen=true})}
        if(downloadsOpen)DownloadSheet(vm){downloadsOpen=false}
        collectionTracks.find { it.id==metadataId }?.let { track -> MetadataEditor(track,{metadataId=null},{title,artists,album,image -> vm.editMetadata(track.id,title,artists,album,image);metadataId=null},{manifestId=track.id;manifestExport.launch(track.fileName.ifBlank { "music" }+".yono.json")},{manifestId=track.id;metadataId=null;manifestImport.launch(arrayOf("application/json","text/*","application/octet-stream"))}) }
        collectionTracks.find { it.id==relocationId }?.let { track ->
            val candidates by produceState<List<String>>(emptyList(),track.id) { value=runCatching { val values=org.json.JSONArray(vm.repository.dao.state("relocation:${track.id}")?.value ?: "[]");(0 until values.length()).map { values.getString(it) } }.getOrDefault(emptyList()) }
            RelocationDialog(track,candidates,{relocationId=null},{relocationTarget=track.id;relocationId=null;relocateFile.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("audio/*","video/webm","application/ogg")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))},{relocationId=null;folder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))},{uri -> vm.relink(track.id,uri);relocationId=null})
        }
        if (addIds != null) {
            if (addIds!!.isEmpty()) AddTracksDialog(tracks, detailTracks.map { it.id }.toSet(), { addIds = null }) { ids -> vm.add(selectedPlaylist, ids); addIds = null }
            else ModalBottomSheet(onDismissRequest = { addIds = null }) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(), contentPadding = PaddingValues(bottom = Design.page)) {
                    item { SectionTitle("Add to playlist") }
                    if (playlists.isEmpty()) item { EmptyState("A home for this song", "Create your first playlist, then add songs to it.", "playlist", { Button({ nameAction = "createWithTracks" }) { Text("Create playlist") } }) }
                    else {
                        items(playlists, key = { it.id }) { p -> ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text("${p.count} songs") }, leadingContent = { Icon(Icons.Rounded.QueueMusic, null) }, modifier = Modifier.clickable { vm.add(p.id, addIds.orEmpty()); addIds = null }) }
                        item { TextButton({ nameAction = "createWithTracks" }, Modifier.padding(horizontal = Design.page)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(Design.sm)); Text("New playlist") } }
                    }
                }
            }
        }
        if(onlineLink)OnlinePlaylistImportDialog(vm){onlineLink=false}
        if(nameAction=="create")CreateCollectionDialog({nameAction=""}){name,online->if(online)vm.createOnline(name)else vm.create(name);nameAction=""}
        else if (nameAction.isNotEmpty()) NameDialog(if (nameAction == "rename") "Rename playlist" else "Create playlist", if (nameAction == "rename") playlists.find { it.id == selectedPlaylist }?.name.orEmpty() else "", { nameAction = "" }) { name ->
            if (nameAction == "rename") vm.rename(selectedPlaylist, name) else vm.create(name, if (nameAction == "createWithTracks") addIds.orEmpty() else emptyList())
            if (nameAction == "createWithTracks") addIds = null
            nameAction = ""
        }
        if (removeId != null) AlertDialog(onDismissRequest = { removeId = null }, title = { Text("Remove song?") }, text = { Text("Remove this song from the playlist? The audio file stays on your device.") }, confirmButton = { TextButton({ vm.remove(selectedPlaylist, removeId!!); removeId = null }) { Text("Remove") } }, dismissButton = { TextButton({ removeId = null }) { Text("Cancel") } })
        if (deleteId > 0) AlertDialog(onDismissRequest = { deleteId = 0 }, title = { Text("Delete playlist?") }, text = { Text("This removes the playlist and its song references. Your audio files and favorites stay safe.") }, confirmButton = { TextButton({ vm.delete(deleteId); deleteId = 0; destination = "playlists"; parent = "playlists" }) { Text("Delete", color = colors.error) } }, dismissButton = { TextButton({ deleteId = 0 }) { Text("Cancel") } })
        collectionTracks.find { it.id == infoId }?.let { t -> AlertDialog(onDismissRequest = { infoId = null }, title = { Text("Track details") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Design.sm)) { Text(t.title, style = MaterialTheme.typography.titleMedium); Text("${t.artist}\n${t.album}\n${time(t.duration)}\n${t.folder}\n${t.fileName}\n\n${Availability.label(t.availability)}\n${t.uri}", style = MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton({ infoId = null }) { Text("Done") } }) }
        historyReport?.let { report ->
            val rows by produceState<List<ImportRow>>(emptyList(), report.id) { value = vm.repository.dao.importDetails(report.id).map { ImportRow(it.input, it.status, it.detail) } }
            ModalBottomSheet(onDismissRequest = { historyReport = null }) {
                LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.8f).navigationBarsPadding(), contentPadding = PaddingValues(bottom = Design.page)) { item { SectionTitle(report.name, "Import history") }; itemsIndexed(rows) { _, row -> ImportResultRow(row) } }
            }
        }
    }
    }
}

@Composable fun MenuAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit) {
    ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable(onClick = action).heightIn(min = Design.touch))
}
@Composable fun NameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it.take(100) }, label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }, confirmButton = { TextButton({ save(value.trim()) }, enabled = value.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
@Composable fun AddTracksDialog(tracks: List<Track>, existing: Set<String>, dismiss: () -> Unit, save: (List<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val filtered = remember(tracks, query) { tracks.filter { it.available && (it.title.contains(query, true) || it.artist.contains(query, true)) } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Add songs") }, text = {
        Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Find a song") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(filtered, key = { it.id }) { t ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = t.id !in existing) { selected = if (t.id in selected) selected - t.id else selected + t.id }.padding(vertical = Design.sm), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(t.id in selected || t.id in existing, onCheckedChange = null, enabled = t.id !in existing)
                        Spacer(Modifier.width(Design.sm))
                        Column(Modifier.weight(1f)) { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if (t.id in existing) "Already in playlist" else t.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (filtered.isEmpty()) item { Text("No songs found", Modifier.padding(Design.gap)) }
            }
        }
    }, confirmButton = { TextButton({ save(selected.toList()) }, enabled = selected.isNotEmpty()) { Text("Add (${selected.size})") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
fun albumKey(track: Track) = "${track.album}\u0000${track.artist}"
