package com.yonzmusic.app.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.data.*
import com.yonzmusic.app.network.SafeHttp
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

class DownloadEngine(private val app: YonzApp, private val testTransport: JSONObject? = null) {
    init { require(testTransport == null || com.yonzmusic.app.BuildConfig.DEBUG) }
    private val dao get() = app.repository.dao
    private val resolver get() = app.contentResolver
    private fun children(parent: Uri): List<Pair<String, Uri>> {
        val result = mutableListOf<Pair<String, Uri>>()
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent)),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) { require(result.size < 20000) { "Choose a smaller output folder." }; result += cursor.getString(1).orEmpty() to DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0)) }
        } ?: error("Destination is unavailable. Select it again.")
        return result
    }
    private fun folder(tree: String, name: String): Uri {
        val root = Uri.parse(tree)
        require(DocumentsContract.isTreeUri(root))
        val parent = DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
        val safe = RecordingMatch.safeName(name)
        val selectedName = resolver.query(parent, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).orEmpty() else ""
        }.orEmpty()
        if (selectedName.equals(safe, ignoreCase = true)) return parent
        val existing = children(parent).find { it.first == safe }?.second
        if (existing != null) {
            resolver.query(existing, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
                require(it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR) { "A file already uses this playlist folder name. Rename the playlist." }
            } ?: error("Destination unavailable.")
            return existing
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, safe) ?: error("Could not create the playlist folder.")
    }
    private suspend fun save(job: DownloadJob) { dao.jobs(listOf(job)) }
    suspend fun run(initial: DownloadJob, progress: suspend (DownloadJob) -> Unit): DownloadJob {
        var job = initial
        val workspace = File(app.cacheDir, "audio-transfer").apply { mkdirs() }
        var raw = File(workspace, "${job.id}.part")
        val tagged = File(workspace, "${job.id}.tagged")
        val engineWorkspace = File(workspace, job.id)
        val picture = File(workspace, "${job.id}.picture")
        suspend fun checkCancelled() { coroutineContext.ensureActive(); check(dao.download(job.id)?.status != "Cancelled") { "Cancelled by you" } }
        try {
            checkCancelled()
            // A previous queued batch may have completed since this one was prepared.
            val existing = dao.completedDownloads().firstOrNull { it.sourceId == job.sourceId && it.id != job.id }
            val known = existing?.let { dao.track(it.trackId) }
            if (existing != null && known != null && runCatching { contentHash(Uri.parse(known.uri)) == existing.sha256 }.getOrDefault(false)) {
                job = job.copy(uri = known.uri, trackId = known.id, sha256 = existing.sha256, received = existing.received,
                    title = known.title, artist = known.artist, album = known.album)
                return finish(job, "Existing")
            }
            require(job.attempts < 4) { "Retry limit reached. Review the source and start a new import." }
            job = job.copy(attempts = job.attempts + 1, error = "")
            // A completed private download may be restarted; shared publication is journalled and recovered separately.
            val journal = dao.state("publish:${job.id}")?.value?.takeIf { it.isNotBlank() }?.let(::JSONObject)
            if (job.uri.isNotBlank() && journal != null) {
                val parent = Uri.parse(journal.getString("parent"))
                val expectedHash = journal.getString("hash")
                val target = children(parent).find { it.first == journal.getString("name") }?.second
                if (target != null && runCatching { contentHash(target) == expectedHash }.getOrDefault(false)) {
                    return finish(job.copy(uri = target.toString(), sha256 = expectedHash))
                }
                val stage = Uri.parse(job.uri)
                if (isOwnedStage(stage, job.id) && runCatching { contentHash(stage) == expectedHash }.getOrDefault(false)) {
                    val recovered = DocumentsContract.renameDocument(resolver, stage, journal.getString("name")) ?: error("Could not finalize the recovered transfer.")
                    job = job.copy(status = "Saved", uri = recovered.toString(), sha256 = expectedHash); save(job)
                    return finish(job)
                }
                cleanupStage(job)
            }
            job = job.copy(status = "Downloading", received = 0, total = job.expectedSize, uri = "")
            save(job)
            val servicePermission = dao.state("download_authorization:${job.batchId}")?.value == "content-and-service"
            if (job.sourceId.startsWith("yt-dlp:")) {
                engineWorkspace.deleteRecursively(); engineWorkspace.mkdirs()
                val transferContext = coroutineContext
                var lastProgress = 0L
                raw = EmbeddedEngine.transfer(app, job, engineWorkspace, servicePermission, object : EmbeddedEngine.Callback {
                    override fun cancelled(): Boolean = !transferContext.isActive || runBlocking { dao.download(job.id)?.status == "Cancelled" }
                    override fun progress(received: Long, total: Long) {
                        if (System.currentTimeMillis() - lastProgress < 400 && received != total) return
                        lastProgress = System.currentTimeMillis()
                        runBlocking { checkCancelled(); job = job.copy(received = received, total = total); save(job); progress(job) }
                    }
                }, testTransport)
            } else SafeHttp.download(job.url, CommonsSource.hosts, raw, Repository.MAX_AUDIO_BYTES, job.expectedSize,
                progress = { received, total -> checkCancelled(); job = job.copy(received = received, total = total); save(job); progress(job) })
            checkCancelled(); job = job.copy(status = "Validating"); save(job); progress(job)
            require(job.expectedSize <= 0 || raw.length() == job.expectedSize) { "Source size changed or the audio is incomplete. Analyze the source again." }
            val info = AudioValidation.validate(raw, job.expectedDuration)
            val embedded = AudioValidation.embeddedMetadata(raw)
            dao.state("requested:${job.id}")?.value?.let { value ->
                val requested = JSONObject(value)
                val generic = job.sourceId.startsWith("yt-dlp:Generic", true)
                require(!generic || (embedded.first.isNotBlank() && embedded.second.isNotBlank())) { "File tags do not verify the requested recording. Review an exact source separately." }
                val actual = AudioCandidate(job.sourceId, embedded.first.ifBlank { job.title }, embedded.second.ifBlank { job.artist }, job.album,
                    info.duration, raw.length(), job.url, job.license, job.licenseUrl, job.url)
                val request = com.yonzmusic.app.importer.SongRequest("", requested.getString("title"), requested.getString("artist"), duration = requested.optLong("duration"))
                val reason = RecordingMatch.rejection(request, actual)
                require(reason == null) { "Requested recording was rejected: $reason. No audio was published." }
            }
            val missingCredits = job.artist in setOf("Unspecified credits", "Unknown artist", "")
            if (missingCredits) job = job.copy(title = embedded.first.ifBlank { job.title }, artist = embedded.second.ifBlank { job.artist }, album = embedded.third.ifBlank { job.album })
            else if (job.album.isBlank()) job = job.copy(album = embedded.third)
            job = job.copy(expectedDuration = if (job.expectedDuration > 0) job.expectedDuration else info.duration); save(job)
            raw.copyTo(tagged, overwrite = true)
            var artwork: File? = null
            if (job.artUrl.isNotBlank()) artwork = runCatching { EmbeddedEngine.artwork(app, job.artUrl, picture, servicePermission) }.getOrNull()
            EmbeddedEngine.tags(app, tagged, job, artwork)
            AudioValidation.validate(tagged, info.duration)
            val complete = tagged
            require(workspace.usableSpace > complete.length() + 4 * 1024 * 1024) { "Not enough temporary storage." }
            val hash = SafeHttp.sha256(complete)
            val parent = folder(job.destination, job.playlistName)
            app.downloadGallery.beforePublish(Uri.parse(job.destination), parent)
            val stem = RecordingMatch.safeName("${job.artist} - ${job.title}")
            val names = children(parent).map { it.first }.toSet()
            var name = "$stem.${info.extension}"; var suffix = 2
            while (name in names) { name = "$stem (${suffix++}).${info.extension}"; require(suffix < 10000) }
            val mime = when (info.extension) { "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; "webm" -> "audio/webm"; else -> info.mime }
            val journalData = JSONObject().put("parent", parent.toString()).put("name", name).put("hash", hash)
            dao.state(AppState("publish:${job.id}", journalData.toString()))
            val stage = DocumentsContract.createDocument(resolver, parent, mime, ".yono-part-${job.id}.${info.extension}") ?: error("Cannot write to this destination. Select it again.")
            job = job.copy(status = "Publishing", uri = stage.toString(), sha256 = hash, received = complete.length()); save(job)
            resolver.openOutputStream(stage, "w")?.use { output ->
                complete.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { checkCancelled(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
                }
            } ?: error("The destination rejected writing.")
            require(contentHash(stage) == hash) { "The saved copy is incomplete. Nothing was indexed." }
            checkCancelled()
            val published = DocumentsContract.renameDocument(resolver, stage, name) ?: error("This provider cannot finalize staged audio. Choose another output folder.")
            job = job.copy(status = "Saved", uri = published.toString()); save(job)
            return finish(job)
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) { cleanupStage(job) }; throw e
        } catch (e: Exception) {
            if (!coroutineContext.isActive) { withContext(NonCancellable) { cleanupStage(job) }; throw kotlinx.coroutines.CancellationException("Transfer interrupted", e) }
            val cancelled = dao.download(job.id)?.status == "Cancelled"
            // Saved full files stay recoverable for a retry. Incomplete staging files are ours to remove.
            if (job.status == "Publishing") cleanupStage(job)
            job = job.copy(status = if (cancelled) "Cancelled" else "Failed", error = if (cancelled) "Cancelled by you" else if (e is com.chaquo.python.PyException) SourceFailure.describe(e) else e.message?.take(200) ?: "Download failed. Retry later.")
            save(job); return job
        } finally { raw.delete(); tagged.delete(); picture.delete(); engineWorkspace.deleteRecursively() }
    }
    private suspend fun finish(job: DownloadJob, status: String = "Complete"): DownloadJob {
        if (dao.download(job.id)?.status == "Cancelled") return job.copy(status = "Cancelled", error = "Cancelled by you")
        if (status == "Complete") ensureAttribution(job)
        val track = app.repository.indexDownloaded(Uri.parse(job.uri), job)
        val complete = job.copy(status = status, trackId = track.id, error = "")
        return app.database.withTransaction {
            if (dao.download(job.id)?.status == "Cancelled" || !dao.playlistExists(job.playlistId)) return@withTransaction job.copy(status = "Cancelled", error = "Collection action cancelled this transfer")
            save(complete)
            val jobs = dao.batch(job.batchId)
            val current = dao.entries(job.playlistId).map { it.trackId }
            val following = jobs.filter { it.ordinal > job.ordinal && it.trackId.isNotBlank() }.map { it.trackId }.toSet()
            val ordered = CollectionOrder.insertBeforeFollowing(current, track.id, following)
            if (ordered != current) {
                dao.clearEntries(job.playlistId)
                dao.addEntries(ordered.mapIndexed { n, id -> PlaylistEntry(job.playlistId, id, n) })
            }
            complete
        }
    }
    private suspend fun ensureAttribution(job: DownloadJob) {
        val journal = dao.state("publish:${job.id}")?.value?.let(::JSONObject) ?: return
        val parent = Uri.parse(journal.getString("parent"))
        val name = journal.getString("name") + ".license.txt"
        val text = "${job.title}\n${job.artist}\n${job.album}\nSource: ${job.url}\nSource ID: ${job.sourceId}\nLicense/authorization: ${job.license} ${job.licenseUrl}\nSaved by Yono Music. Audio was not re-encoded.\n"
        val existing = children(parent).find { it.first == name }?.second
        if (existing != null && runCatching {
            resolver.openInputStream(existing)?.bufferedReader()?.use { reader ->
                val buffer = CharArray(8193); var count = 0
                while (count < buffer.size) { val n = reader.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                String(buffer, 0, count) == text
            } == true
        }.getOrDefault(false)) return
        // Never replace someone else's sidecar. A failed partial sidecar gets a provider-safe suffix.
        val attribution = DocumentsContract.createDocument(resolver, parent, "text/plain", name) ?: error("Could not save source attribution.")
        resolver.openOutputStream(attribution)?.bufferedWriter()?.use { it.write(text) } ?: error("Could not write source attribution.")
    }
    private suspend fun cleanupStage(job: DownloadJob) {
        if (job.uri.isNotBlank()) runCatching { val uri = Uri.parse(job.uri); if (isOwnedStage(uri, job.id)) DocumentsContract.deleteDocument(resolver, uri) }
    }
    private fun isOwnedStage(uri: Uri, id: String): Boolean = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            it.moveToFirst() && Regex("^\\.yono-part-" + Regex.escape(id) + "(?: \\([0-9]+\\))?\\.[a-z0-9]{1,8}$").matches(it.getString(0))
        } ?: false
    }.getOrDefault(false)
    private fun contentHash(uri: Uri): String = resolver.openInputStream(uri)?.use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256"); var total = 0L; val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= Repository.MAX_AUDIO_BYTES + 2 * 1024 * 1024); digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    } ?: error("Saved audio cannot be read.")
}
