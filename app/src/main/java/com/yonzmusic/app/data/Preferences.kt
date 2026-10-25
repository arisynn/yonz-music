package com.yonzmusic.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.IOException

private val Context.preferences by preferencesDataStore("yonz_settings")
data class UserPreferences(val theme: String = "System", val sort: String = "Title", val reduceMotion: Boolean = false, val glass: Boolean = true)
data class PlaybackSnapshot(val ids: List<String>, val index: Int, val position: Long, val shuffle: Boolean, val repeat: Int)
class Preferences(context: Context) {
    private val store = context.preferences
    private val theme = stringPreferencesKey("theme")
    private val sort = stringPreferencesKey("sort")
    private val motion = booleanPreferencesKey("reduce_motion")
    private val glass = booleanPreferencesKey("glass")
    private val queue = stringPreferencesKey("playback_snapshot")
    private val data = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
    val flow: Flow<UserPreferences> = data.map { UserPreferences(it[theme] ?: "System", it[sort] ?: "Title", it[motion] ?: false, it[glass] ?: true) }
    suspend fun theme(value: String) { store.edit { it[theme] = value } }
    suspend fun sort(value: String) { store.edit { it[sort] = value } }
    suspend fun motion(value: Boolean) { store.edit { it[motion] = value } }
    suspend fun glass(value: Boolean) { store.edit { it[glass] = value } }
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
