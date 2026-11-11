package com.yonzmusic.app.data

import androidx.room.withTransaction
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.playback.StreamRepository
import com.yonzmusic.app.search.OnlineSong
import kotlinx.coroutines.flow.first
import org.json.JSONObject

fun OnlineActivity.song(): OnlineSong? = runCatching { StreamRepository.decode(JSONObject(metadata)) }.getOrNull()

/** Online metadata stays separate from local content and never contains a signed media URL. */
class OnlineActivityRepository(private val app: YonzApp) {
    private val historyEpoch=java.util.concurrent.atomic.AtomicLong()
    fun historyToken(): Long = historyEpoch.get()
    fun invalidateHistory() { historyEpoch.incrementAndGet() }
    private val dao get() = app.repository.dao
    suspend fun favorite(song: OnlineSong) = app.database.withTransaction {
        app.streams.register(song)
        val old = dao.onlineActivity(if(song.id.startsWith("mb:"))song.id else song.videoId)
        require(old?.favorite == true || dao.onlineActivity().first().count { it.favorite } < 5000) { "Maksimal 5.000 favorit daring." }
        dao.onlineActivity((old ?: OnlineActivity(if(song.id.startsWith("mb:"))song.id else song.videoId, "")).copy(metadata = StreamRepository.encode(song).toString(), favorite = old?.favorite != true))
        dao.trimOnlineActivity()
    }
    suspend fun listened(id: String, milliseconds: Long, epoch: Long = historyToken()) {
        if (!app.preferences.flow.first().onlineHistory) return
        val song = app.streams.lookup(id) ?: return
        app.database.withTransaction {
            // Recheck inside the transaction so clearing/disabling history cannot resurrect an old write.
            if (!app.preferences.flow.first().onlineHistory || historyEpoch.get()!=epoch) return@withTransaction
            val old = dao.onlineActivity(if(song.id.startsWith("mb:"))song.id else song.videoId) ?: OnlineActivity(if(song.id.startsWith("mb:"))song.id else song.videoId, "")
            dao.onlineActivity(old.copy(metadata = StreamRepository.encode(song).toString(), lastPlayed = System.currentTimeMillis(),
                playCount = old.playCount + 1, listenedMs = old.listenedMs + milliseconds.coerceAtLeast(0)))
            dao.trimOnlineActivity()
        }
    }
    suspend fun clearHistory() { invalidateHistory(); app.database.withTransaction { dao.clearOnlineHistory(); dao.trimOnlineActivity() } }
}
