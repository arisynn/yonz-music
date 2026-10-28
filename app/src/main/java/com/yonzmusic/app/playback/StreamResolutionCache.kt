package com.yonzmusic.app.playback

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Session-only results; failed/cancelled requests and signed URLs never reach disk. */
internal class StreamResolutionCache<T>(private val clock: () -> Long, private val capacity: Int = 8) {
    private data class Entry<T>(val until: Long, val value: T)
    private class Gate { val lock = ReentrantLock(); var users = 0 }
    private val entries = linkedMapOf<String, Entry<T>>()
    private val gates = mutableMapOf<String, Gate>()

    @Synchronized fun invalidate(id: String) { entries.remove(id) }
    @Synchronized private fun cached(id: String, accepts: (T) -> Boolean): T? {
        val entry = entries[id] ?: return null
        if (clock() >= entry.until) { entries.remove(id); return null }
        if (!accepts(entry.value)) return null
        entries.remove(id); entries[id] = entry
        return entry.value
    }

    /** A playback request joins an in-flight warm-up, rather than extracting the source twice.
     * Waiting callers keep their own cancellation/deadline; producer failure is never cached. */
    fun resolve(id: String, accepts: (T) -> Boolean, checkActive: () -> Unit,
                lifetime: (T) -> Long, load: () -> T): T {
        checkActive()
        cached(id, accepts)?.let { return it }
        val gate = synchronized(this) { gates.getOrPut(id) { Gate() }.also { it.users++ } }
        var locked = false
        try {
            while (!locked) { checkActive(); locked = gate.lock.tryLock(50, TimeUnit.MILLISECONDS) }
            checkActive()
            cached(id, accepts)?.let { return it }
            val value = load()
            checkActive()
            val ttl = lifetime(value).coerceAtLeast(0)
            if (ttl > 0) synchronized(this) {
                entries[id] = Entry(clock() + ttl, value)
                while (entries.size > capacity) entries.remove(entries.keys.first())
            }
            return value
        } finally {
            if (locked) gate.lock.unlock()
            synchronized(this) { if (--gate.users == 0) gates.remove(id) }
        }
    }
}
