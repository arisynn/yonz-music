package com.yonzmusic.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks", indices = [Index("uri", unique = true), Index("source"), Index("title")])
data class Track(
    @PrimaryKey val id: String,
    val uri: String,
    val title: String,
    val artist: String = "Unknown artist",
    val album: String = "Unknown album",
    val folder: String = "Music",
    val fileName: String = "",
    val duration: Long = 0,
    val added: Long = 0,
    val artUri: String = "",
    val source: String = "media",
    val available: Boolean = true
)
@Entity(tableName = "favorites")
data class Favorite(@PrimaryKey val trackId: String)
@Entity(tableName = "history")
data class History(@PrimaryKey val trackId: String, val playedAt: Long, val position: Long = 0)
@Entity(tableName = "playlists")
data class Playlist(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val created: Long = System.currentTimeMillis())
@Entity(tableName = "playlist_entries", primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [ForeignKey(entity = Playlist::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Track::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)], indices = [Index("trackId")])
data class PlaylistEntry(val playlistId: Long, val trackId: String, val position: Int)
data class PlaylistSummary(val id: Long, val name: String, val created: Long, val count: Int)
@Entity(tableName = "imports")
data class ImportLog(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val date: Long = System.currentTimeMillis(), val report: String)

@Entity(tableName = "import_details", primaryKeys = ["importId", "position"],
    foreignKeys = [ForeignKey(entity = ImportLog::class, parentColumns = ["id"], childColumns = ["importId"], onDelete = ForeignKey.CASCADE)])
data class ImportDetail(val importId: Long, val position: Int, val input: String, val status: String, val detail: String)

@Dao
interface MusicDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE") fun tracks(): Flow<List<Track>>
    @Query("SELECT * FROM tracks") suspend fun allTracks(): List<Track>
    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun track(id: String): Track?
    @Upsert suspend fun upsert(tracks: List<Track>)
    @Query("UPDATE tracks SET available = 0 WHERE source = :source") suspend fun unavailableSource(source: String)
    @Query("UPDATE tracks SET available = 0 WHERE id = :id") suspend fun unavailable(id: String)
    @Query("SELECT * FROM favorites") fun favorites(): Flow<List<Favorite>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun favorite(f: Favorite)
    @Query("DELETE FROM favorites WHERE trackId = :id") suspend fun unfavorite(id: String)
    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT 100") fun history(): Flow<List<History>>
    @Upsert suspend fun remember(history: History)
    @Query("SELECT p.id, p.name, p.created, COUNT(e.trackId) AS count FROM playlists p LEFT JOIN playlist_entries e ON p.id = e.playlistId GROUP BY p.id ORDER BY p.created DESC") fun playlists(): Flow<List<PlaylistSummary>>
    @Query("SELECT * FROM playlist_entries ORDER BY position") fun entries(): Flow<List<PlaylistEntry>>
    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position") suspend fun entries(id: Long): List<PlaylistEntry>
    @Insert suspend fun create(playlist: Playlist): Long
    @Query("UPDATE playlists SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: Long)
    @Query("DELETE FROM playlist_entries WHERE playlistId = :id") suspend fun clearEntries(id: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addEntries(entries: List<PlaylistEntry>)
    @Query("DELETE FROM playlist_entries WHERE playlistId = :id AND trackId = :trackId") suspend fun removeEntry(id: Long, trackId: String)
    @Insert suspend fun log(log: ImportLog): Long
    @Insert suspend fun logDetails(rows: List<ImportDetail>)
    @Query("SELECT * FROM import_details WHERE importId = :id ORDER BY position") suspend fun importDetails(id: Long): List<ImportDetail>
    @Query("SELECT * FROM imports ORDER BY date DESC LIMIT 30") fun imports(): Flow<List<ImportLog>>
}

@Database(entities = [Track::class, Favorite::class, History::class, Playlist::class, PlaylistEntry::class, ImportLog::class, ImportDetail::class], version = 1, exportSchema = true)
abstract class MusicDatabase : RoomDatabase() { abstract fun music(): MusicDao }

/** Pure ordering operations shared by database edits and tests. */
object CollectionOrder {
    fun append(existing: List<String>, incoming: List<String>): List<String> = (existing + incoming).distinct()
    fun move(items: List<String>, from: Int, to: Int): List<String> {
        if (from !in items.indices || to !in items.indices || from == to) return items
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }
}

/** A finished song resumes from its beginning; all other positions stay paused in place. */
object PlaybackResume {
    fun position(saved: Long, duration: Long): Long {
        val end = duration.coerceAtLeast(0)
        val bounded = saved.coerceIn(0, end)
        return if (end - bounded <= 500) 0 else bounded
    }
}
