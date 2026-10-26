package com.yonzmusic.app.data

import android.net.Uri
import androidx.room.withTransaction
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.update.JsonGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Portable collection metadata, never an implicit copy/delete of audio or grant of storage access. */
class CollectionBackup(private val app: YonzApp) {
    private val dao get() = app.repository.dao
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val document = app.database.withTransaction {
            val tracks = JSONArray().apply { dao.allTracks().forEach { t -> put(JSONObject().put("id", t.id).put("uri", t.uri).put("title", t.title).put("artist", t.artist).put("album", t.album).put("folder", t.folder).put("fileName", t.fileName).put("duration", t.duration).put("added", t.added).put("sha256",t.sha256).put("fileSize",t.fileSize).put("sourceIdentity",t.sourceIdentity).put("sourceUrl",t.sourceUrl).put("metadataOrigin",t.metadataOrigin)) } }
            val playlists = JSONArray().apply { dao.playlists().first().forEach { p -> put(JSONObject().put("name", p.name).put("ids", JSONArray(dao.entries(p.id).map { it.trackId }))) } }
            val history = JSONArray().apply { dao.history().first().forEach { h -> put(JSONObject().put("id", h.trackId).put("playedAt", h.playedAt).put("position", h.position)) } }
            JSONObject().put("schemaVersion", 1).put("applicationId", "com.yonzmusic.app").put("tracks", tracks).put("playlists", playlists)
                .put("favorites", JSONArray(dao.favorites().first().map { it.trackId })).put("history", history)
        }
        app.preferences.snapshot()?.let { snapshot ->
            val ids = snapshot.ids.map { dao.state("track_alias:$it")?.value ?: it }
            document.put("playback", JSONObject().put("ids", JSONArray(ids)).put("index", snapshot.index).put("position", snapshot.position).put("shuffle", snapshot.shuffle).put("repeat", snapshot.repeat))
        }
        val preferences = app.preferences.flow.first()
        document.put("preferences", JSONObject().put("theme", preferences.theme).put("sort", preferences.sort).put("reduceMotion", preferences.reduceMotion).put("glass", preferences.glass).put("smartMix",preferences.smartMix))
        val text = document.toString(2); require(text.toByteArray().size <= LIMIT) { "Collection backup exceeds 10 MB." }
        app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } ?: error("Backup could not be saved.")
    }
    suspend fun restore(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = app.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= LIMIT) { "Backup exceeds 10 MB." }; out.write(buffer, 0, n) }; out.toString("UTF-8")
        } ?: error("Backup could not be read.")
        val root = JsonGuard.parse(text, LIMIT)
        require(root.getInt("schemaVersion") == 1 && root.getString("applicationId") == "com.yonzmusic.app") { "Not a supported Yono collection backup." }
        val tracks = root.getJSONArray("tracks"); val playlists = root.getJSONArray("playlists"); val favorites = root.getJSONArray("favorites"); val history = root.getJSONArray("history")
        require(tracks.length() <= 10000 && playlists.length() <= 1000 && favorites.length() <= 10000 && history.length() <= 1000)
        val mapping = mutableMapOf<String, String>()
        app.database.withTransaction {
            val existing = dao.allTracks().filter { it.availability!=Availability.ALIAS }.associateBy { it.uri }
            val new = mutableListOf<Track>()
            repeat(tracks.length()) { n ->
                val item = tracks.getJSONObject(n)
                fun field(name: String, limit: Int = 512): String = item.optString(name).also { require(it.length <= limit) { "Backup field is too long." } }
                val reference = field("uri", 4096); require(Uri.parse(reference).scheme == "content") { "Backup tracks must reference local content." }
                val oldId = field("id", 128); require(oldId.isNotBlank() && oldId !in mapping)
                val known = existing[reference]
                val id = known?.id ?: app.repository.stable("backup:$reference")
                mapping[oldId] = id
                if (known == null) new += Track(id, reference, field("title"), field("artist"), field("album"), field("folder"), field("fileName"), item.optLong("duration").coerceAtLeast(0), item.optLong("added"), source = "restored", available = false, active = false,availability=Availability.MISSING,
                    sha256=item.optString("sha256").takeIf { it.matches(Regex("[a-f0-9]{64}")) }.orEmpty(),fileSize=item.optLong("fileSize"),
                    sourceIdentity=field("sourceIdentity"),sourceUrl=field("sourceUrl",4096),metadataOrigin=item.optString("metadataOrigin","tags"))
            }
            dao.upsert(new.distinctBy { it.uri })
            repeat(playlists.length()) { n ->
                val p = playlists.getJSONObject(n); val ids = p.getJSONArray("ids"); require(ids.length() <= 10000)
                app.repository.create(p.getString("name"), (0 until ids.length()).mapNotNull { mapping[ids.getString(it)] })
            }
            repeat(favorites.length()) { n -> mapping[favorites.getString(n)]?.let { dao.favorite(Favorite(it)) } }
            repeat(history.length()) { n -> val h = history.getJSONObject(n); mapping[h.getString("id")]?.let { dao.remember(History(it, h.optLong("playedAt"), h.optLong("position").coerceAtLeast(0))) } }
        }
        root.optJSONObject("playback")?.let { p ->
            val values = p.getJSONArray("ids"); require(values.length() <= 10000)
            val old = (0 until values.length()).map { values.getString(it) }
            val current = old.getOrNull(p.optInt("index"))?.let(mapping::get)
            val ids = old.mapNotNull(mapping::get).distinct()
            app.preferences.save(PlaybackSnapshot(ids, ids.indexOf(current).coerceAtLeast(0), p.optLong("position").coerceAtLeast(0), p.optBoolean("shuffle"), p.optInt("repeat").coerceIn(0, 2)))
        }
        root.optJSONObject("preferences")?.let { p ->
            p.optString("theme").takeIf { it in setOf("System", "Dark", "Light") }?.let { app.preferences.theme(it) }
            p.optString("sort").takeIf { it in setOf("Title", "Artist", "Album", "Recently added", "Duration") }?.let { app.preferences.sort(it) }
            app.preferences.motion(p.optBoolean("reduceMotion")); app.preferences.glass(p.optBoolean("glass", true));app.preferences.smartMix(p.optBoolean("smartMix",true))
        }
        "Collections restored. Choose music sources to reconnect unavailable tracks. Original audio was not changed."
    }
    companion object { const val LIMIT = 10 * 1024 * 1024 }
}
