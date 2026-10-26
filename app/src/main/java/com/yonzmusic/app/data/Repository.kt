package com.yonzmusic.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.File
import android.os.storage.StorageManager
import android.os.Environment
import org.json.JSONObject

/** The registry, never persistedUriPermissions or MediaStore enumeration, defines the library. */
class Repository(val context: Context, val db: MusicDatabase) {
    val dao = db.music()
    val scanning = MutableStateFlow(false)
    private val scanMutex = Mutex()
    private val resolver get() = context.contentResolver
    fun stable(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun identity(uri: Uri): String = runCatching {
        if (DocumentsContract.isDocumentUri(context, uri)) "${uri.authority}:${DocumentsContract.getDocumentId(uri)}" else uri.toString()
    }.getOrDefault(uri.toString())

    private fun treeRoot(uri: Uri): String = if(DocumentsContract.isDocumentUri(context,uri)) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri)
    suspend fun scan(): String = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            scanning.value = true
            try {
                recoverEngineMetadata()
                val sources = dao.allSources().filter { it.enabled }
                var failed = 0
                for (source in sources) {
                    coroutineContext.ensureActive()
                    if (!scanSource(source)) failed++
                }
                refreshVisibility()
                if (sources.isEmpty()) "Choose music files or a folder in Import. Only your selections are indexed."
                else "Selected sources refreshed${if (failed > 0) " · $failed need attention in Import" else ""}"
            } finally { scanning.value = false }
        }
    }
    suspend fun importAudio(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            scanning.value = true
            try {
                recoverEngineMetadata()
                var valid = 0
                for (uri in uris.distinct().take(1000)) {
                    if (uri.scheme != "content") continue
                    val source = source(uri, "document")
                    dao.source(source)
                    if (scanSource(source)) valid++
                }
                refreshVisibility()
                "$valid selected files indexed · originals stay in place"
            } finally { scanning.value = false }
        }
    }
    suspend fun importFolder(uri: Uri): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "Choose a document folder." }
        require(uri.authority != "com.android.externalstorage.documents" || treeRoot(uri).substringAfter(':').trim('/').isNotBlank()) { "Choose a music folder instead of a storage root." }
        scanMutex.withLock {
            scanning.value = true
            try {
                recoverEngineMetadata()
                val source = source(uri, "tree")
                dao.source(source)
                val ok = scanSource(source)
                refreshVisibility()
                if (ok) "Selected music folder indexed" else "Folder access needs attention. Select it again in Import."
            } finally { scanning.value = false }
        }
    }
    private suspend fun source(uri: Uri, kind: String): MusicSource {
        val id = stable("$kind:$uri")
        val existing = dao.source(id)
        val document = if (kind == "tree") DocumentsContract.buildDocumentUriUsingTree(uri, treeRoot(uri)) else uri
        var name = if (kind == "tree") "Music folder" else "Audio file"
        runCatching { resolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0).orEmpty().take(200).ifBlank { name } } }
        val persistent = resolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }
        return (existing ?: MusicSource(id, uri.toString(), name, kind)).copy(enabled = true, persistent = persistent)
    }
    suspend fun changeSource(id: String, enabled: Boolean? = null, recursive: Boolean? = null) = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val old = dao.source(id) ?: return@withLock
            val next = old.copy(enabled = enabled ?: old.enabled, recursive = recursive ?: old.recursive)
            dao.source(next)
            if (recursive != null && next.enabled) { scanning.value = true; try { scanSource(next) } finally { scanning.value = false } }
            refreshVisibility()
        }
    }
    suspend fun removeSource(id: String) = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            db.withTransaction { dao.clearLocations(id); dao.deleteSource(id); refreshVisibility() }
            // Grants may also serve an overlapping source/output destination. Never revoke shared access here.
        }
    }
    private data class Document(val uri: Uri, val name: String, val folder: String, val size: Long, val modified: Long)
    private val portable = PortableMetadata(context)
    private fun sha(uri: Uri): String {
        val hash=MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer=ByteArray(262144);var bytes=0L
            while(true) { val n=input.read(buffer);if(n<0) break;bytes+=n;if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException();hash.update(buffer,0,n) }
        } ?: throw FileNotFoundException()
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun storageMissing(uri: Uri): Boolean = runCatching {
        val volume=DocumentsContract.getDocumentId(uri).substringBefore(':')
        val volumes=context.getSystemService(StorageManager::class.java).storageVolumes
        val found=volumes.firstOrNull { if(volume=="primary") it.isPrimary else it.uuid.equals(volume,true) }
        found!=null && found.state !in setOf(Environment.MEDIA_MOUNTED,Environment.MEDIA_MOUNTED_READ_ONLY)
    }.getOrDefault(false)
    private fun reason(uri: Uri, error: Exception): String = when {
        error is SecurityException -> Availability.PERMISSION
        storageMissing(uri) -> Availability.STORAGE
        error is FileNotFoundException -> Availability.MISSING
        error is IOException -> Availability.STORAGE
        else -> Availability.UNREADABLE
    }
    /** Trusted app-owned engine journals repair v2 metadata/hash evidence; no Python or network startup. */
    suspend fun recoverEngineMetadata() {
        val tracks=dao.allTracks().filter { it.availability!=Availability.ALIAS }.associateBy { identity(Uri.parse(it.uri)) }
        val updates=linkedMapOf<String,Track>()
        File(context.filesDir,"yono-engine/plans").listFiles().orEmpty().filter { it.extension=="json" && it.length()<=16*1024*1024 }.forEach { file ->
            runCatching {
                val plan=JSONObject(file.readText());val rows=plan.getJSONArray("entries")
                for(i in 0 until rows.length()) {
                    val row=rows.getJSONObject(i);val uri=row.optString("uri");if(uri.isBlank())continue
                    val previous=tracks[identity(Uri.parse(uri))] ?: continue
                    val song=row.optJSONObject("song") ?: continue
                    val audio=row.optJSONObject("audio") ?: JSONObject()
                    val hash=audio.optString("sha256",row.optString("pending_sha256"))
                    if(!hash.matches(Regex("[a-f0-9]{64}")))continue
                    val artists=song.optJSONArray("artists")
                    val artist=(0 until (artists?.length() ?: 0)).map { artists!!.getString(it) }.filter { MetadataPolicy.known(it).isNotBlank() }.joinToString("; ")
                    val origin=song.optString("metadata_origin").ifBlank { if(song.optString("youtube_id").isNotBlank() || artist.isNotBlank()) "source" else "filename" }
                    val fields=MetadataPolicy.choose(previous.fileName,MetadataPolicy.Fields(),MetadataPolicy.Fields(song.optString("title"),artist,song.optString("album"),origin))
                    if(previous.metadataOrigin=="user")continue
                    updates[previous.id]=previous.copy(title=fields.title,artist=fields.artist,album=fields.album,sha256=hash,
                        sourceIdentity=song.optString("youtube_id").takeIf { it.isNotBlank() }?.let { "youtube:$it" } ?: previous.sourceIdentity,
                        sourceUrl=song.optString("source_url"),metadataOrigin=fields.origin,
                        artUri=row.optString("artwork_uri").ifBlank { previous.artUri },artRevision=System.currentTimeMillis())
                }
            }
        }
        if(updates.isNotEmpty()) dao.upsert(updates.values.toList())
        // Also retain old worker identity evidence, even if the URI has already moved.
        val oldJobs=dao.completedDownloads().filter { it.trackId.isNotBlank() && it.sha256.length==64 }
        for(job in oldJobs) dao.track(job.trackId)?.takeIf { it.sha256.isBlank() }?.let { dao.upsert(listOf(it.copy(sha256=job.sha256,fileSize=job.received,sourceIdentity=job.sourceId,sourceUrl=job.url))) }
    }
    private suspend fun scanSource(source: MusicSource): Boolean {
        val root=Uri.parse(source.uri)
        val documents=mutableListOf<Document>();val manifests=mutableListOf<Document>();val pictures=mutableListOf<Document>()
        val oldLocations=dao.locations().filter { it.sourceId==source.id }.associateBy { it.identity }
        try {
            if(source.kind=="tree") {
                val seen=hashSetOf<String>();val pending=ArrayDeque<Pair<String,String>>()
                pending.add(treeRoot(root) to source.name)
                while(pending.isNotEmpty()) {
                    coroutineContext.ensureActive();check(seen.size<20000)
                    val (id,path)=pending.removeFirst();if(!seen.add(id))continue
                    val list=DocumentsContract.buildChildDocumentsUriUsingTree(root,id)
                    resolver.query(list,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED),null,null,null)?.use { cursor ->
                        while(cursor.moveToNext()) {
                            val child=cursor.getString(0);val name=cursor.getString(1).orEmpty();val mime=cursor.getString(2).orEmpty()
                            if(mime==DocumentsContract.Document.MIME_TYPE_DIR) { if(source.recursive)pending.add(child to "$path/$name");continue }
                            val doc=Document(DocumentsContract.buildDocumentUriUsingTree(root,child),name,path,cursor.getLong(3),cursor.getLong(4))
                            if(name.endsWith(".json",true) && doc.size<=128*1024) { if(manifests.size<10000)manifests+=doc }
                            else if(mime.startsWith("image/") && pictures.size<2000 && doc.size<=8*1024*1024) pictures+=doc
                            else if(!name.startsWith(".yono-part-") && !name.endsWith(".part") && (mime.startsWith("audio/") || name.substringAfterLast('.').lowercase() in AUDIO_EXT)) { check(documents.size<10000);documents+=doc }
                        }
                    } ?: throw IOException("Folder sementara tidak tersedia")
                }
            } else {
                var name="";var size=0L;var modified=0L
                resolver.query(root,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED),null,null,null)?.use {
                    if(it.moveToFirst()) { name=it.getString(0).orEmpty();size=it.getLong(1);modified=it.getLong(2) }
                }
                // Opening before probing distinguishes URI/permission problems from unreadable audio.
                resolver.openFileDescriptor(root,"r")?.use { } ?: throw FileNotFoundException()
                documents+=Document(root,name,"Chosen files",size,modified)
            }
        } catch(e:kotlinx.coroutines.CancellationException) { throw e }
        catch(e:Exception) {
            val status=reason(root,e).let { if(source.kind=="tree" && it==Availability.UNREADABLE) Availability.STORAGE else it }
            db.withTransaction { dao.locations(oldLocations.values.map { it.copy(available=false,status=status) });dao.source(source.copy(status=Availability.label(status))) }
            return false
        }
        val portableRecords=manifests.mapNotNull { d -> portable.read(d.uri)?.let { d to it } }.groupBy { it.second.hash }
        // Covers can be shared by thousands of tracks. Read each candidate at most once per scan.
        val pictureHashes=mutableMapOf<String,String>()
        fun pictureHash(uri:Uri)=pictureHashes.getOrPut(uri.toString()) { runCatching { sha(uri) }.getOrDefault("") }
        val allLocations=dao.locations().groupBy { it.identity }
        val oldTracks=dao.allTracks().filter { it.availability!=Availability.ALIAS }
        val byId=oldTracks.associateBy { it.id };val byUri=oldTracks.groupBy { identity(Uri.parse(it.uri)) }
        val foundTracks=linkedMapOf<String,Track>();val found=mutableListOf<TrackLocation>()
        val ambiguity=mutableListOf<Pair<String,String>>()
        for(doc in documents) {
            coroutineContext.ensureActive();val key=identity(doc.uri)
            val exact=(allLocations[key].orEmpty().map { it.trackId }+byUri[key].orEmpty().map { it.id }).distinct().filter { it in byId }
            val cached=exact.firstOrNull()?.let(byId::get)
            try {
                resolver.openFileDescriptor(doc.uri,"r")?.use { } ?: throw FileNotFoundException()
                val digest=if(cached!=null && cached.sha256.isNotBlank() && doc.size>0 && doc.modified>0 && cached.fileSize==doc.size && cached.modified==doc.modified) cached.sha256 else sha(doc.uri)
                val hashIds=MetadataPolicy.relocationCandidates(digest,oldTracks)
                val candidates=(exact+listOfNotNull(legacyMediaId(doc.uri,oldTracks))).distinct().filter { byId[it]?.sha256.isNullOrBlank() || byId[it]?.sha256==digest }
                val id=when { candidates.isNotEmpty() -> candidates.first();hashIds.size==1 -> hashIds.single();else -> if(exact.isNotEmpty()) stable("$key:$digest") else stable(key) }
                val previous=byId[id]
                if(hashIds.size>1 && exact.isEmpty()) hashIds.forEach { ambiguity+=it to doc.uri.toString() }
                val tags=documentTrack(doc.uri,doc.folder,source.id,doc.name) ?: throw IllegalArgumentException("Audio tidak dapat dibaca")
                // Conflicting manifests do not get to choose an identity or silently overwrite metadata.
                val matching=portableRecords[digest].orEmpty()
                val revision=matching.maxOfOrNull { it.second.revision }
                val latest=matching.filter { it.second.revision==revision }
                val distinct=latest.map { it.second.fields }.distinct()
                val record=if(distinct.size==1) latest.maxByOrNull { it.first.modified } else null
                val prior=previous?.takeIf { it.sha256==digest && it.metadataOrigin in setOf("source","verified","manifest","user","filename") }
                val fields=MetadataPolicy.choose(doc.name,MetadataPolicy.Fields(tags.title,tags.artist,tags.album,tags.metadataOrigin),
                    prior?.let { MetadataPolicy.Fields(it.title,it.artist,it.album,it.metadataOrigin) } ?: record?.second?.fields)
                var art=previous?.artUri.orEmpty()
                record?.second?.let { portable ->
                    val covers=pictures.filter { it.folder==record.first.folder && it.name==portable.artName }
                    val candidate=covers.firstOrNull { portable.artHash.isNotBlank() && pictureHash(it.uri)==portable.artHash }
                    val recovered=candidate ?: if(portable.artHash.isNotBlank()) pictures.firstOrNull { pictureHash(it.uri)==portable.artHash } else null
                    if(recovered!=null)art=recovered.uri.toString()
                }
                val t=tags.copy(id=id,title=fields.title,artist=fields.artist,album=fields.album,metadataOrigin=fields.origin,
                    sha256=digest,fileSize=doc.size,modified=doc.modified,added=previous?.added ?: tags.added,
                    artUri=art,artRevision=System.currentTimeMillis(),sourceIdentity=prior?.sourceIdentity ?: record?.second?.sourceIdentity.orEmpty(),
                    sourceUrl=prior?.sourceUrl ?: record?.second?.sourceUrl.orEmpty(),availability=Availability.AVAILABLE)
                foundTracks.putIfAbsent(id,t);found+=TrackLocation(source.id,key,id,doc.uri.toString(),doc.size,doc.modified,true)
            } catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(e:Exception) {
                val status=reason(doc.uri,e)
                if(cached!=null)found+=TrackLocation(source.id,key,cached.id,doc.uri.toString(),doc.size,doc.modified,false,status)
            }
        }
        db.withTransaction {
            val seen=found.map { it.identity }.toSet()
            val missing=oldLocations.values.filter { it.identity !in seen }.map { it.copy(available=false,status=Availability.MISSING) }
            dao.upsert(foundTracks.values.toList());dao.clearLocations(source.id);dao.locations(found+missing)
            ambiguity.groupBy { it.first }.forEach { (id, options) -> dao.state(AppState("relocation:$id",org.json.JSONArray(options.map { it.second }.distinct()).toString())) }
            dao.source(source.copy(status="${found.count { it.available }} tersedia · ${found.count { !it.available }+missing.size} lokasi perlu diperiksa"))
        }
        return true
    }
    /** Android's document→MediaStore mapping is identity evidence. Filenames/artist guesses are not. */
    private fun legacyMediaId(uri: Uri, old: List<Track>): String? {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrDefault("")
        val mediaDocumentId = if (uri.authority == "com.android.providers.media.documents" && documentId.startsWith("audio:")) documentId.substringAfter(':') else null
        val mapped = if (Build.VERSION.SDK_INT >= 29) runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull() else null
        val selectedId = mediaDocumentId ?: mapped?.takeIf { it.authority == "media" && (it.pathSegments.contains("audio") || it.pathSegments.contains("file")) }?.lastPathSegment ?: return null
        val matches = old.filter { track ->
            val previous = Uri.parse(track.uri)
            previous.authority == "media" && previous.pathSegments.takeLast(3).take(2) == listOf("audio", "media") &&
                previous.lastPathSegment == selectedId
        }
        return matches.singleOrNull()?.id
    }
    /** Transactional identity reconciliation only after exact document/media/hash evidence. */
    suspend fun mergeIdentities(target: String, aliases: List<String>) = db.withTransaction {
        require(dao.track(target) != null)
        for (old in aliases.distinct().filter { it != target }) {
            if (dao.track(old) == null) continue
            if (dao.favoriteRow(old) != null) { dao.favorite(Favorite(target)); dao.unfavorite(old) }
            val history = dao.historyRow(old)
            if (history != null) {
                if ((dao.historyRow(target)?.playedAt ?: Long.MIN_VALUE) < history.playedAt) dao.remember(history.copy(trackId = target))
                dao.deleteHistory(old)
            }
            for (playlist in dao.memberships(old).map { it.playlistId }.distinct()) {
                val entries = dao.entries(playlist).map { if (it.trackId == old) target else it.trackId }.distinct()
                dao.clearEntries(playlist); dao.addEntries(entries.mapIndexed { n, id -> PlaylistEntry(playlist, id, n) })
            }
            dao.rebindLocations(old, target); dao.rebindDownloads(old, target)
            dao.rebindListens(old,target)
            dao.compressAliases(old, target); dao.state(AppState("track_alias:$old", target))
            dao.track(old)?.let { dao.upsert(listOf(it.copy(active=false,available=false,availability=Availability.ALIAS))) }
        }
    }
    private fun hashBinding(uri: Uri, size: Long, completed: List<DownloadJob>): String? {
        val candidates = completed.filter { it.received == size && size in 1..MAX_AUDIO_BYTES && it.sha256.length == 64 }
        if (candidates.isEmpty()) return null
        val hash = runCatching { resolver.openInputStream(uri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var total = 0L
            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= MAX_AUDIO_BYTES); digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        } }.getOrNull()
        return candidates.filter { it.sha256 == hash }.map { it.trackId }.filter { it.isNotBlank() }.distinct().singleOrNull()
    }
    suspend fun refreshVisibility() {
        val enabled=dao.allSources().filter { it.enabled }.map { it.id }.toSet()
        val locations=dao.locations().filter { it.sourceId in enabled }.groupBy { it.trackId }
        val changes=dao.allTracks().filter { it.availability!=Availability.ALIAS }.mapNotNull { track ->
            val membership=locations[track.id].orEmpty();val usable=membership.firstOrNull { it.available && it.uri==track.uri } ?: membership.firstOrNull { it.available }
            val state=if(usable!=null) Availability.AVAILABLE else if(dao.state("relocation:${track.id}")?.value?.let { it!="[]" }==true) Availability.AMBIGUOUS else membership.map { it.status }.firstOrNull { it!=Availability.MISSING } ?: Availability.MISSING
            val next=track.copy(active=membership.isNotEmpty(),available=usable!=null,uri=usable?.uri ?: track.uri,availability=state)
            next.takeIf { it!=track }
        }
        if(changes.isNotEmpty())dao.upsert(changes)
    }
    suspend fun relink(id: String, uri: Uri): String = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val old=dao.track(id) ?: error("Track tidak ditemukan")
            val digest=sha(uri)
            require(old.sha256.isBlank() || old.sha256==digest) { "Isi audio berbeda. Pilih rekaman asli; file bernama sama belum tentu lagu yang sama." }
            val src=source(uri,"document");val parsed=documentTrack(uri,old.folder,src.id) ?: error("Audio tidak dapat dibaca")
            if(old.sha256.isBlank()) require(kotlin.math.abs(parsed.duration-old.duration)<=2500) { "Durasi berbeda. Rekaman belum dapat diverifikasi." }
            db.withTransaction {
                val duplicate=dao.allTracks().filter { it.id!=id && identity(Uri.parse(it.uri))==identity(uri) && it.availability!=Availability.ALIAS }
                if(duplicate.isNotEmpty())mergeIdentities(id,duplicate.map { it.id })
                dao.source(src);dao.upsert(listOf(old.copy(uri=uri.toString(),fileName=parsed.fileName,sha256=digest,available=true,active=true,availability=Availability.AVAILABLE,artRevision=System.currentTimeMillis())))
                dao.locations(listOf(TrackLocation(src.id,identity(uri),id,uri.toString(),old.fileSize)))
                dao.state(AppState("relocation:$id","[]"))
            }
            "Lokasi diperbarui. Favorit, playlist dan riwayat dipertahankan."
        }
    }
    suspend fun playbackFailure(id: String, code: Int) = withContext(Dispatchers.IO) {
        val track=dao.track(id) ?: return@withContext
        val status=when(code) {
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> Availability.PERMISSION
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> if(storageMissing(Uri.parse(track.uri))) Availability.STORAGE else Availability.MISSING
            androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED, androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> Availability.UNREADABLE
            else -> Availability.STORAGE
        }
        db.withTransaction {
            dao.upsert(listOf(track.copy(available=false,availability=status)))
            dao.locations(dao.locations().filter { it.trackId==id && it.uri==track.uri }.map { it.copy(available=false,status=status) })
        }
    }
    suspend fun editMetadata(id: String, title: String, artists: String, album: String, artwork: Uri? = null): String = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val old=dao.track(id) ?: error("Track tidak ditemukan")
            val digest=old.sha256.ifBlank { sha(Uri.parse(old.uri)) }
            val next=old.copy(title=title.trim().take(512).ifBlank { old.title },artist=artists.trim().take(512),album=album.trim().take(512),
                artUri=artwork?.toString() ?: old.artUri,sha256=digest,metadataOrigin="user",artRevision=System.currentTimeMillis())
            dao.upsert(listOf(next))
            val cover=next.artUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
            val json=portable.encode(next,cover?.let { runCatching { portable.displayName(it) }.getOrDefault("") }.orEmpty(),cover?.let { runCatching { sha(it) }.getOrDefault("") }.orEmpty())
            val saved=portable.writeNextTo(next,json)
            if(saved==null) "Metadata tersimpan di database. Pilih ekspor manifest agar dapat dibawa ke folder lain." else "Metadata dan manifest pendamping tersimpan; audio asli tidak diubah."
        }
    }
    suspend fun importMetadata(id:String,document:Uri):String=withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val old=dao.track(id) ?: error("Track tidak ditemukan")
            val record=portable.read(document) ?: error("Manifest metadata tidak valid")
            val hash=sha(Uri.parse(old.uri));require(hash==record.hash){"Manifest berasal dari isi audio yang berbeda."}
            val fields=MetadataPolicy.choose(old.fileName,MetadataPolicy.Fields(old.title,old.artist,old.album,old.metadataOrigin),record.fields)
            dao.upsert(listOf(old.copy(title=fields.title,artist=fields.artist,album=fields.album,sha256=hash,sourceIdentity=record.sourceIdentity,
                sourceUrl=record.sourceUrl,metadataOrigin="manifest",artRevision=System.currentTimeMillis())))
            "Metadata cocok dengan hash audio dan disimpan. Untuk sampul pendamping, pilih folder atau pilih gambar lokal."
        }
    }
    suspend fun exportMetadata(id: String, target: Uri) = withContext(Dispatchers.IO) {
        val track=dao.track(id) ?: error("Track tidak ditemukan")
        val updated=if(track.sha256.isBlank())track.copy(sha256=sha(Uri.parse(track.uri))) else track
        val cover=updated.artUri.takeIf(String::isNotBlank)?.let(Uri::parse)
        val text=portable.encode(updated,cover?.let { runCatching { portable.displayName(it) }.getOrDefault("") }.orEmpty(),cover?.let { runCatching { sha(it) }.getOrDefault("") }.orEmpty())
        resolver.openOutputStream(target,"wt")!!.use { it.write(text.toByteArray()) };dao.upsert(listOf(updated))
    }
    suspend fun indexDownloaded(uri: Uri, job: DownloadJob, origin: String = "source"): Track = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val source = source(uri, "download")
            val parsed = documentTrack(uri, job.playlistName, source.id) ?: error("Saved audio could not be read.")
            val existing = dao.track(job.trackId)
            val fields=MetadataPolicy.choose(parsed.fileName,MetadataPolicy.Fields(parsed.title,parsed.artist,parsed.album,parsed.metadataOrigin),MetadataPolicy.Fields(job.title,job.artist,job.album,origin))
            val track = parsed.copy(id = existing?.id ?: stable(identity(uri)), title = fields.title, artist = fields.artist,
                album = MetadataPolicy.known(job.album).ifBlank { parsed.album }, added = existing?.added ?: parsed.added,
                sha256=job.sha256.ifBlank { sha(uri) },fileSize=job.received,sourceIdentity=job.sourceId,sourceUrl=job.url,
                metadataOrigin=fields.origin,artRevision=System.currentTimeMillis())
            db.withTransaction {
                dao.source(source.copy(persistent = true, status = "Validated download"))
                dao.upsert(listOf(track)); dao.locations(listOf(TrackLocation(source.id, identity(uri), track.id, uri.toString(), job.received)))
            }
            track
        }
    }
    private fun clean(value: String?, fallback: String) = (value?.takeUnless { it.isBlank() || it == "<unknown>" } ?: fallback).take(512)
    private fun documentTrack(uri: Uri, folder: String, source: String, knownName: String = ""): Track? = runCatching {
        var name = knownName
        if (name.isBlank()) resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0).orEmpty() }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(duration > 0)
            val fields=MetadataPolicy.choose(name,MetadataPolicy.Fields(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()))
            Track(stable(identity(uri)),uri.toString(),fields.title,fields.artist,fields.album,folder,name,duration,System.currentTimeMillis(),source=source,metadataOrigin=fields.origin)
        } finally { retriever.release() }
    }.getOrNull()
    companion object {
        val AUDIO_EXT = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "webm", "weba")
        const val MAX_AUDIO_BYTES = 256L * 1024 * 1024
    }
    suspend fun create(name: String, ids: List<String> = emptyList()): Long = db.withTransaction {
        val id = dao.create(Playlist(name = validName(name)))
        dao.addEntries(ids.distinct().mapIndexed { index, track -> PlaylistEntry(id, track, index) }); id
    }
    suspend fun append(id: Long, ids: List<String>) = db.withTransaction {
        val ordered = CollectionOrder.append(dao.entries(id).map { it.trackId }, ids)
        dao.clearEntries(id)
        dao.addEntries(ordered.mapIndexed { n, track -> PlaylistEntry(id, track, n) })
    }
    /** Retry inserts missing downloaded items before their next known sibling, retaining user edits. */
    suspend fun reconcileDownloadOrder(id: Long, ids: List<String>) = db.withTransaction {
        val incoming = ids.distinct()
        var ordered = dao.entries(id).map { it.trackId }
        incoming.forEachIndexed { index, track ->
            ordered = CollectionOrder.insertBeforeFollowing(ordered, track, incoming.drop(index + 1).toSet())
        }
        dao.clearEntries(id)
        dao.addEntries(ordered.mapIndexed { n, track -> PlaylistEntry(id, track, n) })
    }
    suspend fun move(id: Long, from: Int, to: Int) = db.withTransaction {
        val ordered = CollectionOrder.move(dao.entries(id).map { it.trackId }, from, to)
        dao.clearEntries(id); dao.addEntries(ordered.mapIndexed { n, track -> PlaylistEntry(id, track, n) })
    }
    fun validName(value: String) = value.trim().take(100).also { require(it.isNotEmpty()) { "Give your playlist a name." } }
}
