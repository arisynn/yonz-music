package com.yonzmusic.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MusicDatabase::class.java)
    @Test fun versionOneMigrationKeepsCollectionsAndHidesAllUnselectedGlobalAudio() = runBlocking {
        val name = "source-migration-test"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO tracks VALUES ('a','content://media/external/audio/media/81','One','Artist','Album','Music','one.mp3',12000,1,'','media',1)")
            execSQL("INSERT INTO tracks VALUES ('b','content://media/external/audio/media/82','Two','Artist','Album','Music','two.mp3',14000,1,'','media',1)")
            execSQL("INSERT INTO favorites VALUES ('a')")
            execSQL("INSERT INTO history VALUES ('a',100,4567)")
            execSQL("INSERT INTO playlists VALUES (1,'Keep my order',1)")
            execSQL("INSERT INTO playlist_entries VALUES (1,'b',0)")
            execSQL("INSERT INTO playlist_entries VALUES (1,'a',1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, MusicDatabase.MIGRATION_1_2).close()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, MusicDatabase::class.java, name).addMigrations(MusicDatabase.MIGRATION_1_2,MusicDatabase.MIGRATION_2_3).build()
        try {
            val dao = db.music(); val repo = Repository(context, db)
            assertTrue(dao.tracks().first().isEmpty()); assertEquals(2, dao.allTracks().size)
            assertEquals(listOf("b", "a"), dao.entries(1).map { it.trackId })
            assertEquals("a", dao.favorites().first().single().trackId)
            assertEquals(4567L, dao.historyRow("a")!!.position)
            assertEquals("content://media/external/audio/media/81", dao.track("a")!!.uri)
            assertEquals("1", dao.state("source_migration_notice")!!.value)
            repo.scan() // No selected registry entries: does not enumerate MediaStore or persisted grants.
            assertTrue(dao.tracks().first().isEmpty()); assertTrue(dao.allSources().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    private suspend fun withRepository(block: suspend (Repository) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        try { block(Repository(context, db)) } finally { db.close() }
    }
    @Test fun overlappingSourcesKeepOneTrackAndPreserveCollectionsWhenRemoved() = runBlocking { withRepository { repo ->
        val dao = repo.dao
        dao.upsert(listOf(Track("a", "content://test/audio", "Selected")))
        repo.create("Keep", listOf("a")); dao.favorite(Favorite("a"))
        dao.source(MusicSource("parent", "content://test/parent", "Parent", "tree"))
        dao.source(MusicSource("child", "content://test/child", "Child", "tree"))
        dao.locations(listOf(TrackLocation("parent", "physical:1", "a", "content://test/audio"), TrackLocation("child", "physical:1", "a", "content://test/audio")))
        repo.changeSource("parent", enabled = false)
        assertTrue(dao.track("a")!!.active); assertTrue(dao.track("a")!!.available); assertEquals(1, dao.tracks().first().size)
        repo.removeSource("parent"); assertTrue(dao.track("a")!!.active)
        repo.removeSource("child"); assertFalse(dao.track("a")!!.active)
        assertEquals("a", dao.entries(1).single().trackId); assertEquals(1, dao.favorites().first().size)
    } }
    @Test fun exactIdentityRemappingIsTransactionalAndPreservesOrderFavoritesAndLatestHistory() = runBlocking { withRepository { repo ->
        val dao = repo.dao
        dao.upsert(listOf(Track("media", "content://media/external/audio/media/1", "Same"), Track("saf", "content://docs/audio", "Same"), Track("other", "content://docs/other", "Other")))
        val id = repo.create("Order", listOf("saf", "other", "media"))
        dao.favorite(Favorite("saf")); dao.remember(History("saf", 200, 3000)); dao.remember(History("media", 100, 1000))
        repo.mergeIdentities("media", listOf("saf"))
        assertEquals(listOf("media", "other"), dao.entries(id).map { it.trackId })
        assertEquals("media", dao.favorites().first().single().trackId); assertEquals(3000L, dao.historyRow("media")!!.position)
        assertEquals(Availability.ALIAS,dao.track("saf")!!.availability);assertFalse(dao.track("saf")!!.active); assertEquals("media", dao.state("track_alias:saf")!!.value)
        repo.mergeIdentities("other", listOf("media"))
        assertEquals("other", dao.state("track_alias:saf")!!.value)
    } }
    @Test fun revokedSourceDoesNotDropItsMembershipOrOriginalIndexContext() = runBlocking { withRepository { repo ->
        repo.dao.upsert(listOf(Track("a", "content://missing-provider/audio", "Recoverable")))
        repo.dao.source(MusicSource("chosen", "content://missing-provider/file", "Unavailable SD", "document"))
        repo.dao.locations(listOf(TrackLocation("chosen", "id:1", "a", "content://missing-provider/audio")))
        repo.create("Retain", listOf("a")); repo.scan()
        assertTrue(repo.dao.track("a")!!.active); assertFalse(repo.dao.track("a")!!.available)
        assertEquals(1, repo.dao.locations().size); assertEquals("a", repo.dao.entries(1).single().trackId)
    } }
}
