package com.yonzmusic.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.data.*
import com.yonzmusic.app.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OnlineCollectionsTest {
    @Test fun concurrentEditsPersistWithoutTouchingLocalCollections():Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>();val repo=app.onlineCollections;val id=UUID.randomUUID().toString()
        val tracks=app.repository.dao.allTracks();val entries=app.repository.dao.entries().first()
        fun song(i:Int):OnlineSong {val v="QAlst"+i.toString().padStart(6,'0');return OnlineSong("engine:$v",SearchProvider.ENGINE,"Owned fixture$i","Karisyono QA",page="https://www.youtube.com/watch?v=$v",videoId=v,artistVerified=true)}
        try {
            repo.save(OnlineCollection(id,"QA online"))
            coroutineScope {(0..19).map{i->async(Dispatchers.IO){repo.append(id,song(i));repo.append(id,song(i))}}.awaitAll()}
            val stored=OnlineCollections(app).get(id)!!;assertEquals(20,stored.songs.size);assertEquals(20,stored.songs.map{it.id}.toSet().size)
            val first=stored.songs.first();repo.move(id,0,19);assertEquals(first,repo.get(id)!!.songs.last())
            repo.shift(id,first.id,-1);assertEquals(first,repo.get(id)!!.songs[18])
            repo.edit(id){it.copy(name="Renamed")};repo.removeSong(id,first.id)
            repo.removeSong(id,first.id) // stale repeated removal cannot delete a different row
            val expected=repo.get(id)!!;assertEquals(19,expected.songs.size);assertEquals("Renamed",expected.name)
            try{repo.edit(id){it.copy(name="")};fail()}catch(_:IllegalArgumentException){}
            assertEquals(expected,repo.get(id));assertEquals(tracks,app.repository.dao.allTracks());assertEquals(entries,app.repository.dao.entries().first())
            repo.delete(id);assertNull(repo.get(id));assertEquals(tracks,app.repository.dao.allTracks())
        } finally {repo.delete(id)}
    }
}
