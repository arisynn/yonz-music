package com.yonzmusic.app.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import com.yonzmusic.app.search.OnlineSong
import java.text.Normalizer

internal const val RADIO_ORIGIN="yono.radio.origin"
internal const val RADIO_SESSION="yono.radio.session"
internal const val RADIO_SEED="yono.radio.seed"
internal const val RADIO_GENERATED="yono.radio.generated"
internal fun radioItem(item:MediaItem,origin:String,session:String,seed:String,generated:Boolean=false):MediaItem =
    item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
        putString(RADIO_ORIGIN,origin);putString(RADIO_SESSION,session);putString(RADIO_SEED,seed);putBoolean(RADIO_GENERATED,generated)
    }).build()).build()
internal fun preserveRadioContext(requested:MediaItem,resolved:MediaItem):MediaItem {
    val extras=requested.mediaMetadata.extras ?: return resolved
    val origin=extras.getString(RADIO_ORIGIN).takeIf {it in setOf("direct","playlist")} ?: return resolved
    val session=extras.getString(RADIO_SESSION)?.takeIf {it.matches(Regex("[a-fA-F0-9-]{36}"))} ?: return resolved
    val seed=extras.getString(RADIO_SEED)?.takeIf(StreamRepository::valid) ?: return resolved
    return radioItem(resolved,origin,session,seed,extras.getBoolean(RADIO_GENERATED))
}
internal fun MediaItem.generatedRadio()=mediaMetadata.extras?.getBoolean(RADIO_GENERATED)==true

/** Independent of the player: one recording per session even when catalogue IDs differ by album. */
object RadioQueuePolicy {
    fun recording(title:String,artist:String):String {
        fun normalize(value:String)=Normalizer.normalize(value,Normalizer.Form.NFKD).lowercase(java.util.Locale.ROOT)
            .replace(Regex("\\p{M}+"),"").replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
        return normalize(title)+"|"+normalize(artist)
    }
    fun manualFirst(order:List<Int>,current:Int,generated:Set<Int>):List<Int> {
        val cursor=order.indexOf(current)
        if(cursor<0 || generated.isEmpty())return order
        val upcoming=order.drop(cursor+1)
        return order.take(cursor+1)+upcoming.filter {it !in generated}+upcoming.filter {it in generated}
    }
    fun pick(candidates:List<OnlineSong>,ids:Set<String>,recordings:Set<String>,limit:Int):List<OnlineSong> {
        val seenIds=ids.toMutableSet();val seenRecordings=recordings.toMutableSet()
        return candidates.filter {song->
            val key=recording(song.title,song.credit)
            song.id !in seenIds && key !in seenRecordings && seenIds.add(song.id) && seenRecordings.add(key)
        }.take(limit.coerceIn(0,4))
    }
}
