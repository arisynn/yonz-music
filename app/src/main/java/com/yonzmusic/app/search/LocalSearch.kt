package com.yonzmusic.app.search

import java.text.Normalizer
import java.util.Locale

/** Offline word matching: accents, punctuation and artist/title word order are independent. */
object LocalSearch {
    private fun normal(value: String)=Normalizer.normalize(value,Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
    fun score(query: String,title: String,artist: String="",album: String=""): Int? {
        val q=normal(query);if(q.isBlank())return null
        val words=q.split(' ').filter {it.isNotBlank()}.take(10)
        val t=normal(title);val a=normal(artist);val all="$t $a ${normal(album)}"
        if(!words.all {it in all})return null
        return when {q==t->0;t.startsWith(q)->1;words.all {it in t}->2;q==a->3;else->4}
    }
}
