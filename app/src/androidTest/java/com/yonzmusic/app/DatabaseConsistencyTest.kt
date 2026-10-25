package com.yonzmusic.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseConsistencyTest {
    private lateinit var db: MusicDatabase
    private lateinit var repo: Repository
    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        repo = Repository(context, db)
    }
    @After fun tearDown() { db.close() }
    private suspend fun seed() { repo.dao.upsert(listOf(Track("a", "content://local/a", "One"), Track("b", "content://local/b", "Two"), Track("c", "content://local/c", "Three"))) }
    @Test fun scanUpdatePreservesFavoritesHistoryAndPlaylistOrder() = runBlocking {
        seed(); val id = repo.create("Collection", listOf("b", "a"))
        repo.dao.favorite(Favorite("a")); repo.dao.remember(History("a", 123, 456))
        repo.dao.upsert(listOf(Track("a", "content://local/a", "Updated title")))
        assertEquals(listOf("b", "a"), repo.dao.entries(id).map { it.trackId })
        assertEquals("a", repo.dao.favorites().first().single().trackId)
        assertEquals(456L, repo.dao.history().first().single().position)
    }
    @Test fun repeatedAppendDoesNotDuplicateAndMoveIsConsistent() = runBlocking {
        seed(); val id = repo.create("Collection", listOf("a", "b"))
        repo.append(id, listOf("b", "c", "c")); repo.move(id, 2, 0)
        assertEquals(listOf("c", "a", "b"), repo.dao.entries(id).map { it.trackId })
        assertEquals(listOf(0, 1, 2), repo.dao.entries(id).map { it.position })
    }
    @Test fun playlistDeleteCascadesOnlyMembership() = runBlocking {
        seed(); val id = repo.create("Collection", listOf("a", "b"))
        repo.dao.favorite(Favorite("a")); repo.dao.deletePlaylist(id)
        assertTrue(repo.dao.entries(id).isEmpty())
        assertEquals(3, repo.dao.allTracks().size)
        assertEquals(1, repo.dao.favorites().first().size)
    }
    @Test fun tenThousandImportRowsReadAcrossCursorWindows() = runBlocking {
        val id = repo.dao.log(ImportLog(name = "Large import", report = "{}"))
        repo.dao.logDetails((0 until 10000).map { ImportDetail(id, it, "x".repeat(256), "Unmatched", "No local recording") })
        val rows = repo.dao.importDetails(id)
        assertEquals(10000, rows.size); assertEquals(9999, rows.last().position)
    }
    @Test fun missingTrackPreservesCollectionReference() = runBlocking {
        seed(); val id = repo.create("Collection", listOf("a")); repo.dao.unavailable("a")
        assertFalse(repo.dao.track("a")!!.available); assertEquals("a", repo.dao.entries(id).single().trackId)
    }
}
