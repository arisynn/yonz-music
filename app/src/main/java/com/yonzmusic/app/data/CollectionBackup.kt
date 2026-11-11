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
import com.yonzmusic.app.playback.StreamRepository

/** Portable collection metadata, never an implicit copy/delete of audio or grant of storage access. */
class CollectionBackup(private val app: YonzApp) {
    private val dao get() = app.repository.dao
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val document = app.database.withTransaction {
            val tracks = JSONArray().apply { dao.allTracks().forEach { t -> put(JSONObject().put("id", t.id).put("uri", t.uri).put("title", t.title).put("artist", t.artist).put("album", t.album).put("folder", t.folder).put("fileName", t.fileName).put("duration", t.duration).put("added", t.added).put("sha256",t.sha256).put("fileSize",t.fileSize).put("sourceIdentity",t.sourceIdentity).put("sourceUrl",t.sourceUrl).put("metadataOrigin",t.metadataOrigin)) } }
            val playlists = JSONArray().apply { dao.playlists().first().forEach { p -> put(JSONObject().put("name", p.name).put("ids", JSONArray(dao.entries(p.id).map { it.trackId }))) } }
            val history = JSONArray().apply { dao.history().first().forEach { h -> put(JSONObject().put("id", h.trackId).put("playedAt", h.playedAt).put("position", h.position)) } }
            JSONObject().put("schemaVersion", 2).put("applicationId", "com.yonzmusic.app").put("tracks", tracks).put("playlists", playlists)
                .put("favorites", JSONArray(dao.favorites().first().map { it.trackId })).put("history", history)
                .put("onlinePlaylists",JSONArray().apply {dao.states(OnlineCollectionContract.PREFIX+"%").forEach {put(OnlineCollectionContract.encode(OnlineCollectionContract.decode(JSONObject(it.value))))}})
                .put("onlineActivity",JSONArray().apply {dao.onlineActivity().first().forEach {row->put(JSONObject().put("song",JSONObject(row.metadata)).put("favorite",row.favorite).put("lastPlayed",row.lastPlayed).put("playCount",row.playCount).put("listenedMs",row.listenedMs).put("position",row.position))}})
        }
        app.preferences.snapshot()?.let { snapshot ->
            val ids = snapshot.ids.map { dao.state("track_alias:$it")?.value ?: it }
            document.put("playback", JSONObject().put("ids", JSONArray(ids)).put("index", snapshot.index).put("position", snapshot.position).put("shuffle", snapshot.shuffle).put("repeat", snapshot.repeat))
        }
        val preferences = app.preferences.flow.first()
        document.put("preferences", JSONObject().put("theme", preferences.theme).put("sort", preferences.sort).put("reduceMotion", preferences.reduceMotion).put("glass", preferences.glass).put("smartMix",preferences.smartMix)
            .put("streamQuality",preferences.streamQuality).put("streamCacheMb",preferences.streamCacheMb).put("autoplay",preferences.autoplay).put("onlineHistory",preferences.onlineHistory))
        val text = document.toString(2); require(text.toByteArray().size <= LIMIT) { "Collection backup exceeds 10 MB." }
        app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } ?: error("Backup could not be saved.")
    }
    suspend fun restore(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = app.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= LIMIT) { "Backup exceeds 10 MB." }; out.write(buffer, 0, n) }; out.toString("UTF-8")
        } ?: error("Backup could not be read.")
        val root = JsonGuard.parse(text, LIMIT)
        require(root.getInt("schemaVersion") in 1..2 && root.getString("applicationId") == "com.yonzmusic.app") { "Not a supported Yono collection backup." }
        val tracks = root.getJSONArray("tracks"); val playlists = root.getJSONArray("playlists"); val favorites = root.getJSONArray("favorites"); val history = root.getJSONArray("history")
        require(tracks.length() <= 10000 && playlists.length() <= 1000 && favorites.length() <= 10000 && history.length() <= 1000)
        val onlineRows=root.optJSONArray("onlinePlaylists") ?: JSONArray()
        require(onlineRows.length()<=100)
        val online=(0 until onlineRows.length()).map {OnlineCollectionContract.decode(onlineRows.getJSONObject(it))}
        val activityRows=root.optJSONArray("onlineActivity") ?: JSONArray()
        require(activityRows.length()<=6000) {"Terlalu banyak aktivitas daring."}
        val activity=(0 until activityRows.length()).map {n->
            val value=activityRows.getJSONObject(n);val song=StreamRepository.decode(value.getJSONObject("song"))
            OnlineActivity(song.videoId,StreamRepository.encode(song).toString(),value.optBoolean("favorite"),value.optLong("lastPlayed").coerceIn(0,System.currentTimeMillis()),
                value.optLong("playCount").coerceIn(0,Int.MAX_VALUE.toLong()),value.optLong("listenedMs").coerceIn(0,Long.MAX_VALUE/2),value.optLong("position").coerceIn(0,7*86400000L))
        }
        require(activity.map {it.videoId}.distinct().size==activity.size)
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
            online.forEach {p->app.onlineCollections.save(p.copy(id=java.util.UUID.randomUUID().toString()))}
            activity.forEach {row->
                val old=dao.onlineActivity(row.videoId)
                dao.onlineActivity(row.copy(favorite=row.favorite || old?.favorite==true,lastPlayed=maxOf(row.lastPlayed,old?.lastPlayed ?: 0),playCount=maxOf(row.playCount,old?.playCount ?: 0),listenedMs=maxOf(row.listenedMs,old?.listenedMs ?: 0)))
                app.streams.register(StreamRepository.decode(JSONObject(row.metadata)))
            }
            require(dao.onlineActivity().first().count {it.favorite}<=5000) {"Maksimal 5.000 favorit daring."}
            dao.trimOnlineActivity()
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
            suspend fun restoredId(id:String):String?=mapping[id] ?: id.takeIf {app.streams.lookup(it)!=null}
            val current = old.getOrNull(p.optInt("index"))?.let {restoredId(it)}
            val ids = old.mapNotNull {restoredId(it)}.distinct()
            app.preferences.save(PlaybackSnapshot(ids, ids.indexOf(current).coerceAtLeast(0), p.optLong("position").coerceAtLeast(0), p.optBoolean("shuffle"), p.optInt("repeat").coerceIn(0, 2)))
        }
        root.optJSONObject("preferences")?.let { p ->
            p.optString("theme").takeIf { it in setOf("System", "Dark", "Light") }?.let { app.preferences.theme(it) }
            p.optString("sort").takeIf { it in setOf("Title", "Artist", "Album", "Recently added", "Duration") }?.let { app.preferences.sort(it) }
            app.preferences.motion(p.optBoolean("reduceMotion")); app.preferences.glass(p.optBoolean("glass", true));app.preferences.smartMix(p.optBoolean("smartMix",true))
            p.optString("streamQuality").takeIf {it in setOf("economy","balanced","best")}?.let {app.preferences.streamQuality(it)}
            if(p.has("streamCacheMb"))p.optInt("streamCacheMb").takeIf {it in setOf(0,32,64)}?.let {app.preferences.streamCacheMb(it)}
            if(p.has("autoplay"))app.preferences.autoplay(p.getBoolean("autoplay"))
            if(p.has("onlineHistory"))app.preferences.onlineHistory(p.getBoolean("onlineHistory"))
        }
        "Collections restored. Choose music sources to reconnect unavailable tracks. Original audio was not changed."
    }
    companion object { const val LIMIT = 10 * 1024 * 1024 }
}
