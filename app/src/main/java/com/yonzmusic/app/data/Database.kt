package com.yonzmusic.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks", indices = [Index("uri"), Index("source"), Index("title")])
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
    val available: Boolean = true,
    @ColumnInfo(defaultValue = "1") val active: Boolean = true,
    @ColumnInfo(defaultValue = "''") val sha256: String = "",
    @ColumnInfo(defaultValue = "0") val fileSize: Long = 0,
    @ColumnInfo(defaultValue = "0") val modified: Long = 0,
    @ColumnInfo(defaultValue = "'available'") val availability: String = Availability.AVAILABLE,
    @ColumnInfo(defaultValue = "''") val sourceIdentity: String = "",
    @ColumnInfo(defaultValue = "''") val sourceUrl: String = "",
    @ColumnInfo(defaultValue = "'tags'") val metadataOrigin: String = "tags",
    @ColumnInfo(defaultValue = "0") val artRevision: Long = 0
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
    @Query("SELECT * FROM tracks WHERE active = 1 ORDER BY title COLLATE NOCASE") fun tracks(): Flow<List<Track>>
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE") fun collectionTracks(): Flow<List<Track>>
    @Query("SELECT * FROM tracks") suspend fun allTracks(): List<Track>
    @Query("SELECT * FROM app_state WHERE name LIKE :prefix") suspend fun states(prefix: String): List<AppState>
    @Query("SELECT * FROM app_state WHERE name LIKE :prefix") fun statesFlow(prefix: String): Flow<List<AppState>>
    @Query("DELETE FROM app_state WHERE name = :name") suspend fun deleteState(name: String)
    @Query("SELECT * FROM listens") suspend fun allListens(): List<ListenEvent>
    @Query("SELECT trackId, COUNT(*) AS playCount, MAX(startedAt) AS lastPlayed FROM listens GROUP BY trackId") fun listenStats(): Flow<List<ListenStats>>
    @Insert suspend fun listen(event: ListenEvent)
    @Query("UPDATE listens SET trackId = :target WHERE trackId = :old") suspend fun rebindListens(old: String, target: String)
    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun track(id: String): Track?
    @Upsert suspend fun upsert(tracks: List<Track>)
    @Query("UPDATE tracks SET available = 0 WHERE source = :source") suspend fun unavailableSource(source: String)
    @Query("UPDATE tracks SET available = 0 WHERE id = :id") suspend fun unavailable(id: String)
    @Query("SELECT * FROM history WHERE trackId = :id") suspend fun historyRow(id: String): History?
    @Query("SELECT * FROM favorites WHERE trackId = :id") suspend fun favoriteRow(id: String): Favorite?
    @Query("DELETE FROM history WHERE trackId = :id") suspend fun deleteHistory(id: String)
    @Query("SELECT * FROM playlist_entries WHERE trackId = :id") suspend fun memberships(id: String): List<PlaylistEntry>
    @Query("UPDATE locations SET trackId = :target WHERE trackId = :old") suspend fun rebindLocations(old: String, target: String)
    @Query("UPDATE download_jobs SET trackId = :target WHERE trackId = :old") suspend fun rebindDownloads(old: String, target: String)
    @Query("DELETE FROM tracks WHERE id = :id") suspend fun deleteIndexRow(id: String)
    @Query("UPDATE app_state SET value = :target WHERE name LIKE 'track_alias:%' AND value = :old") suspend fun compressAliases(old: String, target: String)
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
    @Query("SELECT * FROM sources ORDER BY added") fun sources(): Flow<List<MusicSource>>
    @Query("SELECT * FROM sources ORDER BY added") suspend fun allSources(): List<MusicSource>
    @Query("SELECT * FROM sources WHERE id = :id") suspend fun source(id: String): MusicSource?
    @Upsert suspend fun source(source: MusicSource)
    @Query("DELETE FROM locations WHERE sourceId = :id") suspend fun clearLocations(id: String)
    @Query("DELETE FROM sources WHERE id = :id") suspend fun deleteSource(id: String)
    @Query("SELECT * FROM locations") suspend fun locations(): List<TrackLocation>
    @Upsert suspend fun locations(rows: List<TrackLocation>)
    @Query("SELECT * FROM app_state WHERE name = :name") suspend fun state(name: String): AppState?
    @Query("SELECT * FROM app_state WHERE name = :name") fun stateFlow(name: String): Flow<AppState?>
    @Query("SELECT * FROM app_state WHERE name = 'source_migration_notice'") fun migrationNotice(): Flow<AppState?>
    @Upsert suspend fun state(state: AppState)
    @Query("SELECT * FROM download_jobs WHERE status IN ('Queued','Downloading','Validating','Publishing','Saved') OR id IN (SELECT id FROM download_jobs ORDER BY created DESC, ordinal LIMIT 400) ORDER BY created DESC, ordinal") fun downloads(): Flow<List<DownloadJob>>
    @Query("SELECT * FROM download_jobs WHERE batchId = :batch ORDER BY ordinal") suspend fun batch(batch: String): List<DownloadJob>
    @Query("SELECT * FROM download_jobs WHERE id = :id") suspend fun download(id: String): DownloadJob?
    @Query("SELECT * FROM download_jobs WHERE status IN ('Queued','Downloading','Validating','Publishing','Saved') ORDER BY created, ordinal LIMIT 1") suspend fun nextTransfer(): DownloadJob?
    @Query("SELECT * FROM download_jobs WHERE status = 'Complete'") suspend fun completedDownloads(): List<DownloadJob>
    @Upsert suspend fun jobs(jobs: List<DownloadJob>)
    @Query("UPDATE download_jobs SET status = 'Cancelled', error = 'Cancelled by you' WHERE batchId = :batch AND status NOT IN ('Complete', 'Existing', 'Duplicate')") suspend fun cancelBatch(batch: String)
    @Query("SELECT DISTINCT batchId FROM download_jobs WHERE playlistId = :playlist") suspend fun playlistBatches(playlist: Long): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM playlists WHERE id = :id)") suspend fun playlistExists(id: Long): Boolean
}

@Entity(tableName = "sources")
data class MusicSource(@PrimaryKey val id: String, val uri: String, val name: String, val kind: String,
    val enabled: Boolean = true, val recursive: Boolean = true, val persistent: Boolean = false,
    val added: Long = System.currentTimeMillis(), val status: String = "Not refreshed")

/** Separate memberships mean overlapping trees and file selections never own/delete audio. */
@Entity(tableName = "locations", primaryKeys = ["sourceId", "identity"], indices = [Index("trackId"), Index("identity")])
data class TrackLocation(val sourceId: String, val identity: String, val trackId: String, val uri: String,
    val size: Long = 0, val modified: Long = 0, val available: Boolean = true,
    @ColumnInfo(defaultValue = "'available'") val status: String = Availability.AVAILABLE)
@Entity(tableName = "app_state") data class AppState(@PrimaryKey val name: String, val value: String)
@Entity(tableName = "download_jobs", indices = [Index("batchId"), Index("sourceId")])
data class DownloadJob(@PrimaryKey val id: String, val batchId: String, val ordinal: Int, val playlistId: Long,
    val playlistName: String, val destination: String, val title: String, val artist: String, val album: String,
    val sourceId: String, val url: String, val license: String, val licenseUrl: String,
    val expectedDuration: Long = 0, val expectedSize: Long = 0, val artUrl: String = "",
    val status: String = "Queued", val received: Long = 0, val total: Long = 0, val attempts: Int = 0,
    val uri: String = "", val trackId: String = "", val sha256: String = "", val error: String = "",
    val created: Long = System.currentTimeMillis())

@Entity(tableName = "listens", indices = [Index("trackId"), Index("startedAt")])
data class ListenEvent(@PrimaryKey(autoGenerate = true) val id: Long = 0, val trackId: String, val startedAt: Long, val listenedMs: Long)
data class ListenStats(val trackId: String, val playCount: Int, val lastPlayed: Long)

@Database(entities = [Track::class, Favorite::class, History::class, Playlist::class, PlaylistEntry::class,
    ImportLog::class, ImportDetail::class, MusicSource::class, TrackLocation::class, AppState::class, DownloadJob::class, ListenEvent::class], version = 3, exportSchema = true)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun music(): MusicDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN sha256 TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tracks ADD COLUMN fileSize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN modified INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN availability TEXT NOT NULL DEFAULT 'available'")
                db.execSQL("ALTER TABLE tracks ADD COLUMN sourceIdentity TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tracks ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tracks ADD COLUMN metadataOrigin TEXT NOT NULL DEFAULT 'tags'")
                db.execSQL("ALTER TABLE tracks ADD COLUMN artRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE tracks SET availability = 'location_missing' WHERE available = 0")
                db.execSQL("ALTER TABLE locations ADD COLUMN status TEXT NOT NULL DEFAULT 'available'")
                db.execSQL("UPDATE locations SET status = 'location_missing' WHERE available = 0")
                // Retain alias rows as inactive history; reconciliation must never delete track records.
                db.execSQL("DROP INDEX IF EXISTS index_tracks_uri")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_uri ON tracks(uri)")
                db.execSQL("CREATE TABLE IF NOT EXISTS listens (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, trackId TEXT NOT NULL, startedAt INTEGER NOT NULL, listenedMs INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listens_trackId ON listens(trackId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listens_startedAt ON listens(startedAt)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN active INTEGER NOT NULL DEFAULT 1")
                // Keep every collection reference. Legacy global entries become recoverable, inactive rows.
                db.execSQL("UPDATE tracks SET active = 0, available = 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS sources (id TEXT NOT NULL PRIMARY KEY, uri TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, enabled INTEGER NOT NULL, recursive INTEGER NOT NULL, persistent INTEGER NOT NULL, added INTEGER NOT NULL, status TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS locations (sourceId TEXT NOT NULL, identity TEXT NOT NULL, trackId TEXT NOT NULL, uri TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, available INTEGER NOT NULL, PRIMARY KEY(sourceId, identity))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_locations_trackId ON locations(trackId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_locations_identity ON locations(identity)")
                db.execSQL("CREATE TABLE IF NOT EXISTS app_state (name TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL("INSERT INTO app_state SELECT 'source_migration_notice', '1' WHERE EXISTS(SELECT 1 FROM tracks)")
                db.execSQL("CREATE TABLE IF NOT EXISTS download_jobs (id TEXT NOT NULL PRIMARY KEY, batchId TEXT NOT NULL, ordinal INTEGER NOT NULL, playlistId INTEGER NOT NULL, playlistName TEXT NOT NULL, destination TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, sourceId TEXT NOT NULL, url TEXT NOT NULL, license TEXT NOT NULL, licenseUrl TEXT NOT NULL, expectedDuration INTEGER NOT NULL, expectedSize INTEGER NOT NULL, artUrl TEXT NOT NULL, status TEXT NOT NULL, received INTEGER NOT NULL, total INTEGER NOT NULL, attempts INTEGER NOT NULL, uri TEXT NOT NULL, trackId TEXT NOT NULL, sha256 TEXT NOT NULL, error TEXT NOT NULL, created INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_jobs_batchId ON download_jobs(batchId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_jobs_sourceId ON download_jobs(sourceId)")
            }
        }
    }
}

/** Pure ordering operations shared by database edits and tests. */
object CollectionOrder {
    fun append(existing: List<String>, incoming: List<String>): List<String> = (existing + incoming).distinct()
    fun move(items: List<String>, from: Int, to: Int): List<String> {
        if (from !in items.indices || to !in items.indices || from == to) return items
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun insertBeforeFollowing(current: List<String>, arriving: String, following: Set<String>): List<String> {
        if (arriving in current) return current
        val index = current.indexOfFirst { it in following }.let { if (it < 0) current.size else it }
        return current.toMutableList().apply { add(index, arriving) }
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
