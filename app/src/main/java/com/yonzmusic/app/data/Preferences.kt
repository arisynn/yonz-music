package com.yonzmusic.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.IOException

private val Context.preferences by preferencesDataStore("yonz_settings")
data class UserPreferences(val theme: String = "System", val sort: String = "Title", val reduceMotion: Boolean = false, val glass: Boolean = true, val smartMix: Boolean = true, val hideDownloadsFromGallery: Boolean = true,
    val streamQuality: String = "balanced", val streamCacheMb: Int = 64, val autoplay: Boolean = true, val onlineHistory: Boolean = true)
data class PlaybackSnapshot(val ids: List<String>, val index: Int, val position: Long, val shuffle: Boolean, val repeat: Int)
class Preferences(context: Context) {
    private val store = context.preferences
    private val theme = stringPreferencesKey("theme")
    private val sort = stringPreferencesKey("sort")
    private val motion = booleanPreferencesKey("reduce_motion")
    private val glass = booleanPreferencesKey("glass")
    private val mixes = booleanPreferencesKey("smart_mix")
    private val hideDownloads = booleanPreferencesKey("hide_downloads_from_gallery")
    private val queue = stringPreferencesKey("playback_snapshot")
    private val quality = stringPreferencesKey("stream_quality")
    private val cache = intPreferencesKey("stream_cache_mb")
    private val autoplay = booleanPreferencesKey("stream_autoplay")
    private val onlineHistory = booleanPreferencesKey("online_history")
    private val data = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
    val flow: Flow<UserPreferences> = data.map { UserPreferences(it[theme] ?: "System", it[sort] ?: "Title", it[motion] ?: false, it[glass] ?: true, it[mixes] ?: true, it[hideDownloads] ?: true,
        it[quality]?.takeIf { v -> v in setOf("economy", "balanced", "best") } ?: "balanced",
        it[cache]?.takeIf { v -> v in setOf(0,32,64) } ?: 64, it[autoplay] ?: true, it[onlineHistory] ?: true) }
    suspend fun streamQuality(value: String) { require(value in setOf("economy","balanced","best")); store.edit { it[quality] = value } }
    suspend fun streamCacheMb(value: Int) { require(value in setOf(0,32,64)); store.edit { it[cache] = value } }
    suspend fun autoplay(value: Boolean) { store.edit { it[autoplay] = value } }
    suspend fun onlineHistory(value: Boolean) { store.edit { it[onlineHistory] = value } }
    suspend fun theme(value: String) { store.edit { it[theme] = value } }
    suspend fun sort(value: String) { store.edit { it[sort] = value } }
    suspend fun motion(value: Boolean) { store.edit { it[motion] = value } }
    suspend fun glass(value: Boolean) { store.edit { it[glass] = value } }
    suspend fun smartMix(value: Boolean) { store.edit { it[mixes] = value } }
    suspend fun hideDownloadsFromGallery(value: Boolean) { store.edit { it[hideDownloads] = value } }
    suspend fun save(snapshot: PlaybackSnapshot) {
        val json = JSONObject().put("ids", org.json.JSONArray(snapshot.ids)).put("index", snapshot.index).put("position", snapshot.position).put("shuffle", snapshot.shuffle).put("repeat", snapshot.repeat)
        store.edit { it[queue] = json.toString() }
    }
    suspend fun snapshot(): PlaybackSnapshot? = runCatching {
        val json = JSONObject(data.first()[queue] ?: return null)
        val ids = json.getJSONArray("ids")
        PlaybackSnapshot((0 until ids.length()).map { ids.getString(it) }, json.optInt("index"), json.optLong("position"), json.optBoolean("shuffle"), json.optInt("repeat"))
    }.getOrNull()
}
