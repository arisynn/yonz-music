package com.yonzmusic.app.playback

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/** Metadata work yields to foreground audio; no global lock around a whole song/download. */
object StreamWork {
    private val opens = AtomicInteger()
    @Volatile var buffering = false
    val urgent get() = buffering || opens.get() > 0
    fun begin() { opens.incrementAndGet() }
    fun end() { opens.decrementAndGet() }
    suspend fun awaitAudio() { while (urgent) delay(100) }
}

/** Session-local timings only: no titles, queries, URLs or user identifiers in logs. */
object StreamTimings {
    private val samples = linkedMapOf<String, ArrayDeque<Long>>()
    @Synchronized fun record(stage: String, milliseconds: Long) {
        val values = samples.getOrPut(stage) { ArrayDeque() }
        if (values.size == 100) values.removeFirst()
        values.addLast(milliseconds.coerceAtLeast(0))
    }
    @Synchronized fun snapshot(): Map<String, List<Long>> = samples.mapValues { it.value.toList() }
}
