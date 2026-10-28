package com.yonzmusic.app.playback

import kotlin.random.Random

/** Start a new shuffle cycle at the current song, without moving/restarting its audio. */
internal object ShuffleCycle {
    fun insertNext(order:List<Int>,current:Int,inserted:List<Int>):IntArray {
        val rest=order.filter {it !in inserted}.toMutableList()
        val after=(rest.indexOf(current)+1).coerceIn(0,rest.size)
        rest.addAll(after,inserted);return rest.toIntArray()
    }
    fun indices(size: Int, current: Int, random: Random = Random.Default): IntArray {
        if (size <= 0) return intArrayOf()
        val anchor = current.coerceIn(0, size - 1)
        val rest = (0 until size).filter { it != anchor }.shuffled(random).toMutableList()
        // With at least three songs avoid an unchanged, ordinary forward traversal by chance.
        val forward = (1 until size).map { (anchor + it) % size }
        if (rest.size > 1 && rest == forward) java.util.Collections.rotate(rest, 1)
        return (listOf(anchor) + rest).toIntArray()
    }
}
